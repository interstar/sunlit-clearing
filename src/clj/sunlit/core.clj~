(ns codestory.core
  (:require [clojure.tools.cli :refer [parse-opts]]
            [codestory.repo :as repo]
            [codestory.server :as server])
  (:gen-class))

(def cli-options
  [["-p" "--port PORT" "HTTP port"
    :default 4567
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be 1-65535"]]
   ["-h" "--help"]])

(defn usage [summary]
  (str "Sunlit Clearing — wiki-like explorer for a local code repository\n\n"
       "Usage: sunlit [options] <repo-root>\n\n"
       "Options:\n" summary))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options)
      (do (println (usage summary)) (System/exit 0))

      errors
      (do (doseq [e errors] (println e))
          (println (usage summary))
          (System/exit 1))

      (empty? arguments)
      (do (println "Error: repository root required.")
          (println (usage summary))
          (System/exit 1))

      :else
      (let [root (first arguments)]
        (repo/set-repo-root! root)
        (server/start! {:port (:port options)})
        ;; Keep JVM alive
        @(promise)))))
