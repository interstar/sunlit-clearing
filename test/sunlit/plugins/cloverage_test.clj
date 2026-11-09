(ns sunlit.plugins.cloverage-test
  (:require [clojure.test :refer [deftest is testing]]
            [sunlit.plugins.cloverage :as cov]
            [sunlit.repo :as repo])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest discover-report-from-stdout
  (let [dir (.toFile (Files/createTempDirectory "cov-test" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (testing "parses absolute path under repo"
        (let [abs (str (.getAbsolutePath dir) "/target/coverage/index.html")
              cache {:stdout (str "Ran tests.\nWriting HTML report to: " abs "\n| table |")}]
          (is (= "target/coverage/index.html"
                 (cov/discover-report-path cache {})))))
      (testing "explicit :report wins"
        (is (= "custom/report.html"
               (cov/discover-report-path {:stdout ""} {:report "custom/report.html"}))))
      (testing "fallback default"
        (is (= "target/coverage/index.html"
               (cov/discover-report-path {:stdout "no path here"} {}))))
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))
