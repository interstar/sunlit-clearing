(ns sunlit.sessions.pi
  "Pi agent sessions: ~/.pi/agent/sessions/<project>/<session>.jsonl. A
  `session` header record carries the id, cwd and start time; `message`
  records carry {role content}. Tool results (role `toolResult`) include the
  text of their nested content; assistant turns with no text or tool call
  (thinking only) are skipped."
  (:require [clojure.string :as str]
            [sunlit.sessions.common :as common]))

(defn- nested-text [inner]
  (cond
    (string? inner) [inner]
    (sequential? inner) (keep #(when (map? %) (some-> (get % "text") str)) inner)
    :else []))

(defn- block-text [role block]
  (concat (when (string? (get block "text")) [(get block "text")])
          (when (and (= role "toolResult") (contains? block "content"))
            (nested-text (get block "content")))))

(defn- content-text [content role]
  (cond
    (string? content) content
    (sequential? content) (str/join "\n" (mapcat #(when (map? %) (block-text role %)) content))
    :else ""))

(defn- visible?
  "Whether assistant `content` has text or a tool call (not just thinking)."
  [content]
  (if (string? content)
    (not (str/blank? content))
    (boolean (some #(and (map? %) (or (contains? % "text") (contains? % "toolCall"))) content))))

(defn- record-message [record]
  (let [{:strs [role content]} (get record "message")
        role (str (or role "unknown"))]
    (when (and (= "message" (get record "type"))
               (or (not= role "assistant") (visible? content)))
      (common/message role (common/iso (get record "timestamp")) (content-text content role) "pi"))))

(defn- header [f]
  (common/first-json-line f 3 #(= "session" (get % "type"))))

(defn collect
  "Transcripts of the Pi sessions under `root` whose cwd is in `repo`."
  [root repo]
  (for [f (common/files-under root ".jsonl")
        :let [head (header f)]
        :when (and head (common/within? repo (get head "cwd")))
        :let [msgs (common/dedupe-messages (keep record-message (common/read-json-lines f)))]
        :when (seq msgs)]
    (common/transcript {:id (str "pi:" (or (not-empty (get head "id")) (common/stem f)))
                        :source "pi"
                        :cwd (get head "cwd")
                        :slug (or (get head "cwd") (common/stem f))
                        :started-at (common/iso (get head "timestamp"))}
                       msgs)))
