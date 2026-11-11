(ns sunlit.overview-test
  "Run log, the overview plugin, example fences and built-in doc pages."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sunlit.fences :as fences]
            [sunlit.jobs :as jobs]
            [sunlit.pages :as pages]
            [sunlit.plugins.overview :as overview]
            [sunlit.render :as render]
            [sunlit.repo :as repo]
            [sunlit.runlog :as runlog])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-repo [f]
  (let [dir (.getCanonicalFile (.toFile (Files/createTempDirectory "sunlit-overview" (into-array FileAttribute []))))
        old (repo/repo-root)]
    (try
      (repo/set-repo-root! (str dir))
      (f dir)
      (finally
        (repo/set-repo-root! (str old))
        (doseq [x (reverse (file-seq dir))] (.delete x))))))

(defn- touch! [dir rel epoch-ms]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f "x")
    (.setLastModified f epoch-ms)))

(def ^:private t0 "2026-01-01T10:00:00Z")
(def ^:private t0-ms (.toEpochMilli (java.time.Instant/parse t0)))

(deftest finished-jobs-are-logged
  (with-temp-repo
    (fn [_]
      (jobs/write-cache! "unittests" "python" {:status "running"})
      (jobs/write-cache! "unittests" "python" {:status "ok" :exit 0 :finished_at t0 :summary {:passed 3}})
      (let [es (runlog/entries)]
        (is (= 1 (count es)) "running is not a finished run")
        (is (= {:at t0 :plugin "unittests" :id "python" :status "ok" :exit 0
                :summary {:passed 3} :msg "unittests/python ok"}
               (first es)))))))

(deftest overview-rows
  (with-temp-repo
    (fn [dir]
      (runlog/append! {:at t0 :plugin "unittests" :id "python" :status "ok" :msg "unittests/python ok"})
      (runlog/append! {:at "2026-01-01T11:00:00Z" :plugin "mystyle-py" :id "mystyle-py" :status "failed" :msg "x"})
      (runlog/append! {:at "2026-01-01T09:00:00Z" :msg "deployed to prod"})
      (touch! dir "src/app.py" (+ t0-ms 60000))       ; edited after the tests ran
      (touch! dir "src/old.clj" (- t0-ms 60000))
      (let [rows (->> (overview/run {:params {:checks [{:label "Py tests" :plugin "unittests" :id "python" :compare "src/**/*.py"}
                                                       {:label "Clj tests" :plugin "unittests" :id "python" :compare "src/**/*.clj"}
                                                       {:label "Lint" :plugin "mystyle-py"}
                                                       {:label "Deploy" :match "deployed"}
                                                       {:label "Cov" :plugin "pycov"}]}})
                      :data :checks)]
        (is (= ["stale" "ok" "failed" "ok" "never"] (mapv :state rows)))
        (is (= "src/app.py" (get-in rows [0 :changed :path])))
        (is (= "2026-01-01T09:00:00Z" (get-in rows [3 :last_run]))))
      (testing "with no checks, one row per plugin/id in the log"
        (is (= ["mystyle-py" "unittests / python"]
               (map :label (:checks (:data (overview/run {:params {}})))))))
      (is (str/includes? (:html (overview/run {:params {}})) "overview-failed")))))

(def ^:private doc-with-example
  (str "# Doc\n\n````markdown\n```plugin run\n{:cmd [\"rm\"]}\n```\n````\n\n"
       "```plugin overview\n{}\n```\n"))

(deftest example-fences-do-not-run
  (is (= ["overview"] (map :name (render/plugin-fences doc-with-example))))
  (is (= "overview" (:name (fences/nth-fence doc-with-example 0))))
  (with-temp-repo
    (fn [_]
      (let [html (:html (render/render-markdown doc-with-example "page:Doc"))]
        (is (str/includes? html "<pre><code class=\"language-markdown\">```plugin run"))
        (is (= 1 (count (re-seq #"data-plugin=" html))))))))

(deftest builtin-doc-pages
  (with-temp-repo
    (fn [_]
      (let [p (pages/read-page "Guide")]
        (is (:builtin p))
        (is (str/includes? (:markdown p) "](#/page/Writing%20plugins)"))
        (is (not (str/includes? (:markdown p) "../README.md"))))
      (is (some #{"Writing plugins"} (pages/list-pages)))
      (testing "the guides' examples are shown, never run"
        (doseq [n (keys pages/builtin-docs)]
          (is (empty? (render/plugin-fences (:markdown (pages/read-page n)))) n)))
      (pages/write-page! "Guide" "# My guide\n")
      (is (= {:name "Guide" :exists true :markdown "# My guide\n"} (pages/read-page "Guide"))
          "a saved copy overrides the built-in"))))
