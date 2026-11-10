(ns sunlit.mystyle
  "Shared machinery for the mystyle-* style-linter plugins.

  Each linter prints findings as `path:line:col [LEVEL] message` lines. This
  namespace resolves the bundled script, runs it, parses that output, and
  renders a grouped findings panel (plus machine-readable :data)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [sunlit.clj-source :as src]
            [sunlit.jobs :as jobs]
            [sunlit.python :as py]
            [sunlit.repo :as repo]
            [hiccup.core :as hiccup]
            [hiccup.util :as h])
  (:import [java.io File]))

(def linters
  {:mystyle-clj {:name "mystyle-clj"
                 :title "mystyle-clj"
                 :script "mystyle-clj.clj"
                 :kind :bb}
   :mystyle-py {:name "mystyle-py"
                :title "mystyle-py"
                :script "mystyle-py.py"
                :kind :python}
   :mystyle-js {:name "mystyle-js"
                :title "mystyle-js"
                :script "mystyle-js.py"
                :kind :python}
   :mystyle-cpp {:name "mystyle-cpp"
                 :title "mystyle-cpp"
                 :script "mystyle-cpp.py"
                 :kind :python}
   ;; Native binary (Haxe compiled to C++), vendored like the scripts.
   :mystyle-hx {:name "mystyle-hx"
                :title "mystyle-hx"
                :script "mystyle-haxe"
                :kind :binary}})

;; ---------------------------------------------------------------------------
;; Script / interpreter resolution
;; ---------------------------------------------------------------------------

(defn- extract-resource!
  "Copy classpath resource `scripts/<script>` (byte-for-byte, so binaries
  survive) into a fresh temp dir, executable. Returns its absolute path."
  [script]
  (let [dir (File/createTempFile "sunlit-mystyle" nil)
        out (io/file dir script)]
    (.delete dir)
    (.mkdirs dir)
    (with-open [in (io/input-stream (io/resource (str "scripts/" script)))]
      (io/copy in out))
    (.setExecutable out true)
    (.getAbsolutePath out)))

(defn- script-path
  "Locate a bundled linter script or binary: dev `scripts/` first, then
  classpath resources (extracted to a temp dir when running from the uberjar)."
  [script]
  (let [from-cwd (io/file (System/getProperty "user.dir") "scripts" script)]
    (cond
      (.isFile from-cwd) (.getAbsolutePath from-cwd)
      (io/resource (str "scripts/" script)) (extract-resource! script)
      :else nil)))

(defn- fallback-python
  "SUNLIT_PYTHON, else the shared ~/.main_python_venv, else python3."
  []
  (or (System/getenv "SUNLIT_PYTHON")
      (let [shared (str (System/getProperty "user.home") "/.main_python_venv/bin/python")]
        (if (.isFile (io/file shared)) shared "python3"))))

(defn- python-bin
  "The repo's .sunlit/venv python (created, and given the tree-sitter
  grammars, on first use), else `fallback-python` when it can't be set up."
  []
  (let [{:keys [ok python]} (py/ensure-analysis-env!)]
    (if ok python (fallback-python))))

(defn- executable-on-path? [prog]
  (try
    (let [{:keys [exit]} (sh/sh "which" prog)]
      (zero? exit))
    (catch Exception _
      false)))

(defn- subprocess-env []
  (-> (into {} (System/getenv))
      (dissoc "CLASSPATH")))

