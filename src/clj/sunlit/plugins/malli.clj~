(ns codestory.plugins.malli
  "Long-running Malli schema extract plugin."
  (:require [codestory.jobs :as jobs]
            [codestory.malli :as malli]))

(def plugin
  {:name "malli"
   :description
   (str "Scans Clojure source under src/ for Malli schemas "
        "(def / defschema of :map, :vector, m/schema, …) and lists them with source. "
        "Long-running with cache; place on any wiki page. Rerun re-scans.")
   :long-running? true
   :run
   (fn [{:keys [params]}]
     (let [id (or (:id params) "repo")]
       (jobs/render-panel
        {:plugin "malli"
         :id id
         :title (or (:title params) "malli/repo")
         :params (assoc params :id id)})))
   :start!
   (fn [{:keys [params]}]
     (let [id (or (:id params) "repo")]
       (jobs/start-compute!
        {:plugin "malli"
         :id id
         :label "malli-scan"
         :compute! malli/compute!})))})
