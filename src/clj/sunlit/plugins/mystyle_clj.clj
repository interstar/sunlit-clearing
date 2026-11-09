(ns sunlit.plugins.mystyle-clj
  "Long-running runner for the bundled mystyle-clj (babashka) linter."
  (:require [sunlit.jobs :as jobs]
            [sunlit.mystyle :as mystyle]))

(def ^:private plugin-name "mystyle-clj")

(defn run
  [{:keys [params]}]
  (let [id (or (:id params) plugin-name)
        cache (jobs/read-cache plugin-name id)]
    (jobs/render-panel
     {:plugin plugin-name
      :id id
      :title (or (:title params) plugin-name)
      :summary-html (mystyle/summary-html (:summary cache))
      :params (assoc params :id id)})))

(defn start!
  [{:keys [params]}]
  (let [id (or (:id params) plugin-name)]
    (jobs/start-compute!
     {:plugin plugin-name
      :id id
      :label (str plugin-name "-scan")
      :compute! #(mystyle/compute! :mystyle-clj)})))

(def plugin
  {:name plugin-name
   :description (str "Runs the bundled mystyle-clj linter on the repository and lists structural "
                     "style findings for Clojure sources: statefulness, fail-soft input, long "
                     "functions, oversized files, near-duplicate s-exprs, and reader-conditionals. "
                     "Long-running with cache; requires babashka ('bb') on PATH.")
   :long-running? true
   :run run
   :start! start!})
