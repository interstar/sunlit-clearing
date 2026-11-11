(ns sunlit.plugins
  "Plugin registry. Built-ins under sunlit.plugins.* plus .sunlit/plugins/*.clj via SCI."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sunlit.plugins.leiningen :as leiningen]
            [sunlit.plugins.cloverage :as cloverage]
            [sunlit.plugins.gallery :as gallery]
            [sunlit.plugins.hxcov :as hxcov]
            [sunlit.plugins.image :as image]
            [sunlit.plugins.instaparse :as instaparse]
            [sunlit.plugins.malli :as malli]
            [sunlit.plugins.matrix :as matrix]
            [sunlit.plugins.mystyle-clj :as mystyle-clj]
            [sunlit.plugins.overview :as overview]
            [sunlit.plugins.mystyle-py :as mystyle-py]
            [sunlit.plugins.mystyle-js :as mystyle-js]
            [sunlit.plugins.mystyle-cpp :as mystyle-cpp]
            [sunlit.plugins.mystyle-hx :as mystyle-hx]
            [sunlit.plugins.projectlint :as projectlint]
            [sunlit.plugins.pycov :as pycov]
            [sunlit.plugins.python-tools :as python-tools]
            [sunlit.plugins.run :as run]
            [sunlit.plugins.structure :as structure]
            [sunlit.plugins.unittests :as unittests]
            [sunlit.git :as git]
            [sunlit.repo :as repo]
            [hiccup.core :as hiccup]
            [sci.core :as sci]))

(defonce ^:private registry (atom {}))

(def ^:private builtin-defs
  [leiningen/plugin
   image/plugin
   gallery/plugin
   mystyle-clj/plugin
   mystyle-py/plugin
   mystyle-js/plugin
   mystyle-cpp/plugin
   mystyle-hx/plugin
   run/plugin
   unittests/plugin
   cloverage/plugin
   pycov/plugin
   hxcov/plugin
   python-tools/plugin
   structure/plugin
   structure/clojure-plugin
   structure/python-plugin
   structure/javascript-plugin
   structure/haxe-plugin
   structure/cpp-plugin
   malli/plugin
   instaparse/plugin
   matrix/plugin
   overview/plugin
   projectlint/plugin])

(defn- normalize
  "Accept a plugin map or (legacy) bare run fn."
  [entry & [{:keys [name source description file]}]]
  (cond
    (map? entry)
    (let [n (or (:name entry) name)
          run-fn (or (:run entry) (:handler entry))]
      (when (and n (fn? run-fn))
        {:name (str n)
         :description (or (:description entry) description "")
         :run run-fn
         :start! (:start! entry)
         :update (:update entry)
         :long-running? (boolean (or (:long-running? entry) (:start! entry)))
         :source (or (:source entry) source :builtin)
         :file file}))

    (fn? entry)
    (when name
      {:name (str name)
       :description (or description "")
       :run entry
       :start! nil
       :long-running? false
       :source (or source :repo)
       :file file})

    :else nil))

(defn register!
  ([entry]
   (when-let [p (normalize entry)]
     (swap! registry assoc (keyword (:name p)) p)
     p))
  ([name run-or-map]
   (if (map? run-or-map)
     (register! (assoc run-or-map :name name))
     (register! {:name name :run run-or-map :description "" :source :repo}))))

(defn get-plugin [name]
  (get @registry (keyword name)))

(defn get-run-fn [name]
  (:run (get-plugin name)))

(defn- catalog-entry [p]
  (-> p
      (select-keys [:name :description :source :file :long-running?])
      (update :source #(if (keyword? %) (clojure.core/name %) (str %)))))

(defn list-plugins []
  (->> @registry vals (map :name) sort))

(defn catalog []
  (let [all (->> @registry vals (map catalog-entry) (sort-by :name))
        builtin (filterv #(= "builtin" (:source %)) all)
        repo (filterv #(= "repo" (:source %)) all)]
    {:ok true
     :builtin builtin
     :repo repo
     :plugins all}))

(defn- load-sci-plugin! [file]
  (try
    (let [n (-> file .getName (str/replace #"\.clj$" ""))
          code (slurp file)
          !pending (atom nil)
          ctx (sci/init
               {:namespaces
                {'sunlit.plugin
                 {'repo-root (fn [] (str (repo/repo-root)))
                  'read-file (fn [p] (repo/read-file p))
                  'slurp-repo (fn [p] (:content (repo/read-file p)))
                  'git-available? (fn [] (git/available?))
                  'tracked? (fn [p] (git/tracked? p))
                  'git-status (fn [p] (git/status-for p))
                  'define! (fn [m]
                             (reset! !pending
                                     (normalize (if (map? m) m {})
                                                {:name (or (:name m) n)
                                                 :source :repo
                                                 :file (.getName file)}))
                             @!pending)
                  'register! (fn [name-or-map & [maybe-fn]]
                               (let [p (if maybe-fn
                                         (normalize {:name name-or-map :run maybe-fn}
                                                    {:source :repo :file (.getName file)})
                                         (normalize name-or-map
                                                    {:name n :source :repo :file (.getName file)}))]
                                 (reset! !pending p)
                                 p))}}
                :classes {'java.lang.String String}})
          result (sci/eval-string* ctx code)
          plugin (or @!pending
                     (normalize result {:name n :source :repo :file (.getName file)
                                        :description ""}))]
      (if plugin
        (do (register! plugin)
            {:file (.getName file) :ok true :name (:name plugin)})
        {:file (.getName file) :ok false :error "Plugin did not define a runnable handler"}))
    (catch Exception e
      {:file (.getName file) :ok false :error (.getMessage e)})))

(defn load-user-plugins! []
  (let [dir (repo/sidecar-file "plugins")]
    (when (.isDirectory dir)
      (vec
       (for [f (sort-by #(.getName %) (.listFiles dir))
             :when (and (.isFile f) (str/ends-with? (.getName f) ".clj"))]
         (load-sci-plugin! f))))))

(defn load-builtins! []
  (doseq [p builtin-defs]
    (register! (assoc p :source :builtin))))

(defn init! []
  (reset! registry {})
  (load-builtins!)
  (load-user-plugins!))

(defn run-plugin
  [name {:keys [path params] :as ctx}]
  (if-let [f (get-run-fn name)]
    (try
      (merge {:plugin name :ok true}
             (f (assoc ctx :repo-root (str (repo/repo-root)))))
      (catch Exception e
        {:plugin name :ok false :error (.getMessage e)
         :html (hiccup/html [:div.plugin-error [:p (str "Plugin error: " (.getMessage e))]])}))
    {:plugin name :ok false :error (str "Unknown plugin: " name)
     :html (hiccup/html [:div.plugin-error [:p (str "Unknown plugin: " name)]])}))

(defn start-plugin!
  "Kick off a long-running plugin job."
  [name {:keys [path params] :as ctx}]
  (if-let [p (get-plugin name)]
    (if-let [start! (:start! p)]
      (try
        (merge {:plugin name :ok true}
               (start! (assoc ctx :repo-root (str (repo/repo-root)))))
        (catch Exception e
          {:ok false :plugin name :error (.getMessage e)}))
      {:ok false :plugin name :error (str "Plugin is not long-running: " name)})
    {:ok false :plugin name :error (str "Unknown plugin: " name)}))
