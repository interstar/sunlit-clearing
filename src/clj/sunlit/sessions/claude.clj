(ns sunlit.sessions.claude
  "Claude Code sessions: <config>/projects/<slug>/<session-id>.jsonl, where
  <slug> is the session's cwd with every non-alphanumeric character replaced
  by \"-\". Slugs are lossy (`repo` and `repo-old` share a prefix), so candidate
  folders are found by slug and each session is confirmed by its records' cwd.

  One assistant reply is written as several records (one per content block)
  sharing a message id; they are joined back together. User records that only
  carry tool results become role \"tool\". Meta and sidechain (subagent)
  records, and empty (redacted) thinking blocks, are left out."
  (:require [clojure.string :as str]
            [sunlit.sessions.common :as common])
  (:import [java.io File]))

(defn- thinking->reasoning
  "Non-empty thinking as a reasoning block (which flatten-content shows);
  redacted, empty thinking dropped."
  [content]
  (if (sequential? content)
    (keep (fn [part]
            (if (and (map? part) (= "thinking" (get part "type")))
              (when-not (str/blank? (get part "thinking"))
                {"type" "reasoning" "text" (get part "thinking")})
              part))
          content)
    content))

(defn- role [record]
  (let [content (get-in record ["message" "content"])]
    (if (and (= "user" (get record "type"))
             (sequential? content)
             (seq content)
             (every? #(and (map? %) (= "tool_result" (get % "type"))) content))
      "tool"
      (get record "type"))))

(defn- conversation-record? [record]
  (and (#{"user" "assistant"} (get record "type"))
       (map? (get record "message"))
       (not (get record "isMeta"))
       (not (get record "isSidechain"))))

(defn- turn
  "{:id :msg} for one record: the assistant message id (nil for user turns)
  and its message, whose text may be blank."
  [record]
  {:id (when (= "assistant" (get record "type")) (get-in record ["message" "id"]))
   :msg {:role (role record)
         :timestamp (common/iso (get record "timestamp"))
         :text (str/trim (common/flatten-content (thinking->reasoning (get-in record ["message" "content"]))))
         :source "claude"}})

(defn- join-turn
  "Add `t` to `msgs`, appending to the previous message when it continues the
  same assistant message."
  [{:keys [msgs last-id]} {:keys [id msg]}]
  (if (and id (= id last-id) (seq msgs))
    {:msgs (update-in msgs [(dec (count msgs)) :text] #(str/trim (str % "\n\n" (:text msg))))
     :last-id id}
    {:msgs (conj msgs msg) :last-id id}))

(defn messages
  "Messages of the session in `records`, one per turn."
  [records]
  (->> records
       (filter conversation-record?)
       (map turn)
       (reduce join-turn {:msgs [] :last-id nil})
       :msgs
       (remove #(str/blank? (:text %)))
       vec))

(defn- session-info
  "cwd, start time and (latest) AI-written title of a session's records."
  [records]
  {:cwd (some #(get % "cwd") records)
   :started-at (some #(when (#{"user" "assistant"} (get % "type")) (common/iso (get % "timestamp"))) records)
   :title (last (keep #(when (= "ai-title" (get % "type")) (not-empty (get % "aiTitle"))) records))})

(defn- candidate-dirs
  "Project folders that may hold sessions run in `repo` or below it."
  [^File root repo]
  (let [slug (common/path-slug repo)]
    (filter #(and (.isDirectory ^File %)
                  (let [n (.getName ^File %)] (or (= n slug) (str/starts-with? n (str slug "-")))))
            (common/children root))))

(defn- session-transcript [repo f]
  (let [records (common/read-json-lines f)
        {:keys [cwd] :as info} (session-info records)
        msgs (when (common/within? repo cwd) (messages records))]
    (when (seq msgs)
      (common/transcript (assoc info :id (str "claude:" (common/stem f)) :source "claude"
                                :slug (or (:title info) (common/stem f)))
                         msgs))))

(defn collect
  "Transcripts of the Claude Code sessions under `root` run in `repo` or below."
  [root repo]
  (for [dir (candidate-dirs root repo)
        f (common/files-under dir ".jsonl")
        :when (= dir (.getParentFile ^File f))   ; not subagent files in subfolders
        :let [t (session-transcript repo f)]
        :when t]
    t))