(defn- native-platform?
  "True on the platform the vendored binaries are built for (Linux x86-64)."
  []
  (and (= "Linux" (System/getProperty "os.name"))
       (contains? #{"amd64" "x86_64"} (System/getProperty "os.arch"))))

(defn- binary-path
  "The vendored binary `program` on Linux x86-64, else `program` itself when
  it is on PATH (e.g. built locally on another platform), else nil."
  [program]
  (or (when (native-platform?) (script-path program))
      (when (executable-on-path? program) program)))

(defn- resolve-cmd [linter]
  (let [root (str (repo/repo-root))]
    (if (= :binary (:kind linter))
      (some-> (binary-path (:script linter)) (vector root))
      (when-let [sp (script-path (:script linter))]
        (case (:kind linter)
          :bb (when (executable-on-path? "bb") ["bb" sp root])
          :python [(python-bin) sp root]
          nil)))))

;; ---------------------------------------------------------------------------
;; Output parsing
;; ---------------------------------------------------------------------------

(def ^:private finding-re
  #"(?m)^(.+?):(\d+):(\d+)\s+\[(INFO|WARN|HARD|ERROR)\]\s+(.*)$")

(defn- relativize-path [s]
  (try
    (let [rel (repo/relativize (str s))]
      (if (or (str/blank? rel) (str/starts-with? rel ".."))
        (str s)
        rel))
    (catch Exception _
      (str s))))

(defn parse-findings
  "Parse `path:line:col [LEVEL] message` lines into finding maps."
  [text]
  (->> (re-seq finding-re (or text ""))
       (map (fn [[_ file line col level message]]
              {:file (relativize-path file)
               :line (try (Integer/parseInt line) (catch Exception _ 1))
               :col (try (Integer/parseInt col) (catch Exception _ 1))
               :level level
               :message message}))
       vec))

(defn- file-lines [path]
  (some-> (src/read-rel path) str/split-lines vec))

(defn attach-source
  "Add each finding's source line (as found at lint time) under :source."
  [findings]
  (let [lines-of (memoize file-lines)]
    (mapv (fn [{:keys [file line] :as f}]
            (if-let [text (get (lines-of file) (dec line))]
              (assoc f :source (str/trimr text))
              f))
          findings)))

(defn summarize [findings]
  (let [by (frequencies (map :level findings))]
    {:count (count findings)
     :warn (get by "WARN" 0)
     :hard (get by "HARD" 0)
     :info (get by "INFO" 0)
     :error (get by "ERROR" 0)}))

;; ---------------------------------------------------------------------------
;; Rendering
;; ---------------------------------------------------------------------------

(defn summary-html [s]
  (when (and (map? s) (pos? (:count s 0)))
    (hiccup/html
     [:div.test-summary
      [:div.test-stat.lint
       [:span.test-stat-value (:count s 0)]
       [:span.test-stat-label "findings"]]
      [:div.test-stat.mystyle-warn
       [:span.test-stat-value (:warn s 0)]
       [:span.test-stat-label "warn"]]
      [:div.test-stat.mystyle-hard
       [:span.test-stat-value (:hard s 0)]
       [:span.test-stat-label "hard"]]
      [:div.test-stat.mystyle-info
       [:span.test-stat-value (:info s 0)]
       [:span.test-stat-label "info"]]
      [:div.test-stat.mystyle-error
       [:span.test-stat-value (:error s 0)]
       [:span.test-stat-label "error"]]])))

(defn- level-class [level]
  (case level
    "HARD" "mystyle-hard"
    "WARN" "mystyle-warn"
    "ERROR" "mystyle-error"
    "mystyle-info"))

(defn- message-parts
  "\"fail-soft-input: detail\" -> [\"fail-soft-input\" \"detail\"]; [nil msg] otherwise."
  [msg]
  (if-let [[_ category detail] (re-matches #"(?s)([a-z][a-z0-9-]*): (.*)" (str msg))]
    [category detail]
    [nil (str msg)]))

(defn- message-html
  "Escaped message hiccup; `backticked` spans become <code>."
  [text]
  (map-indexed (fn [i part]
                 (if (odd? i)
                   [:code (h/escape-html part)]
                   (h/escape-html part)))
               (str/split text #"`" -1)))

(defn- finding-item [file {:keys [line col level message source]}]
  (let [[category detail] (message-parts message)]
    [:li {:class (str "mystyle-item " (level-class level))}
     [:a.mystyle-loc {:href (str (src/file-href file) "?line=" line)
                      :title "Open at this line"}
      (str line ":" col)]
     [:span.mystyle-level level]
     (when category [:span.mystyle-cat (h/escape-html category)])
     [:span.mystyle-msg (message-html detail)]
     (when-not (str/blank? source)
       [:pre.mystyle-src (h/escape-html (str/trim source))])]))

(defn findings-html [findings]
  (if (empty? findings)
    (hiccup/html
     [:div.plugin-panel
      [:p.muted "No style findings."]])
    (let [grouped (group-by :file findings)]
      (hiccup/html
       [:div.mystyle-findings
        (for [[file fs] (sort-by first grouped)]
          [:div.mystyle-file
           [:div.mystyle-file-head
            [:a {:href (src/file-href file)} [:code file]]
            [:span.meta (str (count fs) " finding(s)")]]
           [:ul.mystyle-list
            (for [f (sort-by (juxt :line :col) fs)]
              (finding-item file f))]])]))))

(defn- error-html [title msg]
  (hiccup/html
   [:div.plugin-error
    [:p (str title ": " msg)]]))

;; ---------------------------------------------------------------------------
;; Compute! (runs in the job cache worker)
;; ---------------------------------------------------------------------------

(defn- error-payload
  "Jobs cache payload for a failed linter run. stdout/stderr default to
  \"\" and `msg`."
  [title cmd exit msg & {:keys [stdout stderr] :or {stdout "" stderr msg}}]
  {:status "error"
   :cmd (vec cmd)
   :exit exit
   :stdout stdout
   :stderr stderr
   :error msg
   :html (error-html title msg)
   :summary nil
   :data {:findings [] :error msg}})

(defn- unavailable-msg [{:keys [name kind script]}]
  (str name " is not available: "
       (case kind
         :bb "babashka ('bb') was not found on PATH."
         :binary (str "the bundled '" script "' binary is Linux x86-64 only, and no '"
                      script "' was found on PATH. Build it from self-written-tools/"
                      "mystyle/haxe (or vendor it into scripts/) to enable this panel.")
         "no Python interpreter found.")))

(defn- findings-payload
  "Jobs cache payload for a linter run that produced findings (or none)."
  [{:keys [name]} cmd exit out err findings]
  (let [summary (summarize findings)
        file-count (count (distinct (map :file findings)))]
    {:status "ok"
     :cmd (vec cmd)
     :exit exit
     :stdout (str name ": " (:count summary) " findings across "
                  file-count " file" (when (not= 1 file-count) "s") ".")
     :stderr err
     :html (findings-html findings)
     :summary summary
     :data {:findings findings :summary summary :raw out :cmd (vec cmd)}}))

(defn- run-linter
  "Shell out to `cmd` in the repo root and turn its output into a payload."
  [{:keys [title] :as linter} cmd]
  (let [{:keys [exit out err]}
        (apply sh/sh (concat (mapv str cmd)
                             [:dir (str (repo/repo-root))
                              :env (subprocess-env)]))
        out (str out)
        err (str err)
        findings (attach-source (parse-findings out))]
    (if (and (not (zero? exit)) (empty? findings))
      (error-payload title cmd exit
                     (str/trim (or (not-empty err) (not-empty out) "linter failed"))
                     :stdout out :stderr err)
      (findings-payload linter cmd exit out err findings))))

(defn compute!
  "Run one linter and return a jobs cache payload."
  [linter-key]
  (let [{:keys [title] :as linter} (get linters linter-key)
        cmd (resolve-cmd linter)]
    (if (nil? cmd)
      (error-payload title [] 1 (unavailable-msg linter))
      (try
        (run-linter linter cmd)
        (catch Exception e
          (error-payload title cmd 1 (or (ex-message e) (str e))))))))

;; ---------------------------------------------------------------------------
;; Plugin maps (one per linter; see sunlit.plugins.mystyle-*)
;; ---------------------------------------------------------------------------

(defn- run-panel
  "Panel showing the cached findings of plugin `plugin-name`."
  [plugin-name params]
  (let [id (or (:id params) plugin-name)
        cache (jobs/read-cache plugin-name id)]
    (jobs/render-panel
     {:plugin plugin-name
      :id id
      :title (or (:title params) plugin-name)
      :summary-html (summary-html (:summary cache))
      :params (assoc params :id id)})))

(defn- start-scan!
  "Launch a background run of linter `linter-key` into the jobs cache."
  [linter-key plugin-name params]
  (jobs/start-compute!
   {:plugin plugin-name
    :id (or (:id params) plugin-name)
    :label (str plugin-name "-scan")
    :compute! #(compute! linter-key)}))

(defn linter-plugin
  "Plugin map for the long-running, cached runner of `linter-key`."
  [linter-key description]
  (let [plugin-name (:name (get linters linter-key))]
    {:name plugin-name
     :description description
     :long-running? true
     :run (fn [{:keys [params]}] (run-panel plugin-name params))
     :start! (fn [{:keys [params]}] (start-scan! linter-key plugin-name params))}))
