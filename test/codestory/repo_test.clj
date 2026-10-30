(ns codestory.repo-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [codestory.repo :as repo])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest language-detection
  (is (= "clojure" (repo/language-for "src/foo.clj")))
  (is (= "python" (repo/language-for "a/b.py")))
  (is (= "cpp" (repo/language-for "main.cpp")))
  (is (= "wchnt" (repo/language-for "rules/foo.wcn"))))
(deftest safe-resolve-stays-in-repo
  (let [dir (.toFile (Files/createTempDirectory "codestory-repo" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (spit (io/file dir "hello.txt") "hi")
      (is (.exists (repo/safe-resolve "hello.txt")))
      (is (thrown? Exception (repo/safe-resolve "../outside")))
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(deftest root-doc-entries-case-insensitive
  (let [dir (.toFile (Files/createTempDirectory "codestory-docs" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (spit (io/file dir "readme.md") "# hi")
      (spit (io/file dir "MUSHROOM.edn") "{}")
      (spit (io/file dir "notes.txt") "x")
      (let [entries (repo/root-doc-entries)
            names (mapv :name entries)]
        (is (= ["readme.md" "MUSHROOM.edn"] names))
        (is (= names (mapv :path entries))))
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(deftest repo-name-from-mushroom
  (let [dir (.toFile (Files/createTempDirectory "codestory-name" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      ;; No MUSHROOM.edn -> directory name
      (is (= (.getName dir) (repo/repo-name)))
      ;; MUSHROOM.edn without :full_name -> directory name
      (spit (io/file dir "MUSHROOM.edn") "{:id \"x\"}\n")
      (is (= (.getName dir) (repo/repo-name)))
      ;; With :full_name -> that name
      (spit (io/file dir "MUSHROOM.edn") "{:full_name \"My Fancy Project\"}\n")
      (is (= "My Fancy Project" (repo/repo-name)))
      ;; Malformed EDN -> graceful fallback
      (spit (io/file dir "MUSHROOM.edn") "{ not valid")
      (is (= (.getName dir) (repo/repo-name)))
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(deftest text-file-detects-dotfiles
  (let [dir (.toFile (Files/createTempDirectory "codestory-text" (into-array FileAttribute [])))]
    (try
      (doseq [n [".gitignore" ".dockerignore" ".gitattributes" ".editorconfig" ".env"]]
        (let [f (io/file dir n)]
          (spit f "# config\n")
          (is (true? (repo/text-file? f)) (str n " should be text"))))
      ;; A dotfile whose content is binary (NUL byte) is still rejected.
      (let [f (io/file dir ".gitignore")]
        (spit f "x\u0000y")
        (is (false? (repo/text-file? f))))
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))
