(ns sunlit.plugins.cloverage
  "Long-running cloverage runner; discovers HTML report path from cloverage output."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sunlit.jobs :as jobs]
            [sunlit.repo :as repo]
            [sunlit.test-report :as report]))

(def ^:private default-report "target/coverage/index.html")

(def ^:private html-report-re
  #"(?m)Writing HTML report to:\s*(.+?)\s*$")

(defn- strip-ansi [s]
  (str/replace (str s) #"\u001B\[[0-9;]*m" ""))

(defn- relativize-to-repo
  "Turn an absolute report path into a repo-relative path when possible."
  [path]
  (let [path (str/trim (str path))]
    (if (str/blank? path)
      nil
      (try
        (let [root (.getCanonicalFile (repo/repo-root))
              f (.getCanonicalFile (io/file path))]
          (if (str/starts-with? (.getPath f) (str (.getPath root) java.io.File/separator))
            (-> (.toPath root)
                (.relativize (.toPath f))
                str
                (str/replace #"\\" "/"))
            path))
        (catch Exception _
          path)))))

(defn discover-report-path
  "Find HTML report path from explicit params, then cloverage stdout/stderr."
  [cache params]
  (or (some-> (:report params) str str/trim not-empty)
      (some-> (:iframe params) str str/trim not-empty)
      (when-let [[_ found] (re-find html-report-re
                                   (strip-ansi (str (:stdout cache) "\n" (:stderr cache))))]
        (relativize-to-repo found))
      (when (:report cache)
        (relativize-to-repo (:report cache)))
      default-report))

(defn run
  [{:keys [params]}]
  (let [id (or (:id params) "cloverage")
        cmd (or (:cmd params) ["lein" "cloverage"])
        cache (jobs/read-cache "cloverage" id)
        report (discover-report-path cache params)
        summary (when (#{"ok" "failed"} (:status cache))
                  (report/parse-coverage-summary
                   (str (:stdout cache) "\n" (:stderr cache))))]
    (jobs/render-panel
     {:plugin "cloverage"
      :id id
      :title (or (:title params) "cloverage")
      :iframe report
      :summary-html (report/summary-html summary)
      :params (assoc params :id id :cmd cmd :report report)})))

(defn start!
  [{:keys [params]}]
  (let [id (or (:id params) "cloverage")
        cmd (or (:cmd params) ["lein" "cloverage"])]
    (jobs/start! {:plugin "cloverage" :id id :cmd cmd})))

(def plugin
  {:name "cloverage"
   :description "Runs lein cloverage (long-running). Parses 'Writing HTML report to: …' from the command output to locate the HTML report and embed it in an iframe. Optional :report overrides discovery."
   :long-running? true
   :run run
   :start! start!})
