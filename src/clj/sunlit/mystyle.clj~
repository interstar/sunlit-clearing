(ns codestory.mystyle
  "Shared machinery for the mystyle-* style-linter plugins.

  Each linter prints findings as `path:line:col [LEVEL] message` lines. This
  namespace resolves the bundled script, runs it, parses that output, and
  renders a grouped findings panel (plus machine-readable :data)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [codestory.clj-source :as src]
            [codestory.repo :as repo]
            [hiccup.core :as hiccup])
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
      (let [dir (File/createTempFile "codestory-mystyle" nil)]
        (.delete dir)
        (.mkdirs dir)
        (let [out (io/file dir script)]
          (spit out (slurp (io/resource (str "scripts/" script))))
          (.getAbsolutePath out)))

      :else nil)))

(defn- python-bin []
  (or (System/getenv "CODESTORY_PYTHON")
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
              [:li {:class (str "mystyle-item " (level-class (:level f)))}
               [:span.mystyle-loc (str (:line f) ":" (:col f))]
               [:span.mystyle-level (:level f)]
               [:span.mystyle-msg (:message f)]])]])]))))

(defn- error-html [title msg]
  (hiccup/html
   [:div.plugin-error
    [:p (str title ": " msg)]]))

;; ---------------------------------------------------------------------------
;; Compute! (runs in the job cache worker)
;; ---------------------------------------------------------------------------

(defn compute!
  "Run one linter and return a jobs cache payload."
  [linter-key]
  (let [{:keys [name title] :as linter} (get linters linter-key)
        cmd (resolve-cmd linter)]
    (if (nil? cmd)
      (let [msg (str name " is not available: "
                     (if (= :bb (:kind linter))
                       "babashka ('bb') was not found on PATH."
                       "no Python interpreter found."))]
        {:status "error"
         :cmd []
         :exit 1
         :stdout ""
         :stderr msg
         :error msg
         :html (error-html title msg)
         :summary nil
         :data {:findings [] :error msg}})
      (try
        (let [{:keys [exit out err]}
              (apply sh/sh (concat (mapv str cmd)
                                   [:dir (str (repo/repo-root))
                                    :env (subprocess-env)]))
              out (str out)
              err (str err)
              findings (parse-findings out)
              summary (summarize findings)
              file-count (count (distinct (map :file findings)))
              short (str name ": " (:count summary) " findings across "
                         file-count " file" (when (not= 1 file-count) "s") ".")]
          (if (and (not (zero? exit)) (empty? findings))
            (let [msg (str (str/trim (or (not-empty err) (not-empty out) "linter failed")))]
              {:status "error"
               :cmd (vec cmd)
               :exit exit
               :stdout out
               :stderr err
               :error msg
               :html (error-html title msg)
               :summary nil
               :data {:findings [] :error msg}})
            {:status "ok"
             :cmd (vec cmd)
             :exit exit
             :stdout short
             :stderr err
             :html (findings-html findings)
             :summary summary
             :data {:findings findings
                    :summary summary
                    :raw out
                    :cmd (vec cmd)}}))
        (catch Exception e
          (let [msg (or (ex-message e) (.getMessage e) (str e))]
            {:status "error"
             :cmd (vec cmd)
             :exit 1
             :stdout ""
             :stderr msg
             :error msg
             :html (error-html title msg)
             :summary nil
             :data {:findings [] :error msg}}))))))
