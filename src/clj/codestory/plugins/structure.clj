(ns codestory.plugins.structure
  "Per-language deterministic architecture plugins (structure-clojure, etc.)."
  (:require [clojure.string :as str]
            [codestory.jobs :as jobs]
            [codestory.structure :as structure]))

(defn- cache-id [lang params]
  (or (:id params) (str "structure-" (name lang))))

(defn- make-plugin [lang label]
  (let [pname (str "structure-" (name lang))]
    {:name pname
     :description
     (str "Deterministic " label
          " architecture extract (modules/namespaces, types/classes, Mermaid diagrams). "
          "Long-running with cache; place on any wiki page. Rerun re-scans.")
     :long-running? true
     :run
     (fn [{:keys [params]}]
       (let [id (cache-id lang params)]
         (jobs/render-panel
          {:plugin pname
           :id id
           :title (or (:title params) (str pname "/" id))
           :params (assoc params :id id)})))
     :start!
     (fn [{:keys [params]}]
       (let [id (cache-id lang params)]
         (jobs/start-compute!
          {:plugin pname
           :id id
           :label (str pname "-scan")
           :compute! #(structure/compute-lang! lang)})))}))

(def clojure-plugin (make-plugin :clojure "Clojure"))
(def python-plugin (make-plugin :python "Python"))
(def javascript-plugin (make-plugin :javascript "JavaScript/TypeScript"))
(def haxe-plugin (make-plugin :haxe "Haxe"))
(def cpp-plugin (make-plugin :cpp "C/C++"))

(defn- legacy-compute! []
  (let [parts (->> [:clojure :python :javascript :haxe :cpp]
                   (map structure/compute-lang!)
                   (remove #(zero? (get-in % [:summary :unit_count] 0))))
        html (if (seq parts)
               (str/join "" (map :html parts))
               (str "<div class=\"structure-lang-box structure-lang-empty\">"
                    "<p class=\"muted\">No supported source found "
                    "(Clojure, Python, JavaScript/TypeScript, Haxe, or C/C++).</p></div>"))
        totals (reduce (fn [acc p]
                         (-> acc
                             (update :unit_count + (get-in p [:summary :unit_count] 0))
                             (update :type_count + (get-in p [:summary :type_count] 0))))
                       {:unit_count 0 :type_count 0}
                       parts)]
    {:html html
     :summary totals
     :stdout (str "Legacy structure scan: " (:unit_count totals) " units.")}))

(def plugin
  "Legacy multi-language fence. Prefer structure-* per-language plugins."
  (let [pname "structure"]
    {:name pname
     :description "Runs Clojure+Python+JS+Haxe+C++ extracts and shows only languages that have content. Prefer per-language structure-* plugins so you can place boxes on different pages."
     :long-running? true
     :run
     (fn [{:keys [params]}]
       (let [id (or (:id params) "repo")]
         (jobs/render-panel
          {:plugin pname
           :id id
           :title (or (:title params) "structure/repo")
           :params (assoc params :id id)})))
     :start!
     (fn [{:keys [params]}]
       (let [id (or (:id params) "repo")]
         (jobs/start-compute!
          {:plugin pname
           :id id
           :label "structure-scan"
           :compute! legacy-compute!})))}))
