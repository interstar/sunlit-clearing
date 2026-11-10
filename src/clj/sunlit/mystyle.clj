(ns sunlit.mystyle
  "Shared machinery for the mystyle-* style-linter plugins.

  Each linter prints findings as `path:line:col [LEVEL] message` lines. This
  namespace resolves the bundled script, runs it, parses that output, and
  renders a grouped findings panel (plus machine-readable :data)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [sunlit.clj-source :as src]
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
                :kind :python}})

;; ---------------------------------------------------------------------------
;; Script / interpreter resolution
;; ---------------------------------------------------------------------------

(defn- script-path
  "Locate a bundled linter script: dev `scripts/` first, then classpath
  resources (extracted to a temp dir when running from the uberjar)."
  [script]
  (let [from-cwd (io/file (System/getProperty "user.dir") "scripts" script)]
    (cond
      (.isFile from-cwd)
      (.getAbsolutePath from-cwd)

      (io/resource (str "scripts/" script))
      (let [dir (File/createTempFile "sunlit-mystyle" nil)]
        (.delete dir)
        (.mkdirs dir)
        (let [out (io/file dir script)]
          (spit out (slurp (io/resource (str "scripts/" script))))
          (.getAbsolutePath out)))

      :else nil)))

(defn- python-bin []
  (or (System/getenv "SUNLIT_PYTHON")
      (let [shared (str (System/getProperty "user.home") "/.main_python_venv/bin/python")]
        (if (.isFile (io/file shared)) shared "python3"))))

(defn- executable-on-path? [prog]
  (try
    (let [{:keys [exit]} (sh/sh "which" prog)]
      (zero? exit))
    (catch Exception _
      false)))

(defn- subprocess-env []
  (-> (into {} (System/getenv))
      (dissoc "CLASSPATH")))

(defn- resolve-cmd [linter]
  (let [sp (script-path (:script linter))
        root (str (repo/repo-root))]
    (when sp
      (case (:kind linter)
        :bb (when (executable-on-path? "bb") ["bb" sp root])
        :python [(python-bin) sp root]
        nil))))

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

(defn- unavailable-msg [{:keys [name kind]}]
  (str name " is not available: "
       (if (= :bb kind)
         "babashka ('bb') was not found on PATH."
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
