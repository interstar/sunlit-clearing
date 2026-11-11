(ns sunlit.plugins.overview
  "When were tests, coverage, linters… last run, and has the code changed
  since? Reads only the run log (sunlit.runlog) and file modification times,
  so it depends on no other plugin.

  Fence params:
    {:title \"…\"
     :checks [{:label \"Python tests\"     ; row name
               :plugin \"unittests\"       ; match log entries by plugin…
               :id \"python\"              ; …and cache id…
               :match \"regex\"            ; …and/or a regex on the entry's :msg
               :compare \"src/**/*.py\"}]} ; glob(s): warn if newer than the run
  Without :checks, every plugin/id in the log gets a row (no comparison)."
  (:require [clojure.string :as str]
            [hiccup.core :as hiccup]
            [hiccup.util :refer [escape-html]]
            [sunlit.repo :as repo]
            [sunlit.runlog :as runlog]
            [sunlit.sandbox :as sandbox])
  (:import [java.time Duration Instant]))

;; ---------------------------------------------------------------------------
;; Matching log entries

(defn- parse-instant [s]
  (try (Instant/parse s) (catch Exception _ nil)))

(defn- entry-matches? [{:keys [plugin id match]} entry]
  (and (or (nil? plugin) (= (str plugin) (:plugin entry)))
       (or (nil? id) (= (str id) (:id entry)))
       (or (nil? match) (boolean (re-find (re-pattern (str match)) (str (:msg entry)))))))

(defn- latest [entries]
  (last (sort-by :at (filter (comp parse-instant :at) entries))))

(defn- default-checks
  "One check per plugin/id that appears in the log."
  [entries]
  (->> entries
       (filter :plugin)
       (map (juxt :plugin :id))
       distinct
       sort
       (mapv (fn [[p id]] {:label (if (and id (not= id p)) (str p " / " id) p) :plugin p :id id}))))

;; ---------------------------------------------------------------------------
;; Comparing with source files

(defn- globs [compare]
  (cond (string? compare) [compare]
        (sequential? compare) (mapv str compare)
        :else []))

(defn- newest-file
  "{:path :modified} of the most recently modified project file matching
  any of `patterns`, or nil."
  [all-paths patterns]
  (when (seq patterns)
    (let [res (map sandbox/glob->regex patterns)]
      (->> all-paths
           (filter (fn [p] (some #(re-matches % p) res)))
           (map (fn [p] {:path p :modified (Instant/ofEpochMilli (.lastModified (repo/safe-resolve p)))}))
           (sort-by :modified)
           last))))

;; ---------------------------------------------------------------------------
;; Rows

(defn- failed?
  "Jobs log ok/failed/error; a line without :status (a script's note) is a success."
  [entry]
  (contains? #{"failed" "error"} (:status entry)))

(defn- row-state [entry newer]
  (cond
    (nil? entry) "never"
    (failed? entry) "failed"
    newer "stale"
    :else "ok"))

(defn- check-row
  "The overview row for `check`, given the log and (lazily) the file list."
  [entries all-paths check]
  (let [hits (filter #(entry-matches? check %) entries)
        entry (latest hits)
        last-ok (latest (remove failed? hits))
        newest (when (:compare check) (newest-file @all-paths (globs (:compare check))))
        ran (some-> entry :at parse-instant)
        newer (when (and newest ran (.isAfter ^Instant (:modified newest) ran)) newest)]
    {:label (str (or (:label check) (:plugin check) (:match check) "?"))
     :state (row-state entry newer)
     :last_run (:at entry)
     :status (:status entry)
     :last_ok (:at last-ok)
     :summary (:summary entry)
     :changed (when newer {:path (:path newer) :modified (str (:modified newer))})}))

;; ---------------------------------------------------------------------------
;; HTML

(defn- ago [iso now]
  (if-let [t (some-> iso parse-instant)]
    (let [s (max 0 (.getSeconds (Duration/between t now)))]
      (cond (< s 90) "just now"
            (< s 5400) (str (quot (+ s 30) 60) " min ago")
            (< s 129600) (str (quot (+ s 1800) 3600) " h ago")
            :else (str (quot (+ s 43200) 86400) " days ago")))
    "never"))

(def ^:private state-text
  {"ok" "✓ up to date" "stale" "⚠ code changed since" "failed" "✗ last run failed" "never" "– never run"})

(defn- detail [{:keys [state changed last_ok summary]} now]
  (cond
    changed (list "newer: " [:a {:href (str "#/file/" (:path changed))} [:code (:path changed)]]
                  " (" (ago (:modified changed) now) ")")
    (and (= state "failed") last_ok) (str "last passed " (ago last_ok now))
    (map? summary) (escape-html (str/join ", " (map (fn [[k v]] (str (name k) " " v)) summary)))
    (string? summary) (escape-html summary)
    :else ""))

(defn- row-html [row now]
  [:tr {:class (str "overview-" (:state row))}
   [:td (:label row)]
   [:td.overview-state (state-text (:state row))]
   [:td {:title (or (:last_run row) "")} (ago (:last_run row) now)]
   [:td.muted (detail row now)]])

(defn- panel-html [title rows]
  (let [now (Instant/now)]
    (hiccup/html
     [:div.plugin-panel.overview
      [:h3 (escape-html (or title "Overview"))]
      (if (empty? rows)
        [:p.muted "Nothing has been run yet. Rerun tests, coverage or linters "
         "and they will appear here."]
        [:table.overview-table
         [:thead [:tr [:th "Check"] [:th "State"] [:th "Last run"] [:th ""]]]
         [:tbody (for [r rows] (row-html (update r :label escape-html) now))]])])))

(defn run
  [{:keys [params]}]
  (let [entries (runlog/entries)
        checks (or (not-empty (:checks params)) (default-checks entries))
        all-paths (delay (repo/list-project-files))
        rows (mapv #(check-row entries all-paths %) checks)]
    {:html (panel-html (:title params) rows)
     :data {:checks rows}
     :text (str/join "\n" (map #(str (:label %) ": " (:state %) " (last run " (or (:last_run %) "never") ")") rows))}))

(def plugin
  {:name "overview"
   :description (str "When tests, coverage, linters and scans were last run, whether they passed, "
                     "and whether matching source files changed since. Reads the run log "
                     "(.sunlit/cache/runs.jsonl). Optional :checks [{:label :plugin :id :match :compare}].")
   :run run})
