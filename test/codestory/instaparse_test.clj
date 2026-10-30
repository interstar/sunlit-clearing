(ns codestory.instaparse-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [codestory.instaparse :as instaparse]
            [codestory.repo :as repo])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-repo [f]
  (let [dir (.toFile (Files/createTempDirectory "codestory-insta" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(def ^:private sample-src
  (str "(ns demo.grammar\n"
       "  (:require [instaparse.core :as insta]))\n\n"
       "(def arithmetic\n"
       "  (insta/parser\n"
       "    \"expr = add-sub\n"
       "     <add-sub> = mul-div | add | sub\n"
       "     add = add-sub <'+'> mul-div\n"
       "     sub = add-sub <'-'> mul-div\n"
       "     <mul-div> = term | mul | div\n"
       "     mul = mul-div <'*'> term\n"
       "     div = mul-div <'/'> term\n"
       "     <term> = number | <'('> add-sub <')'>\n"
       "     number = #'[0-9]+'\"))\n\n"
       "(insta/defparser words\n"
       "  \"S = WORD+\n"
       "   WORD = #'\\\\w+'\")\n\n"
       "(def my-grammar\n"
       "  \"R = 'x' | 'y'\")\n\n"
       "(def ordinary\n"
       "  \"just a string\")\n"))

(deftest extract-grammars-from-sample
  (let [unit (instaparse/parse-file "src/demo/grammar.clj" sample-src)
        names (set (map :name (:grammars unit)))]
    (is (= "demo.grammar" (:ns unit)))
    (is (= #{"arithmetic" "words" "my-grammar"} names))
    (is (not (contains? names "ordinary")))
    (let [arith (first (filter #(= "arithmetic" (:name %)) (:grammars unit)))]
      (is (= "def+parser" (:kind arith)))
      (is (str/includes? (:grammar arith) "expr = add-sub")))))

(deftest ebnf-heuristic
  (is (instaparse/ebnf-string? "S = A B\nA = 'a'"))
  (is (instaparse/ebnf-string? "R = 'x' | 'y'"))
  (is (not (instaparse/ebnf-string? "just a string")))
  (is (not (instaparse/ebnf-string? 42))))

(deftest scan-only-src
  (with-temp-repo
    (fn [dir]
      (let [src (io/file dir "src/demo/grammar.clj")
            other (io/file dir "resources/hidden.clj")]
        (io/make-parents src)
        (io/make-parents other)
        (spit src sample-src)
        (spit other "(def hidden (insta/parser \"H = 'h'\"))\n")
        (let [summary (instaparse/scan {:max-depth 12})
              names (set (map :name (:grammars summary)))]
          (is (= 3 (:grammar_count summary)))
          (is (= #{"arithmetic" "words" "my-grammar"} names))
          (is (str/includes? (instaparse/->html summary) "Instaparse grammars"))
          (is (str/includes? (instaparse/->html summary) "arithmetic")))))))

(deftest empty-html
  (let [html (instaparse/->html {:grammar_count 0 :file_count 0 :grammars []})]
    (is (str/includes? html "No Instaparse grammars"))))
