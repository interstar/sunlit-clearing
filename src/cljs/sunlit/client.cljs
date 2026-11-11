(ns sunlit.client
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [clojure.string :as str]
            [sunlit.client.api :as api]
            [sunlit.client.views :as views]))

(defonce app-state
  (r/atom {:route {:page :tree}
           :tree nil
           :repo_name nil
           :root_docs nil
           :tree-error nil
           :index nil
           :index_html nil
           :expanded #{""}
           :file nil
           :dir nil
           :wiki nil
           :plugins nil
           :conventions nil
           :testing nil
           :sessions nil
           :session nil
           :status nil
           :agents nil}))

(defn- parse-query
  "\"line=3&sym=foo\" -> {\"line\" \"3\" \"sym\" \"foo\"}"
  [q]
  (into {}
        (for [part (str/split (or q "") #"&")
              :when (not (str/blank? part))
              :let [[k v] (str/split part #"=" 2)]]
          [(js/decodeURIComponent k) (js/decodeURIComponent (or v ""))])))

(defn- parse-hash []
  (let [h (or (.-hash js/location) "")
        h (if (str/starts-with? h "#") (subs h 1) h)
        h (if (str/starts-with? h "/") h (str "/" h))]
    (cond
      (or (= h "/") (= h "/tree") (str/blank? h))
      {:page :tree}

      (str/starts-with? h "/file/")
      (let [[p q] (str/split (subs h 6) #"\?" 2)
            qp (parse-query q)]
        {:page :file
         :path (js/decodeURIComponent p)
         :line (not-empty (get qp "line"))
         :sym (not-empty (get qp "sym"))})

      (str/starts-with? h "/dir/")
      {:page :dir :path (js/decodeURIComponent (subs h 5))}

      (str/starts-with? h "/page/")
      {:page :wiki :name (js/decodeURIComponent (subs h 6))}

      (= h "/plugins")
      {:page :plugins}

      (= h "/conventions")
      {:page :conventions}

      (= h "/testing")
      {:page :testing}

      (= h "/lint")
      {:page :wiki :name "Lint"}

      (= h "/sessions")
      {:page :sessions}

      (= h "/agents")
      {:page :agents}

      (str/starts-with? h "/session/")
      {:page :session :id (js/decodeURIComponent (subs h 9))}

      :else {:page :tree})))

(defn navigate! [path]
  (set! (.-hash js/location) path))

(defn- load-tree! []
  (api/get-json "/api/tree"
                (fn [data]
                  (if (:ok data)
                    (swap! app-state assoc
                           :tree (:tree data)
                           :repo_name (:repo_name data)
                           :repo (:repo data)
                           :root_docs (:root_docs data)
                           :index (:index data)
                           :index_html (:index_html data)
                           :tree-error nil)
                    (swap! app-state assoc :tree-error (:error data))))
                (fn [err]
                  (swap! app-state assoc :tree-error err))))

(defn- load-file!
  "Fetch a file (and resolve `sym` to a line range server-side). Reloading the
  file already on screen keeps it visible instead of flashing a spinner."
  [path & [sym]]
  (when-not (= path (get-in @app-state [:file :file :path]))
    (swap! app-state assoc :file {:loading true :path path}))
  (api/get-json (str "/api/file?path=" (js/encodeURIComponent path)
                     (when sym (str "&sym=" (js/encodeURIComponent sym))))
                (fn [data]
                  (swap! app-state assoc :file (assoc data :sym sym)))
                (fn [err]
                  (swap! app-state assoc :file {:ok false :error err :path path}))))

(defn- load-dir! [path]
  (swap! app-state assoc :dir {:loading true :path path})
  (api/get-json (str "/api/dir?path=" (js/encodeURIComponent (or path "")))
                (fn [data]
                  (swap! app-state assoc :dir data)
                  ;; open immediate subdirectories one level by default
                  (when-let [children (get-in data [:dir :children])]
                    (swap! app-state update :expanded
                           (fn [s]
                             (into (or s #{})
                                   (keep #(when (= "dir" (:type %)) (:path %))
                                         children))))))
                (fn [err]
                  (swap! app-state assoc :dir {:ok false :error err :path path}))))

(defn- load-wiki! [name]
  (when-not (and (= name (get-in @app-state [:wiki :name]))
                 (get-in @app-state [:wiki :ok]))
    (swap! app-state assoc :wiki {:loading true :name name}))
  (api/get-json (str "/api/pages/" (js/encodeURIComponent name))
                (fn [data]
                  (swap! app-state assoc :wiki (assoc data :name name)))
                (fn [err]
                  (swap! app-state assoc :wiki {:ok false :error err :name name}))))

(defn- load-plugins! []
  (swap! app-state assoc :plugins {:loading true})
  (api/get-json "/api/plugins"
                (fn [data] (swap! app-state assoc :plugins data))
                (fn [err] (swap! app-state assoc :plugins {:ok false :error err}))))

(defn- load-conventions! []
  (swap! app-state assoc :conventions {:loading true})
  (api/get-json "/api/conventions"
                (fn [data] (swap! app-state assoc :conventions data))
                (fn [err] (swap! app-state assoc :conventions {:ok false :error err}))))

(defn- panels-state
  "Page state from every plugin's reply in `rs`, in `plugins` order. When any
  failed, :error names each failed plugin and its reason."
  [plugins rs]
  (let [failed (remove #(:ok (get rs %)) plugins)]
    (cond-> {:ok (empty? failed)
             :panels (mapv (fn [p] {:plugin p :html (:html (get rs p))}) plugins)}
      (seq failed)
      (assoc :error (str/join "; " (map #(str % ": " (or (:error (get rs %)) "failed"))
                                        failed))))))

(defn- record-panel!
  "Store `plugin`'s reply; once all `plugins` have replied, publish them under `k`."
  [k plugins results plugin data]
  (let [rs (swap! results assoc plugin data)]
    (when (= (count rs) (count plugins))
      (swap! app-state assoc k (panels-state plugins rs)))))

(defn- load-plugin-panels!
  "Run `plugins` in parallel and show their panels together under state key `k`."
  [k plugins]
  (swap! app-state assoc k {:loading true})
  (let [results (atom {})]
    (doseq [plugin plugins]
      (let [on-done (partial record-panel! k plugins results plugin)]
        (api/post-json (str "/api/plugins/" plugin "/run")
                       {:params {}}
                       on-done
                       #(on-done {:ok false :error %}))))))

(defn- load-testing! []
  (load-plugin-panels! :testing ["unittests" "projectlint"]))

(defn- load-sessions! []
  (api/get-json "/api/sessions"
                (fn [data] (swap! app-state assoc :sessions data))
                (fn [err] (swap! app-state assoc :sessions {:ok false :error err}))))

(defn- load-sessions-busy! []
  (swap! app-state assoc :sessions {:loading true})
  (load-sessions!))

(defn- refresh-sessions! []
  (swap! app-state assoc-in [:sessions :status] "running")
  (api/post-json "/api/sessions/refresh"
                 {}
                 (fn [_]
                   (doseq [ms [500 2000 5000 12000 25000]]
                     (js/setTimeout load-sessions! ms)))
                 (fn [err]
                   (swap! app-state assoc :sessions {:ok false :error err}))))

(defn- load-session! [id]
  (swap! app-state assoc :session {:loading true :id id})
  (api/get-json (str "/api/sessions/" (js/encodeURIComponent id))
                (fn [data] (swap! app-state assoc :session data))
                (fn [err] (swap! app-state assoc :session {:ok false :error err}))))

(defn- load-agents! []
  (swap! app-state assoc :agents {:loading true})
  (api/get-json "/agents/"
                (fn [data] (swap! app-state assoc :agents data))
                (fn [err] (swap! app-state assoc :agents {:ok false :error err}))))

(defn- same-file-loaded?
  "Moving between lines of the file on screen needs no refetch; a new symbol
  does (the server resolves it)."
  [prev-route route]
  (let [f (:file @app-state)]
    (and (= :file (:page prev-route))
         (= (:path prev-route) (:path route))
         (:ok f)
         (= (:sym f) (:sym route)))))

(defn sync-route! []
  (let [prev (:route @app-state)
        route (parse-hash)]
    (swap! app-state assoc :route route)
    (case (:page route)
      :tree (load-tree!)
      :file (when-not (same-file-loaded? prev route)
              (load-file! (:path route) (:sym route)))
      :dir (load-dir! (:path route))
      :wiki (load-wiki! (:name route))
      :plugins (load-plugins!)
      :conventions (load-conventions!)
      :testing (load-testing!)
      :sessions (load-sessions-busy!)
      :session (load-session! (:id route))
      :agents (load-agents!)
      nil)))

(defn- toast! [msg]
  (swap! app-state assoc :status msg)
  (js/setTimeout #(swap! app-state assoc :status nil) 2000))

(defn- toggle-expanded! [path]
  (swap! app-state update :expanded
         (fn [s] (if (contains? s path) (disj s path) (conj s path)))))

(defn- save-index! [markdown]
  (api/put-json "/api/pages/_index"
                {:markdown markdown}
                (fn [data]
                  (swap! app-state assoc
                         :index (:page data)
                         :index_html (:page_html data))
                  (toast! "Tree notes saved"))
                (fn [err] (toast! (str "Save failed: " err)))))

(defn- save-annotation! [path markdown]
  (api/put-json (str "/api/annotation?path=" (js/encodeURIComponent path))
                {:markdown markdown}
                (fn [data]
                  (swap! app-state assoc-in [:file :annotation] (:annotation data))
                  (swap! app-state assoc-in [:file :annotation_html] (:annotation_html data))
                  (toast! "Annotation saved"))
                (fn [err] (toast! (str "Save failed: " err)))))

(defn- save-page! [name markdown]
  (api/put-json (str "/api/pages/" (js/encodeURIComponent name))
                {:markdown markdown}
                (fn [data]
                  (swap! app-state assoc :wiki data)
                  (toast! "Page saved"))
                (fn [err] (toast! (str "Save failed: " err)))))

(defn- create-page! [name]
  (api/post-json (str "/api/pages/" (js/encodeURIComponent name) "/create")
                 {}
                 (fn [data]
                   (swap! app-state assoc :wiki data)
                   (toast! "Page created"))
                 (fn [err] (toast! (str "Create failed: " err)))))

(defn- reload-file! []
  (when-let [p (get-in @app-state [:route :path])]
    (load-file! p (get-in @app-state [:route :sym]))))

(defn- reload-wiki! []
  (when-let [n (get-in @app-state [:route :name])]
    (load-wiki! n)))

(defn root []
  (let [st @app-state
        route (:route st)]
    [:div.app
     [views/nav-bar route (:repo_name st)]
     [:main.main
      (case (:page route)
        :tree [views/tree-page st load-tree! toggle-expanded! save-index!]
        :file [views/file-page st navigate! save-annotation! reload-file!]
        :dir [views/dir-page st toggle-expanded!]
        :wiki [views/wiki-page st save-page! create-page! reload-wiki!]
        :plugins [views/plugins-page st load-plugins!]
        :conventions [views/conventions-page st load-conventions!]
        :testing [views/testing-page st load-testing!]
        :sessions [views/sessions-page st navigate! load-sessions! refresh-sessions!]
        :session [views/session-page st navigate!]
        :agents [views/agents-page st load-agents!]
        [:p "Unknown page"])]
     (when-let [status (:status st)]
       [:div.status-toast status])]))

(defn- load-meta! []
  (api/get-json "/api/health"
                (fn [data]
                  (when (:ok data)
                    (swap! app-state assoc :repo_name (:repo_name data))))
                (fn [_] nil)))

(defn ^:export init []
  (load-meta!)
  (sync-route!)
  (set! (.-onhashchange js/window) (fn [_] (sync-route!)))
  (rdom/render [root] (.getElementById js/document "app")))

(defn ^:dev/after-load reload []
  (rdom/render [root] (.getElementById js/document "app")))
