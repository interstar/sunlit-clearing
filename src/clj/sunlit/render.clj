(ns sunlit.render
  "Markdown + fenced plugin blocks + wiki-style [[path]] / [[Page]] links."
  (:require [clojure.string :as str]
            [sunlit.locate :as locate]
            [sunlit.plugins :as plugins]
            [sunlit.plugins.gallery :as gallery]
            [sunlit.plugins.image :as image]
            [markdown.core :as md]))

(def plugin-fence-re
  "Group 1 = plugin name, group 2 = fence body (EDN params)."
  #"(?ms)^```plugin[ \t]+([A-Za-z0-9_./-]+)\s*\n(.*?)```")

(def ^:private example-fence-re
  "A fence of four or more backticks or tildes: an example block, which may
  show ```plugin fences without running them."
  #"(?ms)^(`{4,}|~{4,})[^\n]*\n.*?^\1[ \t]*$")

(defn- example-spans [markdown]
  (let [m (re-matcher example-fence-re markdown)]
    (loop [spans []]
      (if (.find m) (recur (conj spans [(.start m) (.end m)])) spans))))

(defn live-fences
  "{:name :body :start :end :body-start :body-end} for each ```plugin fence
  in `markdown` that is not inside an example block, in order."
  [markdown]
  (let [md (or markdown "")
        spans (example-spans md)
        inside? (fn [i] (some (fn [[s e]] (<= s i (dec e))) spans))
        m (re-matcher plugin-fence-re md)]
    (loop [out []]
      (if (.find m)
        (recur (if (inside? (.start m))
                 out
                 (conj out {:name (.group m 1) :body (.group m 2)
                            :start (.start m) :end (.end m)
                            :body-start (.start m 2) :body-end (.end m 2)})))
        out))))

(defn- example-block-html
  "An example fence as an escaped code block."
  [block]
  (let [lines (str/split-lines block)
        lang (str/trim (str/replace (first lines) #"^[`~]+" ""))
        body (str/join "\n" (butlast (rest lines)))
        esc (-> body (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;"))]
    (str "<pre><code" (when-not (str/blank? lang) (str " class=\"language-" lang "\"")) ">" esc "</code></pre>")))

(def ^:private wiki-link-re
  #"\[\[([^\]]+)\]\]")

(def ^:private file-like-re
  #"(/)|(\.[A-Za-z0-9]+$)")

(def ^:private placeholder-prefix "SUNLITXBLK")

(defn parse-plugin-params [body]
  (let [trimmed (str/trim (or body ""))]
    (if (str/blank? trimmed)
      {}
      (try
        ;; no #=() evaluation: fences may now hold code (matrix :check),
        ;; which is evaluated only in SCI, never by the reader
        (let [v (binding [*read-eval* false] (read-string trimmed))]
          (cond
            (map? v) v
            (nil? v) {}
            :else {:value v}))
        (catch Exception _
          {:raw trimmed})))))

(defn- link-href
  "[[path]] / [[path#anchor]] -> file route (anchor = line, range or symbol);
  anything else -> wiki page route."
  [target]
  (let [[path anchor] (locate/split-anchor target)]
    (if (re-find file-like-re path)
      (locate/file-href path anchor)
      (str "#/page/" (locate/url-encode (str/trim target))))))

(defn- wiki-link-html [target]
  (let [t (str/trim target)
        [label href] (if (str/includes? t "|")
                       (let [[a b] (str/split t #"\|" 2)]
                         [(str/trim a) (str/trim b)])
                       [t t])]
    (str "<a class=\"wiki-link\" href=\"" (link-href href) "\">" label "</a>")))

(defn- plugin-slot-html [pname body path fence-index]
  (let [params (parse-plugin-params body)
        result (plugins/run-plugin pname {:path path
                                          :params params
                                          :fence-index fence-index})
        html (or (:html result)
                 (when (:markdown result)
                   ;; Plugin-supplied markdown is rendered in isolation so
                   ;; surrounding page markdown cannot alter its underscores.
                   (md/md-to-html-string (:markdown result)))
                 "")
        lr? (or (:long_running result)
                (:long-running? (plugins/get-plugin pname)))
        attrs (cond-> (str "class=\"plugin-slot"
                           (when lr? " long-running")
                           "\" data-plugin=\"" pname "\"")
                (:cache_id result)
                (str " data-cache-id=\"" (:cache_id result) "\""))]
    (str "<div " attrs ">" html "</div>")))

(defn- next-token [blocks]
  (str placeholder-prefix (count @blocks) "X"))

(defn- store-block! [blocks html]
  (let [tok (next-token blocks)]
    (swap! blocks conj {:token tok :html html})
    tok))

(defn- protect-examples
  "`text` with each example fence swapped for a stored code block."
  [text blocks]
  (str/replace text example-fence-re
               (fn [[block _]] (str "\n\n" (store-block! blocks (example-block-html block)) "\n\n"))))

(defn- extract-protected-blocks
  "Pull plugin fences and wiki links out of markdown before md processing.
  Underscores in plugin HTML / link labels must not become <em> tags."
  [markdown path]
  (let [blocks (atom [])
        live (live-fences markdown)
        without-plugins
        (loop [out (StringBuilder.) pos 0 [f & more] (map-indexed vector live)]
          (if-let [[i {:keys [name body start end]}] f]
            (recur (doto out
                     (.append (protect-examples (subs markdown pos start) blocks))
                     (.append (str "\n\n" (store-block! blocks (plugin-slot-html name body path i)) "\n\n")))
                   end more)
            (str (.append out (protect-examples (subs markdown pos) blocks)))))
        without-links
        (str/replace
         without-plugins
         wiki-link-re
         (fn [[_ target]]
           (store-block! blocks (wiki-link-html target))))]
    {:markdown without-links
     :blocks @blocks}))

(defn- restore-blocks [html blocks]
  (reduce (fn [acc {:keys [token html]}]
            (str/replace acc token html))
          html
          blocks))

(defn render-markdown
  "Render markdown (annotations or wiki pages) in context of path/name.
  Plugin HTML and wiki-link anchors are spliced in after markdown conversion
  so names like md_to_reveal_outliner are not italicized."
  [markdown path]
  (let [md (or markdown "")
        {:keys [markdown blocks]} (extract-protected-blocks md path)
        html (-> (md/md-to-html-string markdown :heading-anchors true)
                 (restore-blocks blocks))]
    {:html html}))

(defn render-annotation
  [markdown path]
  (render-markdown markdown path))

(defn plugin-fences
  "Return [{:name \"…\" :params {…}}] for each ```plugin fence in markdown."
  [markdown]
  (mapv (fn [{:keys [name body]}] {:name name :params (parse-plugin-params body)})
        (live-fences markdown)))

(defn auto-plugin-fences
  "Return [{:name \"…\" :params {…}}] for plugins that auto-attach to a file path."
  [path]
  (cond
    (and path (str/ends-with? path "project.clj"))
    [{:name "leiningen" :params {}}]

    (image/image-path? path)
    [{:name "image" :params {}}]

    :else []))

(defn auto-plugins-for
  [path]
  (mapv (fn [{:keys [name params]}]
          (plugins/run-plugin name {:path path :params params}))
        (auto-plugin-fences path)))

(defn auto-plugin-fences-for-dir
  "Return [{:name \"…\" :params {…}}] for plugins that auto-attach to a directory."
  [path]
  (cond
    (seq (gallery/gallery-images (or path "")))
    [{:name "gallery" :params {}}]

    :else []))

(defn auto-plugins-for-dir
  [path]
  (mapv (fn [{:keys [name params]}]
          (plugins/run-plugin name {:path path :params params}))
        (auto-plugin-fences-for-dir path)))
