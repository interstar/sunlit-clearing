(ns sunlit.sessions.common
  "Shared pieces for reading agent transcripts: where each agent keeps them,
  JSON-lines files, timestamps, matching a session's working directory to a
  repo, content flattening, and the session/message maps every source returns.

  A message is {:role :timestamp :text :source}; a transcript is
  {:session entry :messages [message ...]}, where entry is
  {:session_id :source :cwd :slug :title :message_count :started_at}.
  Source data is parsed with string keys, as written by the agents."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.time Instant OffsetDateTime ZoneOffset]))

;; ---------------------------------------------------------------------------
;; Where agents keep their sessions

(defn- home-file [& parts]
  (apply io/file (System/getProperty "user.home") parts))

(defn- env-dir
  "$`var` when set, else ~/`fallback`."
  [var fallback]
  (if-let [v (not-empty (System/getenv var))]
    (io/file v)
    (home-file fallback)))

(defn default-roots
  "Session stores, per source. Codex and Claude Code honour CODEX_HOME and
  CLAUDE_CONFIG_DIR, as those tools do."
  []
  {:codex (io/file (env-dir "CODEX_HOME" ".codex") "sessions")
   :pi (home-file ".pi" "agent" "sessions")
   :claude (io/file (env-dir "CLAUDE_CONFIG_DIR" ".claude") "projects")
   :cursor-agent (home-file ".cursor")
   :cursor-ide (home-file ".config" "Cursor")})

;; ---------------------------------------------------------------------------
;; JSON lines

(defn- parse-json-line [line]
  (when-not (str/blank? line)
    (try
      (let [v (json/parse-string line)]
        (when (map? v) v))
      (catch Exception _ nil))))

(defn read-json-lines
  "Every JSON object in JSON-lines file `f` (blank and malformed lines
  skipped). With `wanted?`, only lines whose raw text passes it are parsed: a
  cheap pre-filter for files where most records are large and irrelevant."
  ([f] (read-json-lines f (constantly true)))
  ([f wanted?]
   (with-open [r (io/reader f :encoding "UTF-8")]
     (into [] (comp (filter wanted?) (keep parse-json-line)) (line-seq r)))))

(defn first-json-line
  "The first object among the opening `n` lines of `f` that satisfies `pred`."
  [f n pred]
  (with-open [r (io/reader f :encoding "UTF-8")]
    (some #(let [o (parse-json-line %)] (when (and o (pred o)) o))
          (take n (line-seq r)))))

