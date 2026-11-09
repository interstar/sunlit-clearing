(ns sunlit.plugins.pycov
  "Long-running Python coverage (pytest + coverage.py / pytest-cov)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sunlit.jobs :as jobs]
            [sunlit.python :as py]
            [sunlit.repo :as repo]
            [sunlit.source :as source]
            [sunlit.test-report :as report]))

(def ^:private default-report "htmlcov/index.html")

(defn- default-cmd [params]
  (let [target (or (py/discover-pytest-path (or (:path params) (:tests params))) ".")
        cov (or (some-> (:cov params) str)
                (when-let [dirs (seq (source/discover-source-dirs :python))]
                  (str/join "," dirs))
                ".")]
    ["python3" "-m" "pytest" target
     (str "--cov=" cov)
     "--cov-report=term" "--cov-report=html"]))

(def ^:private html-report-re
  #"(?mi)(?:Wrote HTML report to:?\s+(\S+)|Coverage HTML written to dir\s+(\S+)|Writing HTML report to:\s+(\S+))")

(defn- strip-ansi [s]
  (str/replace (str s) #"\u001B\[[0-9;]*m" ""))

(defn- relativize-to-repo [path]
  (let [path (str/trim (str path))]
    (when-not (str/blank? path)
      (try
        (let [root (.getCanonicalFile (repo/repo-root))
              f (.getCanonicalFile (io/file path))]
          (if (str/starts-with? (.getPath f) (str (.getPath root) java.io.File/separator))
            (-> (.toPath root)
                (.relativize (.toPath f))
                str
                (str/replace #"\\" "/"))
            (-> path
                (str/replace #"^\./" "")
                (str/replace #"\\" "/"))))
        (catch Exception _
          path)))))

(defn discover-report-path [cache params]
  (or (some-> (:report params) str str/trim not-empty)
      (some-> (:iframe params) str str/trim not-empty)
      (when-let [m (re-find html-report-re
                            (strip-ansi (str (:stdout cache) "\n" (:stderr cache))))]
        (let [found (some identity (rest m))
              found (if (and found (not (str/ends-with? found ".html")))
                      (str (str/replace found #"/+$" "") "/index.html")
                      found)]
          (relativize-to-repo found)))
      default-report))

(defn run
  [{:keys [params]}]
  (let [id (or (:id params) "pycov")
        cmd (py/with-analysis-python (or (:cmd params) (default-cmd params)))
        cache (jobs/read-cache "pycov" id)
        report (discover-report-path cache params)
        summary (when (#{"ok" "failed"} (:status cache))
                  (report/parse-summary
                   (str (:stdout cache) "\n" (:stderr cache))))]
    (jobs/render-panel
     {:plugin "pycov"
      :id id
      :title (or (:title params) "pycov")
      :iframe report
      :summary-html (report/summary-html summary)
      :params (assoc params :id id :cmd cmd :report report)})))

(defn start!
  [{:keys [params]}]
  (let [id (or (:id params) "pycov")
        ensured (py/ensure-project-editable!)]
    (if-not (:ok ensured)
      {:ok false :error (str "pycov: " (:error ensured))}
      (let [cmd (py/with-analysis-python (or (:cmd params) (default-cmd params)))]
        (jobs/start! {:plugin "pycov" :id id :cmd cmd})))))

(def plugin
  {:name "pycov"
   :description "Runs pytest with coverage.py (pytest-cov) using .sunlit/venv (created and tools installed on first run). Parses coverage HTML report location from output and embeds it."
   :long-running? true
   :run run
   :start! start!})
