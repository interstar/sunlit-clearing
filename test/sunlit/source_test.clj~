(ns codestory.source-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [codestory.repo :as repo]
            [codestory.source :as source])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-repo [f]
  (let [dir (.toFile (Files/createTempDirectory "codestory-source" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(deftest discover-src-layout
  (with-temp-repo
    (fn [dir]
      (let [p (io/file dir "src/pkg/mod.py")]
        (io/make-parents p)
        (spit p ""))
      (let [t (io/file dir "tests/test_mod.py")]
        (io/make-parents t)
        (spit t ""))
      (is (= ["src"] (source/discover-source-dirs :python))))))

(deftest discover-flat-layout
  (with-temp-repo
    (fn [dir]
      (let [p (io/file dir "mailquery/__init__.py")]
        (io/make-parents p)
        (spit p ""))
      (let [t (io/file dir "tests/test_x.py")]
        (io/make-parents t)
        (spit t ""))
      (is (= ["mailquery"] (source/discover-source-dirs :python))))))

(deftest discover-root-module
  (with-temp-repo
    (fn [dir]
      (spit (io/file dir "app.py") "")
      (is (= ["."] (source/discover-source-dirs :python))))))

(deftest discover-unknown-language-empty
  (with-temp-repo
    (fn [dir]
      (spit (io/file dir "app.py") "")
      (is (= [] (source/discover-source-dirs :nope))))))

(deftest source-files-excludes-tests-and-generated
  (with-temp-repo
    (fn [dir]
      (let [src (io/file dir "src/demo/core.clj")
            gen (io/file dir "resources/public/js/compiled/out/cljs/reader.cljs")
            tst (io/file dir "test/demo/core_test.clj")]
        (io/make-parents src)
        (io/make-parents gen)
        (io/make-parents tst)
        (spit src "")
        (spit gen "")
        (spit tst ""))
      (is (= ["src/demo/core.clj"]
             (source/source-files #{".clj" ".cljs" ".cljc"}))))))

(deftest under-source?-matches-dirs-and-root
  (is (true? (source/under-source? ["src"] "src/foo/bar.clj")))
  (is (false? (source/under-source? ["src"] "test/foo.clj")))
  (is (true? (source/under-source? ["mailquery"] "mailquery/mailbox.py")))
  (is (true? (source/under-source? ["."] "anything.py"))))
