(ns codestory.test-report-test
  (:require [clojure.test :refer [deftest is]]
            [codestory.test-report :as report]))

(deftest parse-pytest-summary-line
  (let [s (report/parse-pytest-summary
           "...\n277 passed, 929 warnings in 17.22s\n")]
    (is (= 277 (:passed s)))
    (is (= 929 (:warnings s)))
    (is (= 277 (:cases s)))
    (is (= "17.22" (:duration_s s)))))

(deftest parse-pytest-with-failures
  (let [s (report/parse-pytest-summary
           "======= 2 failed, 3 passed, 1 skipped, 1 error in 1.50s =======")]
    (is (= 3 (:passed s)))
    (is (= 2 (:failed s)))
    (is (= 1 (:skipped s)))
    (is (= 1 (:errors s)))
    (is (= 7 (:cases s)))))

(deftest parse-lein-summary
  (let [s (report/parse-lein-summary
           "\nRan 7 tests containing 26 assertions.\n0 failures, 0 errors.\n")]
    (is (= 7 (:tests s)))
    (is (= 26 (:cases s)))
    (is (= 26 (:passed s)))
    (is (= 0 (:failed s)))
    (is (= 0 (:errors s)))))

(deftest parse-haxe-unit-tester-summary
  (let [s (report/parse-haxe-summary
           "UnitTester.hx:47: OK: Literal.match\nTest Summary:\n  Total Tests: 7\n  Errors: 0\n")]
    (is (= 7 (:tests s)))
    (is (= 7 (:passed s)))
    (is (= 0 (:errors s)))))

(deftest parse-cloverage-all-files
  (let [s (report/parse-coverage-summary
           (str "Ran 5 tests containing 15 assertions.\n0 failures, 0 errors.\n"
                "| ALL FILES | 19.56 | 31.55 |\n"))]
    (is (= 5 (:tests s)))
    (is (= 15 (:cases s)))
    (is (= 19.56 (:forms_pct s)))
    (is (= 31.55 (:lines_pct s)))))

(deftest summary-html-includes-values
  (let [html (report/summary-html
              {:runner :pytest :cases 10 :passed 10 :warnings 2 :duration_s "1.00"})]
    (is (re-find #"test-summary" html))
    (is (re-find #">10<" html))
    (is (re-find #"passed" html))
    (is (re-find #"failed" html))
    (is (re-find #">0</span><span class=\"test-stat-label\">failed" html))
    (is (re-find #">0</span><span class=\"test-stat-label\">errors" html))))

(deftest summary-html-shows-zero-passed
  (let [html (report/summary-html
              {:runner :pytest :cases 2 :passed 0 :failed 2 :errors 0})]
    (is (re-find #">0</span><span class=\"test-stat-label\">passed" html))
    (is (re-find #">2</span><span class=\"test-stat-label\">failed" html))))
