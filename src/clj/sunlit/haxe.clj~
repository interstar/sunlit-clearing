(ns codestory.haxe
  "Discover Haxe test / coverage HXML files in the watched repo."
  (:require [clojure.string :as str]
            [codestory.repo :as repo]))

(def test-hxml-candidates
  ["test.hxml" "tests.hxml" "test.interp.hxml" "unit.hxml" "unittest.hxml"])

(def coverage-hxml-candidates
  ["testCoverage.hxml" "coverage.hxml" "test.coverage.hxml" "cov.hxml"])

(defn- exists-rel? [rel]
  (try
    (.isFile (repo/safe-resolve rel))
    (catch Exception _
      false)))

(defn find-hxml
  "Return the first existing candidate path, or nil."
  [candidates]
  (some #(when (exists-rel? %) %) candidates))

(defn default-test-cmd
  "Default `haxe <hxml>` for unit tests. Optional explicit :hxml wins."
  [params]
  (let [hxml (or (some-> (:hxml params) str not-empty)
                 (find-hxml test-hxml-candidates))]
    (when hxml
      ["haxe" hxml])))

(defn default-coverage-cmd
  "Default coverage compile/run via instrument-style HXML if present."
  [params]
  (let [hxml (or (some-> (:hxml params) str not-empty)
                 (find-hxml coverage-hxml-candidates))]
    (when hxml
      ["haxe" hxml])))

(defn parse-lcov
  "Summarise an LCOV file: file count, lines found/hit, percentage."
  [content]
  (let [lines (str/split-lines (or content ""))
        files (count (filter #(str/starts-with? % "SF:") lines))
        lf (->> lines
                (keep #(when-let [[_ n] (re-find #"^LF:(\d+)" %)]
                         (Long/parseLong n)))
                (reduce + 0))
        lh (->> lines
                (keep #(when-let [[_ n] (re-find #"^LH:(\d+)" %)]
                         (Long/parseLong n)))
                (reduce + 0))
        pct (when (pos? lf)
              (double (* 100.0 (/ lh lf))))]
    {:files files
     :lines_found lf
     :lines_hit lh
     :percent pct}))

(defn read-lcov
  "Read repo-relative lcov path if present."
  [rel]
  (try
    (let [f (repo/safe-resolve rel)]
      (when (.isFile f)
        (parse-lcov (slurp f))))
    (catch Exception _
      nil)))
