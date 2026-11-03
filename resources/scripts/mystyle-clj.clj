#!/usr/bin/env bb

(ns mystyle.lint
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.tools.reader :as tr]
   [clojure.tools.reader.reader-types :as rt]
   [clojure.walk :as walk]))

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

(defn- find-atom-refs [form]
  (let [hits (atom [])]
    (walk/prewalk
     (fn [x]
       (when (atom-or-ref? x)
         (let [{:keys [line column]} (meta x)]
           (swap! hits conj {:line line :col column :form x})))
       x)
     form)
    @hits))

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
  (let [hits (atom [])]
    (walk/prewalk
     (fn [x]
       (when (let-form? x)
         (doseq [b (binding-forms x)]
           (let [{:keys [line column]} (meta b)
                 end-line (read-form-end-line text starts b)]
             (when (and line end-line (> (inc (- end-line line)) 2))
               (swap! hits conj {:line line :col column :end-line end-line})))))
       x)
     form)
    @hits))

(defn- symbol-named? [x names]
  (and (symbol? x) (contains? names (name x))))

(defn- keyword-lookup-form? [x]
  (and (seq? x)
       (keyword? (first x))
       (<= 2 (count x) 3)))

(defn- input-access-form? [x]
  (and (seq? x)
       (or (and (symbol? (first x))
                (#{"get" "get-in"} (name (first x))))
           (keyword-lookup-form? x))))

(defn- fail-soft-warning [path line col message]
  {:file path
   :line (or line 1)
   :col (or col 1)
   :level "WARN"
   :message message})

(defn- find-fail-soft-inputs [path form]
  (let [hits (atom [])]
    (walk/prewalk
     (fn [x]
       (when (seq? x)
         (let [{:keys [line column]} (meta x)
               head (first x)]
           (cond
             (and (symbol-named? head #{"get" "get-in"})
                  (>= (count x) 4))
             (swap! hits conj
                    (fail-soft-warning
                     path line column
                     "fail-soft-input: lookup supplies a default value; prefer explicit validation and a clear failure for missing/bad input"))

             (and (symbol-named? head #{"or"})
                  (>= (count x) 3)
                  (input-access-form? (second x)))
             (swap! hits conj
                    (fail-soft-warning
                     path line column
                     "fail-soft-input: fallback around lookup may mask missing/bad input; prefer explicit validation and a clear failure")))))
       x)
     form)
    @hits))

(declare collect-near-dup-forms)

(defn- file-warnings [path]
  (let [raw (slurp path)
        text (normalize-text raw)
        parse-text (neutralize-tagged-literals text)
        has-reader-conditional (contains-actionable-reader-conditional? text)
        starts (line-starts parse-text)
        lines (max 1 (count (str/split-lines text)))
        {:keys [forms parse-error]} (safe-read-top-level-forms parse-text)
        warnings (atom [])]
    (when parse-error
      (swap! warnings conj {:file path
                            :line 1
                            :col 1
                            :level "WARN"
                            :message (format "Parser fallback used (%s); structural checks were skipped for this file."
                                             (.getMessage parse-error))}))
    (when (> lines 600)
      (swap! warnings conj {:file path
                            :line 1
                            :col 1
                            :level "WARN"
                            :message (format "File has %d lines; consider splitting it." lines)}))
    (let [global-uses (mapcat (fn [{:keys [form]}]
                                (when-not (defn-form? form)
                                  (find-atom-refs form)))
                              forms)]
      (when (seq global-uses)
        (let [global-count (count global-uses)]
          (doseq [{:keys [line col]} global-uses]
            (swap! warnings conj {:file path
                                  :line (or line 1)
                                  :col (or col 1)
                                  :level "WARN"
                                  :message (if (= global-count 1)
                                             "Global atom/ref usage; a single global atom can be OK, but prefer stateless solutions."
                                             "Multiple global atoms/refs found; prefer a single global atom or stateless design.")})))))
    (doseq [{:keys [form]} forms
            warning (find-fail-soft-inputs path form)]
      (swap! warnings conj warning))
    (doseq [{:keys [form start-line end-line]} forms
            :when (defn-form? form)]
      (let [fname (defn-name form)
            line-count (when (and start-line end-line) (inc (- end-line start-line)))]
        (when (and line-count (> line-count 30))
          (swap! warnings conj {:file path
                                :line (or start-line 1)
                                :col 1
                                :level (if (> line-count 50) "HARD" "WARN")
                                :message (format "Function %s is %d lines (ends at line %d); consider refactoring."
                                                 fname line-count (or end-line start-line))}))
        (doseq [body (defn-bodies form)]
          (doseq [{:keys [line col]} (find-atom-refs body)]
            (swap! warnings conj {:file path
                                  :line (or line 1)
                                  :col (or col 1)
                                  :level "WARN"
                                  :message (format "Local atom/ref usage in %s; prefer stateless solutions unless necessary." fname)}))
          (doseq [{:keys [line col end-line]} (find-inner-fns parse-text starts body)]
            (swap! warnings conj {:file path
                                  :line (or line 1)
                                  :col (or col 1)
                                  :level "HARD"
                                  :message (format "Inner function in let inside %s spans >2 lines (ends at line %d); hoist it out."
                                                   fname end-line)})))))
    {:warnings @warnings
     :forms (collect-near-dup-forms path parse-text (line-starts parse-text) forms)
     :has-reader-conditional has-reader-conditional}))

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

(defn- collect-files [root]
  (letfn [(walk [^java.io.File dir]
            (when (and dir (.isDirectory dir))
              (mapcat (fn [^java.io.File f]
                        (cond
                          (.isDirectory f)
                          (if (skip-dir? f) [] (walk f))

                          (clj-source-file? f) [f]
                          :else []))
                      (.listFiles dir))))]
    (walk (io/file root))))

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

(defn- form->tokens [form]
  (let [tokens (transient [])]
    (letfn [(emit [x head?]
              (cond
                (list? x)
                (do
                  (conj! tokens "(")
                  (when (seq x)
                    (emit (first x) true)
                    (doseq [e (rest x)] (emit e false)))
                  (conj! tokens ")"))

                (vector? x)
                (do
                  (conj! tokens "[")
                  (doseq [e x] (emit e false))
                  (conj! tokens "]"))

                (map? x)
                (do
                  (conj! tokens "{")
                  (doseq [[k v] (sorted-map-entries x)]
                    (emit k false)
                    (emit v false))
                  (conj! tokens "}"))

                (set? x)
                (do
                  (conj! tokens "#{")
                  (doseq [e (sorted-set-elems x)] (emit e false))
                  (conj! tokens "}"))

                (symbol? x)
                (conj! tokens (if head?
                                (str "SYM:" (normalize-name x))
                                "SYM"))

                (keyword? x)
                (conj! tokens (str "KW:" (normalize-name x)))

                (string? x) (conj! tokens "STR")
                (number? x) (conj! tokens "NUM")
                (char? x) (conj! tokens "CHAR")
                (true? x) (conj! tokens "TRUE")
                (false? x) (conj! tokens "FALSE")
                (nil? x) (conj! tokens "NIL")
                :else (conj! tokens "LIT")))]
      (emit form false)
      (persistent! tokens))))

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

(defn- collect-near-dup-forms [path text starts forms]
  (let [hits (atom [])]
    (letfn [(add-form! [x]
              (when-let [{:keys [line column]} (meta x)]
                (let [end-line (read-form-end-line text starts x)
                      tokens (form->tokens x)]
                  (when (and line (>= (count tokens) near-dup-min-tokens))
                    (swap! hits conj {:file path
                                      :line line
                                      :col (or column 1)
                                      :end-line end-line
                                      :tokens tokens
                                      :token-count (count tokens)})))))]
      (doseq [{:keys [form]} forms]
        (when (or (defn-form? form) (def-form? form))
          (add-form! form))
        (walk/prewalk
         (fn [x]
           (when (let-form? x)
             (doseq [b (let-binding-forms x)]
               (when (coll? b)
                 (add-form! b))))
           x)
         form)))
    @hits))

(defn- near-dup-warnings [forms]
  (let [formsv (vec forms)
        cnt (count formsv)
        warnings (atom [])]
    (loop [i 0]
      (when (< i cnt)
        (let [a (nth formsv i)
              ta (:token-count a)
              sa (shingle-set (:tokens a) near-dup-shingle-size)]
          (loop [j (inc i)]
            (when (< j cnt)
              (let [b (nth formsv j)
                    tb (:token-count b)]
                (when (size-compatible? ta tb)
                  (let [sb (shingle-set (:tokens b) near-dup-shingle-size)
                        sim (jaccard sa sb)]
                    (when (>= sim near-dup-similarity)
                      (swap! warnings conj
                             {:file (:file a)
                              :line (:line a)
                              :col (:col a)
                              :level "WARN"
                              :message (format "Near-duplicate s-exp (%.2f similarity) also at %s:%d:%d."
                                               sim (:file b) (:line b) (:col b))}))))
                (recur (inc j)))))
          (recur (inc i)))))
    @warnings))

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
