(ns sunlit.test-report
  "Parse pytest / lein / Haxe test (and coverage) output into UI headline summaries."
  (:require [clojure.string :as str]
            [hiccup.core :as hiccup]))

(defn- parse-int [s]
  (try (Integer/parseInt s) (catch Exception _ nil)))

(defn- parse-float [s]
  (try (Double/parseDouble s) (catch Exception _ nil)))

(defn- strip-ansi [s]
  (str/replace (str s) #"\u001B\[[0-9;]*[A-Za-z]" ""))

(defn- normalize-kind [k]
  (let [k (str/lower-case (str k))]
    (case k
      ("error" "errors") :errors
      ("warning" "warnings") :warnings
      ("failure" "failures") :failed
      (keyword k))))

(defn parse-pytest-summary
  "Extract counts from pytest's final summary line."
  [text]
  (when-not (str/blank? text)
    (when-let [[_ body secs]
               (re-find
                #"(?im)(?:^=+\s*)?((?:\d+\s+(?:passed|failed|errors?|skipped|xfailed|xpassed|warnings?)(?:,\s*)?)+)\s+in\s+([\d.]+)s"
                text)]
      (let [counts
            (reduce (fn [m [_ n kind]]
                      (if-let [v (parse-int n)]
                        (assoc m (normalize-kind kind) v)
                        m))
                    {}
                    (re-seq #"(\d+)\s+(passed|failed|errors?|skipped|xfailed|xpassed|warnings?)"
                            body))
            outcome-keys [:passed :failed :errors :skipped :xfailed :xpassed]
            cases (apply + (map #(get counts % 0) outcome-keys))]
        (assoc counts
               :runner :pytest
               :cases cases
               :duration_s secs)))))

(defn parse-lein-summary
  "Extract counts from clojure.test / lein test output."
  [text]
  (when-not (str/blank? text)
    (let [[_ tests assertions]
          (re-find #"(?im)Ran\s+(\d+)\s+tests?\s+containing\s+(\d+)\s+assertions?" text)
          [_ failures errors]
          (re-find #"(?im)(\d+)\s+failures?,\s+(\d+)\s+errors?" text)]
      (when (or tests failures)
        (let [tests-n (or (parse-int tests) 0)
              cases-n (or (parse-int assertions) tests-n)
              fail-n (or (parse-int failures) 0)
              err-n (or (parse-int errors) 0)
              passed (max 0 (- cases-n fail-n err-n))]
          {:runner :lein
           :tests tests-n
           :cases cases-n
           :passed passed
           :failed fail-n
           :errors err-n})))))

(defn- haxe-matches [text]
  (let [assertions (re-find #"(?im)assertions?\s*:\s*(\d+)\s*/\s*(\d+)" text)]
    {:total (second (re-find #"(?im)Total\s+Tests?\s*:\s*(\d+)" text))
     :errors (second (re-find #"(?im)(?:^|\s)Errors?\s*:\s*(\d+)" text))
     :failed (second (re-find #"(?im)(?:^|\s)Failed\s*:\s*(\d+)" text))
     :passed (second (re-find #"(?im)(?:^|\s)(?:Passed|Successes?)\s*:\s*(\d+)" text))
     :ok-n (nth assertions 1 nil)
     :total-n (nth assertions 2 nil)
     :munit-n (second (re-find #"(?im)(\d+)\s+Tests?\s+Passed" text))
     :ok-lines (count (re-seq #"(?m):\s*OK\b" text))
     :fail-lines (count (re-seq #"(?m):\s*(?:FAIL|FAILED|ERROR)\b" text))}))

(defn- haxe-result [tests passed failed errors]
  {:runner :haxe
   :tests tests
   :cases tests
   :passed passed
   :failed failed
   :errors errors})

(defn parse-haxe-summary
  "Extract counts from common Haxe unit-test runners (custom UnitTester, utest, munit)."
  [text]
  (when-not (str/blank? text)
    (let [text (strip-ansi text)
          m (haxe-matches text)]
      (cond
        (:total m)
        (let [tests-n (parse-int (:total m))
              err-n (or (parse-int (:errors m)) 0)
              fail-n (or (parse-int (:failed m)) 0)
              pass-n (or (parse-int (:passed m))
                         (max 0 (- tests-n err-n fail-n)))]
          (haxe-result tests-n pass-n fail-n err-n))

        (and (:ok-n m) (:total-n m))
        (let [t (parse-int (:total-n m))
              p (parse-int (:ok-n m))]
          (haxe-result t p (max 0 (- t p)) 0))

        (:munit-n m)
        (let [p (parse-int (:munit-n m))]
          (haxe-result p p 0 0))

        (pos? (+ (:ok-lines m) (:fail-lines m)))
        (let [t (+ (:ok-lines m) (:fail-lines m))]
          (haxe-result t (:ok-lines m) (:fail-lines m) 0))

        :else nil))))

(defn parse-cloverage-coverage
  "Pull overall forms/lines % from cloverage's ALL FILES table row."
  [text]
  (when-not (str/blank? text)
    (let [text (strip-ansi text)
          [_ forms lines]
          (re-find #"(?im)ALL FILES\s*\|\s*([\d.]+)\s*\|\s*([\d.]+)" text)]
      (when (or forms lines)
        (cond-> {:runner :cloverage}
          forms (assoc :forms_pct (parse-float forms))
          lines (assoc :lines_pct (parse-float lines)))))))

(defn parse-summary
  "Try known unit-test summary parsers against combined stdout/stderr."
  [text]
  (or (parse-pytest-summary text)
      (parse-lein-summary text)
      (parse-haxe-summary text)))

(defn parse-coverage-summary
  "Unit-test summary (if any) plus cloverage coverage totals."
  [text]
  (merge (or (parse-summary text) {})
         (or (parse-cloverage-coverage text) {})))

(defn- fmt-pct [v]
  (when (number? v)
    (format "%.1f%%" (double v))))

(defn- stat [label value class]
  (when (some? value)
    [:div.test-stat {:class class}
     [:span.test-stat-value (str value)]
     [:span.test-stat-label label]]))

(defn- with-outcome-defaults
  "Ensure core outcome fields are present (including 0) whenever we have a run summary."
  [summary]
  (cond-> summary
    (or (contains? summary :passed)
        (contains? summary :failed)
        (contains? summary :errors)
        (contains? summary :cases)
        (contains? summary :tests))
    (assoc :passed (get summary :passed 0)
           :failed (get summary :failed 0)
           :errors (get summary :errors 0))))

(defn summary-html
  "Hiccup/HTML headline block for a parsed summary map.
  Always shows passed / failed / errors (including zeros) when any outcome is known."
  [summary]
  (let [summary (some-> summary with-outcome-defaults)]
    (when (and (map? summary) (seq (dissoc summary :runner)))
      (hiccup/html
       [:div.test-summary
        (stat "tests" (:tests summary) "tests")
        (stat "cases" (:cases summary) "cases")
        (stat "passed" (:passed summary) "passed")
        (stat "failed" (:failed summary) "failed")
        (stat "errors" (:errors summary) "errors")
        (stat "skipped" (:skipped summary) "skipped")
        (stat "warnings" (:warnings summary) "warnings")
        (stat "files" (:files summary) "files")
        (stat "lines" (when (and (:lines_hit summary) (:lines_found summary))
                        (str (:lines_hit summary) "/" (:lines_found summary)))
             "lines")
        (stat "forms" (fmt-pct (:forms_pct summary)) "forms")
        (stat "lines %" (fmt-pct (:lines_pct summary)) "lines-pct")
        (stat "coverage" (or (fmt-pct (:coverage_pct summary))
                             (fmt-pct (:percent summary)))
             "coverage")
        (when (:duration_s summary)
          (stat "time" (str (:duration_s summary) "s") "time"))]))))
