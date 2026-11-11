(ns sunlit.sessions
  "The repo's agent sessions (Codex, Pi, Claude Code, Cursor), collected by
  sunlit.sessions.engine and cached under .sunlit/cache/sessions/: an
  index.json of session entries plus one details/<id>.json transcript per
  session. Reads (list, show, search, stats, export) only touch the cache;
  `refresh!` rebuilds it in the background."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [sunlit.jobs :as jobs]
            [sunlit.repo :as repo]
            [sunlit.sessions.common :as common]
            [sunlit.sessions.engine :as engine])
  (:import [java.io File]
           [java.time Instant LocalDate]))

;; ---------------------------------------------------------------------------
;; Cache files

(defn- cache-root []
  (repo/sidecar-file "cache" "sessions"))

(defn- index-file []
  (io/file (cache-root) "index.json"))

(defn- details-dir []
  (io/file (cache-root) "details"))

(defn- detail-file [session-id]
  (let [safe (-> (str session-id)
                 (str/replace #"[^A-Za-z0-9._-]+" "_")
                 (#(if (str/blank? %) "unknown" %)))]
    (io/file (details-dir) (str safe ".json"))))

(defn- read-json-file [^File f]
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

(defn- clear-details!
  "Remove previously cached transcripts so deleted sessions disappear."
  []
  (doseq [^File f (.listFiles (details-dir))
          :when (.isFile f)]
    (.delete f)))

(defn- cached-transcripts
  "Every cached transcript ({:session :messages})."
  []
  (->> (common/children (details-dir))
       (keep read-json-file)
       (filter :session)))

;; ---------------------------------------------------------------------------
;; Rebuild

(defn rebuild-cache!
  "Slow path: collect every session run in the repo, then write the index and
  one transcript file per session."
  []
  (let [{:keys [repo transcripts warnings]} (engine/collect-for-repo (repo/repo-root))
        cached-at (str (Instant/now))
        sessions (mapv :session transcripts)]
    (clear-details!)
    (write-json-file! (index-file) {:ok true
                                    :cached true
                                    :cached_at cached-at
                                    :repo repo
                                    :sessions sessions
                                    :count (count sessions)
                                    :warnings warnings})
    (doseq [t transcripts]
      (write-json-file! (detail-file (get-in t [:session :session_id]))
                        (assoc t :ok true :cached_at cached-at)))
    {:stdout (str "Cached " (count sessions) " sessions at " cached-at
                  (when (seq warnings) (str " (" (str/join "; " warnings) ")")))
     :summary {:count (count sessions) :cached_at cached-at}}))

(defn refresh!
  "Background rebuild of the session cache."
  []
  (jobs/start-compute!
   {:plugin "sessions"
    :id "index"
    :label "sessions-refresh"
    :compute! rebuild-cache!}))

;; ---------------------------------------------------------------------------
;; Reads (cache only)

(defn list-for-repo
  "Fast path: the cached index, with the refresh job's status."
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

(def ^:private not-cached
  {:ok false :error "Session not in cache. Open Sessions and click Refresh."})

(defn- resolve-id
  "`session-id`, or the one cached id containing it; else an error map."
  [session-id]
  (if (.isFile ^File (detail-file session-id))
    session-id
    (let [matches (->> (:sessions (read-index))
                       (map :session_id)
                       (filter #(str/includes? (str %) (str session-id)))
                       vec)]
      (case (count matches)
        0 not-cached
        1 (first matches)
        {:ok false :error (str "Ambiguous session id in cache: " session-id) :matches matches}))))

(defn show
  "A cached transcript, by id or unambiguous part of one."
  [session-id]
  (let [id (resolve-id session-id)]
    (if (map? id)
      id
      (or (some-> (read-json-file (detail-file id)) (assoc :cached true))
          {:ok false :error (str "Index has " id " but its transcript is missing. Refresh.")}))))

(defn- parse-date [s]
  (when-not (str/blank? s)
    (try (LocalDate/parse s) (catch Exception _ nil))))

(defn search
  "Cached messages matching `q`, best first. `params` (strings, as from a
  query string): role, from / to (YYYY-MM-DD), limit."
  [q {:keys [role from to limit]}]
  {:ok true
   :query q
   :hits (engine/search-messages (cached-transcripts) q
                                 {:role (not-empty role)
                                  :from (parse-date from)
                                  :to (parse-date to)
                                  :limit (or (some-> limit parse-long) 200)})})

(defn stats
  "Counts over the cached sessions."
  []
  (assoc (engine/stats (cached-transcripts)) :ok true))

(defn export-markdown
  "{:ok true :filename :markdown} for a cached session, else an error map."
  [session-id]
  (let [t (show session-id)]
    (if (:ok t)
      {:ok true
       :filename (str (str/replace (get-in t [:session :session_id]) #"[^A-Za-z0-9._-]+" "_") ".md")
       :markdown (engine/export-markdown t)}
      t)))
