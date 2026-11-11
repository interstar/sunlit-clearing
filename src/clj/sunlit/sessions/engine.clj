(ns sunlit.sessions.engine
  "Session analysis across every agent: collect the transcripts of sessions
  run in a repo (Codex, Pi, Claude Code, Cursor agent, Cursor IDE), then
  search them, filter them, count and summarise them, and export one as
  Markdown. Operates on transcripts ({:session entry :messages [...]}, see
  sunlit.sessions.common), so it works the same on fresh or cached data."
  (:require [clojure.string :as str]
            [sunlit.sessions.claude :as claude]
            [sunlit.sessions.codex :as codex]
            [sunlit.sessions.common :as common]
            [sunlit.sessions.cursor-agent :as cursor-agent]
            [sunlit.sessions.cursor-ide :as cursor-ide]
            [sunlit.sessions.pi :as pi]))

;; ---------------------------------------------------------------------------
;; Collection

(def ^:private sources
  "[name roots-key collect-fn] for each agent."
  [["codex" :codex codex/collect]
   ["pi" :pi pi/collect]
   ["claude" :claude claude/collect]
   ["cursor-agent" :cursor-agent cursor-agent/collect]
   ["cursor-ide" :cursor-ide cursor-ide/collect]])

(defn- collect-source
  "{:transcripts [...]} from one source, or {:warning msg} when it failed (one
  unreadable agent store must not hide the others)."
  [[source-name k collect-fn] roots repo]
  (try
    {:transcripts (vec (collect-fn (get roots k) repo))}
    (catch Exception e
      {:warning (str source-name ": " (or (ex-message e) (str e)))})))

(defn- started-key [t]
  (or (some-> (common/instant (get-in t [:session :started_at])) .toEpochMilli) 0))

