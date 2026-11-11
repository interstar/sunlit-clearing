(ns sunlit.jobs
  "Long-running plugin jobs with on-disk cache under .sunlit/cache/."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [sunlit.repo :as repo]
            [hiccup.core :as hiccup])
  (:import [java.time Instant]
           [java.util.concurrent Executors]))

(defonce ^:private executor (Executors/newCachedThreadPool))
(defonce ^:private in-flight (atom {}))

(defn- now-iso []
  (str (Instant/now)))

(defn- safe-id [id]
  (-> (str (or id "default"))
      (str/replace #"[^A-Za-z0-9._-]+" "_")
      (str/replace #"^_+" "")
      (#(if (str/blank? %) "default" %))))

(defn cache-key [plugin id]
  (str (name plugin) "/" (safe-id id)))

(defn- cache-dir [plugin id]
  (repo/sidecar-file "cache" "plugins"
           (name plugin) (safe-id id)))

(defn- result-file [plugin id]
  (io/file (cache-dir plugin id) "result.json"))

(defn read-cache [plugin id]
  (let [f (result-file plugin id)]
    (if (.isFile f)
      (try
        (json/parse-string (slurp f) true)
        (catch Exception _
          {:status "error" :error "Corrupt cache file"}))
      {:status "never" :plugin (name plugin) :id (safe-id id)})))

(defn write-cache! [plugin id data]
  (let [f (result-file plugin id)
        payload (merge {:plugin (name plugin)
                        :id (safe-id id)}
                       data)]
    (io/make-parents f)
    (spit f (json/generate-string payload))
    payload))

(defn running? [plugin id]
  (boolean (get @in-flight (cache-key plugin id))))

(defn- truncate [s n]
  (let [s (str s)]
    (if (<= (count s) n) s (str (subs s 0 n) "\n…(truncated)"))))

(defn- format-cmd [cmd]
  (cond
    (sequential? cmd) (vec (map str cmd))
    (string? cmd) (str/split (str/trim cmd) #"\s+")
    :else nil))

(defn- subprocess-env
  "Inherit the process environment but drop vars that confuse nested Leiningen.
  Sunlit Clearing itself is often started with `lein run`, which injects CLASSPATH;
  child `lein test` / `lein cloverage` then warn and may fail to find project.clj."
  []
  (-> (into {} (System/getenv))
      (dissoc "CLASSPATH")))

(defn run-shell!
  "Synchronously run cmd in repo root. Returns cache payload map."
  [{:keys [plugin id cmd]}]
  (let [argv (format-cmd cmd)
        started (now-iso)
        root (str (repo/repo-root))]
    (if (empty? argv)
      (write-cache! plugin id
                    {:status "error"
                     :error "No command provided"
                     :started_at started
                     :finished_at (now-iso)
                     :cmd []})
      (let [{:keys [exit out err]}
            (apply sh/sh (concat argv [:dir root :env (subprocess-env)]))
            finished (now-iso)
            status (if (zero? exit) "ok" "failed")]
        (write-cache! plugin id
                      {:status status
                       :exit exit
                       :stdout (truncate out 200000)
                       :stderr (truncate err 50000)
                       :cmd argv
                       :cwd root
                       :started_at started
                       :finished_at finished})))))

(defn start!
  "Start a background shell job if not already running. Returns current cache view."
  [{:keys [plugin id cmd] :as opts}]
  (let [key (cache-key plugin id)]
    (if (running? plugin id)
      (assoc (read-cache plugin id) :status "running" :ok true)
      (do
        (write-cache! plugin id
                      {:status "running"
                       :cmd (format-cmd cmd)
                       :started_at (now-iso)
                       :stdout ""
                       :stderr ""})
        (swap! in-flight assoc key true)
        (.execute
         executor
         (fn []
           (try
             (run-shell! opts)
             (finally
               (swap! in-flight dissoc key)))))
        (assoc (read-cache plugin id) :ok true)))))

(defn run-compute!
  "Run a pure compute thunk and write the result map into the job cache.
  `compute!` should return a map (merged into the cache); may include :html, :summary, :stdout."
  [{:keys [plugin id compute! label]}]
  (let [started (now-iso)
        label (or label "(compute)")]
    (try
      (let [result (compute!)
            finished (now-iso)
            base {:status "ok"
                  :cmd [label]
                  :started_at started
                  :finished_at finished
                  :exit 0}]
        (write-cache! plugin id
                      (merge base (if (map? result) result {:html (str result)}))))
      (catch Exception e
        (write-cache! plugin id
                      {:status "error"
                       :error (.getMessage e)
                       :cmd [label]
                       :started_at started
                       :finished_at (now-iso)
                       :exit 1})))))

(defn start-compute!
  "Background compute job (no shell). Same concurrency/cache rules as start!."
  [{:keys [plugin id compute! label] :as opts}]
  (let [key (cache-key plugin id)
        label (or label "(compute)")]
    (if (running? plugin id)
      (assoc (read-cache plugin id) :status "running" :ok true)
      (do
        (write-cache! plugin id
                      {:status "running"
                       :cmd [label]
                       :started_at (now-iso)
                       :stdout ""
                       :stderr ""})
        (swap! in-flight assoc key true)
        (.execute
         executor
         (fn []
           (try
             (run-compute! opts)
             (finally
               (swap! in-flight dissoc key)))))
        (assoc (read-cache plugin id) :ok true)))))

(defn- report-exists? [rel]
  (try
    (let [f (repo/safe-resolve rel)]
      (.isFile f))
    (catch Exception _
      false)))

(defn- artifact-url [rel]
  (str "/api/artifacts/"
       (-> (str rel)
           (str/replace #"^/+" "")
           (str/replace #"\\" "/"))))

(defn- render-toolbar [plugin id title status cache collapsible?]
  [:div.lr-toolbar
   [:div.lr-meta
    (when collapsible?
      [:button.lr-toggle.js-lr-toggle {:type "button" :title "Show/hide details"
                                       :aria-expanded "false"}
       [:span.lr-chevron "▸"]])
    [:strong (or title (str plugin "/" (safe-id id)))]
    [:span.lr-status {:class status} status]
    (when (:finished_at cache)
      [:span.meta " · " (:finished_at cache)])
    (when (some? (:exit cache))
      [:span.meta " · exit " (:exit cache)])]
   [:button.btn.js-plugin-rerun
    (cond-> {:type "button"}
      (= status "running") (assoc :disabled "disabled"))
    (if (= status "running") "Running…" "Rerun")]])

(defn- render-log-lines [cache]
  (list
   (when-not (str/blank? (:stdout cache))
     [:pre.lr-out (:stdout cache)])
   (when-not (str/blank? (:stderr cache))
     [:pre.lr-err (:stderr cache)])))

(defn- render-body
  [{:keys [status show-iframe? report title rich-html summary-html
           html-marker cache]}]
  (cond
    (= status "running")
    [:p.muted "Job running… refresh shortly or wait for auto-update."]

    (= status "error")
    [:pre.lr-out.error (or (:error cache) "Error")]

    :else
    [:div.lr-body
     (when show-iframe?
       [:div.lr-iframe-wrap
        [:p.lr-iframe-label
         "Report: " [:a {:href (artifact-url report) :target "_blank"} report]]
        [:iframe.lr-iframe
         {:src (artifact-url report)
          :title (str title " report")}]])
     (when (and (= status "never")
                (not show-iframe?)
                (str/blank? rich-html)
                (str/blank? summary-html))
       [:p.muted "Not run yet. Click Rerun to execute and cache the result."])
     (when-not (str/blank? rich-html)
       [:div.lr-html html-marker])
     (when (and (not show-iframe?)
                (#{"ok" "failed"} status)
                (or (not (str/blank? (:stdout cache)))
                    (not (str/blank? (:stderr cache)))))
       (render-log-lines cache))
     (when (and show-iframe?
                (or (not (str/blank? (:stdout cache)))
                    (not (str/blank? (:stderr cache)))))
       [:details.lr-log
        [:summary "Command log"]
        (render-log-lines cache)])]))

(defn- current-cache [plugin id]
  (let [c (read-cache plugin id)]
    (if (running? plugin id) (assoc c :status "running") c)))

(defn- cmd-string [cache]
  (let [c (:cmd cache)]
    (cond (sequential? c) (str/join " " c)
          (string? c) c
          :else "")))

(def ^:private html-marker "SUNLIT_LR_HTML_SLOT")
(def ^:private summary-marker "SUNLIT_LR_SUMMARY_SLOT")

(defn- panel-skeleton
  "Panel hiccup with marker strings where the rich and summary HTML go."
  [{:keys [plugin id title params collapsible? cache status summary-html] :as body}]
  (let [cmd-str (cmd-string cache)]
    [:div
     {:class (str "lr-panel" (when collapsible? " lr-collapsible"))
      :data-plugin (name plugin)
      :data-cache-id (safe-id id)
      :data-params (json/generate-string (or params {}))}
     (render-toolbar plugin id title status cache collapsible?)
     (when-not (str/blank? summary-html)
       [:div.lr-summary summary-marker])
     [:div.lr-collapse
      (when-not (str/blank? cmd-str)
        [:p.lr-cmd [:code cmd-str]])
      (render-body (assoc body :html-marker html-marker))]]))

(defn render-panel
  "HTML panel for a cached long-running result, including rerun affordance.
  Optional :iframe is a repo-relative HTML path to embed when the file exists.
  Optional :summary-html is unescaped HTML shown after the command, before logs.
  If the cache (or :body-html option) contains an :html string, it is spliced
  in unescaped so plugins can ship rich deterministic panels.
  With :collapsible? the command and body fold away under a toggle (closed by
  default; the client remembers each panel's state), leaving the toolbar and
  summary visible."
  [{:keys [plugin id title params iframe body-html summary-html collapsible?]}]
  (let [cache (current-cache plugin id)
        status (:status cache "never")
        report (or iframe (:iframe params) (:report params))
        show-iframe? (and report (not= status "running") (report-exists? report))
        rich-html (or body-html (:html cache) "")
        summary-html (or summary-html "")
        skeleton (hiccup/html
                  (panel-skeleton {:plugin plugin :id id :title title :params params
                                   :collapsible? collapsible? :cache cache :status status
                                   :show-iframe? show-iframe? :report report
                                   :rich-html rich-html :summary-html summary-html}))]
    {:html (-> skeleton
               (cond-> (not (str/blank? rich-html))
                 (str/replace html-marker rich-html))
               (cond-> (not (str/blank? summary-html))
                 (str/replace summary-marker summary-html)))
     :long_running true
     :plugin (name plugin)
     :cache_id (safe-id id)
     :status status
     :iframe (when show-iframe? report)
     :cache cache}))
