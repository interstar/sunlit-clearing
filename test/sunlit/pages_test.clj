(ns sunlit.pages-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sunlit.pages :as pages]
            [sunlit.repo :as repo])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-repo [f]
  (let [dir (.toFile (Files/createTempDirectory "sunlit-test" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (pages/ensure-store!)
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(deftest sanitize-and-roundtrip
  (with-temp-repo
    (fn [_]
      (testing "create and read free-titled page"
        (let [created (pages/create-page! "Hello World" "# Hi\n")
              read (pages/read-page "Hello World")]
          (is (:created created))
          (is (:exists read))
          (is (= "# Hi\n" (:markdown read)))))
      (testing "index seeded"
        (is (pages/exists? pages/index-page-name))
        (is (pages/exists? "Architecture"))
        (is (pages/exists? "Testing"))
        (is (pages/exists? "Python tools"))
        (let [md (:markdown (pages/read-page pages/index-page-name))]
          (is (str/includes? md "[[Architecture]]"))
          (is (str/includes? md "[[Testing]]"))
          (is (str/includes? md "README.md"))
          (is (not (str/includes? md "project.clj")))))
      (testing "architecture embeds structure and extract plugins"
        (let [md (:markdown (pages/read-page "Architecture"))]
          (is (str/includes? md "```plugin structure"))
          (is (str/includes? md "```plugin malli"))
          (is (str/includes? md "```plugin instaparse"))))
      (testing "lint page has one editable fence per linter"
        (let [md (:markdown (pages/read-page "Lint"))]
          (doseq [p ["mystyle-clj" "mystyle-py" "mystyle-js" "mystyle-cpp" "mystyle-hx"]]
            (is (str/includes? md (str "```plugin " p "\n{:exclude []}\n```")))))))))

(deftest rejects-path-traversal
  (is (thrown? Exception (pages/sanitize-name "../etc"))))
