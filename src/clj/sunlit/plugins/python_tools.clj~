(ns codestory.plugins.python-tools
  "Manage Sunlit Clearing's private .sunlit/venv for Python analysis tools."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [codestory.jobs :as jobs]
            [codestory.python :as py]
            [hiccup.core :as hiccup]))

(defn- action-panel
  [{:keys [action title cmd detail]}]
  (let [id (str action)
        cache (let [c (jobs/read-cache "python-tools" id)]
                (if (jobs/running? "python-tools" id) (assoc c :status "running") c))
        status (:status cache "never")
        params {:action action :id id :cmd cmd}
        params-json (json/generate-string params)]
    [:div.lr-panel
     {:data-plugin "python-tools"
      :data-cache-id id
      :data-params params-json}
     [:div.lr-toolbar
      [:div.lr-meta
       [:strong title]
       [:span.lr-status {:class (or status "never")} (or status "never")]
       (when (:finished_at cache)
         [:span.meta " · " (:finished_at cache)])
       (when (some? (:exit cache))
         [:span.meta " · exit " (:exit cache)])]
      [:button.btn.js-plugin-rerun
       (cond-> {:type "button"}
         (= status "running") (assoc :disabled "disabled"))
       (if (= status "running") "Running…" title)]]
     (when detail [:p.muted detail])
     (when-let [c (:cmd cache)]
       (when (and (sequential? c) (seq c))
         [:p.lr-cmd [:code (str/join " " c)]]))
     (when (#{"ok" "failed"} status)
       [:details.lr-log
        [:summary "Command log"]
        (when-not (str/blank? (:stdout cache))
          [:pre.lr-out (:stdout cache)])
        (when-not (str/blank? (:stderr cache))
          [:pre.lr-err (:stderr cache)])])
     (when (and (= status "error") (:error cache))
       [:pre.lr-out.error (:error cache)])]))

(defn- status-html [st]
  (hiccup/html
   [:div.plugin-panel.python-tools
    [:h3 "Python analysis tools"]
    [:p "Private venv under " [:code ".sunlit/venv"]
     " for Sunlit Clearing tooling only (pytest, coverage) — not project app dependencies."]
    [:dl
     [:dt "Host Python"] [:dd [:code (:host_python st)]]
     [:dt "Private venv"]
     [:dd (if (:venv st)
            [:code (:venv_python st)]
            [:span.muted "not created"])]
     [:dt "Status"] [:dd (:detail st)]]
    (action-panel
     {:action "create-venv"
      :title "Create venv"
      :cmd [(py/host-python) "-m" "venv" ".sunlit/venv"]
      :detail "Creates .sunlit/venv using the host Python."})
    (action-panel
     {:action "install-tools"
      :title "Install tools"
      :cmd (into [(or (py/venv-python) ".sunlit/venv/bin/python")
                  "-m" "pip" "install" "--upgrade" "pip"]
                 py/tools-packages)
      :detail (str "Installs: " (str/join ", " py/tools-packages))})]))

(defn run
  [_ctx]
  {:html (status-html (py/tool-status))
   :long_running true})

(defn start!
  [{:keys [params]}]
  (let [action (keyword (or (:action params) "create-venv"))]
    (case action
      :create-venv
      (jobs/start!
       {:plugin "python-tools"
        :id "create-venv"
        :cmd [(py/host-python) "-m" "venv" ".sunlit/venv"]})

      :install-tools
      (if-not (py/venv-exists?)
        {:ok false
         :error "Create the venv first."
         :plugin "python-tools"}
        (jobs/start!
         {:plugin "python-tools"
          :id "install-tools"
          :cmd (into [(py/venv-python) "-m" "pip" "install" "--upgrade" "pip"]
                     py/tools-packages)}))

      {:ok false :error (str "Unknown action: " action) :plugin "python-tools"})))

(def plugin
  {:name "python-tools"
   :description "Control panel for Sunlit Clearing's private .sunlit/venv: create the venv and install analysis tools (pytest, pytest-cov, coverage). Does not install project app dependencies."
   :long-running? true
   :run run
   :start! start!})
