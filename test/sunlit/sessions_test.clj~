(ns codestory.sessions-test
  (:require [clojure.test :refer [deftest is testing]]
            [codestory.repo :as repo]
            [codestory.sessions :as sessions])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-repo [f]
  (let [dir (.toFile (Files/createTempDirectory "codestory-sess" (into-array FileAttribute [])))]
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
