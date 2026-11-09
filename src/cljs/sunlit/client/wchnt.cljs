(ns sunlit.client.wchnt
  "WCHNT (.wcn) syntax colouring — regex tokenizer adapted from
   wchnt_lang/website/build.py (mirrors live editor token kinds)."
  (:require [clojure.string :as str]))

(def ^:private token-re
  (js/RegExp.
   (str
    "(?<comment>//[^\\n]*|/\\*[\\s\\S]*?\\*/)"
    "|(?<string>\"(?:\\\\.|[^\"\\\\])*\")"
    "|(?<number>-?\\d+\\.\\d+|-?\\d+)"
    "|(?<rel_mailbox>(?<![\\w])>[A-Z][A-Za-z0-9_]*)"
    "|(?<rel_context>(?<![\\w>\\[]):[A-Z][A-Za-z0-9_]*)"
    "|(?<rel_delegate>\\+[A-Z][A-Za-z0-9_]*)"
    "|(?<rel_reactive>\\$[A-Z][A-Za-z0-9_]*)"
    "|(?<rel_external>@[A-Z][A-Za-z0-9_]*)"
    "|(?<sigil>\\$|@|(?<![\\w])>(?=[A-Z])|(?<![\\w>\\[]):(?=[A-Z]))"
    "|(?<keyword>if|else|or|and|not|true|false)\\b"
    "|(?<class>(?<=\\[:)[A-Z][A-Za-z0-9_]*|[A-Z][A-Za-z0-9_]*(?=\\s*=|::))"
    "|(?<type>[A-Z][A-Za-z0-9_]*)"
    "|(?<method>(?<=::)[a-z_][A-Za-z0-9_]*)"
    "|(?<name>(?<=/)[a-z_][A-Za-z0-9_]*)"
    "|(?<path>[a-z_][A-Za-z0-9_]*(?:\\.[a-z_][A-Za-z0-9_]*)+)"
    "|(?<ident>[a-z_][A-Za-z0-9_]*)"
    "|(?<punct>[^\\sA-Za-z0-9_])")
   "g"))

(def ^:private token-class
  {"comment" "tok-comment"
   "string" "tok-string"
   "number" "tok-number"
   "sigil" "tok-sigil"
   "rel_mailbox" "tok-rel-mailbox"
   "rel_context" "tok-rel-context"
   "rel_delegate" "tok-rel-delegate"
   "rel_reactive" "tok-rel-reactive"
   "rel_external" "tok-rel-external"
   "keyword" "tok-keyword"
   "class" "tok-class"
   "type" "tok-type"
   "method" "tok-method"
   "name" "tok-name"
   "path" "tok-path"})

(def ^:private host-js-re
  #"\bfunction\s+\w+\s*\(|\bvar\s+\w+|%(?:canvas|openfl|terminal|cli)")

(defn- escape-html [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- match-kind [m]
  (let [groups (.-groups m)]
    (when groups
      (some (fn [k]
              (when (aget groups k) k))
            (array-seq (js/Object.keys groups))))))

(defn highlight-html
  "Return WCHNT source as HTML with span-based syntax colouring."
  [code]
  (let [code (or code "")
        out (atom [])
        re token-re]
    (set! (.-lastIndex re) 0)
    (loop [pos 0]
      (if-let [m (.exec re code)]
        (let [start (.-index m)
              text (aget m 0)
              kind (match-kind m)
              css (get token-class kind)]
          (when (> start pos)
            (swap! out conj (escape-html (subs code pos start))))
          (swap! out conj
                 (if css
                   (str "<span class=\"" css "\">" (escape-html text) "</span>")
                   (escape-html text)))
          (recur (.-lastIndex re)))
        (do
          (when (< pos (count code))
            (swap! out conj (escape-html (subs code pos))))
          (str/join "" @out))))))

(defn wchnt-lang?
  [lang]
  (contains? #{"wchnt" "wcn"} (str/lower-case (or lang ""))))

(defn host-js?
  [code]
  (boolean (re-find host-js-re (or code ""))))

(defn should-highlight?
  "Colour explicit wchnt/wcn fences, or unlabeled fences that are not host JS
  when unlabeled? is true (used for .wcn document view)."
  [lang code {:keys [unlabeled?] :or {unlabeled? false}}]
  (let [lang (str/lower-case (or lang ""))]
    (cond
      (contains? #{"js" "javascript" "html" "css" "typescript" "ts"} lang) false
      (wchnt-lang? lang) true
      (and unlabeled? (str/blank? lang) (not (host-js? code))) true
      :else false)))

(defn- fence-lang [el]
  (let [cls (or (.-className el) "")
        m (re-find #"language-(\S+)" cls)]
    (or (second m) "")))

(defn enhance-pre-codes!
  "Replace matching <pre><code> bodies with WCHNT-coloured HTML."
  [root {:keys [unlabeled?] :or {unlabeled? false}}]
  (when root
    (doseq [el (array-seq (.querySelectorAll root "pre code"))]
      (let [lang (fence-lang el)
            code (.-textContent el)]
        (when (should-highlight? lang code {:unlabeled? unlabeled?})
          (set! (.-innerHTML el) (highlight-html code))
          (.add (.-classList el) "language-wchnt")
          (.add (.-classList el) "wchnt-coloured"))))))

(defn markdown-doc?
  "True when content looks like a WCHNT markdown program (headings or fences)."
  [content]
  (let [c (or content "")]
    (or (str/includes? c "```")
        (boolean (re-find #"(?m)^\s*#" c)))))

(defn prepare-markdown
  "Wrap raw schema-only .wcn content in a wchnt fence so markdown view works."
  [content]
  (let [c (or content "")]
    (if (markdown-doc? c)
      c
      (str "```wchnt\n" c "\n```"))))
