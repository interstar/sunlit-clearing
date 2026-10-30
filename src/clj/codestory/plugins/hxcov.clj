(ns codestory.plugins.hxcov
  "Long-running Haxe coverage via instrument (lcov.info) or custom :cmd."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [codestory.haxe :as haxe]
            [codestory.jobs :as jobs]
            [codestory.repo :as repo]
            [codestory.test-report :as report]
            [hiccup.core :as hiccup]))

(def ^:private default-lcov "lcov.info")

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
            (-> path (str/replace #"^\./" "") (str/replace #"\\" "/"))))
        (catch Exception _
          path)))))

(defn discover-lcov [params]
  (or (some-> (:lcov params) str str/trim not-empty)
      (some-> (:report params) str str/trim
              (#(when (str/ends-with? % ".info") %)))
      default-lcov))

(defn discover-html-report [params cache]
  (or (some-> (:iframe params) str str/trim not-empty)
      (when-let [r (some-> (:report params) str str/trim not-empty)]
        (when (str/ends-with? r ".html") r))
      (when-let [r (:html_report cache)]
        (relativize-to-repo r))))

(defn- notes-html [lcov-rel has-summary?]
  (hiccup/html
   [:div.hxcov-notes
    [:p.muted
     "LCOV: " [:code lcov-rel]
     " · typically from " [:code "instrument"]
     " via " [:code "testCoverage.hxml"]
     (when-not has-summary?
       " — no report yet; click Rerun after configuring coverage.")]]))

(defn run
  [{:keys [params]}]
  (let [id (or (:id params) "hxcov")
        cmd (or (:cmd params) (haxe/default-coverage-cmd params))
        cache (jobs/read-cache "hxcov" id)
        lcov (discover-lcov params)
        html-report (discover-html-report params cache)
        lcov-summary (haxe/read-lcov lcov)
        run-summary (when (#{"ok" "failed"} (:status cache))
                      (report/parse-summary
                       (str (:stdout cache) "\n" (:stderr cache))))
        summary (merge (or run-summary {})
                       (when lcov-summary
                         {:files (:files lcov-summary)
                          :lines_hit (:lines_hit lcov-summary)
                          :lines_found (:lines_found lcov-summary)
                          :coverage_pct (:percent lcov-summary)}))
        body (notes-html lcov (boolean lcov-summary))]
    (if (nil? cmd)
      {:html
       (hiccup/html
        [:div.plugin-panel
         [:p.muted
          "No Haxe coverage HXML found (tried testCoverage.hxml, coverage.hxml, …). "
          "Pass " [:code ":hxml"] " or " [:code ":cmd"] "."]])
       :ok true
       :plugin "hxcov"}
      (jobs/render-panel
       {:plugin "hxcov"
        :id id
        :title (or (:title params) "hxcov")
        :iframe html-report
        :summary-html (report/summary-html summary)
        :body-html body
        :params (assoc params :id id :cmd cmd :lcov lcov
                       :report (or html-report lcov))}))))

(defn start!
  [{:keys [params]}]
  (let [id (or (:id params) "hxcov")
        cmd (or (:cmd params) (haxe/default-coverage-cmd params))]
    (if (nil? cmd)
      {:ok false
       :error "hxcov: no coverage HXML found; provide :hxml or :cmd"}
      (jobs/start! {:plugin "hxcov" :id id :cmd cmd}))))

(def plugin
  {:name "hxcov"
   :description "Haxe coverage runner. Defaults to `haxe testCoverage.hxml` (or coverage.hxml). Expects instrument-style LCOV at lcov.info; shows a headline coverage summary. Optional :hxml / :cmd / :lcov / HTML :report."
   :long-running? true
   :run run
   :start! start!})
