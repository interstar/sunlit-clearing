(ns sunlit.plugins.mystyle-py
  "Long-running runner for the bundled mystyle-py (Python ast) linter."
  (:require [sunlit.jobs :as jobs]
            [sunlit.mystyle :as mystyle]))

(def ^:private plugin-name "mystyle-py")

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
      :compute! #(mystyle/compute! :mystyle-py)})))

(def plugin
  {:name plugin-name
   :description (str "Runs the bundled mystyle-py linter on the repository and lists structural "
                     "style findings for Python sources: long functions, deep nesting, explicit "
                     "global usage, fail-soft input, mutable default args, high parameter counts, "
                     "context-manager candidates, type-hint coverage, oversized files, file-scope "
                     "comments, and near-duplicates. Long-running with cache.")
   :long-running? true
   :run run
   :start! start!})
