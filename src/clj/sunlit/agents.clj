(ns sunlit.agents
  "Machine-readable /agents/ endpoints for agent access.

  Long-running plugins use stale-while-revalidate: the current cached result is
  returned immediately (with its finished_at datestamp), and a background run
  is launched when the cache is never/error, older than the TTL, or ?refresh=1
  was requested. Synchronous plugins run inline."
  (:require [clojure.string :as str]
            [sunlit.annotations :as ann]
            [sunlit.conventions :as conventions]
            [sunlit.jobs :as jobs]
            [sunlit.pages :as pages]
            [sunlit.plugins :as plugins]
            [sunlit.render :as render]
            [sunlit.repo :as repo]
            [sunlit.test-report :as test-report])
  (:import [java.time Duration Instant]))

(def default-ttl-seconds 60)

(defn- url-encode [s]
  (-> (str s)
      (java.net.URLEncoder/encode "UTF-8")
      (str/replace "+" "%20")))

(defn- parse-instant [s]
  (try (Instant/parse s) (catch Exception _ nil)))

(defn- age-seconds [finished-at]
  (when-let [inst (parse-instant finished-at)]
    (max 0 (.getSeconds (Duration/between inst (Instant/now))))))

(defn- stale-cache? [status cache]
  (or (contains? #{"never" "error"} status)
      (let [age (age-seconds (:finished_at cache))]
        (boolean (and age (> age default-ttl-seconds))))))

(defn- combined-out [cache]
  (str (:stdout cache) "\n" (:stderr cache)))

(defn- derived-data [name cache]
  (case name
    "unittests" (test-report/parse-summary (combined-out cache))
    "cloverage" (test-report/parse-coverage-summary (combined-out cache))
    "pycov" (test-report/parse-summary (combined-out cache))
    "hxcov" (test-report/parse-summary (combined-out cache))
    nil))

(defn- run-failed?
  "A plugin run fails when it threw or returned :ok false."
  [run-result]
  (false? (:ok run-result)))

(defn- run-status
  "Reported status: a failed run is \"error\", whatever its cache says."
  [run-result cache-status]
  (if (run-failed? run-result) "error" cache-status))

(defn- content-fields
  "Output fields of a plugin result, preferring the fresh run over the cache."
  [name run-result cache]
  (let [finished-at (:finished_at cache)]
    {:started_at (:started_at cache)
     :finished_at finished-at
     :age_seconds (age-seconds finished-at)
     :cmd (:cmd cache)
     :exit (:exit cache)
     :data (or (:data run-result)
               (:data cache)
               (derived-data name cache)
               (:summary cache))
     :text (or (:text run-result)
               (:text cache)
               (:markdown run-result)
               (:markdown cache))
     :markdown (or (:markdown run-result) (:markdown cache))
     :html (or (:html run-result) (:html cache))
     :stdout (:stdout cache)
     :stderr (:stderr cache)}))

(defn- long-running-state
  "Cache, running/stale flags and any background launch for a long-running
  plugin. A launch happens when the cache is never/error or stale, or on
  `refresh?`, and only when the plugin is cache-backed and not already running."
  [name run-result {:keys [params id path refresh?]}]
  (let [cache (or (:cache run-result) (jobs/read-cache name id))
        cache-status (or (:status run-result) (:status cache) "never")
        running? (jobs/running? name id)
        stale? (stale-cache? cache-status cache)
        launch? (and (:cache_id run-result) (not running?) (or refresh? stale?))
        launch-result (when launch?
                        (plugins/start-plugin! name {:params (assoc params :id id) :path path}))]
    {:cache cache
     :cache-status cache-status
     :running (boolean (or running? (and launch? (:ok launch-result))))
     :stale (boolean stale?)
     :launch-error (:error launch-result)}))

(defn plugin-result
  "The /agents/ shape for one plugin invocation.

  opts may contain :params (plugin fence params), :id, :path, and :refresh?.
  Long-running plugins read their cache and (when cache-backed) auto-launch a
  background run if the cache is never/error, stale, or refresh? is true."
  [name {:keys [params id refresh? path]}]
  (if-let [p (plugins/get-plugin name)]
    (let [params (or params {})
          long-running? (boolean (or (:long-running? p) (:start! p)))
          run-result (plugins/run-plugin name {:params params :path path})
          id (or id (:cache_id run-result) "default")
          {:keys [cache cache-status running stale launch-error]}
          (if long-running?
            (long-running-state name run-result
                                {:params params :id id :path path :refresh? refresh?})
            {:cache-status (or (:status run-result) "ok") :running false :stale false})]
      (merge
       {:ok (not (run-failed? run-result))
        :name (str name)
        :id id
        :long_running long-running?
        :status (run-status run-result cache-status)
        :running running
        :stale stale
        :error (or (:error run-result) launch-error)
        :agent_url (str "/agents/plugin/" (url-encode (str name)) "/" (url-encode id))}
       (content-fields name run-result cache)))
    {:ok false :error (str "Unknown plugin: " name) :name name}))

(defn- dedupe-results [results]
  (reduce (fn [acc r]
            (if (some #(and (= (:name %) (:name r))
                            (= (:id %) (:id r)))
                      acc)
              acc
              (conj acc r)))
          []
          results))

(defn- resolve-fences [fences opts]
  (->> fences
       (map (fn [{:keys [name params]}]
              (plugin-result name (merge opts {:params params}))))
       dedupe-results
       vec))

(defn page-payload [name]
  (let [page (pages/read-page name)]
    {:ok true
     :name (:name page)
     :exists (:exists page)
     :markdown (:markdown page)
     :plugins (resolve-fences (render/plugin-fences (:markdown page)) {})}))

(defn file-payload [path]
  (let [file (repo/read-file path)
        annotation (ann/read-annotation path)
        fences (concat (render/auto-plugin-fences path)
                       (render/plugin-fences (:markdown annotation)))]
    {:ok true
     :path (:path file)
     :name (:name file)
     :language (:language file)
     :text? (:text? file)
     :content (:content file)
     :annotation {:exists (:exists annotation)
                  :markdown (:markdown annotation)}
     :plugins (resolve-fences fences {:path path})}))

(defn tree-payload []
  (let [index (pages/read-page pages/index-page-name)]
    {:ok true
     :repo_name (repo/repo-name)
     :repo (str (repo/repo-root))
     :tree (repo/build-tree)
     :root_docs (repo/root-doc-entries)
     :index {:name (:name index) :markdown (:markdown index)}
     :plugins (resolve-fences (render/plugin-fences (:markdown index)) {})}))

(defn conventions-payload []
  {:ok true :report (conventions/check)})

(defn index-payload []
  (let [catalog (plugins/catalog)
        plugin-names (mapv :name (:plugins catalog))
        page-names (pages/list-pages)]
    {:ok true
     :repo (str (repo/repo-root))
     :repo_name (repo/repo-name)
     :plugins (:plugins catalog)
     :pages page-names
     :endpoints ["/agents/"
                 "/agents/tree"
                 "/agents/page/{name}"
                 "/agents/file/{path}"
                 "/agents/plugin/{name}"
                 "/agents/plugin/{name}/{id}"
                 "/agents/conventions"]
     :examples (vec (concat ["/agents/tree" "/agents/conventions"]
                            (map #(str "/agents/page/" (url-encode %)) page-names)
                            (map #(str "/agents/plugin/" (url-encode %)) plugin-names)))}))
