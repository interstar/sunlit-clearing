(ns sunlit.sessions-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sunlit.repo :as repo]
            [sunlit.sessions :as sessions]
            [sunlit.sessions.common :as common])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-repo [f]
  (let [dir (.toFile (Files/createTempDirectory "sunlit-sess" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(deftest list-without-cache-is-never
  (with-temp-repo
    (fn [_]
      (let [idx (sessions/list-for-repo)]
        (is (:ok idx))
        (is (= "never" (:status idx)))
        (is (empty? (:sessions idx)))
        (is (false? (:cached idx)))))))

(deftest cached-reads-search-and-export
  (with-temp-repo
    (fn [dir]
      (let [projects (doto (java.io.File. (str dir "/claude-home/projects/" (str/replace (str (.getCanonicalPath dir)) #"[^A-Za-z0-9]" "-")))
                       .mkdirs)]
        (spit (java.io.File. projects "s1.jsonl")
              (str (json/generate-string {"type" "user" "cwd" (.getCanonicalPath dir) "timestamp" "2026-01-01T00:00:00Z"
                                          "message" {"role" "user" "content" "please fix the parser"}})
                   "\n"))
        (with-redefs [common/default-roots (fn [] {:claude (.getParentFile projects)})]
          (sessions/rebuild-cache!))
        (is (= ["claude:s1"] (map :session_id (:sessions (sessions/list-for-repo)))))
        (testing "show by partial id"
          (is (= "please fix the parser" (get-in (sessions/show "s1") [:messages 0 :text]))))
        (is (= ["claude:s1"] (map :session_id (:hits (sessions/search "PARSER" {})))))
        (is (= 1 (:sessions (sessions/stats))))
        (is (str/includes? (:markdown (sessions/export-markdown "claude:s1")) "please fix the parser"))
        (is (false? (:ok (sessions/show "nope"))))))))
