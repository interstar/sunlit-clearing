(ns sunlit.plugins.mystyle-js
  "Long-running runner for the bundled mystyle-js (tree-sitter) linter."
  (:require [sunlit.jobs :as jobs]
            [sunlit.mystyle :as mystyle]))

(def ^:private plugin-name "mystyle-js")

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
      :compute! #(mystyle/compute! :mystyle-js)})))

(def plugin
  {:name plugin-name
   :description (str "Runs the bundled mystyle-js linter on the repository and lists structural "
                     "style findings for JavaScript/TypeScript sources: long functions, deep "
                     "nesting, parameter counts, var usage, eqeqeq, console.log leftovers, "
                     "fail-soft input, module-level mutable state, try/using candidates, file-scope "
                     "comments, near-duplicates, and TS any/as/@ts-ignore/type-coverage. Long-running "
                     "with cache; requires tree-sitter Python packages.")
   :long-running? true
   :run run
   :start! start!})