(defn collect-for-repo
  "Transcripts of every session run in `repo-dir` or below it, newest first,
  as {:repo :transcripts [...] :warnings [...]}. `roots` overrides where each
  source's sessions live (see common/default-roots)."
  ([repo-dir] (collect-for-repo repo-dir (common/default-roots)))
  ([repo-dir roots]
   (let [repo (common/canonical-path repo-dir)
         results (map #(collect-source % roots repo) sources)]
     {:repo repo
      :transcripts (vec (sort-by started-key > (mapcat :transcripts results)))
      :warnings (vec (keep :warning results))})))

;; ---------------------------------------------------------------------------
;; Counting

(defn role-counts
  "{role n} over the messages of `transcripts`."
  [transcripts]
  (frequencies (mapcat #(map :role (:messages %)) transcripts)))

(defn stats
  "Sessions and messages per source, role counts and date range."
  [transcripts]
  (let [entries (map :session transcripts)
        starts (sort (keep #(common/instant (:started_at %)) entries))]
    {:sessions (count transcripts)
     :messages (reduce + (map #(count (:messages %)) transcripts))
     :by_source (frequencies (map :source entries))
     :roles (role-counts transcripts)
     :first_started (some-> (first starts) str)
     :last_started (some-> (last starts) str)}))

;; ---------------------------------------------------------------------------
;; Search and filter

(defn- occurrences [haystack needle]
  (count (re-seq (re-pattern (java.util.regex.Pattern/quote needle)) haystack)))

(defn- in-range?
  "Whether `day` (a LocalDate or nil) lies within optional `from`..`to`;
  with any bound set, an undated message does not."
  [day from to]
  (or (and (nil? from) (nil? to))
      (and day
           (or (nil? from) (not (.isBefore day from)))
           (or (nil? to) (not (.isAfter day to))))))

(defn- snippet
  "Up to `radius` characters either side of the first match of `query`."
  [text query radius]
  (let [i (str/index-of (str/lower-case text) (str/lower-case query))]
    (if (nil? i)
      (subs text 0 (min (count text) (* 2 radius)))
      (let [start (max 0 (- i radius))
            end (min (count text) (+ i (count query) radius))]
        (str (when (pos? start) "...") (subs text start end) (when (< end (count text)) "..."))))))

(defn- message-hit
  "A search hit for message `m` of `entry`, or nil when it doesn't match."
  [entry m needle query {:keys [role from to]}]
  (let [folded (str/lower-case (:text m))
        day (common/utc-date (or (:timestamp m) (:started_at entry)))]
    (when (and (or (nil? role) (= role "all") (= role (:role m)))
               (str/includes? folded needle)
               (in-range? day from to))
      {:session_id (:session_id entry)
       :source (:source entry)
       :timestamp (:timestamp m)
       :role (:role m)
       :snippet (snippet (:text m) query 110)
       :score (+ (occurrences folded needle) (if (str/starts-with? folded needle) 4 0))})))

(defn- hit-order [hit]
  [(- (:score hit)) (- (or (some-> (common/instant (:timestamp hit)) .toEpochMilli) 0))])

(defn search-messages
  "Messages in `transcripts` containing `query` (case-insensitive), best first:
  {:session_id :source :timestamp :role :snippet :score}. opts: :role (or
  \"all\"), :from / :to (LocalDate, inclusive), :limit (default 200)."
  [transcripts query & [{:keys [limit] :or {limit 200} :as opts}]]
  (let [query (str/trim (str query))
        needle (str/lower-case query)]
    (if (str/blank? query)
      []
      (->> (for [{:keys [session messages]} transcripts
                 m messages
                 :let [hit (message-hit session m needle query opts)]
                 :when hit]
             hit)
           (sort-by hit-order)
           (take limit)
           vec))))

(defn- message-score
  "1 + occurrences of `needle` in `m` when it occurs (0 when not); 1 without a needle."
  [needle m]
  (if needle
    (let [n (occurrences (str/lower-case (:text m)) needle)]
      (if (pos? n) (inc n) 0))
    1))

(defn- session-score
  "A transcript's filter score: summed message scores with a query, else 1 if
  any message passes the role/date filters."
  [{:keys [session messages]} needle {:keys [role from to]}]
  (->> messages
       (filter #(and (or (nil? role) (= "all" role) (= role (:role %)))
                     (in-range? (common/utc-date (or (:started_at session) (:timestamp %))) from to)))
       (map #(message-score needle %))
       (reduce (if needle + max) 0)))

(defn filter-sessions
  "Session entries with at least one message matching opts (:query, :role,
  :from, :to; see search-messages), each with a :score: total query
  occurrences + one per matching message, or 1 without a query. Best first,
  then newest."
  [transcripts {:keys [query] :as opts}]
  (let [needle (some-> query str/trim str/lower-case not-empty)]
    (->> transcripts
         (map #(assoc (:session %) :score (session-score % needle opts)))
         (filter #(pos? (:score %)))
         (sort-by (juxt (comp - :score)
                        #(- (or (some-> (common/instant (:started_at %)) .toEpochMilli) 0))))
         vec)))

;; ---------------------------------------------------------------------------
;; Export

(defn- role-heading [role]
  (get {"user" "User" "assistant" "Assistant" "developer" "Developer"
        "toolResult" "Tool Result" "tool" "Tool"}
       role
       (str/join " " (map str/capitalize (str/split (str role) #"_")))))

(defn- message-section [i {:keys [role timestamp text]}]
  [(str "## " (inc i) ". " (role-heading role)) ""
   (str "_Timestamp: " (or timestamp "unknown") "_") ""
   (str/trimr text) ""])

(defn export-markdown
  "Markdown document for one transcript: a header with the session details,
  then one numbered section per message."
  [{:keys [session messages]}]
  (let [{:keys [session_id source started_at cwd title slug]} session
        header [(str "# " (or title slug session_id)) ""
                (str "- Session ID: `" session_id "`")
                (str "- Source: " source)
                (str "- Started: " (or started_at "unknown"))
                (str "- Working Directory: `" (or cwd "unknown") "`")
                (str "- Messages: " (count messages)) ""]
        body (if (seq messages)
               (mapcat message-section (range) messages)
               ["_No messages found for this session._" ""])]
    (str (str/trimr (str/join "\n" (concat header body))) "\n")))
