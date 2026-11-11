(ns sunlit.sessions.cursor-agent
  "Cursor agent (CLI) sessions under ~/.cursor:

  - chats/<hash>/<agent-id>/meta.json (cwd, title, creation time) beside a
    store.db SQLite file whose `blobs` table holds the messages as JSON
    ({role content}), in rowid order: source \"cursor-sqlite\".
  - projects/<slug>/agent-transcripts/<agent-id>/<agent-id>.jsonl, the same
    conversation as JSON lines ({role message}): source \"cursor-jsonl\".
    <slug> is the project path, non-alphanumerics replaced by \"-\", without
    the leading one. Transcripts for agents with no matching chat meta are
    picked up from the repo's own project folder."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [sunlit.sessions.common :as common]
            [sunlit.sessions.sqlite :as sqlite])
  (:import [java.io File]))

(def ^:private max-system-chars 4000)

(defn- timestamp-in
  "The `<timestamp>…</timestamp>` Cursor embeds in a message, as written
  (e.g. \"Tuesday, Sep 1, 2026, 7:43 PM (UTC+1)\")."
  [text]
  (when (string? text)
    (second (re-find #"(?i)<timestamp>\s*([^<]+?)\s*</timestamp>" text))))

(defn- message [role text source]
  (let [text (if (and (= role "system") (> (count text) max-system-chars))
               (str (subs text 0 max-system-chars) "\n…(truncated)")
               text)]
    (common/message role (timestamp-in text) text source)))

(defn- parse-json [s]
  (try (json/parse-string s) (catch Exception _ nil)))

(defn sqlite-messages
  "Messages from a chat's store.db, in rowid order."
  [^File store]
  ;; JSON rows only (first byte "{"): the table also holds large binary blobs
  (->> (sqlite/query-or-empty store "SELECT data FROM blobs WHERE hex(substr(data, 1, 1)) = '7B' ORDER BY rowid")
       (keep (fn [[data]]
               (let [obj (when (string? data) (parse-json data))]
                 (when (and (map? obj) (contains? obj "role"))
                   (message (str (or (get obj "role") "unknown"))
                            (common/flatten-content (get obj "content"))
                            "cursor-sqlite")))))
       vec))

(defn jsonl-messages
  "Messages from an agent-transcript JSONL file."
  [f]
  (->> (common/read-json-lines f)
       (keep (fn [obj]
               (let [m (get obj "message")]
                 (message (str (or (get obj "role") "unknown"))
                          (common/flatten-content (if (map? m) (get m "content") m))
                          "cursor-jsonl"))))
       vec))

(defn- transcript-file
  "<agent-id>.jsonl in an agent-transcripts folder, else its first .jsonl."
  [^File agent-dir]
  (let [named (io/file agent-dir (str (.getName agent-dir) ".jsonl"))]
    (if (.isFile named) named (first (common/files-under agent-dir ".jsonl")))))

(defn- jsonl-for-agent
  "The agent's transcript file in any project folder, or nil."
  [^File root agent-id]
  (let [projects (io/file root "projects")]
    (some #(let [d (io/file % "agent-transcripts" agent-id)]
             (when (.isDirectory d) (transcript-file d)))
          (common/children projects))))

(defn- chat-metas
  "[meta-file meta] for every chat whose cwd is in `repo`."
  [root repo]
  (for [chat-hash (common/children (io/file root "chats"))
        agent-dir (common/children chat-hash)
        :let [f (io/file agent-dir "meta.json")
              meta (when (.isFile f) (parse-json (slurp f)))]
        :when (and (map? meta) (common/within? repo (get meta "cwd")))]
    [f meta]))

(defn- chat-transcripts
  "The sqlite and jsonl transcripts of one chat."
  [root [^File meta-file meta]]
  (let [agent-id (str (or (get meta "agentId") (.getName (.getParentFile meta-file))))
        title (or (get meta "title") (get meta "name"))
        info {:cwd (get meta "cwd") :title title :slug (or title agent-id)
              :started-at (common/ms->iso (or (get meta "createdAtMs") (get meta "createdAt")))}
        sqlite-msgs (sqlite-messages (io/file (.getParentFile meta-file) "store.db"))
        jsonl-msgs (some-> (jsonl-for-agent root agent-id) jsonl-messages)]
    (cond-> []
      (seq sqlite-msgs) (conj (common/transcript (assoc info :id (str "cursor-sqlite:" agent-id) :source "cursor-sqlite") sqlite-msgs))
      (seq jsonl-msgs) (conj (common/transcript (assoc info :id (str "cursor-jsonl:" agent-id) :source "cursor-jsonl") jsonl-msgs)))))

(defn- project-transcripts
  "JSONL transcripts in the repo's own project folder not already found via chats."
  [root repo seen-ids]
  (let [dir (io/file root "projects" (str/replace (common/path-slug repo) #"^-+" "") "agent-transcripts")]
    (for [agent-dir (common/children dir)
          :let [agent-id (.getName ^File agent-dir)
                f (when (.isDirectory ^File agent-dir) (transcript-file agent-dir))
                msgs (when (and f (not (seen-ids (str "cursor-jsonl:" agent-id)))) (jsonl-messages f))]
          :when (seq msgs)]
      (common/transcript {:id (str "cursor-jsonl:" agent-id) :source "cursor-jsonl"
                          :cwd repo :slug agent-id}
                         msgs))))

(defn collect
  "Transcripts of the Cursor agent sessions under `root` run in `repo` or below."
  [root repo]
  (let [from-chats (vec (mapcat #(chat-transcripts root %) (chat-metas root repo)))
        seen (set (map #(get-in % [:session :session_id]) from-chats))]
    (into from-chats (project-transcripts root repo seen))))
