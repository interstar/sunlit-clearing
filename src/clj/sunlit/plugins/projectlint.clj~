(ns codestory.plugins.projectlint
  "Long-running runner for project-specific linters.

  When a repo declares `projectlint.sh` plus a `project_lint/` directory, this
  plugin runs the runner (which discovers project_lint/*.clj and *.bb scripts)
  and captures the combined output, with a headline OK/ERROR summary — the
  same shape as the unit-testing plugins."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [codestory.jobs :as jobs]
            [codestory.repo :as repo]
            [codestory.test-report :as report]
            [hiccup.core :as hiccup]))

(def ^:private runner-script "projectlint.sh")
(def ^:private lint-dir "project_lint")

(defn present?
  "True when the repo declares project-specific linters: a projectlint.sh
  runner and a project_lint/ directory."
  []
  (and (.isFile (repo/safe-resolve runner-script))
       (.isDirectory (repo/safe-resolve lint-dir))))

(defn default-cmd
  "bash, not ./projectlint.sh, so a missing executable bit doesn't hide it."
  []
  ["bash" runner-script])

(defn parse-summary
  "Headline counts from project lint output: one OK / ERROR line per check."
  [text]
  (when-not (str/blank? text)
    (let [oks (count (re-seq #"(?m)^OK\s" text))
          errs (count (re-seq #"(?m)^ERROR\s" text))]
      (when (pos? (+ oks errs))
        {:runner :projectlint
         :cases (+ oks errs)
         :passed oks
         :failed errs}))))

(defn run
  [{:keys [params]}]
  (if-not (present?)
    {:html
     (hiccup/html
      [:div.plugin-panel
       [:p.muted
        "No project-specific linters found. Add a "
        [:code "projectlint.sh"] " runner and a " [:code "project_lint/"]
        " directory with " [:code "*.clj"] " / " [:code "*.bb"] " scripts."]])}
    (let [id (or (:id params) "projectlint")
          cmd (or (:cmd params) (default-cmd))
          cache (jobs/read-cache "projectlint" id)
          summary (when (#{"ok" "failed"} (:status cache))
                    (parse-summary (str (:stdout cache) "\n" (:stderr cache))))]
      (jobs/render-panel
       {:plugin "projectlint"
        :id id
        :title (or (:title params) "projectlint")
        :summary-html (report/summary-html summary)
        :params (assoc params :id id :cmd cmd)}))))

(defn start!
  [{:keys [params]}]
  (if-not (present?)
    {:ok false
     :error (str "projectlint: no " runner-script " / " lint-dir "/ found in this repo")}
    (let [id (or (:id params) "projectlint")
          cmd (or (:cmd params) (default-cmd))]
      (jobs/start! {:plugin "projectlint" :id id :cmd cmd}))))

(def plugin
  {:name "projectlint"
   :description "Runs projectlint.sh (project_lint/*.clj and *.bb project-specific linters) and captures the output with a headline OK/ERROR summary. Optional :cmd override."
   :long-running? true
   :run run
   :start! start!})