(defn children
  "Entries of `dir` in name order; none when `dir` is not a directory (an
  agent that isn't installed has no store)."
  [^File dir]
  (if (.isDirectory dir)
    (sort-by #(.getName ^File %) (.listFiles dir))
    []))

(defn files-under
  "Files below `dir` whose names end with `suffix`, in path order."
  [^File dir suffix]
  (if (.isDirectory dir)
    (->> (file-seq dir)
         (filter #(and (.isFile ^File %) (str/ends-with? (.getName ^File %) suffix)))
         (sort-by #(.getPath ^File %)))
    []))

(defn stem [^File f]
  (str/replace (.getName f) #"\.[^.]+$" ""))

;; ---------------------------------------------------------------------------
;; Timestamps (always reported as ISO-8601 instants)

(defn instant
  "An Instant from an ISO-8601 string (with or without an offset), else nil."
  [s]
  (when (string? s)
    (or (try (Instant/parse s) (catch Exception _ nil))
        (try (.toInstant (OffsetDateTime/parse s)) (catch Exception _ nil)))))

(defn iso
  "`s` normalised to an ISO instant string, or nil when it isn't a timestamp."
  [s]
  (some-> (instant s) str))

(defn ms->iso
  "Epoch milliseconds (a number or numeric string) as an ISO instant string;
  an ISO string passes through `iso`."
  [v]
  (cond
    (number? v) (str (Instant/ofEpochMilli (long v)))
    (and (string? v) (re-matches #"\d+(\.\d+)?" v)) (str (Instant/ofEpochMilli (long (Double/parseDouble v))))
    :else (iso v)))

(defn utc-date
  "UTC calendar date of ISO timestamp `s`, or nil."
  [s]
  (some-> (instant s) (.atOffset ZoneOffset/UTC) .toLocalDate))

(defn earliest
  "Earliest parseable ISO timestamp among `stamps`, or nil."
  [stamps]
  (->> stamps (keep instant) sort first (#(some-> % str))))

;; ---------------------------------------------------------------------------
;; Repo matching

(defn canonical-path
  "Canonical form of `path` (symlinks resolved, `~` expanded), or nil."
  [path]
  (when-not (str/blank? (str path))
    (let [p (str/replace-first (str path) #"^~(?=/|$)" (System/getProperty "user.home"))]
      (try (.getCanonicalPath (io/file p)) (catch Exception _ nil)))))

(defn within?
  "Whether working directory `cwd` is `root` (a canonical path) or inside it."
  [root cwd]
  (when-let [c (canonical-path cwd)]
    (or (= c root) (str/starts-with? c (str root File/separator)))))

(defn path-slug
  "`path` with every non-alphanumeric character replaced by \"-\": how Claude
  Code and Cursor name their per-project folders."
  [path]
  (str/replace (str path) #"[^A-Za-z0-9]" "-"))

;; ---------------------------------------------------------------------------
;; Messages and transcripts

(defn message
  "A message map, or nil when `text` is blank."
  [role timestamp text source]
  (let [t (str/trim (str text))]
    (when-not (str/blank? t)
      {:role (str role) :timestamp timestamp :text t :source source})))

(defn dedupe-messages
  "`msgs` without repeats of the same (timestamp, role, text): Codex and Pi
  can log one message twice."
  [msgs]
  (first (reduce (fn [[out seen] m]
                   (let [k ((juxt :timestamp :role :text) m)]
                     (if (seen k) [out seen] [(conj out m) (conj seen k)])))
                 [[] #{}]
                 msgs)))

(defn transcript
  "{:session entry :messages msgs} from a session description and its messages."
  [{:keys [id source cwd slug title started-at]} msgs]
  {:session {:session_id id
             :source source
             :cwd cwd
             :slug (or slug title id)
             :title title
             :message_count (count msgs)
             :started_at (or started-at (earliest (map :timestamp msgs)))}
   :messages (vec msgs)})

;; ---------------------------------------------------------------------------
;; Content flattening (Claude Code and Cursor agent content blocks)

(def ^:private max-tool-chars 8000)

(defn- clip [s]
  (if (> (count s) max-tool-chars) (str (subs s 0 max-tool-chars) "\n…(truncated)") s))

(def ^:private pretty-opts
  {:pretty {:indentation 2 :indent-arrays? true :object-field-value-separator ": "}})

(defn- pretty [v]
  (if (string? v) v (json/generate-string v pretty-opts)))

(defn- tool-call-text [part]
  (let [tool (or (get part "name") (get part "toolName") "tool")
        args (or (get part "arguments") (get part "input") (get part "args"))]
    (str "<tool_call>\n" tool "\n" (when (some? args) (clip (pretty args))) "\n</tool_call>")))

(defn- tool-result-text [part]
  (let [body (or (get part "content") (get part "output") (get part "result") part)]
    (str "<tool_result>\n" (clip (pretty body)) "\n</tool_result>")))

(defn- part-text [part]
  (cond
    (string? part) (when-not (str/blank? part) part)
    (not (map? part)) nil
    :else (case (get part "type")
            ("text" "reasoning") (not-empty (str (get part "text")))
            ("tool-call" "tool_use" "tool-call-start") (tool-call-text part)
            ("tool-result" "tool_result") (tool-result-text part)
            nil)))

(defn flatten-content
  "Readable text for message `content`: a string, or a list of text,
  reasoning, tool-call and tool-result blocks (others dropped)."
  [content]
  (cond
    (nil? content) ""
    (string? content) content
    (sequential? content) (str/join "\n\n" (keep part-text content))
    :else (pretty content)))
