(ns sunlit.client.views
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [sunlit.client.api :as api]
            [sunlit.client.markdown :as md]
            [sunlit.client.wchnt :as wchnt]))

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
    [:a {:href "#/page/Lint"
         :class (when (and (= :wiki (:page route)) (= "Lint" (:name route))) "active")}
     "Lint"]
    [:a {:href "#/sessions" :class (when (#{:sessions :session} (:page route)) "active")} "Sessions"]
    [:a {:href "#/agents" :class (when (= :agents (:page route)) "active")} "Agents"]
    [:a {:href "#/plugins" :class (when (= :plugins (:page route)) "active")} "Plugins"]]
   [:div.nav-actions
    [:button.btn-ghost {:on-click #(js/history.back)} "Back"]
    [:button.btn-ghost {:on-click #(js/history.forward)} "Forward"]]])

(def image-exts
  #{"png" "jpg" "jpeg" "gif" "webp" "svg" "bmp" "ico" "avif"})

(defn- file-ext [name]
  (let [n (str/lower-case (or name ""))
        i (str/last-index-of n ".")]
    (when (and i (pos? i)) (subs n (inc i)))))

(defn- image-file? [file]
  (boolean (and file (contains? image-exts (file-ext (:name file))))))

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

(defn- panel-open-key [panel]
  (str "sunlit.lr-open:" (.-hash js/location) ":" (.getAttribute panel "data-plugin")
       "/" (.getAttribute panel "data-cache-id")))

(defn- stored-open? [k]
  (try (= "1" (.getItem js/localStorage k)) (catch :default _ false)))

(defn- store-open! [k open?]
  (try (if open? (.setItem js/localStorage k "1") (.removeItem js/localStorage k))
       (catch :default _ nil)))

(defn- set-panel-open! [panel open?]
  (.toggle (.-classList panel) "lr-open" open?)
  (when-let [btn (.querySelector panel ".js-lr-toggle")]
    (.setAttribute btn "aria-expanded" (str open?))))

(defn- toggle-panel! [panel e]
  (.preventDefault e)
  (let [open? (not (.contains (.-classList panel) "lr-open"))]
    (store-open! (panel-open-key panel) open?)
    (set-panel-open! panel open?)))

(defn- bind-collapsibles!
  "Open/close collapsible long-running panels (closed unless the viewer left
  them open; remembered per page and panel in localStorage)."
  [root-el]
  (when root-el
    (doseq [panel (array-seq (.querySelectorAll root-el ".lr-collapsible"))]
      (set-panel-open! panel (stored-open? (panel-open-key panel)))
      (doseq [el (array-seq (.querySelectorAll panel ".js-lr-toggle, .lr-meta strong"))]
        (set! (.-onclick el) (partial toggle-panel! panel))))))

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

(defn- attach-diagram-links! [el]
  (when-let [raw (.getAttribute el "data-links")]
    (try
      (let [links (js->clj (.parse js/JSON raw) :keywordize-keys true)]
        (doseq [{:keys [id href]} links
                :let [sel (str "[id^=\"flowchart-" id "-\"], [id^=\"classId-" id "-\"]")]]
          (doseq [node (array-seq (.querySelectorAll el sel))]
            (set! (.-cursor (.-style node)) "pointer")
            (set! (.-onclick node)
                  (fn [e]
                    (.preventDefault e)
                    (set! (.-hash js/location) href))))))
      (catch :default _))))

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
          (let [result (js-invoke js/mermaid "run" #js {:nodes nodes})]
            (.then result
                   (fn []
                     (doseq [n (array-seq nodes)]
                       (attach-diagram-links! n)))))))
      (catch :default _))))

(defn- query-all [root sel]
  (array-seq (.querySelectorAll root sel)))

(defn- attr [el k] (or (.getAttribute el k) ""))

;; Active slice tab per matrix (doc + fence), so edits don't reset the view.
(defonce ^:private matrix-tabs (atom {}))

(defn- matrix-key [panel]
  (str (attr panel "data-doc") "#" (attr panel "data-fence")))

(defn- matrix-post! [panel action on-done]
  (api/post-json "/api/fence"
                 {:doc (attr panel "data-doc")
                  :index (js/parseInt (attr panel "data-fence") 10)
                  :plugin "matrix"
                  :action action}
                 (fn [res]
                   (if (false? (:ok res))
                     (js/alert (str "Matrix update failed: " (:error res)))
                     (when on-done (on-done))))
                 (fn [err] (js/alert (str "Matrix update failed: " err)))))

(def ^:private matrix-editor-html
  (str "<form class=\"matrix-editor\">"
       "<h4 class=\"js-title\"></h4>"
       "<label>Link <input name=\"link\" autocomplete=\"off\" "
       "placeholder=\"path/to/file.clj#symbol  ·  path#42  ·  path#10-30\"></label>"
       "<p class=\"matrix-editor-hint js-auto\"></p>"
       "<label>Status <select name=\"status\">"
       "<option value=\"\">auto</option><option value=\"done\">done</option>"
       "<option value=\"todo\">todo</option><option value=\"n/a\">n/a (deliberately absent)</option>"
       "</select></label>"
       "<label>Note <input name=\"note\" autocomplete=\"off\"></label>"
       "<div class=\"btn-row\">"
       "<button type=\"submit\" class=\"btn\">Save</button>"
       "<button type=\"button\" class=\"btn-ghost js-clear\">Clear cell</button>"
       "<button type=\"button\" class=\"btn-ghost js-cancel\">Cancel</button>"
       "</div></form>"))

(defn- open-matrix-editor! [panel td on-done]
  (let [coords (js->clj (.parse js/JSON (attr td "data-coords")))
        backdrop (.createElement js/document "div")
        _ (set! (.-className backdrop) "matrix-editor-backdrop")
        _ (set! (.-innerHTML backdrop) matrix-editor-html)
        form (.querySelector backdrop "form")
        field #(.querySelector form (str "[name=" % "]"))
        close! #(.remove backdrop)
        post! #(do (close!) (matrix-post! panel (assoc % :coords coords) on-done))
        auto-link (attr td "data-auto-link")]
    (set! (.-textContent (.querySelector form ".js-title")) (str/join " × " (vals coords)))
    (set! (.-textContent (.querySelector form ".js-auto"))
          (if (str/blank? auto-link)
            "Repo-relative path; #anchor is a symbol or line(s)."
            (str "From pattern: " auto-link " (a link here overrides it)")))
    (set! (.-value (field "link")) (attr td "data-link"))
    (set! (.-value (field "status")) (attr td "data-status"))
    (set! (.-value (field "note")) (attr td "data-note"))
    (set! (.-onsubmit form)
          (fn [e]
            (.preventDefault e)
            (post! {:op "set-cell"
                    :link (.-value (field "link"))
                    :status (.-value (field "status"))
                    :note (.-value (field "note"))})))
    (set! (.-onclick (.querySelector form ".js-clear")) #(post! {:op "clear-cell"}))
    (set! (.-onclick (.querySelector form ".js-cancel")) close!)
    (set! (.-onclick backdrop) #(when (= (.-target %) backdrop) (close!)))
    (set! (.-onkeydown backdrop) #(when (= "Escape" (.-key %)) (close!)))
    (.appendChild (.-body js/document) backdrop)
    (.focus (field "link"))))

(defn- show-matrix-slice! [panel slice]
  (doseq [el (query-all panel ".matrix-slice")]
    (set! (.-hidden el) (not= slice (attr el "data-slice"))))
  (doseq [b (query-all panel ".matrix-tab")]
    (if (= slice (attr b "data-slice"))
      (.add (.-classList b) "active")
      (.remove (.-classList b) "active")))
  (swap! matrix-tabs assoc (matrix-key panel) slice))

(defn- bind-matrix!
  "Wire matrix plugin panels: slice tabs, axis selects, cell editor.
  Edits go to /api/fence, which rewrites the plugin's own fence params."
  [root-el on-done]
  (when root-el
    (doseq [panel (query-all root-el ".matrix-panel")]
      (doseq [b (query-all panel ".matrix-tab")]
        (set! (.-onclick b) #(show-matrix-slice! panel (attr b "data-slice"))))
      (when-let [slice (get @matrix-tabs (matrix-key panel))]
        (when (.querySelector panel (str ".matrix-slice[data-slice=\"" slice "\"]"))
          (show-matrix-slice! panel slice)))
      (when (seq (attr panel "data-doc"))
        (doseq [sel (query-all panel ".matrix-axis")]
          (set! (.-onchange sel)
                (fn [_]
                  (let [v #(.-value (.querySelector panel (str ".matrix-axis[data-axis=" % "]")))]
                    (matrix-post! panel {:op "set-axes" :rows (v "rows") :cols (v "cols")
                                         :changed (attr sel "data-axis")}
                                  on-done)))))
        (doseq [td (query-all panel "td.matrix-cell[data-coords]")]
          (let [open! #(open-matrix-editor! panel td on-done)]
            (when-let [btn (.querySelector td ".matrix-edit")]
              (set! (.-onclick btn) (fn [e] (.preventDefault e) (open!))))
            ;; checklist mode: tick = done, untick = back to open;
            ;; any link/note on the cell is kept
            (when-let [box (.querySelector td ".matrix-check")]
              (set! (.-onchange box)
                    (fn [_]
                      (set! (.-disabled box) true)
                      (matrix-post! panel
                                    {:op "set-cell"
                                     :coords (js->clj (.parse js/JSON (attr td "data-coords")))
                                     :link (attr td "data-link")
                                     :note (attr td "data-note")
                                     :status (if (.-checked box) "done" "")}
                                    on-done))))
            (set! (.-ondblclick td) (fn [e] (.preventDefault e) (open!)))))))))

(defn html-panel
  "Render server HTML and wire long-running Rerun buttons + iframe sizing."
  [{:keys [html on-rerun class]}]
  (let [el (atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [_]
        (bind-rerun-buttons! @el on-rerun)
        (bind-collapsibles! @el)
        (bind-matrix! @el on-rerun)
        (bind-iframes! @el)
        (bind-mermaid! @el)
        (wchnt/enhance-pre-codes! @el {:unlabeled? false}))
      :component-did-update
      (fn [this]
        (let [{:keys [on-rerun]} (second (r/argv this))]
          (bind-rerun-buttons! @el on-rerun)
          (bind-collapsibles! @el)
          (bind-matrix! @el on-rerun))
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
             parent (:parent dir)
             gallery? (boolean (some #(= "gallery" (:plugin %)) (:auto_plugins data)))
             tree-children (if gallery?
                             (remove #(and (= "file" (:type %)) (image-file? %)) children)
                             children)]
         [:<>
          (when (seq (:auto_plugins data))
            [:div.auto-plugins
             (for [[i p] (map-indexed vector (:auto_plugins data))]
               ^{:key i}
               [html-panel {:html (or (:html p) "") :on-rerun nil}])])
          (when parent
            [:p.dir-up
             [:a {:href (str "#/dir/" (js/encodeURIComponent parent))}
              "↑ up to " [:code parent]]])
          (if (empty? tree-children)
            (if gallery?
              [:p.muted "Only images here — shown above."]
              [:p.muted "This directory is empty."])
            [:ul.tree.root
             (for [child tree-children]
               ^{:key (:path child)}
               [tree-node {:node child
                           :expanded expanded
                           :toggle! toggle!
                           :depth 0}])])]))]))


(defn- markdown-tabs [content language target on-line]
  (r/with-let [tab (r/atom (if target :source :rendered))]
    [:<>
     [:div.tab-bar
      [:button.tab {:class (when (= :source @tab) "active")
                    :on-click #(reset! tab :source)} "Source"]
      [:button.tab {:class (when (= :rendered @tab) "active")
                    :on-click #(reset! tab :rendered)} "Rendered"]]
     (if (= :source @tab)
       [md/code-view content language target on-line]
       [md/markdown-doc content])]))

(defn- parse-line-param
  "\"12\" / \"12-30\" -> {:line 12 :end 30}"
  [s]
  (when-let [[_ a b] (re-matches #"(\d+)(?:-(\d+))?" (or s ""))]
    (let [a (js/parseInt a 10)
          b (if b (js/parseInt b 10) a)]
      {:line (min a b) :end (max a b)})))

(defn- file-target [route data]
  (or (parse-line-param (:line route))
      (when (and (:sym route) (get-in data [:target :found]))
        (select-keys (:target data) [:line :end]))))

(defn- line-href [path {:keys [line end]}]
  (str "#/file/" (js/encodeURIComponent path)
       "?line=" line (when (and end (not= end line)) (str "-" end))))

(defn- location-note [route data target path]
  (let [sym (:sym route)
        t (:target data)]
    (cond
      (and sym (false? (:found t)))
      [:p.error (str "Symbol “" sym "” not found in this file.")]

      target
      [:p.location-note
       (if sym
         [:<> "Symbol " [:code sym]
          (when (false? (:exact t)) " (first mention — no definition found)")
          " · "]
         "Selected ")
       (if (= (:line target) (:end target))
         (str "line " (:line target))
         (str "lines " (:line target) "–" (:end target)))
       " · "
       [:a {:href (line-href path target)} "permalink"]
       " · "
       [:a {:href (str "#/file/" (js/encodeURIComponent path))} "clear"]])))

(defn- line-selection
  "Lines selected by a gutter click on `line`: shift extends the current
  `target` to include it; a plain click selects just that line."
  [target line shift?]
  (if (and shift? target)
    {:line (min line (:line target))
     :end (max line (:end target))}
    {:line line :end line}))

(defn- file-head [file]
  [:div.page-head
   [:div
    [:p.crumb
     [:a {:href "#/"} "Tree"]
     " / "
     [:span (:path file)]]
    [:h1 (:name file)]]
   [:div.btn-row
    [:button.btn-ghost {:on-click #(js/history.back)} "Back"]]])

(defn- auto-plugin-panels
  "Panels of the plugins that open automatically for this file (e.g. an image)."
  [plugins reload-file!]
  (when (seq plugins)
    [:div.auto-plugins
     (for [[i p] (map-indexed vector plugins)]
       ^{:key i}
       [html-panel {:html (:html p) :on-rerun reload-file!}])]))

(defn- source-meta [file]
  (str (:size file) " bytes"
       (if (image-file? file)
         " · image"
         (when (:language file)
           (str " · " (:language file))))))

(defn- source-body
  "The file's content: rendered/source tabs for markdown, a code view with a
  line gutter for other text, or a note for binary files. Keyed by path so a
  new file gets a fresh view (and scroll state)."
  [file target on-line]
  (let [{:keys [path content language]} file]
    (cond
      (not (:text? file))
      (if (image-file? file)
        [:p.muted "Image preview is shown above."]
        [:p.muted "Binary or large file — content not shown. A plugin may open this later."])

      (md/wcn-file? file)
      [md/wcn-doc content]

      (md/markdown-file? file)
      ^{:key path}
      [markdown-tabs content language target on-line]

      :else
      ^{:key path}
      [md/code-view content language target on-line])))

(defn- source-section [route data target on-line]
  (let [file (:file data)]
    [:section.source
     [:div.section-head
      [:h2 "Source"]
      [:span.meta (source-meta file)]]
     [location-note route data target (:path file)]
     [source-body file target on-line]]))

(defn- file-view
  "A loaded file: header, auto plugins, annotation editor and source."
  [data route navigate! on-save reload-file!]
  (let [path (get-in data [:file :path])
        target (file-target route data)
        on-line #(navigate! (line-href path (line-selection target %1 %2)))]
    [:<>
     [file-head (:file data)]
     [auto-plugin-panels (:auto_plugins data) reload-file!]
     [markdown-editor {:title "Annotation"
                       :markdown (get-in data [:annotation :markdown])
                       :html (:annotation_html data)
                       :on-save #(on-save path %)
                       :on-rerun reload-file!}]
     [source-section route data target on-line]]))

(defn file-page [st navigate! on-save reload-file!]
  (let [data (:file st)]
    [:section.page.file-page
     (cond
       (or (nil? data) (:loading data))
       [:p.muted "Loading file…"]

       (false? (:ok data))
       [:p.error (or (:error data) "Failed to load file")]

       :else
       [file-view data (:route st) navigate! on-save reload-file!])]))

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
         [:p.muted "Shipped with Sunlit Clearing under src/clj/sunlit/plugins/"]
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

(defn- search-hit [{:keys [session_id source role timestamp snippet]}]
  [:li
   [:a {:href (str "#/session/" (js/encodeURIComponent session_id))}
    [:span.when (or timestamp "")]
    [:span.source {:class (str "src-" source)} source]
    [:span.count role]]
   [:div.slug snippet]])

(defn- run-search! [state]
  (let [q (str/trim (:q @state))]
    (if (str/blank? q)
      (swap! state assoc :hits nil :error nil)
      (do (swap! state assoc :searching true)
          (api/get-json (str "/api/sessions/search?q=" (js/encodeURIComponent q))
                        #(swap! state assoc :searching false :hits (:hits %) :error nil)
                        #(swap! state assoc :searching false :error %))))))

(defn- session-search
  "Search box over the cached transcripts, with its hits."
  []
  (let [state (r/atom {:q ""})]
    (fn []
      (let [{:keys [q hits searching error]} @state]
        [:div.session-search
         [:form {:on-submit (fn [e] (.preventDefault e) (run-search! state))}
          [:input {:type "search" :placeholder "Search transcripts…" :value q
                   :on-change #(swap! state assoc :q (.. % -target -value))}]
          [:button.btn-ghost {:type "submit" :disabled searching} (if searching "Searching…" "Search")]]
         (cond
           error [:p.error error]
           (nil? hits) nil
           (empty? hits) [:p.muted "No messages match."]
           :else [:ul.session-list
                  (for [[i h] (map-indexed vector hits)]
                    ^{:key i} [search-hit h])])]))))

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
        [:p.error (or (:error data) "Could not load sessions")]]

       (= "never" (:status data))
       [:div
        [:p.muted (or (:message data)
                      "No session cache yet.")]
        [:p.muted "Click Refresh cache to scan Codex / Cursor / Pi / Claude Code sessions for this repo."]]

       :else
       [:<>
        (for [w (:warnings data)]
          ^{:key w} [:p.error (str "Could not read " w)])
        (when (seq (:sessions data))
          [session-search])
        [:ul.session-list
         (if (empty? (:sessions data))
           [:li.muted "No agent sessions found for this repository."]
           (for [s (:sessions data)]
             ^{:key (:session_id s)}
             [session-list-item s]))]])]))

(defn session-page [st navigate!]
  (let [data (:session st)]
    [:section.page.session-page
     [:div.page-head
      [:div
       [:p.crumb [:a {:href "#/sessions"} "Sessions"] " / detail"]
       [:h1 "Session"]]
      [:div.btn-row
       (when-let [sid (get-in data [:session :session_id])]
         [:a.btn-ghost {:href (str "/api/sessions/" (js/encodeURIComponent sid) "/export.md")}
          "Export Markdown"])
       [:button.btn-ghost {:on-click #(js/history.back)} "Back"]]]
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

(defn json-endpoint [url]
  (let [state (r/atom {:loading true})]
    (r/create-class
     {:component-did-mount
      (fn [_]
        (api/get-json url
                      (fn [d] (reset! state {:data d}))
                      (fn [e] (reset! state {:error e}))))
      :reagent-render
      (fn [url]
        (let [{:keys [data error loading]} @state]
          [:section.agent-endpoint
           [:div.section-head
            [:h3 [:code url]]
            [:button.btn-ghost
             {:on-click
              (fn []
                (reset! state {:loading true})
                (api/get-json url
                              (fn [d] (reset! state {:data d}))
                              (fn [e] (reset! state {:error e}))))}
             "Refresh"]]
           (cond
             loading [:p.muted "Loading…"]
             error [:p.error error]
             data [:pre.agent-json (js/JSON.stringify (clj->js data) nil 2)]
             :else [:p.muted "No data"])]))})))

(defn agents-page [st reload!]
  (let [data (:agents st)]
    [:section.page.agents-page
     [:div.page-head
      [:h1 "Agent endpoints"]
      [:button.btn {:on-click reload!} "Refresh index"]]
     (cond
       (or (nil? data) (:loading data))
       [:p.muted "Loading agent index…"]

       (false? (:ok data))
       [:p.error (or (:error data) "Could not load /agents/")]

       :else
       [:div
        [:section.agent-endpoint
         [:div.section-head
          [:h3 [:code "/agents/"]]
          [:button.btn-ghost {:on-click reload!} "Refresh"]]
         [:pre.agent-json (js/JSON.stringify (clj->js data) nil 2)]]
        [json-endpoint "/agents/tree"]
        [json-endpoint "/agents/conventions"]
        (for [p (:pages data)]
          ^{:key p}
          [json-endpoint (str "/agents/page/" (js/encodeURIComponent p))])
        (for [pl (map :name (:plugins data))]
          ^{:key pl}
          [json-endpoint (str "/agents/plugin/" (js/encodeURIComponent pl))])])]))
