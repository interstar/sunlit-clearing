(ns sunlit.sessions.codex
  "Codex CLI sessions: <codex-home>/sessions/YYYY/MM/DD/rollout-<time>-<id>.jsonl.
  The first record (`session_meta`) carries the session id, cwd and start time,
  so only sessions run in the repo are parsed further. Messages are
  `response_item` messages plus `event_msg` user/agent messages; Codex can log
  one message both ways, so repeats are dropped."
  (:require [clojure.string :as str]
            [sunlit.sessions.common :as common]))

(defn- file-session-id
  "rollout-2026-10-21T12-59-42-<uuid> -> <uuid> (the last five dash groups)."
  [f]
  (let [s (common/stem f)
        parts (str/split s #"-")]
    (if (and (str/starts-with? s "rollout-") (>= (count parts) 3))
      (str/join "-" (take-last 5 parts))
      s)))

(defn- content-text
  "Text of a response_item's content: a string, or the string text/message/content
  fields of its parts."
  [content]
  (cond
    (string? content) content
    (sequential? content) (->> content
                               (filter map?)
                               (mapcat #(map (fn [k] (get % k)) ["text" "message" "content"]))
                               (filter string?)
                               (remove str/blank?)
                               (str/join "\n"))
    :else ""))

(defn- record-message [record]
  (let [payload (get record "payload")
        ts (common/iso (get record "timestamp"))
        said (get payload "message")]
    (when (map? payload)
      (case [(get record "type") (get payload "type")]
        ["response_item" "message"]
        (common/message (or (get payload "role") "unknown") ts (content-text (get payload "content")) "codex")
        ["event_msg" "user_message"] (when (string? said) (common/message "user" ts said "codex"))
        ["event_msg" "agent_message"] (when (string? said) (common/message "assistant" ts said "codex"))
        nil))))

(defn- maybe-message?
  "Cheap test on a raw line: could it be a message record? Most of a Codex
  file is tool calls and their large outputs; a record's type and payload
  type come first, so only the start of the line is examined."
  [^String line]
  (boolean (re-find #"\"(?:message|user_message|agent_message)\""
                    (subs line 0 (min 200 (.length line))))))

(defn- session-meta [f]
  (get (common/first-json-line f 5 #(= "session_meta" (get % "type"))) "payload"))

(defn collect
  "Transcripts of the Codex sessions under `root` whose cwd is in `repo`."
  [root repo]
  (for [f (common/files-under root ".jsonl")
        :let [meta (session-meta f)]
        :when (and (map? meta) (common/within? repo (get meta "cwd")))
        :let [msgs (common/dedupe-messages (keep record-message (common/read-json-lines f maybe-message?)))]
        :when (seq msgs)]
    (common/transcript {:id (str "codex:" (or (get meta "id") (file-session-id f)))
                        :source "codex"
                        :cwd (get meta "cwd")
                        :slug (common/stem f)
                        :started-at (common/iso (get meta "timestamp"))}
                       msgs)))
