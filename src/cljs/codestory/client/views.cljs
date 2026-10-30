(ns codestory.client.views
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [codestory.client.api :as api]
            [codestory.client.markdown :as md]
            [codestory.client.wchnt :as wchnt]))

(defn nav-bar [route brand]
  [:header.topbar
   [:a.brand {:href "#/"} (or brand "Sunlit Clearing")]
   [:nav.topnav
    [:a {:href "#/" :class (when (= :tree (:page route)) "active")} "Tree"]
    [:a {:href "#/page/Architecture"
         :class (when (and (= :wiki (:page route))
                           (= "Architecture" (:name route)))
                  "active")}
     "Architecture"]
    [:a {:href "#/conventions" :class (when (= :conventions (:page route)) "active")} "Conventions"]
    [:a {:href "#/testing" :class (when (= :testing (:page route)) "active")} "Testing"]
    [:a {:href "#/sessions" :class (when (#{:sessions :session} (:page route)) "active")} "Sessions"]
    [:a {:href "#/plugins" :class (when (= :plugins (:page route)) "active")} "Plugins"]]
   [:div.nav-actions
    [:button.btn-ghost {:on-click #(js/history.back)} "Back"]
    [:button.btn-ghost {:on-click #(js/history.forward)} "Forward"]]])

(defn- parse-params-attr [el]
  (try
    (js->clj (.parse js/JSON (or (.getAttribute el "data-params") "{}"))
             :keywordize-keys true)
    (catch :default _
      {})))

(defn- bind-rerun-buttons! [root-el on-done]
  (when root-el
    (let [buttons (.querySelectorAll root-el ".js-plugin-rerun")]
      (doseq [i (range (.-length buttons))]
        (let [btn (aget buttons i)
              panel (.closest btn ".lr-panel")
              plugin (when panel (.getAttribute panel "data-plugin"))
              params (when panel (parse-params-attr panel))]
          (set! (.-onclick btn)
                (fn [e]
                  (.preventDefault e)
                  (when plugin
                    (set! (.-disabled btn) true)
                    (set! (.-textContent btn) "Running…")
                    (api/post-json
                     (str "/api/plugins/" plugin "/rerun")
                     {:params params}
                     (fn [_]
                       (js/setTimeout
                        (fn []
                          (when on-done (on-done))
                          (js/setTimeout #(when on-done (on-done)) 2500)
                          (js/setTimeout #(when on-done (on-done)) 6000))
                        400))
                     (fn [err]
                       (js/alert (str "Rerun failed: " err))
                       (set! (.-disabled btn) false)
                       (set! (.-textContent btn) "Rerun")))))))))))

(defn- resize-iframe! [iframe]
  (try
    (when-let [doc (.-contentDocument iframe)]
      (when-let [body (.-body doc)]
        (let [html (.-documentElement doc)
              h (max (.-scrollHeight body)
                     (.-offsetHeight body)
                     (.-scrollHeight html)
                     (.-offsetHeight html)
                     400)]
          (set! (.-height (.-style iframe)) (str h "px")))))
    (catch :default _)))

(defn- resize-iframe-on-load [iframe _event]
  (resize-iframe! iframe)
  ;; reports may grow after fonts/images; nudge a few times
  (js/setTimeout #(resize-iframe! iframe) 200)
  (js/setTimeout #(resize-iframe! iframe) 1000))

(defn- bind-iframes! [root-el]
  (when root-el
    (let [frames (.querySelectorAll root-el "iframe.lr-iframe")]
      (doseq [i (range (.-length frames))]
        (let [iframe (aget frames i)
              on-load (partial resize-iframe-on-load iframe)]
          (.addEventListener iframe "load" on-load)
          ;; already loaded (cached)
          (when (.-contentDocument iframe)
            (on-load nil)))))))

(defn- bind-mermaid! [root-el]
  (when (and root-el (exists? js/mermaid))
    (try
      (let [nodes (.querySelectorAll root-el "pre.mermaid")]
        (when (pos? (.-length nodes))
          ;; Mermaid 10: only unprocessed nodes; clear flag if content was replaced
          (doseq [i (range (.-length nodes))]
            (let [n (aget nodes i)]
              (when (and (.hasAttribute n "data-processed")
                         (nil? (.querySelector n "svg")))
                (.removeAttribute n "data-processed"))))
          ;; js-invoke keeps "run" intact under :advanced
          (js-invoke js/mermaid "run" #js {:nodes nodes})))
      (catch :default _))))

(defn html-panel
  "Render server HTML and wire long-running Rerun buttons + iframe sizing."
  [{:keys [html on-rerun class]}]
  (let [el (atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [_]
        (bind-rerun-buttons! @el on-rerun)
        (bind-iframes! @el)
        (bind-mermaid! @el)
        (wchnt/enhance-pre-codes! @el {:unlabeled? false}))
      :component-did-update
      (fn [_]
        (bind-rerun-buttons! @el on-rerun)
        (bind-iframes! @el)
        (bind-mermaid! @el)
        (wchnt/enhance-pre-codes! @el {:unlabeled? false}))
      :reagent-render
      (fn [{:keys [html class]}]
        [:div {:class (or class "html-panel")
               :ref #(reset! el %)
               :dangerouslySetInnerHTML {:__html (or html "")}}])})))

(defn- git-class [node]
  (when-let [s (:git-status node)]
    (str "git-" (name s))))

(defn- tree-node [{:keys [node expanded toggle! depth]}]
  (let [dir? (= "dir" (:type node))
        path (:path node)
        open? (or (str/blank? path) (contains? expanded path))
        git (git-class node)]
    [:li {:class (if dir? "dir" "file")}
     (if dir?
       [:div.tree-row
        {:class git
         :title (some-> (:git-status node) name)
         :style {:padding-left (str (* depth 0.9) "rem")}}
        [:button.twist
         {:on-click #(toggle! path)
          :aria-label (if open? "Collapse" "Expand")}
         (if open? "▾" "▸")]
        [:a {:href (str "#/dir/" (js/encodeURIComponent path))}
         [:span.tree-name (:name node)]]]
       [:div.tree-row
        {:class git
         :title (some-> (:git-status node) name)
         :style {:padding-left (str (* depth 0.9) "rem")}}
        [:span.twist-spacer]
        [:a {:href (str "#/file/" (js/encodeURIComponent path))}
         [:span.tree-name (:name node)]
         (when-not (:text? node)
           [:span.meta " · binary"])
         [:span.meta (str " · " (:size node) " B")]]])
     (when (and dir? open? (seq (:children node)))
       [:ul.tree
        (for [child (:children node)]
          ^{:key (:path child)}
          [tree-node {:node child
                      :expanded expanded
                      :toggle! toggle!
                      :depth (inc depth)}])])]))

(defn markdown-editor [{:keys [title markdown html on-save on-rerun placeholder]}]
  (let [draft (r/atom (or markdown ""))
        editing? (r/atom false)]
    (fn [{:keys [title markdown html on-save on-rerun placeholder]}]
      (when (and (not @editing?) (not= @draft markdown) (some? markdown))
        (reset! draft (or markdown "")))
      [:section.annotation
       [:div.section-head
        [:h2 (or title "Notes")]
        (if @editing?
          [:div.btn-row
           [:button.btn {:on-click #(do (on-save @draft)
                                        (reset! editing? false))} "Save"]
           [:button.btn-ghost {:on-click #(do (reset! draft (or markdown ""))
                                              (reset! editing? false))} "Cancel"]]
          [:button.btn-ghost {:on-click #(reset! editing? true)} "Edit"])]
       (if @editing?
         [:textarea.anno-editor
          {:value @draft
           :on-change #(reset! draft (.. % -target -value))
           :rows 12
           :placeholder (or placeholder
                            "Markdown, [[PageName]] or [[path/file.clj]], and ```plugin fences…")}]
         [:div.anno-rendered
          (if (str/blank? html)
            [:p.muted "Nothing here yet. Click Edit to add notes, links, or plugins."]
            [html-panel {:html html :on-rerun on-rerun}])])])))

(defn tree-page [st reload! toggle! on-save-index]
  (let [tree (:tree st)
        err (:tree-error st)
        expanded (:expanded st)
        index (:index st)
        repo-name (or (:repo_name st) (get-in tree [:name]) "repository")
        root-docs (or (:root_docs st) [])]
    [:section.page.tree-page
     [:div.page-head
      [:div
       [:h1 repo-name]
       (when-let [repo (:repo st)]
         [:p.crumb repo])]
      [:button.btn {:on-click reload!} "Refresh"]]
     (when (seq root-docs)
       [:ul.root-docs
        (for [d root-docs]
          ^{:key (:path d)}
          [:li
           [:a {:href (str "#/file/" (js/encodeURIComponent (:path d)))}
            (:name d)]])])
     [markdown-editor {:title "Tree notes"
                       :markdown (get-in index [:page :markdown] (:markdown index))
                       :html (or (:index_html st) (:html index))
                       :on-save on-save-index
                       :on-rerun reload!
                       :placeholder "Links like [[Testing]] and long-running ```plugin unittests fences…"}]
     (cond
       err [:p.error err]
       (nil? tree) [:p.muted "Loading tree…"]
       :else
       [:ul.tree.root
        [tree-node {:node tree
                    :expanded (conj expanded "")
                    :toggle! toggle!
                    :depth 0}]])]))

(defn- dir-breadcrumb [segs]
  [:p.crumb
   [:a {:href "#/"} "Tree"]
   (map-indexed
    (fn [i seg]
      ^{:key seg}
      [:<>
       " / "
       [:a {:href (str "#/dir/" (js/encodeURIComponent (str/join "/" (take (inc i) segs))))} seg]])
    segs)])

(defn dir-page [st toggle!]
  (let [data (:dir st)
        path (or (get-in data [:dir :path]) "")
        segs (vec (remove str/blank? (str/split path #"/")))
        dir (:dir data)
        expanded (:expanded st)]
    [:section.page.dir-page
     [:div.page-head
      [:div
       [dir-breadcrumb segs]
       [:h1 (or (:name dir) "Directory")]]
      [:div.btn-row
       [:button.btn-ghost {:on-click #(js/history.back)} "Back"]]]
     (cond
       (or (nil? data) (:loading data))
       [:p.muted "Loading directory…"]

       (false? (:ok data))
       [:p.error (or (:error data) "Failed to load directory")]

       :else
       (let [children (:children dir)
             parent (:parent dir)]
         [:<>
          (when parent
            [:p.dir-up
             [:a {:href (str "#/dir/" (js/encodeURIComponent parent))}
              "↑ up to " [:code parent]]])
          (if (empty? children)
            [:p.muted "This directory is empty."]
            [:ul.tree.root
             (for [child children]
               ^{:key (:path child)}
               [tree-node {:node child
                           :expanded expanded
                           :toggle! toggle!
                           :depth 0}])])]))]))


(defn- markdown-tabs [content language]
  (r/with-let [tab (r/atom :source)]
    [:<>
     [:div.tab-bar
      [:button.tab {:class (when (= :source @tab) "active")
                    :on-click #(reset! tab :source)} "Source"]
      [:button.tab {:class (when (= :rendered @tab) "active")
                    :on-click #(reset! tab :rendered)} "Rendered"]]
     (if (= :source @tab)
       [md/code-block content language]
       [md/markdown-doc content])]))

(defn file-page [st navigate! on-save reload-file!]
  (let [data (:file st)]
    [:section.page.file-page
     (cond
       (or (nil? data) (:loading data))
       [:p.muted "Loading file…"]

       (false? (:ok data))
       [:p.error (or (:error data) "Failed to load file")]

       :else
       (let [file (:file data)
             path (:path file)]
         [:<>
          [:div.page-head
           [:div
            [:p.crumb
             [:a {:href "#/"} "Tree"]
             " / "
             [:span path]]
            [:h1 (:name file)]]
           [:div.btn-row
            [:button.btn-ghost {:on-click #(js/history.back)} "Back"]]]
          (when (seq (:auto_plugins data))
            [:div.auto-plugins
             (for [[i p] (map-indexed vector (:auto_plugins data))]
               ^{:key i}
               [html-panel {:html (or (:html p) "") :on-rerun reload-file!}])])
          [markdown-editor {:title "Annotation"
                            :markdown (get-in data [:annotation :markdown])
                            :html (:annotation_html data)
                            :on-save #(on-save path %)
                            :on-rerun reload-file!}]
          [:section.source
           [:div.section-head
            [:h2 "Source"]
            [:span.meta (str (:size file) " bytes"
                             (when (:language file) (str " · " (:language file))))]]
           (if (:text? file)
             (cond
               (md/wcn-file? file)
               [md/wcn-doc (:content file)]

               (md/markdown-file? file)
               [markdown-tabs (:content file) (:language file)]

               :else
               [md/code-block (:content file) (:language file)])
             [:p.muted "Binary or large file — content not shown. A plugin may open this later."])]]))]))

(defn wiki-page [st on-save on-create reload!]
  (let [data (:wiki st)]
    [:section.page.wiki-page
     (cond
       (or (nil? data) (:loading data))
       [:p.muted "Loading page…"]

       (false? (:ok data))
       [:p.error (or (:error data) "Failed to load page")]

       (not (get-in data [:page :exists]))
       (let [name (or (get-in data [:page :name]) (:name data))]
         [:<>
          [:div.page-head
           [:div
            [:p.crumb [:a {:href "#/"} "Tree"] " / pages"]
            [:h1 name]]]
          [:div.missing-page
           [:p "This page does not exist yet."]
           [:button.btn {:on-click #(on-create name)} "Create page"]]])

       :else
       (let [page (:page data)
             name (:name page)]
         [:<>
          [:div.page-head
           [:div
            [:p.crumb
             [:a {:href "#/"} "Tree"]
             " / "
             [:span name]]
            [:h1 name]]
           [:div.btn-row
            [:button.btn-ghost {:on-click #(js/history.back)} "Back"]]]
          [markdown-editor {:title "Page"
                            :markdown (:markdown page)
                            :html (:page_html data)
                            :on-save #(on-save name %)
                            :on-rerun reload!}]]))]))

(defn conventions-page [st reload!]
  (let [data (:conventions st)]
    [:section.page.conventions-page
     [:div.page-head
      [:h1 "Conventions"]
      [:button.btn {:on-click reload!} "Refresh"]]
     (cond
       (or (nil? data) (:loading data))
       [:p.muted "Checking this project against the conventions template…"]

       (false? (:ok data))
       [:p.error (or (:error data) "Could not check conventions")]

       :else
       [:<>
        (when-let [title (get-in data [:report :title])]
          [:p.muted (str "Template: " title)])
        [html-panel {:html (:html data) :on-rerun reload!}]])]))

(defn testing-page [st reload!]
  (let [data (:testing st)]
    [:section.page.testing-page
     [:div.page-head
      [:h1 "Testing"]
      [:button.btn {:on-click reload!} "Refresh"]]
     (cond
       (or (nil? data) (:loading data))
       [:p.muted "Running the test suite…"]

       (false? (:ok data))
       [:p.error (or (:error data) "Could not run tests")]

       :else
       [:<>
        (for [panel (:panels data)]
          ^{:key (:plugin panel)}
          [html-panel {:html (:html panel) :on-rerun reload!}])])]))

(defn- plugin-list [items empty-msg]
  (if (empty? items)
    [:p.muted empty-msg]
    [:ul.plugin-list
     (for [p items]
       ^{:key (:name p)}
       [:li.plugin-card
        [:div.plugin-card-head
         [:h3 [:code (:name p)]
          (when (:long-running? p)
            [:span.badge " long-running"])]
         (when (:file p)
           [:span.meta (:file p)])]
        [:p (if (str/blank? (:description p))
              "No description provided."
              (:description p))]])]))

(defn plugins-page [st reload!]
  (let [data (:plugins st)]
    [:section.page.plugins-page
     [:div.page-head
      [:h1 "Plugins"]
      [:button.btn {:on-click reload!} "Refresh"]]
     (cond
       (or (nil? data) (:loading data))
       [:p.muted "Loading plugins…"]

       (false? (:ok data))
       [:p.error (or (:error data) "Could not load plugins")]

       :else
       [:<>
        [:section.plugin-section
         [:h2 "Built-in"]
         [:p.muted "Shipped with Sunlit Clearing under src/clj/codestory/plugins/"]
         [plugin-list (:builtin data) "No built-in plugins registered."]]
        [:section.plugin-section
         [:h2 "In this repository"]
         [:p.muted "Loaded from .sunlit/plugins/*.clj in the watched repo"]
         [plugin-list (:repo data) "No project plugins found in .sunlit/plugins/."]]])]))

(defn- session-list-item [s]
  [:li
   [:a {:href (str "#/session/" (js/encodeURIComponent (:session_id s)))}
    [:span.when (or (:started_at s) "unknown time")]
    [:span.source {:class (str "src-" (or (:source s) "unknown"))}
     (:source s)]
    [:span.count (str (:message_count s) " msgs")]]
   [:div.slug
    (or (:title s) (:slug s) (:cwd s))]
   (when (and (:title s) (:cwd s))
     [:div.slug.muted (:cwd s)])])

(defn sessions-page [st navigate! reload! refresh!]
  (let [data (:sessions st)
        running? (= "running" (:status data))]
    [:section.page.sessions-page
     [:div.page-head
      [:div
       [:h1 "Agent sessions"]
       (when (:cached_at data)
         [:p.muted (str "Cached " (:cached_at data)
                        " · .sunlit/cache/sessions/")])]
      [:div.btn-row
       [:button.btn-ghost {:on-click reload! :disabled running?} "Reload"]
       [:button.btn {:on-click refresh! :disabled running?}
        (if running? "Refreshing…" "Refresh cache")]]]
     (cond
       (or (nil? data) (:loading data))
       [:p.muted "Loading sessions…"]

       running?
       [:div
        [:p.muted (or (:message data) "Refreshing session cache…")]
        (when (seq (:sessions data))
          [:p.muted "Showing previous cache until refresh finishes."])]

       (and (false? (:ok data)) (empty? (:sessions data)))
       [:div
        [:p.error (or (:error data) "Could not load sessions")]
        [:p.muted "Sessions are cached from Codex / Cursor / Pi via scripts/sessions_bridge.py."]]

       (= "never" (:status data))
       [:div
        [:p.muted (or (:message data)
                      "No session cache yet.")]
        [:p.muted "Click Refresh cache to scan Codex / Cursor / Pi sessions for this repo."]]

       :else
       [:ul.session-list
        (if (empty? (:sessions data))
          [:li.muted "No agent sessions found for this repository."]
          (for [s (:sessions data)]
            ^{:key (:session_id s)}
            [session-list-item s]))])]))

(defn session-page [st navigate!]
  (let [data (:session st)]
    [:section.page.session-page
     [:div.page-head
      [:div
       [:p.crumb [:a {:href "#/sessions"} "Sessions"] " / detail"]
       [:h1 "Session"]]
      [:button.btn-ghost {:on-click #(js/history.back)} "Back"]]
     (cond
       (or (nil? data) (:loading data))
       [:p.muted "Loading transcript…"]

       (false? (:ok data))
       [:p.error (or (:error data) "Failed to load session")]

       :else
       [:div
        [:dl.session-meta
         [:dt "ID"] [:dd [:code (get-in data [:session :session_id])]]
         [:dt "Source"]
         [:dd [:span.source
               {:class (str "src-" (or (get-in data [:session :source]) ""))}
               (get-in data [:session :source])]]
         [:dt "Started"] [:dd (or (get-in data [:session :started_at]) "—")]
         (when (get-in data [:session :title])
           (list [:dt "Title"] [:dd (get-in data [:session :title])]))
         [:dt "CWD"] [:dd [:code (or (get-in data [:session :cwd]) "—")]]]
        [:div.transcript
         (for [[i m] (map-indexed vector (:messages data))]
           ^{:key i}
           [:article.message {:class (:role m)}
            [:header
             [:span.role (:role m)]
             [:span.when (or (:timestamp m) "")]]
            [md/message-body (:text m)]])]])]))
