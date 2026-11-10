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

(deftest findings-html-shows-category-code-and-source
  (let [html (mystyle/findings-html
              [{:file "src/a.clj" :line 7 :col 3 :level "WARN"
                :message "fail-soft-input: `(or (< a b) \"\")` silently falls back to \"\""
                :source "  (let [x (or (< a b) \"\")]"}])]
    (testing "category is split out of the message"
      (is (str/includes? html "<span class=\"mystyle-cat\">fail-soft-input</span>")))
    (testing "backticked code is escaped inside <code>"
      (is (str/includes? html "<code>(or (&lt; a b) &quot;&quot;)</code>")))
    (testing "source line is shown, escaped"
      (is (str/includes? html "<pre class=\"mystyle-src\">(let [x (or (&lt; a b)")))
    (testing "location links to the line"
      (is (str/includes? html "#/file/src%2Fa.clj?line=7")))))

(deftest attach-source-reads-the-flagged-line
  (with-temp-repo
    (fn [dir]
      (let [f (io/file dir "src" "foo.clj")]
        (io/make-parents f)
        (spit f "(ns foo)\n(defn g [m]\n  (or (:a m) \"\"))\n")
        (is (= [{:file "src/foo.clj" :line 3 :col 3 :source "  (or (:a m) \"\"))"}
                {:file "src/foo.clj" :line 99 :col 1}
                {:file "src/missing.clj" :line 1 :col 1}]
               (mystyle/attach-source [{:file "src/foo.clj" :line 3 :col 3}
                                       {:file "src/foo.clj" :line 99 :col 1}
                                       {:file "src/missing.clj" :line 1 :col 1}])))))))

(deftest linter-plugins-share-one-shape
  (doseq [k [:mystyle-clj :mystyle-py :mystyle-js :mystyle-cpp :mystyle-hx]]
    (let [p (mystyle/linter-plugin k "desc")]
      (is (= (:name (get mystyle/linters k)) (:name p)))
      (is (:long-running? p))
      (is (fn? (:run p)))
      (is (fn? (:start! p))))))

(deftest mystyle-cpp-runs-the-vendored-script
  (with-temp-repo
    (fn [dir]
      (spit (io/file dir "foo.cpp") "static int counter = 0;\nint get() { return counter; }\n")
      (let [payload (mystyle/compute! :mystyle-cpp)
            findings (get-in payload [:data :findings])]
        (is (= "ok" (:status payload)) (:error payload))
        (is (= [{:file "foo.cpp" :line 1 :level "WARN"}]
               (mapv #(select-keys % [:file :line :level]) findings)))
        (is (str/starts-with? (:message (first findings)) "global-usage:"))))))

(deftest mystyle-hx-runs-the-vendored-binary
  (with-temp-repo
    (fn [dir]
      (spit (io/file dir "Foo.hx")
            "class Foo {\n\tstatic var counter = 0;\n\tpublic function new() {}\n}\n")
      (let [payload (mystyle/compute! :mystyle-hx)
            findings (get-in payload [:data :findings])]
        (is (= "ok" (:status payload)) (:error payload))
        (is (= [{:file "Foo.hx" :line 2 :level "WARN"}]
               (mapv #(select-keys % [:file :line :level]) findings)))
        (is (str/starts-with? (:message (first findings)) "global-usage:"))))))

(deftest mystyle-hx-fails-gracefully-without-a-usable-binary
  (testing "no vendored binary and nothing on PATH"
    (with-redefs [sunlit.mystyle/script-path (constantly nil)
                  sunlit.mystyle/executable-on-path? (constantly false)]
      (let [payload (mystyle/compute! :mystyle-hx)]
        (is (= "error" (:status payload)))
        (is (str/includes? (:error payload) "Linux x86-64 only"))
        (is (str/includes? (:html payload) "plugin-error"))
        (is (= [] (get-in payload [:data :findings]))))))
  (testing "another platform ignores the vendored Linux binary"
    (with-redefs [sunlit.mystyle/native-platform? (constantly false)
                  sunlit.mystyle/executable-on-path? (constantly false)]
      (is (= "error" (:status (mystyle/compute! :mystyle-hx))))))
  (testing "another platform uses mystyle-haxe from PATH"
    (with-redefs [sunlit.mystyle/native-platform? (constantly false)
                  sunlit.mystyle/executable-on-path? (constantly true)]
      (is (= "mystyle-haxe" (first (#'mystyle/resolve-cmd (get mystyle/linters :mystyle-hx)))))))
  (testing "a binary that cannot run is reported, not thrown"
    (with-redefs [sunlit.mystyle/script-path (constantly "/nonexistent/mystyle-haxe")]
      (let [payload (mystyle/compute! :mystyle-hx)]
        (is (= "error" (:status payload)))
        (is (string? (:error payload)))))))

(deftest summary-html-headline
  (is (nil? (mystyle/summary-html {:count 0})))
  (let [html (mystyle/summary-html {:count 5 :warn 2 :hard 1 :info 1 :error 1})]
    (is (str/includes? html "findings"))
    (is (str/includes? html "5"))))
