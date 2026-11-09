(ns sunlit.jobs-test
  (:require [clojure.test :refer [deftest is testing]]
            [sunlit.jobs :as jobs]
            [sunlit.repo :as repo])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-repo [f]
  (let [dir (.toFile (Files/createTempDirectory "sunlit-jobs" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(deftest shell-cache-roundtrip
  (with-temp-repo
    (fn [_]
      (testing "never run by default"
        (is (= "never" (:status (jobs/read-cache "run" "demo")))))
      (testing "successful command is cached"
        (let [result (jobs/run-shell! {:plugin "run"
                                       :id "demo"
                                       :cmd ["echo" "hello-sunlit"]})
              cached (jobs/read-cache "run" "demo")]
          (is (= "ok" (:status result)))
          (is (zero? (:exit result)))
          (is (re-find #"hello-sunlit" (str (:stdout cached)))))))))
