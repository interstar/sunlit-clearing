(ns codestory.plugins.instaparse
  "Long-running Instaparse grammar extract plugin."
  (:require [codestory.instaparse :as instaparse]
            [codestory.jobs :as jobs]))

(def plugin
  {:name "instaparse"
   :description
   (str "Scans Clojure source under src/ for Instaparse grammars "
        "(insta/parser, defparser, grammar string defs) and lists them. "
        "Long-running with cache; place on any wiki page. Rerun re-scans.")
   :long-running? true
   :run
   (fn [{:keys [params]}]
     (let [id (or (:id params) "repo")]
       (jobs/render-panel
        {:plugin "instaparse"
         :id id
         :title (or (:title params) "instaparse/repo")
         :params (assoc params :id id)})))
   :start!
   (fn [{:keys [params]}]
     (let [id (or (:id params) "repo")]
       (jobs/start-compute!
        {:plugin "instaparse"
         :id id
         :label "instaparse-scan"
         :compute! instaparse/compute!})))})
