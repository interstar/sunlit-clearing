(ns sunlit.server
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [sunlit.agents :as agents]
            [sunlit.annotations :as ann]
            [sunlit.conventions :as conventions]
            [sunlit.fences :as fences]
            [sunlit.git :as git]
            [sunlit.jobs :as jobs]
            [sunlit.locate :as locate]
            [sunlit.pages :as pages]
            [sunlit.plugins :as plugins]
            [sunlit.render :as render]
            [sunlit.repo :as repo]
            [sunlit.sessions :as sessions]
            [compojure.core :refer [defroutes GET PUT POST]]
            [compojure.route :as route]
            [org.httpkit.server :as http]
            [ring.middleware.defaults :refer [site-defaults wrap-defaults]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.util.response :as response]))

(defn- json-response [data & [status]]
  {:status (or status 200)
   :headers {"Content-Type" "application/json; charset=utf-8"
             "Cache-Control" "no-store"}
   :body (json/generate-string data)})

(defn- read-json-body [req]
  (when-let [b (:body req)]
    (let [s (if (string? b) b (slurp b))]
      (when-not (empty? s)
        (json/parse-string s true)))))

(defn- err [status msg]
  (json-response {:ok false :error msg} status))

(defn- page-payload [name]
  (let [page (pages/read-page name)
        rendered (render/render-markdown (:markdown page) (str "page:" (:name page)))]
    {:ok true
     :page page
     :page_html (:html rendered)}))

