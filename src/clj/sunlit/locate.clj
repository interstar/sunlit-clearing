(ns sunlit.locate
  "Locations inside files.

  A location is written `path#anchor`, where anchor is a line (`42`, `L42`),
  a line range (`42-60`) or a symbol name (`draw-circle`, `Shape.area`,
  `defmethod area :circle`). Symbols are resolved to lines heuristically, per
  language, with no parsing or eval: definition patterns first, then the
  first whole-word occurrence, then the first literal occurrence."
  (:require [clojure.string :as str]
            [sunlit.repo :as repo])
  (:import [java.util.regex Pattern]))

;; ---------------------------------------------------------------------------
;; Anchors and hrefs

(defn split-anchor
  "\"src/a.clj#foo\" -> [\"src/a.clj\" \"foo\"]; no anchor -> [path nil]."
  [target]
  (let [t (str/trim (str target))
        i (str/index-of t "#")]
    (if (and i (pos? i))
      (let [a (str/trim (subs t (inc i)))]
        [(str/trim (subs t 0 i)) (when-not (str/blank? a) a)])
      [t nil])))

(defn parse-anchor
  "\"42\" / \"L42\" -> {:line 42 :end 42}; \"10-20\" -> {:line 10 :end 20};
  anything else -> {:symbol s}; blank -> nil."
  [anchor]
  (let [a (str/trim (str anchor))]
    (when-not (str/blank? a)
      (if-let [[_ s e] (re-matches #"(?i)L?(\d+)(?:\s*-\s*L?(\d+))?" a)]
        (let [s (parse-long s)
              e (if e (parse-long e) s)]
          {:line (min s e) :end (max s e)})
        {:symbol a}))))

(defn url-encode [s]
  (-> (str s)
      (java.net.URLEncoder/encode "UTF-8")
      (str/replace "+" "%20")))

(defn file-href
  "Client route for a file, optionally at an anchor (line, range or symbol)."
  ([path] (file-href path nil))
  ([path anchor]
   (str "#/file/" (url-encode path)
        (when-let [{:keys [line end symbol]} (parse-anchor anchor)]
          (if line
            (str "?line=" line (when (not= line end) (str "-" end)))
            (str "?sym=" (url-encode symbol)))))))

;; ---------------------------------------------------------------------------
;; Definition patterns

(defn- q [s] (Pattern/quote s))

(def ^:private brace-languages
  #{"javascript" "typescript" "haxe" "cpp" "c" "java"})

(defn- def-patterns
  "Regexes (matched per line) that indicate `sym` is being defined."
  [language sym]
  (let [s (q sym)
        start "(?<![\\w$])"
        end "(?![\\w$])"
        clojure (str "^\\s*\\((?:[\\w.\\-]+/)?def[\\w\\-]*\\s+(?:\\^\\S+\\s+)*" s
                     "(?=[\\s\\)\\]\\}]|$)")
        python [(str "^\\s*(?:async\\s+)?(?:def|class)\\s+" s end)
                (str "^" s "\\s*(?::[^=]*)?=(?!=)")]
        js [(str "(?:^|[\\s;(])(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?"
                 "(?:function\\*?|class|const|let|var|interface|type|enum)\\s+" s end)
            (str "^\\s*(?:(?:public|private|protected|static|async|get|set|override|readonly)\\s+)*"
                 s "\\s*(?:<[^>]*>)?\\s*\\([^)]*\\)\\s*(?::[^{]*)?\\{")
            (str "^\\s*" s "\\s*[:=]\\s*(?:async\\s*)?(?:function|\\()")]
        haxe [(str start "(?:function|class|interface|enum|abstract|typedef|var|final)\\s+" s end)]
        cpp [(str start "(?:class|struct|enum(?:\\s+class)?|namespace|union|interface|record)\\s+" s end)
             (str "^\\s*(?!return\\b|if\\b|while\\b|for\\b|switch\\b|else\\b|case\\b|throw\\b|new\\b|delete\\b)"
                  "[\\w:<>,\\*&\\s~]*?(?<![\\w$])(?:[\\w]+::)*" s "\\s*\\([^;]*$")]]
    (mapv re-pattern
          (case language
            "clojure" [clojure]
            "python" python
            ("javascript" "typescript") js
            "haxe" haxe
            ("cpp" "c" "java") cpp
            (concat [clojure] python (take 1 js) haxe (take 1 cpp))))))

(defn- first-line-matching
  "Index of the first line at or after `from` where any regex finds a match."
  [lines regexes from]
  (first (for [i (range from (count lines))
               :when (some #(re-find % (nth lines i)) regexes)]
           i)))

;; ---------------------------------------------------------------------------
;; Block extent

(defn- indent-of [line]
  (count (re-find #"^[ \t]*" line)))

(defn- python-block-end [lines start]
  (let [base (indent-of (nth lines start))]
    (loop [i (inc start) last-body start]
      (if (>= i (count lines))
        last-body
        (let [line (nth lines i)]
          (cond
            (str/blank? line) (recur (inc i) last-body)
            (> (indent-of line) base) (recur (inc i) i)
            :else last-body))))))

(def ^:private max-block-lines 600)

(defn- balanced-end
  "Scan from line `start` counting open/close delimiters, skipping strings and
  line comments. Returns the line index where depth returns to zero, or nil.
  When `stop-on-semicolon?`, a `;` before any opener means a one-line
  declaration (returns start)."
  [lines start {:keys [opens closes line-comment block-comments? strings
                       char-escape stop-on-semicolon?]}]
  (let [limit (min (count lines) (+ start max-block-lines))]
    (loop [i start depth 0 opened? false in-block? false]
      (when (< i limit)
        (let [line (nth lines i)
              n (count line)
              [depth opened? in-block? done?]
              (loop [j 0 depth depth opened? opened? in-block? in-block? in-str nil]
                (if (>= j n)
                  [depth opened? in-block? false]
                  (let [c (.charAt ^String line j)
                        nxt (when (< (inc j) n) (.charAt ^String line (inc j)))]
                    (cond
                      in-block?
                      (if (and (= c \*) (= nxt \/))
                        (recur (+ j 2) depth opened? false in-str)
                        (recur (inc j) depth opened? true in-str))

                      in-str
                      (cond
                        (= c \\) (recur (+ j 2) depth opened? in-block? in-str)
                        (= c in-str) (recur (inc j) depth opened? in-block? nil)
                        :else (recur (inc j) depth opened? in-block? in-str))

                      (and char-escape (= c char-escape))
                      (recur (+ j 2) depth opened? in-block? nil)

                      (and line-comment (str/starts-with? (subs line j) line-comment))
                      [depth opened? in-block? false]

                      (and block-comments? (= c \/) (= nxt \*))
                      (recur (+ j 2) depth opened? true nil)

                      (contains? strings c)
                      (recur (inc j) depth opened? in-block? c)

                      (and stop-on-semicolon? (not opened?) (= c \;))
                      [0 true in-block? true]

                      (contains? opens c)
                      (recur (inc j) (inc depth) true in-block? nil)

                      (contains? closes c)
                      (let [d (dec depth)]
                        (if (and opened? (<= d 0))
                          [0 true in-block? true]
                          (recur (inc j) d opened? in-block? nil)))

                      :else (recur (inc j) depth opened? in-block? nil)))))]
          (if done?
            i
            (recur (inc i) depth opened? in-block?)))))))

(defn block-end
  "Best-effort last line index of the definition starting at line `start`."
  [lines start language]
  (or (cond
        (= language "python")
        (python-block-end lines start)

        (= language "clojure")
        (balanced-end lines start {:opens #{\( \[ \{}
                                   :closes #{\) \] \}}
                                   :line-comment ";"
                                   :strings #{\"}
                                   :char-escape \\})

        (contains? brace-languages language)
        (balanced-end lines start {:opens #{\{}
                                   :closes #{\}}
                                   :line-comment "//"
                                   :block-comments? true
                                   :strings #{\" \' \`}
                                   :stop-on-semicolon? true}))
      start))

;; ---------------------------------------------------------------------------
;; Symbol resolution

(defn- word-re [sym]
  (re-pattern (str "(?<![\\w$\\-])" (q sym) "(?![\\w$\\-])")))

(defn- split-qualified
  "\"Shape.area\" / \"Shape::area\" -> [\"Shape\" \"area\"] (non-Clojure only)."
  [language sym]
  (when-not (= language "clojure")
    (when-let [[_ container member] (re-matches #"(.+?)(?:\.|::)([^.:]+)" sym)]
      [container member])))

(defn- find-definition
  "Line index of the least-indented definition of `sym` at or after `from`
  (so a bare `area` prefers a top-level def over a method; use
  `Shape.area` for the method)."
  [lines language sym from]
  (let [res (def-patterns language sym)
        hits (for [i (range from (count lines))
                   :when (some #(re-find % (nth lines i)) res)]
               i)]
    (when (seq hits)
      (apply min-key #(indent-of (nth lines %)) (reverse hits)))))

(defn find-symbol
  "Resolve `sym` to {:line :end :exact} (1-based, inclusive) in `content`.
  :exact is false when only a plain occurrence (not a definition) was found.
  Returns nil when the symbol does not occur at all."
  [content language sym]
  (let [sym (str/trim (str sym))
        lines (str/split-lines (or content ""))
        found (fn [idx exact?]
                (when idx
                  {:line (inc idx)
                   :end (inc (if exact? (block-end lines idx language) idx))
                   :exact exact?}))]
    (when-not (str/blank? sym)
      (or (found (find-definition lines language sym 0) true)
          (when-let [[container member] (split-qualified language sym)]
            (when-let [c (find-definition lines language container 0)]
              (let [body (subvec (vec lines) 0 (inc (block-end lines c language)))]
                (found (or (find-definition body language member (inc c))
                           (first-line-matching body [(word-re member)] (inc c)))
                       true))))
          (found (first-line-matching lines [(word-re sym)] 0) false)
          (found (first (keep-indexed #(when (str/includes? %2 sym) %1) lines)) false)))))

(defn locate
  "Resolve a repo-relative `path#anchor` target.
  Returns {:path :exists :line :end :symbol :found :exact}."
  [target]
  (let [[path anchor] (split-anchor target)
        parsed (parse-anchor anchor)
        f (try (repo/safe-resolve path) (catch Exception _ nil))
        exists? (boolean (and f (.isFile f)))]
    (cond
      (not exists?)
      {:path path :exists false :found false :symbol (:symbol parsed)}

      (:symbol parsed)
      (let [hit (try
                  (find-symbol (slurp f) (repo/language-for path) (:symbol parsed))
                  (catch Exception _ nil))]
        (merge {:path path :exists true :symbol (:symbol parsed) :found (boolean hit)}
               hit))

      :else
      (merge {:path path :exists true :found true} parsed))))
