(ns codestory.plugins.leiningen
  "Built-in plugin: summarise a Leiningen project.clj."
  (:require [clojure.string :as str]
            [codestory.repo :as repo]
            [hiccup.core :as hiccup]))

(defn- parse
  "Very small project.clj reader: pull defproject name, version, description, deps."
  [content]
  (try
    (let [forms (read-string (str "[" content "]"))
          dp (first (filter #(and (seq? %) (= 'defproject (first %))) forms))]
      (when dp
        (let [[_ proj version & more] dp
              opts (apply hash-map more)]
          {:project (str proj)
           :version (str version)
           :description (str (get opts :description ""))
           :dependencies (vec (get opts :dependencies []))
           :main (some-> (:main opts) str)})))
    (catch Exception e
      {:error (.getMessage e)})))

(defn- ->html [info]
  (if (:error info)
    (hiccup/html [:div.plugin-error [:p "Could not parse project.clj: " (:error info)]])
    (hiccup/html
     [:div.plugin-panel.leiningen
      [:h3 "Leiningen project"]
      [:dl
       [:dt "Name"] [:dd [:code (:project info)]]
       [:dt "Version"] [:dd [:code (:version info)]]
       (when-not (str/blank? (:description info))
         (list [:dt "Description"] [:dd (:description info)]))
       (when (:main info)
         (list [:dt "Main"] [:dd [:code (:main info)]]))]
      [:h4 "Dependencies"]
      [:ul.dep-list
       (for [dep (:dependencies info)]
         [:li [:code (pr-str dep)]])]])))

(defn run
  [{:keys [path params]}]
  (let [target (or (:file params) path)
        content (when (and target (str/ends-with? (str target) "project.clj"))
                  (:content (repo/read-file target)))
        info (if content (parse content) {:error "No project.clj content"})]
    {:html (->html info)
     :data info}))

(def plugin
  {:name "leiningen"
   :description "Reads a Leiningen project.clj and renders project name, version, main, and dependencies. Auto-attaches when viewing project.clj; also usable via a ```plugin leiningen fence."
   :run run})
