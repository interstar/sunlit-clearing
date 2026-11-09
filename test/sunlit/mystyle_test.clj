(ns sunlit.mystyle-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sunlit.mystyle :as mystyle]
            [sunlit.repo :as repo])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-repo [f]
  (let [dir (.toFile (Files/createTempDirectory "sunlit-mystyle" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(deftest parse-findings-relativizes-paths
  (with-temp-repo
    (fn [dir]
      (let [abs-a (str (.getAbsolutePath dir) "/src/foo.clj")
            abs-b (str (.getAbsolutePath dir) "/src/bar.clj")
            out (str abs-a ":12:3 [WARN] fail-soft-input\n"
                     abs-b ":40:1 [HARD] long-function\n"
                     abs-a ":7:1 [INFO] type-hint-coverage\n")
            findings (mystyle/parse-findings out)]
        (is (= ["src/foo.clj" "src/bar.clj" "src/foo.clj"]
               (mapv :file findings)))
        (is (= [12 40 7] (mapv :line findings)))
        (is (= ["WARN" "HARD" "INFO"] (mapv :level findings)))
        (is (= ["fail-soft-input" "long-function" "type-hint-coverage"]
               (mapv :message findings)))))))

(deftest summarize-counts-levels
  (let [findings [{:level "WARN"} {:level "WARN"} {:level "HARD"} {:level "INFO"}]
        s (mystyle/summarize findings)]
    (is (= 4 (:count s)))
    (is (= 2 (:warn s)))
    (is (= 1 (:hard s)))
    (is (= 1 (:info s)))
    (is (= 0 (:error s)))))

(deftest findings-html-empty-and-populated
  (is (str/includes? (mystyle/findings-html []) "No style findings."))
  (let [html (mystyle/findings-html
              [{:file "src/a.clj" :line 3 :col 2 :level "WARN" :message "fail-soft-input"}
               {:file "src/a.clj" :line 9 :col 1 :level "HARD" :message "long-function"}])]
    (is (str/includes? html "mystyle-file"))
    (is (str/includes? html "#/file/src%2Fa.clj"))
    (is (str/includes? html "fail-soft-input"))
    (is (str/includes? html "mystyle-hard"))))

(deftest summary-html-headline
  (is (nil? (mystyle/summary-html {:count 0})))
  (let [html (mystyle/summary-html {:count 5 :warn 2 :hard 1 :info 1 :error 1})]
    (is (str/includes? html "findings"))
    (is (str/includes? html "5"))))
