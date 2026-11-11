(ns sunlit.runlog
  "Append-only log of finished runs: .sunlit/cache/runs.jsonl, one JSON object
  per line. sunlit.jobs appends an entry whenever a background run (tests,
  coverage, linters, scans…) finishes, so plugins never write it themselves,
  and readers such as the overview plugin never depend on another plugin.

  An entry is {:at :plugin :id :status :msg} plus, for jobs, :started_at,
  :exit and :summary. Anything else (a script, a git hook, an agent) may
  append its own line; only :at (an ISO-8601 instant) and :msg are needed."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [sunlit.repo :as repo]))

(defonce ^:private write-lock (Object.))

(defn log-file []
  (repo/sidecar-file "cache" "runs.jsonl"))

(defn append!
  "Append `entry` (a map) to the log; returns it."
  [entry]
  (let [f (log-file)]
    (locking write-lock
      (io/make-parents f)
      (spit f (str (json/generate-string entry) "\n") :append true))
    entry))

(defn- parse-line [line]
  (try
    (let [v (json/parse-string line true)]
      (when (and (map? v) (string? (:at v))) v))
    (catch Exception _ nil)))

(defn entries
  "Every well-formed entry, oldest first (malformed lines skipped)."
  []
  (let [f (log-file)]
    (if (.isFile f)
      (with-open [r (io/reader f)]
        (into [] (comp (remove str/blank?) (keep parse-line)) (line-seq r)))
      [])))

(defn- loggable-summary
  "A job's :summary when it is small, plain data."
  [summary]
  (when (and (or (map? summary) (string? summary))
             (< (count (pr-str summary)) 500))
    summary))

(defn job-entry
  "Log entry for a finished job's cache payload."
  [{:keys [plugin id status finished_at started_at exit summary]}]
  (cond-> {:at (or finished_at (str (java.time.Instant/now)))
           :plugin plugin
           :id id
           :status status
           :msg (str plugin "/" id " " status)}
    started_at (assoc :started_at started_at)
    (some? exit) (assoc :exit exit)
    (loggable-summary summary) (assoc :summary summary)))
