(ns codestory.python-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [codestory.python :as py]
            [codestory.repo :as repo])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-repo [f]
  (let [dir (.toFile (Files/createTempDirectory "codestory-py" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(deftest discover-pytest-path-prefers-tests-python
  (with-temp-repo
    (fn [dir]
      (.mkdirs (io/file dir "tests" "python"))
      (.mkdirs (io/file dir "tests"))
      (is (= "tests/python" (py/discover-pytest-path))))))

(deftest discover-pytest-path-falls-back-to-tests
  (with-temp-repo
    (fn [dir]
      (.mkdirs (io/file dir "tests"))
      (is (= "tests" (py/discover-pytest-path))))))

(deftest discover-pytest-path-honours-explicit
  (with-temp-repo
    (fn [_]
      (is (= "custom" (py/discover-pytest-path "custom"))))))
