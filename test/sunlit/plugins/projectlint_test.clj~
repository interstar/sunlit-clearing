(ns codestory.plugins.projectlint-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [codestory.plugins.projectlint :as projectlint]
            [codestory.repo :as repo])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest parse-summary-counts-ok-and-error-lines
  (is (= {:runner :projectlint :cases 2 :passed 1 :failed 1}
         (projectlint/parse-summary
          "OK standard library: WCHNTMaths (21 methods)\nERROR Smalltalk does not handle expression kinds: :bitwise\nProject lint FAILED: backend_coverage.clj")))
  (testing "no check lines means no summary"
    (is (nil? (projectlint/parse-summary "Project lint passed."))))
  (testing "blank output means no summary"
    (is (nil? (projectlint/parse-summary "")))))

(deftest present?-requires-runner-and-dir
  (let [dir (.toFile (Files/createTempDirectory "codestory-projectlint" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (testing "empty repo"
        (is (false? (projectlint/present?))))
      (spit (io/file dir "projectlint.sh") "#!/usr/bin/env bash\n")
      (testing "runner only"
        (is (false? (projectlint/present?))))
      (.delete (io/file dir "projectlint.sh"))
      (.mkdir (io/file dir "project_lint"))
      (testing "lint dir only"
        (is (false? (projectlint/present?))))
      (spit (io/file dir "projectlint.sh") "#!/usr/bin/env bash\n")
      (testing "both present"
        (is (true? (projectlint/present?))))
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))
