(ns sunlit.plugins.run
  "Generic long-running shell command plugin."
  (:require [sunlit.jobs :as jobs]))

(defn- cache-id [params]
  (or (:id params)
      (when-let [cmd (:cmd params)]
        (str "cmd-" (Math/abs (hash (pr-str cmd)))))
      "default"))

(defn run
  "Render cached output only — never executes on page load.
  Optional :iframe / :report embeds a repo-relative HTML file after the run."
  [{:keys [params]}]
  (let [id (cache-id params)
        cmd (:cmd params)
        report (or (:iframe params) (:report params))]
    (jobs/render-panel
     {:plugin "run"
      :id id
      :title (or (:title params) (str "run/" id))
      :iframe report
      :params (cond-> (assoc params :id id :cmd cmd)
                report (assoc :report report))})))

(defn start!
  [{:keys [params]}]
  (let [id (cache-id params)
        cmd (:cmd params)]
    (if (nil? cmd)
      {:ok false :error "run plugin requires :cmd"}
      (jobs/start! {:plugin "run" :id id :cmd cmd}))))

(def plugin
  {:name "run"
   :description "Long-running shell runner. Put :cmd (vector or string) and optional :id in the fence. Optional :iframe / :report embeds a repo-relative HTML file after the run. Page load shows the cache; Rerun executes in the background."
   :long-running? true
   :run run
   :start! start!})
