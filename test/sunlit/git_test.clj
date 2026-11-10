(ns sunlit.git-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is testing]]
            [sunlit.git :as git]
            [sunlit.repo :as repo])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- sh! [dir & args]
  (apply sh/sh (concat (mapv str args) [:dir dir])))

(defn- git-present? []
  (try (zero? (:exit (sh/sh "git" "--version"))) (catch Exception _ false)))

(defn- with-temp-git-repo [f]
  (let [dir (.toFile (Files/createTempDirectory "sunlit-git" (into-array FileAttribute [])))]
    (if-not (git-present?)
      (println "git not available; skipping git tests")
      (do
        (sh! dir "git" "init" "-q")
        (sh! dir "git" "config" "user.email" "t@t")
        (sh! dir "git" "config" "user.name" "t")
        (spit (io/file dir "tracked.txt") "hi")
        (spit (io/file dir "untracked.txt") "hi")
        (spit (io/file dir ".gitignore") "ignored.txt\n")
        (spit (io/file dir "ignored.txt") "hi")
        (sh! dir "git" "add" "tracked.txt" ".gitignore")
        (try
          (repo/set-repo-root! dir)
          (f dir)
          (finally
            (doseq [file (reverse (file-seq dir))]
              (.delete file))))))))

(deftest git-status-queries
  (with-temp-git-repo
    (fn [dir]
      (is (true? (git/available?)))
      (is (true? (git/tracked? "tracked.txt")))
      (is (false? (git/tracked? "untracked.txt")))
      (is (true? (git/tracked? "")))
      (is (true? (git/tracked? ".")))
      (is (= :tracked (git/status-for "tracked.txt")))
      (is (= :untracked (git/status-for "untracked.txt")))
      (is (= :ignored (git/status-for "ignored.txt")))
      (is (false? (git/tracked? "does-not-exist"))))))

(deftest failed-listing-means-unknown-not-untracked
  (with-temp-git-repo
    (fn [_]
      (let [real-run-git @#'git/run-git]
        (with-redefs [git/run-git (fn [& args]
                                    (if (= ["ls-files"] (vec args))
                                      {:ok false :out "" :err "fatal: simulated"}
                                      (apply real-run-git args)))]
          (binding [*err* (java.io.StringWriter.)]
            (is (nil? (git/status-for "tracked.txt")))
            (is (nil? (git/status-for "untracked.txt")))
            (is (= ["tracked.txt" "untracked.txt"]
                   (git/keep-tracked ["tracked.txt" "untracked.txt"])))
            (is (re-find #"git ls-files` failed: fatal: simulated" (str *err*)))))))))

(deftest annotate-entries-assoc-status
  (with-temp-git-repo
    (fn [dir]
      (let [entries [{:name "tracked.txt" :path "tracked.txt" :type "file"}
                     {:name "untracked.txt" :path "untracked.txt" :type "file"}
                     {:name "ignored.txt" :path "ignored.txt" :type "file"}]
            annotated (git/annotate-entries entries)]
        (is (= :tracked (get-in annotated [0 :git-status])))
        (is (= :untracked (get-in annotated [1 :git-status])))
        (is (= :ignored (get-in annotated [2 :git-status])))))))

(deftest keep-tracked-filters-untracked-and-ignored
  (with-temp-git-repo
    (fn [dir]
      (is (= ["tracked.txt"]
             (git/keep-tracked ["tracked.txt" "untracked.txt" "ignored.txt"]))))))
