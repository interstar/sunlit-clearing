(ns sunlit.sessions
  "Agent sessions via eyeball_kid, cached under .sunlit/cache/sessions/."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [sunlit.jobs :as jobs]
            [sunlit.repo :as repo])
  (:import [java.io File]
           [java.time Instant]))

(def ^:private bridge-script
  (delay
    (let [from-cwd (io/file (System/getProperty "user.dir") "scripts" "sessions_bridge.py")
          sibling (io/file (.getParentFile from-cwd) "cursor_sessions.py")]
      (cond
        (and (.exists from-cwd) (.exists sibling))
        (.getAbsolutePath from-cwd)

        (and (io/resource "scripts/sessions_bridge.py")
             (io/resource "scripts/cursor_sessions.py"))
        (let [dir (File/createTempFile "sunlit-scripts" nil)]
          (.delete dir)
          (.mkdirs dir)
          (spit (io/file dir "sessions_bridge.py")
                (slurp (io/resource "scripts/sessions_bridge.py")))
          (spit (io/file dir "cursor_sessions.py")
                (slurp (io/resource "scripts/cursor_sessions.py")))
          (.getAbsolutePath (io/file dir "sessions_bridge.py")))

        (.exists from-cwd)
        (.getAbsolutePath from-cwd)

        :else "scripts/sessions_bridge.py"))))

(defn- python-bin []
  (or (System/getenv "SUNLIT_PYTHON")
      (let [shared (str (System/getProperty "user.home") "/.main_python_venv/bin/python")]
        (if (.exists (io/file shared)) shared "python3"))))

(defn- run-bridge [args]
  (let [cmd (into [(python-bin) @bridge-script] args)
        {:keys [exit out err]} (apply sh/sh cmd)
        parsed (try
                 (json/parse-string out true)
                 (catch Exception _
                   nil))]
    (cond
      (map? parsed) parsed
      (zero? exit) {:ok false :error (str "Unexpected bridge output: " out)}
      :else {:ok false
             :error (str/trim (or (not-empty err) (not-empty out) "sessions bridge failed"))
             :exit exit})))

(defn- cache-root []
  (repo/sidecar-file "cache" "sessions"))

(defn- index-file []
  (io/file (cache-root) "index.json"))

(defn- detail-file [session-id]
  (let [safe (-> (str session-id)
                 (str/replace #"[^A-Za-z0-9._-]+" "_")
                 (#(if (str/blank? %) "unknown" %)))]
    (io/file (cache-root) "details" (str safe ".json"))))

(defn- now-iso []
  (str (Instant/now)))

(defn- read-json-file [f]
  (when (.isFile f)
    (try
      (json/parse-string (slurp f) true)
      (catch Exception _
        nil))))

(defn- write-json-file! [f data]
  (io/make-parents f)
  (spit f (json/generate-string data)))

(defn read-index
  "Cached session index, or nil if missing."
  []
  (read-json-file (index-file)))

(defn- write-index! [data]
  (write-json-file! (index-file) data))

(defn- write-detail! [session-id detail]
  (write-json-file! (detail-file session-id) detail))

(defn- clear-details!
  "Remove previously cached transcripts so deleted sessions disappear."
  []
  (let [dir (io/file (cache-root) "details")]
    (when (.isDirectory dir)
      (doseq [f (.listFiles dir)]
        (when (.isFile f)
          (.delete f))))))

(defn rebuild-cache!
  "Slow path: one eyeball dump, then write index + per-session transcripts."
  []
  (let [root (str (.getCanonicalFile (repo/repo-root)))
        result (run-bridge ["dump" root])]
    (if-not (:ok result false)
      (throw (ex-info (or (:error result) "sessions dump failed")
                      {:result result}))
      (let [sessions (vec (:sessions result []))
            details (:details result {})
            cached-at (now-iso)
            index {:ok true
                   :cached true
                   :cached_at cached-at
                   :repo (:repo result root)
                   :sessions sessions
                   :count (count sessions)}]
        (clear-details!)
        (write-index! index)
        (doseq [[sid detail] details]
          (write-detail! (name sid) (assoc detail :cached_at cached-at)))
        {:stdout (str "Cached " (count sessions) " sessions at " cached-at)
         :summary {:count (count sessions) :cached_at cached-at}}))))

(defn list-for-repo
  "Fast path: read .sunlit cache. Never hits eyeball unless refreshing."
  []
  (let [running? (jobs/running? "sessions" "index")
        idx (read-index)
        job (jobs/read-cache "sessions" "index")]
    (cond
      running?
      {:ok true
       :status "running"
       :cached (boolean idx)
       :cached_at (:cached_at idx)
       :sessions (or (:sessions idx) [])
       :message "Refreshing session cache…"}

      (and idx (:ok idx false))
      (assoc idx
             :status (or (:status job) "ok")
             :cached true)

      :else
      {:ok true
       :status "never"
       :cached false
       :sessions []
       :message "No session cache yet. Click Refresh to scan agent sessions into .sunlit/cache/sessions/."})))

(defn show
  "Read a cached transcript. Does not call eyeball live."
  [session-id]
  (or (when-let [detail (read-json-file (detail-file session-id))]
        (assoc detail :cached true))
      ;; Partial id match against cached index
      (when-let [idx (read-index)]
        (let [matches (->> (:sessions idx)
                           (map :session_id)
                           (filter #(str/includes? (str %) (str session-id)))
                           vec)]
          (cond
            (= 1 (count matches))
            (or (read-json-file (detail-file (first matches)))
                {:ok false :error (str "Index has " (first matches) " but detail cache is missing. Refresh.")})

            (empty? matches)
            {:ok false
             :error "Session not in cache. Open Sessions and click Refresh."}

            :else
            {:ok false
             :error (str "Ambiguous session id in cache: " session-id)
             :matches matches})))
      {:ok false
       :error "Session not in cache. Open Sessions and click Refresh."}))

(defn refresh!
  "Background rebuild of the session cache."
  []
  (jobs/start-compute!
   {:plugin "sessions"
    :id "index"
    :label "sessions-refresh"
    :compute! rebuild-cache!}))
