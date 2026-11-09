(ns sunlit.haxe-test
  (:require [clojure.test :refer [deftest is testing]]
            [sunlit.haxe :as haxe]))

(deftest parse-lcov-summary
  (let [lcov (str "TN:\n"
                  "SF:src/Main.hx\n"
                  "DA:1,1\n"
                  "LF:10\n"
                  "LH:7\n"
                  "end_of_record\n"
                  "SF:src/Other.hx\n"
                  "LF:5\n"
                  "LH:5\n"
                  "end_of_record\n")
        s (haxe/parse-lcov lcov)]
    (is (= 2 (:files s)))
    (is (= 15 (:lines_found s)))
    (is (= 12 (:lines_hit s)))
    (is (pos? (:percent s)))))
