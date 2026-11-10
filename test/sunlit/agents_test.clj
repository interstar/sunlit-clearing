(ns sunlit.agents-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [sunlit.agents :as agents]
            [sunlit.plugins :as plugins]
            [sunlit.render :as render]
            [sunlit.repo :as repo])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-repo [f]
  (let [dir (.toFile (Files/createTempDirectory "sunlit-agents" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (plugins/init!)
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(deftest staleness-logic
  (let [fresh (str (java.time.Instant/now))]
    (is (nil? (#'agents/age-seconds nil)))
    (is (= 0 (#'agents/age-seconds fresh)))
    (is (true? (#'agents/stale-cache? "never" {})))
    (is (true? (#'agents/stale-cache? "error" {})))
    (is (false? (#'agents/stale-cache? "ok" {:finished_at fresh})))
    (is (false? (#'agents/stale-cache? "failed" {:finished_at fresh})))))

(deftest unknown-plugin
  (is (= {:ok false :error "Unknown plugin: nope" :name "nope"}
         (agents/plugin-result "nope" {}))))

(deftest plugin-result-sync
  (with-repo
    (fn [dir]
      (let [r (agents/plugin-result "leiningen" {:params {}})]
        (is (:ok r))
        (is (= "leiningen" (:name r)))
        (is (false? (:long_running r)))
        (is (= "ok" (:status r)))))))

(deftest plugin-result-failed-run
  (with-repo
    (fn [_]
      (testing "a plugin that throws"
        (plugins/register! {:name "boom" :run (fn [_] (throw (ex-info "kaboom" {})))})
        (let [r (agents/plugin-result "boom" {})]
          (is (false? (:ok r)))
          (is (= "error" (:status r)))
          (is (= "kaboom" (:error r)))))
      (testing "a plugin that returns :ok false"
        (plugins/register! {:name "refuses" :run (fn [_] {:ok false :error "bad params"})})
        (let [r (agents/plugin-result "refuses" {})]
          (is (false? (:ok r)))
          (is (= "error" (:status r)))
          (is (= "bad params" (:error r)))))
      (testing "a failed run overrides a status the plugin supplied"
        (plugins/register! {:name "mixed" :run (fn [_] {:ok false :status "ok"})})
        (is (= "error" (:status (agents/plugin-result "mixed" {}))))))))

(deftest plugin-fences-extraction
  (is (= [{:name "structure" :params {}}]
         (render/plugin-fences "```plugin structure\n{}\n```")))
  (is (= [{:name "unittests" :params {:lang :clojure}}]
         (render/plugin-fences "```plugin unittests\n{:lang :clojure}\n```"))))

(deftest page-payload-shape
  (with-repo
    (fn [dir]
      (.mkdirs (io/file dir ".sunlit" "pages"))
      (spit (io/file dir ".sunlit" "pages" "Architecture.md")
            "# Architecture\n\n```plugin leiningen\n{}\n```\n")
      (let [r (agents/page-payload "Architecture")]
        (is (:ok r))
        (is (= "Architecture" (:name r)))
        (is (= 1 (count (:plugins r))))
        (is (= "leiningen" (:name (first (:plugins r)))))))))

(deftest file-payload-auto-plugin
  (with-repo
    (fn [dir]
      (spit (io/file dir "project.clj") "(defproject foo \"0.1.0\")\n")
      (let [r (agents/file-payload "project.clj")]
        (is (:ok r))
        (is (= "project.clj" (:path r)))
        (is (= 1 (count (:plugins r))))
        (let [p (first (:plugins r))]
          (is (= "leiningen" (:name p)))
          (is (= "foo" (get-in p [:data :project]))))))))

(deftest index-payload-shape
  (with-repo
    (fn [dir]
      (let [r (agents/index-payload)]
        (is (:ok r))
        (is (vector? (:endpoints r)))
        (is (vector? (:examples r)))
        (is (vector? (:pages r)))
        (is (seq (:plugins r)))))))
