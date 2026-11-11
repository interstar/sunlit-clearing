(ns sunlit.sessions.cursor-ide
  "Cursor IDE (editor) chats, stored under ~/.config/Cursor/User:

  - workspaceStorage/<hash>/workspace.json names the workspace folder (a file:
    URI) and the state.vscdb beside it lists that workspace's chats
    (\"composers\": id, name, creation time) under ItemTable key
    `composer.composerData`.
  - globalStorage/state.vscdb (often gigabytes) holds every message
    (\"bubble\") as JSON in cursorDiskKV under `bubbleId:<composer>:<bubble>`,
    and each chat's true message order under `composerData:<composer>`.

  Only the repo's chats are read, each by a key-range query, so the large
  global store is never scanned. Bubble type 1 is the user, 2 the assistant."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [sunlit.sessions.common :as common]
            [sunlit.sessions.sqlite :as sqlite])
  (:import [java.io File]
           [java.net URI]))

(defn- parse-json [s]
  (when (string? s)
    (try (json/parse-string s) (catch Exception _ nil))))

(defn- folder-path
  "Local path of a workspace's `folder` URI, or nil for remote/odd URIs."
  [uri]
  (when (and (string? uri) (str/starts-with? uri "file:"))
    (try (.getPath (URI. uri)) (catch Exception _ nil))))

(defn- workspace-folder [^File ws-dir]
  (let [f (io/file ws-dir "workspace.json")]
    (when (.isFile f)
      (some-> (parse-json (slurp f)) (get "folder") folder-path))))

(defn- workspace-composers
  "Composer maps listed in one workspace's state.vscdb."
  [^File ws-dir]
  (let [rows (sqlite/query-or-empty (io/file ws-dir "state.vscdb")
                                    "SELECT value FROM ItemTable WHERE key = 'composer.composerData' LIMIT 1")]
    (filter #(string? (get % "composerId"))
            (get (parse-json (ffirst rows)) "allComposers"))))

(defn repo-composers
  "{:id :name :cwd :created :updated} for each chat of a workspace in `repo`."
  [user-dir repo]
  (for [ws-dir (common/children (io/file user-dir "workspaceStorage"))
        :let [folder (workspace-folder ws-dir)]
        :when (common/within? repo folder)
        c (workspace-composers ws-dir)]
    {:id (get c "composerId")
     :name (not-empty (str (get c "name")))
     :cwd folder
     :created (common/ms->iso (get c "createdAt"))
     :updated (common/ms->iso (get c "lastUpdatedAt"))}))

(defn- bubble-message [bubble]
  (let [text (get bubble "text")]
    (when (string? text)
      (common/message (case (get bubble "type") 1 "user" 2 "assistant" "unknown")
                      (some common/ms->iso [(get bubble "unixMs") (get bubble "createdAt")
                                            (get bubble "lastUpdatedAt") (get bubble "timestamp")])
                      text
                      "cursor"))))

(defn- bubbles
  "{bubble-id bubble} for one composer, by key range (uses the key index)."
  [global-db composer-id]
  (into {}
        (keep (fn [[k v]]
                (when-let [b (parse-json v)]
                  [(last (str/split k #":" 3)) b])))
        (sqlite/query-or-empty global-db
                               "SELECT key, value FROM cursorDiskKV WHERE key >= ? AND key < ?"
                               (str "bubbleId:" composer-id ":")
                               (str "bubbleId:" composer-id ";"))))

(defn- conversation-order
  "Bubble ids in conversation order, from the composer's header list."
  [global-db composer-id]
  (->> (sqlite/query-or-empty global-db "SELECT value FROM cursorDiskKV WHERE key = ?"
                              (str "composerData:" composer-id))
       ffirst
       parse-json
       (#(get % "fullConversationHeadersOnly"))
       (keep #(get % "bubbleId"))))

(defn composer-messages
  "Messages of one chat in conversation order; bubbles missing from the
  header list follow in key order."
  [global-db composer-id]
  (let [by-id (bubbles global-db composer-id)
        ordered (filter by-id (conversation-order global-db composer-id))
        rest-ids (remove (set ordered) (sort (keys by-id)))]
    (common/dedupe-messages (keep (comp bubble-message by-id) (concat ordered rest-ids)))))

(defn collect
  "Transcripts of the Cursor IDE chats under `root` (~/.config/Cursor) whose
  workspace is `repo` or inside it."
  [root repo]
  (let [user-dir (io/file root "User")
        global-db (io/file user-dir "globalStorage" "state.vscdb")]
    (when (.isFile global-db)
      (for [{:keys [id name cwd created updated]} (repo-composers user-dir repo)
            :let [msgs (composer-messages global-db id)]
            :when (seq msgs)]
        (common/transcript {:id (str "cursor:" id) :source "cursor" :cwd cwd
                            :slug (or name id) :title name
                            :started-at (or (common/earliest (map :timestamp msgs)) created updated)}
                           msgs)))))
