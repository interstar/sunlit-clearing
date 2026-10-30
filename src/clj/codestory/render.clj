(ns codestory.render
  "Markdown + fenced plugin blocks + wiki-style [[path]] / [[Page]] links."
  (:require [clojure.string :as str]
            [codestory.plugins :as plugins]
            [markdown.core :as md]))

(def ^:private plugin-fence-re
  #"(?ms)^```plugin[ \t]+([A-Za-z0-9_./-]+)\s*\n(.*?)```")

(def ^:private wiki-link-re
  #"\[\[([^\]]+)\]\]")

(def ^:private file-like-re
  #"(/)|(\.[A-Za-z0-9]+$)")

(def ^:private placeholder-prefix "CODESTORYXBLK")

(defn- parse-plugin-params [body]
  (let [trimmed (str/trim (or body ""))]
    (if (str/blank? trimmed)
      {}
      (try
        (let [v (read-string trimmed)]
          (cond
            (map? v) v
            (nil? v) {}
            :else {:value v}))
        (catch Exception _
          {:raw trimmed})))))

(defn- link-href [target]
  (let [t (str/trim target)]
    (if (re-find file-like-re t)
      (str "#/file/" (-> t
                         (java.net.URLEncoder/encode "UTF-8")
                         (str/replace "+" "%20")))
      (str "#/page/" (-> t
                         (java.net.URLEncoder/encode "UTF-8")
                         (str/replace "+" "%20"))))))

(defn- wiki-link-html [target]
  (let [t (str/trim target)
        [label href] (if (str/includes? t "|")
                       (let [[a b] (str/split t #"\|" 2)]
                         [(str/trim a) (str/trim b)])
                       [t t])]
    (str "<a class=\"wiki-link\" href=\"" (link-href href) "\">" label "</a>")))

(defn- plugin-slot-html [pname body path]
  (let [params (parse-plugin-params body)
        result (plugins/run-plugin pname {:path path :params params})
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

(defn- extract-protected-blocks
  "Pull plugin fences and wiki links out of markdown before md processing.
  Underscores in plugin HTML / link labels must not become <em> tags."
  [markdown path]
  (let [blocks (atom [])
        without-plugins
        (str/replace
         markdown
         plugin-fence-re
         (fn [[_ pname body]]
           (str "\n\n" (store-block! blocks (plugin-slot-html pname body path)) "\n\n")))
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

(defn auto-plugins-for
  [path]
  (cond
    (and path (str/ends-with? path "project.clj"))
    [(plugins/run-plugin "leiningen" {:path path :params {}})]
    :else []))
