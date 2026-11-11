(ns sunlit.jobs-test
  (:require [clojure.test :refer [deftest is testing]]
            [sunlit.jobs :as jobs]
            [sunlit.repo :as repo])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-repo [f]
  (let [dir (.toFile (Files/createTempDirectory "sunlit-jobs" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(deftest shell-cache-roundtrip
  (with-temp-repo
    (fn [_]
      (testing "never run by default"
        (is (= "never" (:status (jobs/read-cache "run" "demo")))))
      (testing "successful command is cached"
        (let [result (jobs/run-shell! {:plugin "run"
                                       :id "demo"
                                       :cmd ["echo" "hello-sunlit"]})
              cached (jobs/read-cache "run" "demo")]
          (is (= "ok" (:status result)))
          (is (zero? (:exit result)))
          (is (re-find #"hello-sunlit" (str (:stdout cached)))))))))

(deftest collapsible-panels-fold-command-and-body
  (with-temp-repo
    (fn [_]
      (let [open (:html (jobs/render-panel {:plugin "run" :id "a" :title "A"}))
            folded (:html (jobs/render-panel {:plugin "run" :id "a" :title "A"
                                              :summary-html "<b>3</b>"
                                              :collapsible? true}))]
        (is (not (re-find #"lr-collapsible|js-lr-toggle" open)))
        (is (re-find #"class=\"lr-panel lr-collapsible\"" folded))
        (is (re-find #"js-lr-toggle" folded))
        (testing "summary stays outside the folding part"
          (is (re-find #"lr-summary\"><b>3</b></div><div class=\"lr-collapse\"" folded)))))))
