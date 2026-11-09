(ns codestory.plugins.pycov-test
  (:require [clojure.test :refer [deftest is testing]]
            [codestory.plugins.pycov :as pycov]))

(deftest discover-report-from-coverage-output
  (testing "parses coverage.py message"
    (is (= "htmlcov/index.html"
           (pycov/discover-report-path
            {:stdout "Wrote HTML report to htmlcov/index.html\nTOTAL 10"}
            {}))))
  (testing "parses pytest-cov dir message"
    (is (= "htmlcov/index.html"
           (pycov/discover-report-path
            {:stdout "Coverage HTML written to dir htmlcov\n"}
            {}))))
  (testing "explicit report wins"
    (is (= "custom/index.html"
           (pycov/discover-report-path
            {:stdout "Wrote HTML report to htmlcov/index.html"}
            {:report "custom/index.html"}))))
  (testing "default fallback"
    (is (= "htmlcov/index.html"
           (pycov/discover-report-path {:stdout ""} {})))))
