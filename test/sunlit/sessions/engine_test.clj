(ns sunlit.sessions.engine-test
  "Each agent's session format, read from synthetic stores in a temp dir."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sunlit.sessions.common :as common]
            [sunlit.sessions.engine :as engine])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.sql DriverManager]))

;; ---------------------------------------------------------------------------
;; Fixture helpers

(defn- temp-dir []
  (.getCanonicalFile (.toFile (Files/createTempDirectory "sunlit-sessions" (into-array FileAttribute [])))))

(defn- delete-tree! [^File dir]
  (doseq [^File f (reverse (file-seq dir))] (.delete f)))

(defmacro with-dir [[sym] & body]
  `(let [~sym (temp-dir)]
     (try ~@body (finally (delete-tree! ~sym)))))

(defn- write-jsonl! [f records]
  (io/make-parents f)
  (spit f (str/join (map #(str (json/generate-string %) "\n") records))))

(defn- write-json! [f data]
  (io/make-parents f)
  (spit f (json/generate-string data)))

(defn- sqlite! [^File f statements]
  (io/make-parents f)
  (with-open [conn (DriverManager/getConnection (str "jdbc:sqlite:" (.getPath f)))]
    (doseq [[sql & params] statements]
      (with-open [st (.prepareStatement conn sql)]
        (doseq [[i p] (map-indexed vector params)] (.setObject st (int (inc i)) p))
        (.executeUpdate st)))))

(defn- roots [dir]
  {:codex (io/file dir "codex") :pi (io/file dir "pi") :claude (io/file dir "claude")
   :cursor-agent (io/file dir "cursor") :cursor-ide (io/file dir "cursor-ide")})

(defn- by-id [result]
  (into {} (map (juxt #(get-in % [:session :session_id]) identity)) (:transcripts result)))

(defn- texts [t] (mapv :text (:messages t)))

;; ---------------------------------------------------------------------------
;; Sources

(deftest codex-sessions
  (with-dir [dir]
    (let [repo (io/file dir "repo")
          rec (fn [type payload] {"timestamp" "2026-01-01T00:00:01Z" "type" type "payload" payload})]
      (.mkdirs repo)
      (write-jsonl! (io/file dir "codex/2026/01/01/rollout-2026-01-01T00-00-00-aaaa-bbbb-cccc-dddd-eeee.jsonl")
                    [{"type" "session_meta" "payload" {"id" "s-1" "cwd" (str repo) "timestamp" "2026-01-01T00:00:00Z"}}
                     (rec "response_item" {"type" "message" "role" "user" "content" [{"type" "input_text" "text" "Hello"}]})
                     (rec "event_msg" {"type" "user_message" "message" "Hello"})  ; same message, logged twice
                     (rec "response_item" {"type" "function_call" "name" "shell" "arguments" "{\"message\":1}"})
                     (rec "event_msg" {"type" "agent_message" "message" "Hi there"})])
      (write-jsonl! (io/file dir "codex/2026/01/02/rollout-elsewhere.jsonl")
                    [{"type" "session_meta" "payload" {"id" "s-2" "cwd" (str dir "/other")}}
                     (rec "event_msg" {"type" "user_message" "message" "not ours"})])
      (let [t (get (by-id (engine/collect-for-repo repo (roots dir))) "codex:s-1")]
        (is (= ["Hello" "Hi there"] (texts t)))
        (is (= ["user" "assistant"] (mapv :role (:messages t))))
        (is (= "2026-01-01T00:00:00Z" (get-in t [:session :started_at])))
        (is (= 1 (count (:transcripts (engine/collect-for-repo repo (roots dir))))))))))

(deftest pi-sessions
  (with-dir [dir]
    (let [repo (io/file dir "repo")
          msg (fn [role content] {"type" "message" "timestamp" "2026-01-01T00:00:02Z"
                                  "message" {"role" role "content" content}})]
      (.mkdirs (io/file repo "sub"))
      (write-jsonl! (io/file dir "pi/--repo--/one.jsonl")
                    [{"type" "session" "id" "p-1" "cwd" (str repo "/sub") "timestamp" "2026-01-01T00:00:00Z"}
                     (msg "user" "Run the tests")
                     (msg "assistant" [{"type" "thinking" "thinking" "hmm"}])   ; thinking only: skipped
                     (msg "assistant" [{"type" "text" "text" "Running"} {"type" "toolCall" "name" "bash"}])
                     (msg "toolResult" [{"type" "toolResult" "content" [{"type" "text" "text" "3 passed"}]}])])
      (let [t (get (by-id (engine/collect-for-repo repo (roots dir))) "pi:p-1")]
        (is (= ["Run the tests" "Running" "3 passed"] (texts t)))
        (is (= ["user" "assistant" "toolResult"] (mapv :role (:messages t))))))))

(defn- claude-session [cwd]
  (let [base {"cwd" (str cwd) "sessionId" "c1"}
        rec (fn [type ts content & {:as extra}]
              (merge base {"type" type "timestamp" ts "message" {"role" type "content" content}} extra))]
    [(rec "user" "2026-01-01T00:00:00Z" "<caveat>" "isMeta" true)
     (rec "user" "2026-01-01T00:00:01Z" "List the files")
     (assoc-in (rec "assistant" "2026-01-01T00:00:02Z" [{"type" "thinking" "thinking" "" "signature" "x"}]) ["message" "id"] "m1")
     (assoc-in (rec "assistant" "2026-01-01T00:00:03Z" [{"type" "text" "text" "Listing now."}]) ["message" "id"] "m1")
     (assoc-in (rec "assistant" "2026-01-01T00:00:04Z" [{"type" "tool_use" "name" "Bash" "input" {"command" "ls"}}]) ["message" "id"] "m1")
     (rec "user" "2026-01-01T00:00:05Z" [{"type" "tool_result" "content" "a.txt"}])
     (assoc-in (rec "assistant" "2026-01-01T00:00:06Z" [{"type" "text" "text" "subagent"}] "isSidechain" true) ["message" "id"] "m9")
     (assoc-in (rec "assistant" "2026-01-01T00:00:07Z" [{"type" "text" "text" "One file."}]) ["message" "id"] "m2")
     {"type" "ai-title" "aiTitle" "List files" "sessionId" "c1"}]))

(deftest claude-sessions
  (with-dir [dir]
    (let [repo (io/file dir "work" "my_repo")
          projects (io/file dir "claude")]
      (.mkdirs (io/file repo "src"))
      (write-jsonl! (io/file projects (common/path-slug repo) "aaa.jsonl") (claude-session repo))
      (write-jsonl! (io/file projects (common/path-slug (io/file repo "src")) "bbb.jsonl") (claude-session (io/file repo "src")))
      ;; shares the slug prefix, but is a different directory
      (write-jsonl! (io/file projects (str (common/path-slug repo) "-old") "ccc.jsonl") (claude-session (str repo "-old")))
      (let [found (by-id (engine/collect-for-repo repo (roots dir)))
            t (get found "claude:aaa")]
        (is (= #{"claude:aaa" "claude:bbb"} (set (keys found))))
        (is (= ["user" "assistant" "tool" "assistant"] (mapv :role (:messages t))))
        (is (str/starts-with? (second (texts t)) "Listing now."))
        (is (str/includes? (second (texts t)) "<tool_call>\nBash\n{\n  \"command\": \"ls\"\n}"))
        (is (not-any? #(str/includes? % "subagent") (texts t)))
        (is (= "List files" (get-in t [:session :title])))
        (is (= "2026-01-01T00:00:00Z" (get-in t [:session :started_at])))))))  ; first record, meta or not

(deftest cursor-agent-sessions
  (with-dir [dir]
    (let [repo (io/file dir "my_repo")
          chat (io/file dir "cursor" "chats" "hash1" "agent-1")]
      (.mkdirs repo)
      (write-json! (io/file chat "meta.json") {"cwd" (str repo) "title" "Fix it" "createdAtMs" 1767225600000})
      (sqlite! (io/file chat "store.db")
               [["CREATE TABLE blobs (id TEXT, data BLOB)"]
                ["INSERT INTO blobs VALUES ('1', ?)" (.getBytes "{\"role\":\"user\",\"content\":\"<timestamp>Mon</timestamp> fix it\"}" "UTF-8")]
                ["INSERT INTO blobs VALUES ('2', ?)" (byte-array [0 1 2 3])]   ; binary blob: ignored
                ["INSERT INTO blobs VALUES ('3', ?)" (.getBytes "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}" "UTF-8")]])
      ;; transcript found via the chat's agent id, in any project folder
      (write-jsonl! (io/file dir "cursor" "projects" "somewhere" "agent-transcripts" "agent-1" "agent-1.jsonl")
                    [{"role" "user" "message" {"content" [{"type" "text" "text" "fix it"}]}}])
      ;; transcript with no chat meta, in this repo's project folder (underscore -> dash)
      (write-jsonl! (io/file dir "cursor" "projects" (str/replace (common/path-slug repo) #"^-+" "")
                             "agent-transcripts" "agent-2" "agent-2.jsonl")
                    [{"role" "user" "message" {"content" "orphan"}}])
      (let [found (by-id (engine/collect-for-repo repo (roots dir)))
            sq (get found "cursor-sqlite:agent-1")]
        (is (= #{"cursor-sqlite:agent-1" "cursor-jsonl:agent-1" "cursor-jsonl:agent-2"} (set (keys found))))
        (is (= ["user" "assistant"] (mapv :role (:messages sq))))
        (is (= "Mon" (:timestamp (first (:messages sq)))))
        (is (= "2026-01-01T00:00:00Z" (get-in sq [:session :started_at])))
        (is (= ["orphan"] (texts (get found "cursor-jsonl:agent-2"))))))))

(deftest cursor-ide-sessions
  (with-dir [dir]
    (let [repo (io/file dir "my repo")      ; a space: workspace folders are URIs
          ws (io/file dir "cursor-ide" "User" "workspaceStorage" "ws1")
          bubble (fn [id type text] [(str "bubbleId:comp-1:" id) (json/generate-string {"type" type "text" text})])]
      (.mkdirs repo)
      (write-json! (io/file ws "workspace.json") {"folder" (str (.toURI repo))})
      (sqlite! (io/file ws "state.vscdb")
               [["CREATE TABLE ItemTable (key TEXT UNIQUE ON CONFLICT REPLACE, value BLOB)"]
                ["INSERT INTO ItemTable VALUES ('composer.composerData', ?)"
                 (json/generate-string {"allComposers" [{"composerId" "comp-1" "name" "Explain" "createdAt" 1767225600000}]})]])
      (sqlite! (io/file dir "cursor-ide" "User" "globalStorage" "state.vscdb")
               (concat [["CREATE TABLE cursorDiskKV (key TEXT UNIQUE ON CONFLICT REPLACE, value BLOB)"]
                        ["INSERT INTO cursorDiskKV VALUES (?, ?)" "composerData:comp-1"
                         (json/generate-string {"fullConversationHeadersOnly" [{"bubbleId" "zz"} {"bubbleId" "aa"}]})]]
                       (for [[k v] [(bubble "aa" 2 "Answer") (bubble "zz" 1 "Question") (bubble "mm" 2 "Late")
                                    ["bubbleId:comp-2:x" (json/generate-string {"type" 1 "text" "other chat"})]]]
                         ["INSERT INTO cursorDiskKV VALUES (?, ?)" k v])))
      (let [t (get (by-id (engine/collect-for-repo repo (roots dir))) "cursor:comp-1")]
        (testing "conversation order from the header list, then the rest"
          (is (= ["Question" "Answer" "Late"] (texts t)))
          (is (= ["user" "assistant" "assistant"] (mapv :role (:messages t)))))
        (is (= "Explain" (get-in t [:session :slug])))
        (is (= "2026-01-01T00:00:00Z" (get-in t [:session :started_at])))))))

(deftest missing-stores-give-no-sessions
  (with-dir [dir]
    (is (= {:repo (str dir) :transcripts [] :warnings []}
           (engine/collect-for-repo dir (roots (io/file dir "nothing")))))))

;; ---------------------------------------------------------------------------
;; Analysis

(def ^:private transcripts
  [(common/transcript {:id "a" :source "codex" :started-at "2026-01-02T00:00:00Z"}
                      [{:role "user" :timestamp "2026-01-02T00:00:00Z" :text "fix the parser bug" :source "codex"}
                       {:role "assistant" :timestamp "2026-01-02T00:00:01Z" :text "Parser fixed; parser tests pass" :source "codex"}])
   (common/transcript {:id "b" :source "claude" :started-at "2026-01-05T00:00:00Z"}
                      [{:role "user" :timestamp "2026-01-05T00:00:00Z" :text "write docs" :source "claude"}])])

(deftest search-messages-ranks-and-filters
  (let [hits (engine/search-messages transcripts "parser")]
    (is (= [["a" "assistant" 6] ["a" "user" 1]] (mapv (juxt :session_id :role :score) hits))))
  (is (= ["user"] (map :role (engine/search-messages transcripts "parser" {:role "user"}))))
  (is (empty? (engine/search-messages transcripts "parser" {:from (java.time.LocalDate/parse "2026-01-03")})))
  (is (empty? (engine/search-messages transcripts "  "))))

(deftest filter-sessions-scores
  (is (= [["a" 5]] (mapv (juxt :session_id :score) (engine/filter-sessions transcripts {:query "parser"}))))
  (is (= ["b" "a"] (mapv :session_id (engine/filter-sessions transcripts {})))))

(deftest stats-and-export
  (let [s (engine/stats transcripts)]
    (is (= {"codex" 1 "claude" 1} (:by_source s)))
    (is (= 3 (:messages s)))
    (is (= {"user" 2 "assistant" 1} (:roles s)))
    (is (= "2026-01-02T00:00:00Z" (:first_started s))))
  (let [md (engine/export-markdown (first transcripts))]
    (is (str/starts-with? md "# a\n\n- Session ID: `a`\n- Source: codex\n"))
    (is (str/includes? md "## 2. Assistant\n\n_Timestamp: 2026-01-02T00:00:01Z_\n\nParser fixed; parser tests pass"))))
