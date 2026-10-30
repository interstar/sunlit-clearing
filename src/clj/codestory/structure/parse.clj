(ns codestory.structure.parse
  "Deterministic source parsers (no eval): Clojure, Python, JS/TS, Haxe.
  Pure functions on source text — no repo or hiccup dependency."
  (:require [clojure.string :as str]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as rt]))

(def ^:private type-form-re
  #"(?m)^\((defprotocol|definterface|defrecord|deftype|defmulti)\s+([A-Za-z0-9*_!?+\-]+)")

(def ^:private py-class-re
  #"(?m)^class\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:\(([^)]*)\))?\s*:")

(def ^:private py-def-re
  #"(?m)^(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(")

(def ^:private js-class-re
  #"(?m)^(?:export\s+)?(?:default\s+)?class\s+([A-Za-z_$][\w$]*)")

(def ^:private js-fn-re
  #"(?m)^(?:export\s+)?(?:async\s+)?function\s+([A-Za-z_$][\w$]*)")

(def ^:private hx-package-re
  #"(?m)^package\s+([A-Za-z_][\w.]*)\s*;")

(def ^:private hx-type-re
  #"(?m)^(?:(?:public|private|extern|final|macro|dynamic)\s+)*(class|interface|enum|abstract|typedef)\s+([A-Za-z_][\w]*)")

(def ^:private hx-class-extends-re
  #"(?m)^(?:(?:public|private|extern|final|macro|dynamic)\s+)*class\s+([A-Za-z_][\w]*)(?:\s+extends\s+([A-Za-z_][\w.]*))?(?:\s+implements\s+([^{]+))?")

(def ^:private hx-import-re
  #"(?m)^import\s+([A-Za-z_][\w.]*)")

(defn- read-first-form [content]
  (try
    (binding [reader/*default-data-reader-fn* (fn [_tag _val] nil)
              reader/*read-eval* false]
      (reader/read {:eof ::eof :read-cond :allow :features #{:clj :cljs :default}}
                   (rt/string-push-back-reader content)))
    (catch Exception _
      nil)))

(defn- libspec-name [spec]
  (cond
    (symbol? spec) (str spec)
    (vector? spec) (when (symbol? (first spec)) (str (first spec)))
    :else nil))

(defn- collect-requires [ns-form]
  (when (and (seq? ns-form) (= 'ns (first ns-form)))
    (let [clauses (nnext ns-form)]
      (->> clauses
           (filter #(and (sequential? %) (#{:require :use} (first %))))
           (mapcat rest)
           (keep libspec-name)
           (remove str/blank?)
           distinct
           vec))))

(defn- extract-types [content]
  (->> (re-seq type-form-re content)
       (map (fn [[_ kind name]]
              {:kind (keyword kind) :name name}))
       vec))

(defn parse-clojure
  "Parse one Clojure(Script) source file. Returns a unit map or nil."
  [path content]
  (let [form (read-first-form content)
        ns-name (when (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
                  (str (second form)))
        requires (or (collect-requires form) [])
        types (extract-types content)]
    (when (or ns-name (seq types))
      {:lang "clojure"
       :path path
       :name (or ns-name path)
       :requires requires
       :types types})))

(defn- py-module-name [path]
  (-> path
      (str/replace #"\\" "/")
      (str/replace #"\.py$" "")
      (str/replace #"/" ".")))

(defn- split-bases [s]
  (->> (str/split (or s "") #",")
       (map str/trim)
       (remove str/blank?)
       (map #(-> % (str/split #"\.") last))
       vec))

(defn parse-python
  "Heuristic (line-oriented) parse of a Python module."
  [path content]
  (let [classes (->> (re-seq py-class-re content)
                     (map (fn [[_ name bases]]
                            {:name name
                             :bases (split-bases bases)}))
                     vec)
        functions (->> (re-seq py-def-re content)
                       (map second)
                       (remove #{"__init__"})
                       distinct
                       vec)]
    {:lang "python"
     :path path
     :name (py-module-name path)
     :classes classes
     :functions functions}))

(defn parse-javascript
  "Heuristic parse of JS/TS modules for top-level classes/functions."
  [path content]
  (let [classes (->> (re-seq js-class-re content)
                     (map (fn [[_ name]] {:name name :bases []}))
                     vec)
        functions (->> (re-seq js-fn-re content)
                       (map second)
                       distinct
                       vec)]
    {:lang "javascript"
     :path path
     :name path
     :classes classes
     :functions functions}))

(defn hx-simple-name [dotted]
  (when-not (str/blank? dotted)
    (-> dotted str/trim (str/split #"\.") last)))

(defn- hx-split-implements [s]
  (->> (str/split (or s "") #",")
       (map str/trim)
       (map hx-simple-name)
       (remove str/blank?)
       vec))

(defn parse-haxe
  "Heuristic parse of a Haxe module (.hx)."
  [path content]
  (let [pkg (when-let [[_ p] (re-find hx-package-re content)] p)
        imports (->> (re-seq hx-import-re content)
                     (map second)
                     distinct
                     vec)
        extends-map
        (into {}
              (for [[_ name ext impl] (re-seq hx-class-extends-re content)]
                [name (vec (concat (when-let [e (hx-simple-name ext)] [e])
                                   (hx-split-implements impl)))]))
        types (->> (re-seq hx-type-re content)
                   (map (fn [[_ kind name]]
                          {:kind kind
                           :name name
                           :bases (get extends-map name [])}))
                   vec)
        unit-name (if pkg
                    (str pkg (when (seq types)
                               (str "." (:name (first types)))))
                    (or (:name (first types)) path))]
    (when (or pkg (seq types))
      {:lang "haxe"
       :path path
       :name unit-name
       :package pkg
       :imports imports
       :types types})))

(def ^:private cpp-include-re
  #"(?m)^\s*#\s*include\s*[<\"]([^>\"]+)[>\"]")

(def ^:private cpp-local-include-re
  #"(?m)^\s*#\s*include\s*\"([^\"]+)\"")

(def ^:private cpp-class-re
  #"(?m)^\s*(?:template\s*<[^>]*>\s*)?(class|struct)\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s+final)?(?:\s*:\s*([^{;]+))?")

(defn cpp-unit-name
  "Basename of a C/C++ path without its source/header extension."
  [path]
  (-> (str path)
      (str/replace #"\\\\" "/")
      (str/split #"/")
      last
      (str/replace #"\.[A-Za-z0-9_+]+$" "")))

(defn- cpp-split-bases [s]
  (->> (str/split (or s "") #",")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- cpp-base-name [spec]
  (when-let [s (not-empty (str/trim (or spec "")))]
    (-> (str/split s #"\s+")
        peek
        (str/replace #"<.*>" "")
        (str/split #"::")
        last)))

(defn parse-cpp
  "Heuristic parse of a C/C++ header or source file."
  [path content]
  (let [includes (->> (re-seq cpp-include-re content)
                      (map second)
                      distinct
                      vec)
        local-includes (->> (re-seq cpp-local-include-re content)
                            (map second)
                            distinct
                            vec)
        types (->> (re-seq cpp-class-re content)
                   (map (fn [[_ kind name bases]]
                          {:kind (keyword kind)
                           :name name
                           :bases (vec (keep cpp-base-name (cpp-split-bases bases)))}))
                   vec)]
    (when (seq types)
      {:lang "cpp"
       :path path
       :name (cpp-unit-name path)
       :includes includes
       :local-includes local-includes
       :types types})))