(defroutes api-routes
  (GET "/api/health" []
    (json-response {:ok true
                    :repo (str (repo/repo-root))
                    :repo_name (repo/repo-name)
                    :plugins (plugins/list-plugins)}))

  (GET "/api/tree" []
    (try
      (let [index (pages/read-page pages/index-page-name)
            rendered (render/render-markdown (:markdown index) "page:_index")]
        (json-response {:ok true
                        :repo_name (repo/repo-name)
                        :repo (str (repo/repo-root))
                        :tree (git/annotate-tree (repo/build-tree))
                        :root_docs (repo/root-doc-entries)
                        :index index
                        :index_html (:html rendered)}))
      (catch Exception e
        (err 500 (.getMessage e)))))

  (GET "/api/file" [path sym]
    (try
      (let [file (repo/read-file path)
            annotation (ann/read-annotation path)
            rendered (render/render-annotation (:markdown annotation) path)
            auto (render/auto-plugins-for path)
            target (when-not (str/blank? sym)
                     (merge {:symbol sym :found false}
                            (some-> (locate/find-symbol (:content file) (:language file) sym)
                                    (assoc :found true))))]
        (json-response (cond-> {:ok true
                                :file file
                                :annotation annotation
                                :annotation_html (:html rendered)
                                :auto_plugins auto}
                         target (assoc :target target))))
      (catch Exception e
        (err 400 (.getMessage e)))))

  (GET "/api/dir" [path]
    (try
      (let [p (or path "")
            dir (repo/dir-info p)
            auto (render/auto-plugins-for-dir p)]
        (json-response {:ok true
                        :dir (git/annotate-tree dir)
                        :auto_plugins auto}))
      (catch Exception e
        (err 400 (.getMessage e)))))

  (GET "/api/annotation" [path]
    (try
      (let [annotation (ann/read-annotation path)
            rendered (render/render-annotation (:markdown annotation) path)]
        (json-response {:ok true
                        :annotation annotation
                        :annotation_html (:html rendered)}))
      (catch Exception e
        (err 400 (.getMessage e)))))

  (PUT "/api/annotation" [path :as req]
    (try
      (let [body (read-json-body req)
            md (or (:markdown body) "")
            saved (ann/write-annotation! path md)
            rendered (render/render-annotation (:markdown saved) path)]
        (json-response {:ok true
                        :annotation saved
                        :annotation_html (:html rendered)}))
      (catch Exception e
        (err 400 (.getMessage e)))))

  (GET "/api/pages" []
    (json-response {:ok true :pages (pages/list-pages)}))

  (GET "/api/pages/:name" [name]
    (try
      (json-response (page-payload name))
      (catch Exception e
        (err 400 (.getMessage e)))))

  (PUT "/api/pages/:name" [name :as req]
    (try
      (let [body (read-json-body req)
            md (or (:markdown body) "")
            saved (pages/write-page! name md)
            rendered (render/render-markdown (:markdown saved) (str "page:" (:name saved)))]
        (json-response {:ok true
                        :page saved
                        :page_html (:html rendered)}))
      (catch Exception e
        (err 400 (.getMessage e)))))

  (POST "/api/pages/:name/create" [name :as req]
    (try
      (let [body (read-json-body req)
            result (pages/create-page! name (:markdown body))
            rendered (render/render-markdown (:markdown result) (str "page:" (:name result)))]
        (json-response {:ok true
                        :page result
                        :page_html (:html rendered)
                        :created (:created result)}))
      (catch Exception e
        (err 400 (.getMessage e)))))

  ;; A plugin rewriting its own ```plugin fence params in a sidecar doc.
  (POST "/api/fence" req
    (try
      (let [{:keys [doc index plugin action]} (read-json-body req)]
        (when (or (str/blank? doc) (not (integer? index)) (str/blank? plugin))
          (throw (ex-info "doc, index and plugin are required" {})))
        (json-response (select-keys (fences/update-fence! doc index plugin action)
                                    [:ok :params])))
      (catch Exception e
        (err 400 (.getMessage e)))))

  (GET "/api/conventions" []
    (try
      (let [report (conventions/check)]
        (json-response {:ok true
                        :report report
                        :html (conventions/->html report)}))
      (catch Exception e
        (err 400 (.getMessage e)))))

  (GET "/api/plugins" []
    (json-response (plugins/catalog)))

  (POST "/api/plugins/:name/run" [name :as req]
    (try
      (let [body (read-json-body req)
            result (plugins/run-plugin name {:path (:path body)
                                             :params (or (:params body) {})})]
        (json-response (assoc result :ok (not (false? (:ok result true))))))
      (catch Exception e
        (err 400 (.getMessage e)))))

  (POST "/api/plugins/:name/rerun" [name :as req]
    (try
      (let [body (read-json-body req)
            result (plugins/start-plugin! name {:path (:path body)
                                                :params (or (:params body) {})})]
        (json-response result (if (:ok result) 202 400)))
      (catch Exception e
        (err 400 (.getMessage e)))))

  (GET "/api/plugins/:name/cache" [name id]
    (try
      (json-response {:ok true
                      :cache (jobs/read-cache name id)
                      :running (jobs/running? name id)})
      (catch Exception e
        (err 400 (.getMessage e)))))

  (GET "/api/artifacts/*" request
    (try
      (let [uri (:uri request)
            prefix "/api/artifacts/"
            rel (when (str/starts-with? uri prefix)
                  (java.net.URLDecoder/decode (subs uri (count prefix)) "UTF-8"))
            f (when rel (repo/safe-resolve rel))]
        (cond
          (or (nil? rel) (str/blank? rel))
          (err 400 "Missing artifact path")

          (not (.isFile f))
          (err 404 "Artifact not found")

          :else
          (-> (response/file-response (.getAbsolutePath f))
              (response/header "Cache-Control" "no-store"))))
      (catch Exception e
        (err 400 (.getMessage e)))))

  (GET "/api/sessions" []
    (json-response (sessions/list-for-repo)))

  (POST "/api/sessions/refresh" []
    (json-response (sessions/refresh!)))

  (GET "/api/sessions/:id" [id]
    (json-response (sessions/show id)))

  ;; ---- Agent-facing JSON endpoints ----

  (GET "/agents" []
    (try (json-response (agents/index-payload))
         (catch Exception e (err 500 (.getMessage e)))))

  (GET "/agents/" []
    (try (json-response (agents/index-payload))
         (catch Exception e (err 500 (.getMessage e)))))

  (GET "/agents/tree" []
    (try (json-response (agents/tree-payload))
         (catch Exception e (err 500 (.getMessage e)))))

  (GET "/agents/conventions" []
    (try (json-response (agents/conventions-payload))
         (catch Exception e (err 500 (.getMessage e)))))

  (GET "/agents/page/:name" [name]
    (try (json-response (agents/page-payload name))
         (catch Exception e (err 400 (.getMessage e)))))

  (GET ["/agents/file/:path" :path #".*"] [path]
    (try (json-response (agents/file-payload path))
         (catch Exception e (err 400 (.getMessage e)))))

  (GET "/agents/plugin/:name/:id" [name id :as req]
    (try
      (let [qp (:query-params req)
            refresh? (contains? #{"1" "true"} (str (get qp "refresh")))]
        (json-response (agents/plugin-result name {:id id :refresh? refresh?})))
      (catch Exception e (err 400 (.getMessage e)))))

  (GET "/agents/plugin/:name" [name :as req]
    (try
      (let [qp (:query-params req)
            id (get qp "id")
            refresh? (contains? #{"1" "true"} (str (get qp "refresh")))]
        (json-response (agents/plugin-result name {:id id :refresh? refresh?})))
      (catch Exception e (err 400 (.getMessage e)))))

  (GET "/" []
    (-> (response/resource-response "public/index.html")
        (response/content-type "text/html; charset=utf-8")))

  (route/resources "/")
  (route/not-found "Not found"))

(def app
  (-> api-routes
      (wrap-defaults (assoc-in site-defaults [:security :anti-forgery] false))
      wrap-content-type))

(defonce ^:private server* (atom nil))

(defn start! [{:keys [port]}]
  (when-let [s @server*]
    (s)
    (reset! server* nil))
  (ann/ensure-store!)
  (pages/ensure-store!)
  (plugins/init!)
  (let [s (http/run-server app {:port port :join? false})]
    (reset! server* s)
    (println (str "Sunlit Clearing listening on http://localhost:" port))
    (println (str "Repository: " (repo/repo-root)))
    s))

(defn stop! []
  (when-let [s @server*]
    (s)
    (reset! server* nil)))
