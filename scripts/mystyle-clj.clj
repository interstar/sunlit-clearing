#!/usr/bin/env bb

(ns mystyle.lint
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.tools.reader :as tr]
   [clojure.tools.reader.reader-types :as rt]))

(defn- usage []
  (println "Usage: bb mystyle_lint.clj path/to/src"))

(defn- normalize-text [s]
  (str/replace s "\r\n" "\n"))

(defn- neutralize-tagged-literals
  "Replace tagged-literal prefixes (e.g. #js, #inst, #my/app) with spaces of the
   same width so line/column positions remain stable for lint diagnostics."
  [text]
  (str/replace text #"#([A-Za-z][\w\.\-\/]*)"
               (fn [[full]]
                 (apply str (repeat (count full) " ")))))

(def ^:private reader-conditional-pattern #"#\?@?")

(defn- reader-conditional-indices [text]
  (let [m (re-matcher reader-conditional-pattern text)]
    (loop [idxs []]
      (if (.find m)
        (recur (conj idxs (.start m)))
        idxs))))

(defn- macro-import-reader-conditional?
  "Treat macro import dispatch as unavoidable:
   - :require-macros
   - :refer-macros
   - :include-macros"
  [text idx]
  (let [end (min (count text) (+ idx 280))
        snippet (subs text idx end)]
    (boolean (re-find #":(require-macros|refer-macros|include-macros)\b" snippet))))

(defn- contains-actionable-reader-conditional?
  "Detect #?/#?@ reader conditionals excluding macro import conditionals."
  [text]
  (let [idxs (reader-conditional-indices text)]
    (boolean
     (some (fn [idx]
             (not (macro-import-reader-conditional? text idx)))
           idxs))))

(defn- line-starts [text]
  (let [lines (str/split-lines text)]
    (loop [idx 0
           [line & more] lines
           starts [0]]
      (if (nil? line)
        starts
        (let [next-idx (+ idx (count line) 1)]
          (recur next-idx more (conj starts next-idx)))))))

(defn- index-from-line-col [starts line col]
  (when (and line col (<= 1 line) (<= line (count starts)))
    (+ (nth starts (dec line)) (dec col))))

(def ^:private reader-opts
  {:eof ::eof
   :read-cond :allow
   :features #{:clj :cljs}
   ;; Keep linting resilient across dialect-specific tagged literals.
   ;; Unknown tags are treated as plain values for structural checks.
   :default (fn [_tag value] value)})

(defn- read-form
  [reader]
  (binding [*data-readers* (merge *data-readers* {'js identity})
            *default-data-reader-fn* (fn [_tag value] value)]
    (tr/read reader-opts reader)))

(defn- read-form-end-line [text starts form]
  (let [{:keys [line column]} (meta form)
        idx (index-from-line-col starts line column)]
    (when (and idx (<= idx (count text)))
      (let [sub (subs text idx)
            r (rt/source-logging-push-back-reader sub)
            _ (read-form r)
            rel-end (rt/get-line-number r)]
        (+ line (dec rel-end))))))

(defn- read-top-level-forms [text]
  (let [r (rt/source-logging-push-back-reader text)]
    (loop [acc []]
      (let [form (read-form r)]
        (if (= form ::eof)
          acc
          (let [m (meta form)
                start-line (:line m)
                start-col (:column m)
                end-line (rt/get-line-number r)]
            (recur (conj acc {:form form
                              :start-line start-line
                              :start-col start-col
                              :end-line end-line}))))))))

(defn- safe-read-top-level-forms [text]
  (try
    {:forms (read-top-level-forms text)}
    (catch Exception e
      {:forms []
       :parse-error e})))

(defn- atom-or-ref? [x]
  (when (and (seq? x) (symbol? (first x)))
    (let [n (name (first x))]
      (or (= n "atom") (= n "ref")))))

(defn- defn-form? [form]
  (and (seq? form)
       (symbol? (first form))
       (#{"defn" "defn-"} (name (first form)))))

(defn- def-form? [form]
  (and (seq? form)
       (symbol? (first form))
       (#{"def" "defonce"} (name (first form)))))

(defn- defn-name [form]
  (let [n (second form)]
    (if (symbol? n) (str n) "<anonymous>")))

(defn- defn-bodies [form]
  (let [[_ _ & more] form
        more (if (string? (first more)) (rest more) more)
        more (if (map? (first more)) (rest more) more)
        fdecl more]
    (cond
      (vector? (first fdecl)) [(rest fdecl)]
      (seq? (first fdecl)) (map rest fdecl)
      :else [])))

(defn- subforms
  "`form` and every form nested inside it, depth-first (pre-order)."
  [form]
  (tree-seq coll? seq form))

(defn- find-atom-refs [form]
  (for [x (subforms form)
        :when (atom-or-ref? x)
        :let [{:keys [line column]} (meta x)]]
    {:line line :col column :form x}))

(defn- let-form? [x]
  (and (seq? x)
       (symbol? (first x))
       (#{"let" "let*" "letfn"} (name (first x)))))

(defn- binding-forms [let-form]
  (let [kind (name (first let-form))
        bindings (second let-form)]
    (cond
      (and (#{"let" "let*"} kind) (vector? bindings))
      (for [[_ v] (partition 2 bindings)
            :when (and (seq? v) (symbol? (first v))
                       (#{"fn" "fn*"} (name (first v))))]
        v)

      (and (= "letfn" kind) (vector? bindings))
      (for [b bindings
            :when (and (seq? b) (symbol? (first b)) (vector? (second b)))]
        b)

      :else [])))

(defn- let-binding-forms [let-form]
  (let [kind (name (first let-form))
        bindings (second let-form)]
    (cond
      (and (#{"let" "let*"} kind) (vector? bindings))
      (for [[_ v] (partition 2 bindings)]
        v)

      (and (= "letfn" kind) (vector? bindings))
      (for [b bindings
            :when (seq? b)]
        b)

      :else [])))

(defn- find-inner-fns [text starts form]
  (for [x (subforms form)
        :when (let-form? x)
        b (binding-forms x)
        :let [{:keys [line column]} (meta b)
              end-line (read-form-end-line text starts b)]
        :when (and line end-line (> (inc (- end-line line)) 2))]
    {:line line :col column :end-line end-line}))

(defn- symbol-named? [x names]
  (and (symbol? x) (contains? names (name x))))

(defn- quoted-form? [x]
  (and (seq? x) (= 2 (count x)) (symbol-named? (first x) #{"quote"})))

(defn- empty-default?
  "nil, a blank string, or an empty collection (including '()): a value that
   stands in for missing input without saying anything is wrong."
  [x]
  (let [v (if (quoted-form? x) (second x) x)]
    (or (nil? v)
        (and (string? v) (str/blank? v))
        (and (coll? v) (empty? v)))))

(defn- empty-fallback-form?
  "(or ... <empty>), (get m k <empty>), (get-in m ks <empty>), (:k m <empty>)."
  [x]
  (and (seq? x)
       (empty-default? (last x))
       (let [head (first x)
             n (count x)]
         (or (and (symbol-named? head #{"or"}) (>= n 3))
             (and (symbol-named? head #{"get" "get-in"}) (= n 4))
             (and (keyword? head) (= n 3))))))

(def ^:private max-snippet-len 90)

(defn- one-line-snippet [s]
  (let [s (str/trim (str/replace s #"\s+" " "))]
    (if (> (count s) max-snippet-len)
      (str (subs s 0 (- max-snippet-len 3)) "...")
      s)))

(defn- form-source
  "Source text of `form`, located by its reader position, or nil."
  [text starts form]
  (let [{:keys [line column]} (meta form)
        idx (index-from-line-col starts line column)]
    (when (and idx (< idx (count text)))
      (let [r (rt/source-logging-push-back-reader (subs text idx))
            _ (read-form r)
            rel-line (rt/get-line-number r)
            rel-col (rt/get-column-number r)
            end (if (= 1 rel-line)
                  (+ idx (dec rel-col))
                  (index-from-line-col starts (+ line (dec rel-line)) rel-col))]
        (when (and end (<= idx end (count text)))
          (subs text idx end))))))

(defn- default-label [x]
  (if (quoted-form? x)
    (str "'" (pr-str (second x)))
    (pr-str x)))

(defn- fail-soft-warning [path text starts x]
  (let [{:keys [line column]} (meta x)
        snippet (one-line-snippet (or (form-source text starts x) (pr-str x)))]
    {:file path
     :line (or line 1)
     :col (or column 1)
     :level "WARN"
     :message (format "fail-soft-input: `%s` silently falls back to %s; validate the input and fail clearly instead"
                      snippet (default-label (last x)))}))

(defn- find-fail-soft-inputs [path text starts form]
  (for [x (subforms form)
        :when (empty-fallback-form? x)]
    (fail-soft-warning path text starts x)))

(declare collect-near-dup-forms)

(defn- warning
  "A finding at `line`:`col` of `path` (missing positions default to 1)."
  [path line col level message]
  {:file path :line (or line 1) :col (or col 1) :level level :message message})

(defn- parse-error-warnings [path parse-error]
  (when parse-error
    [(warning path 1 1 "WARN"
              (format "Parser fallback used (%s); structural checks were skipped for this file."
                      (.getMessage parse-error)))]))

(defn- file-size-warnings [path text]
  (let [lines (max 1 (count (str/split-lines text)))]
    (when (> lines 600)
      [(warning path 1 1 "WARN" (format "File has %d lines; consider splitting it." lines))])))

(defn- global-atom-warnings [path forms]
  (let [uses (mapcat #(when-not (defn-form? (:form %)) (find-atom-refs (:form %))) forms)
        message (if (= 1 (count uses))
                  "Global atom/ref usage; a single global atom can be OK, but prefer stateless solutions."
                  "Multiple global atoms/refs found; prefer a single global atom or stateless design.")]
    (for [{:keys [line col]} uses]
      (warning path line col "WARN" message))))

(defn- defn-length-warnings [path {:keys [form start-line end-line]}]
  (let [line-count (when (and start-line end-line) (inc (- end-line start-line)))]
    (when (and line-count (> line-count 30))
      [(warning path start-line 1 (if (> line-count 50) "HARD" "WARN")
                (format "Function %s is %d lines (ends at line %d); consider refactoring."
                        (defn-name form) line-count (or end-line start-line)))])))

(defn- defn-body-warnings
  "Local atoms, then over-long inner fns, for each body of a defn `form`."
  [path text starts form]
  (let [fname (defn-name form)]
    (mapcat
     (fn [body]
       (concat
        (for [{:keys [line col]} (find-atom-refs body)]
          (warning path line col "WARN"
                   (format "Local atom/ref usage in %s; prefer stateless solutions unless necessary." fname)))
        (for [{:keys [line col end-line]} (find-inner-fns text starts body)]
          (warning path line col "HARD"
                   (format "Inner function in let inside %s spans >2 lines (ends at line %d); hoist it out."
                           fname end-line)))))
     (defn-bodies form))))

(defn- file-warnings [path]
  (let [text (normalize-text (slurp path))
        parse-text (neutralize-tagged-literals text)
        starts (line-starts parse-text)
        {:keys [forms parse-error]} (safe-read-top-level-forms parse-text)
        defns (filter #(defn-form? (:form %)) forms)]
    {:warnings (concat
                (parse-error-warnings path parse-error)
                (file-size-warnings path text)
                (global-atom-warnings path forms)
                (mapcat #(find-fail-soft-inputs path parse-text starts (:form %)) forms)
                (mapcat (fn [d]
                          (concat (defn-length-warnings path d)
                                  (defn-body-warnings path parse-text starts (:form d))))
                        defns))
     :forms (collect-near-dup-forms path parse-text starts forms)
     :has-reader-conditional (contains-actionable-reader-conditional? text)}))

(defn- clj-source-file? [^java.io.File f]
  (let [name (.getName f)]
    (and (.isFile f)
         (re-find #"\.(clj|cljs|cljc)$" name))))

(def ^:private skip-dir-names
  #{".git" ".hg" ".svn" "node_modules" ".venv" "venv" "__pycache__"
    ".pytest_cache" ".mypy_cache" ".ruff_cache" "target" ".shadow-cljs"
    ".cpcache" ".clj-kondo" ".lsp" ".idea" ".sunlit" "resources"
    "out" "compiled" "vendor" "dist" "build" "coverage"})

(defn- skip-dir? [^java.io.File f]
  (and (.isDirectory f) (contains? skip-dir-names (.getName f))))

(defn- source-files-under
  "Clojure source files below `dir`, not descending into skipped directories."
  [^java.io.File dir]
  (mapcat (fn [^java.io.File f]
            (cond
              (.isDirectory f) (if (skip-dir? f) [] (source-files-under f))
              (clj-source-file? f) [f]
              :else []))
          (.listFiles dir)))

(defn- collect-files [root]
  (let [dir (io/file root)]
    (when (.isDirectory dir)
      (source-files-under dir))))

(defn- format-warning [{:keys [file line col level message]}]
  (format "%s:%d:%d [%s] %s" file line col level message))


(def ^:private near-dup-min-tokens 50)
(def ^:private near-dup-shingle-size 7)
(def ^:private near-dup-similarity 0.8)

(defn- normalize-name [x]
  (when x (name x)))

(defn- sorted-map-entries [m]
  (sort-by (fn [[k _]] (pr-str k)) m))

(defn- sorted-set-elems [s]
  (sort-by pr-str s))

(declare tokens-of)

(defn- bracketed
  "`open`, the tokens of each element in `xs` (only the first in head
  position when `head-first?`), then `close`."
  [open close xs head-first?]
  (concat [open]
          (mapcat tokens-of xs (cons head-first? (repeat false)))
          [close]))

(defn- tokens-of
  "Shape tokens for `x`: literals collapse to their type, symbols keep their
  name only in head position, maps and sets are put in a stable order."
  [x head?]
  (cond
    (list? x) (bracketed "(" ")" x true)
    (vector? x) (bracketed "[" "]" x false)
    (map? x) (bracketed "{" "}" (mapcat identity (sorted-map-entries x)) false)
    (set? x) (bracketed "#{" "}" (sorted-set-elems x) false)
    (symbol? x) [(if head? (str "SYM:" (normalize-name x)) "SYM")]
    (keyword? x) [(str "KW:" (normalize-name x))]
    (string? x) ["STR"]
    (number? x) ["NUM"]
    (char? x) ["CHAR"]
    (true? x) ["TRUE"]
    (false? x) ["FALSE"]
    (nil? x) ["NIL"]
    :else ["LIT"]))

(defn- form->tokens [form]
  (vec (tokens-of form false)))

(defn- shingle-set [tokens k]
  (let [cnt (count tokens)]
    (if (< cnt k)
      #{}
      (loop [i 0
             acc #{}]
        (if (> (+ i k) cnt)
          acc
          (recur (inc i) (conj acc (hash (subvec tokens i (+ i k))))))))))

(defn- jaccard [a b]
  (let [u (set/union a b)]
    (if (empty? u)
      0.0
      (/ (double (count (set/intersection a b)))
         (double (count u))))))

(defn- size-compatible? [a b]
  (let [minv (min a b)
        maxv (max a b)]
    (>= (/ (double minv) (double maxv)) 0.75)))

(defn- near-dup-candidate
  "A near-dup comparison entry for form `x`, or nil when it has no position
  or is too small to be worth comparing."
  [path text starts x]
  (when-let [{:keys [line column]} (meta x)]
    (let [tokens (form->tokens x)]
      (when (and line (>= (count tokens) near-dup-min-tokens))
        {:file path
         :line line
         :col (or column 1)
         :end-line (read-form-end-line text starts x)
         :tokens tokens
         :token-count (count tokens)}))))

(defn- near-dup-subjects
  "Forms worth comparing in one top-level `form`: the form itself when it is
  a def/defn, then every collection bound in a let inside it."
  [form]
  (concat (when (or (defn-form? form) (def-form? form)) [form])
          (for [x (subforms form)
                :when (let-form? x)
                b (let-binding-forms x)
                :when (coll? b)]
            b)))

(defn- collect-near-dup-forms [path text starts forms]
  (keep #(near-dup-candidate path text starts %)
        (mapcat (comp near-dup-subjects :form) forms)))

(defn- near-dup-warnings [forms]
  (let [formsv (mapv #(assoc % :shingles (shingle-set (:tokens %) near-dup-shingle-size))
                     forms)]
    (for [[i a] (map-indexed vector formsv)
          b (subvec formsv (inc i))
          :when (size-compatible? (:token-count a) (:token-count b))
          :let [sim (jaccard (:shingles a) (:shingles b))]
          :when (>= sim near-dup-similarity)]
      (warning (:file a) (:line a) (:col a) "WARN"
               (format "Near-duplicate s-exp (%.2f similarity) also at %s:%d:%d."
                       sim (:file b) (:line b) (:col b))))))

(defn -main [& args]
  (let [root (first args)]
    (if-not root
      (usage)
      (let [files (collect-files root)
            per-file (map #(file-warnings (.getPath ^java.io.File %)) files)
            warnings (mapcat :warnings per-file)
            forms (mapcat :forms per-file)
            file-count (count files)
            reader-cond-count (count (filter :has-reader-conditional per-file))
            reader-cond-ratio (if (pos? file-count)
                                (/ (double reader-cond-count) (double file-count))
                                0.0)
            reader-cond-warning (when (> reader-cond-ratio 0.05)
                                  [{:file root
                                    :line 1
                                    :col 1
                                    :level "WARN"
                                    :message (format "Reader conditionals are used in %d/%d files (%.1f%%); consider refactoring to concentrate platform-specific code in fewer namespaces."
                                                     reader-cond-count
                                                     file-count
                                                     (* 100.0 reader-cond-ratio))}])
            near-dups (near-dup-warnings forms)
            all-warnings (concat warnings near-dups reader-cond-warning)
            sorted (sort-by (juxt :file :line :col) all-warnings)]
        (doseq [w sorted]
          (println (format-warning w)))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
