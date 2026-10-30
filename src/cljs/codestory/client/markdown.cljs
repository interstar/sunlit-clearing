(ns codestory.client.markdown
  "Code highlighting and markdown/WCHNT/message rendering used by page views."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [goog.object :as gobj]
            [codestory.client.wchnt :as wchnt]))

(defn- highlight! [el lang]
  (when (and el (exists? js/hljs))
    (try
      (set! (.-textContent el) (.-textContent el))
      (when lang
        (.add (.-classList el) (str "language-" lang)))
      (js-invoke js/hljs "highlightElement" el)
      (catch :default _))))

(defn code-block [content language]
  (let [el (atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [_] (highlight! @el language))
      :component-did-update
      (fn [_] (highlight! @el language))
      :reagent-render
      (fn [content language]
        [:pre.code-pane
         [:code {:ref #(reset! el %)
                 :class (when language (str "language-" language))}
          (or content "")]])})))

(defn- parse-xml-instruction-blocks
  "Split message text into :text and :tag segments for Codex-style wrappers.
  Tag names may use underscores (`skills_instructions`) or spaces
  (`permissions instructions`)."
  [text]
  (let [s (or text "")
        ;; word tokens optionally separated by single spaces; no attributes
        re (js/RegExp.
            "<([A-Za-z_][\\w.-]*(?:[ ][A-Za-z_][\\w.-]*)*)>([\\s\\S]*?)</\\1>"
            "g")
        out (atom [])
        last (atom 0)]
    (loop []
      (when-let [m (.exec re s)]
        (let [start (.-index m)
              full (aget m 0)
              tag (aget m 1)
              body (aget m 2)]
          (when (< @last start)
            (swap! out conj {:type :text :text (subs s @last start)}))
          (swap! out conj {:type :tag :tag tag :body body})
          (reset! last (+ start (count full)))
          (recur))))
    (when (< @last (count s))
      (swap! out conj {:type :text :text (subs s @last)}))
    (let [segs @out]
      (if (seq segs) segs [{:type :text :text s}]))))

(defn- markdown->html [md]
  (try
    (cond
      (and (exists? js/marked) (fn? (gobj/get js/marked "parse")))
      (js-invoke js/marked "parse" (or md ""))

      (and (exists? js/marked) (fn? js/marked))
      (js/marked (or md ""))

      :else nil)
    (catch :default _
      nil)))

(defn- md-src-block [content]
  (let [el (atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [_] (highlight! @el "markdown"))
      :component-did-update
      (fn [_] (highlight! @el "markdown"))
      :reagent-render
      (fn [content]
        [:pre.msg-md-src
         [:code {:ref #(reset! el %)
                 :class "language-markdown"}
          (or content "")]])})))

(defn- highlight-msg-md! [root]
  (when (and root (exists? js/hljs))
    (try
      (doseq [el (array-seq (.querySelectorAll root "pre code"))]
        (try (js-invoke js/hljs "highlightElement" el) (catch :default _)))
      (catch :default _))))

(defn- markdown-html-block [class content]
  (let [root (atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [_]
        (highlight-msg-md! @root)
        (wchnt/enhance-pre-codes! @root {:unlabeled? false}))
      :component-did-update
      (fn [_]
        (highlight-msg-md! @root)
        (wchnt/enhance-pre-codes! @root {:unlabeled? false}))
      :reagent-render
      (fn [class content]
        (if-let [html (markdown->html content)]
          [:div {:class class
                 :ref #(reset! root %)
                 :dangerouslySetInnerHTML {:__html html}}]
          [:div {:ref #(reset! root nil)}
           [md-src-block content]]))})))

(defn- md-block [content]
  [markdown-html-block "msg-md" content])

(defn markdown-doc
  "Render markdown content as HTML (the file page's Rendered tab)."
  [content]
  [markdown-html-block "md-doc" content])

(defn markdown-file?
  "True when a file map looks like a markdown document."
  [file]
  (let [p (str/lower-case (or (:path file) (:name file) ""))]
    (or (= "markdown" (:language file))
        (boolean (re-find #"\.(md|markdown|mdown|mkd)$" p)))))

(defn wcn-doc
  "Render a .wcn file as markdown, colouring WCHNT fences (incl. unlabeled)."
  [content]
  (let [root (atom nil)
        md (wchnt/prepare-markdown content)]
    (r/create-class
     {:component-did-mount
      (fn [_]
        (highlight-msg-md! @root)
        (wchnt/enhance-pre-codes! @root {:unlabeled? true}))
      :component-did-update
      (fn [_]
        (highlight-msg-md! @root)
        (wchnt/enhance-pre-codes! @root {:unlabeled? true}))
      :reagent-render
      (fn [content]
        (let [md (wchnt/prepare-markdown content)]
          (if-let [html (markdown->html md)]
            [:div.wcn-doc
             {:ref #(reset! root %)
              :dangerouslySetInnerHTML {:__html html}}]
            [:div {:ref #(reset! root nil)}
             [code-block content "wchnt"]])))})))

(defn wcn-file? [file]
  (or (wchnt/wchnt-lang? (:language file))
      (str/ends-with? (str/lower-case (or (:path file) (:name file) "")) ".wcn")))

(defn- tag-default-open?
  "Keep the user's query expanded; collapse bulky instruction/tool wrappers."
  [tag]
  (let [t (str/lower-case (or tag ""))]
    (or (= t "user_query")
        (= t "user-query")
        (str/starts-with? t "user "))))

(defn message-body [text]
  (let [segs (parse-xml-instruction-blocks text)]
    (if (and (= 1 (count segs)) (= :text (:type (first segs))))
      [md-block (:text (first segs))]
      [:div.message-body
       (for [[i seg] (map-indexed vector segs)]
         ^{:key i}
         (case (:type seg)
           :tag
           [:details.msg-tag {:open (tag-default-open? (:tag seg))}
            [:summary
             [:span.msg-tag-name (:tag seg)]]
            [md-block (:body seg)]]

           :text
           (when-not (str/blank? (:text seg))
             [md-block (:text seg)])

           nil))])))
