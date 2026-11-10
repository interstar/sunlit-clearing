(ns sunlit.sandbox
  "Read-only view of the watched repository for user-written code (matrix
  :check functions), evaluated with SCI.

  SCI exposes no Java interop and no slurp/spit, so user code can only see
  the repo through the functions below. Paths are repo-relative, and the
  .sunlit sidecar and ignored directories are invisible. File reads are
  cached for the lifetime of one sandbox (one plugin run).

    (files)               all project files
    (files \"src/**/*.hx\") files matching a glob (* within a segment, ** across)
    (exists? path)
    (slurp-file path)     text content, or nil (binary/large/missing)
    (lines path)          vector of lines, or nil
    (grep pattern)        [{:path :line :text}] over all text files;
    (grep pattern glob)   pattern is a regex #\"…\" or a literal string
    (find-symbol path s)  {:line :end :exact} of a definition, or nil
    (link path anchor)    \"path#anchor\" (anchor: symbol, line or a-b range)"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sunlit.locate :as locate]
            [sunlit.repo :as repo]
            [sci.core :as sci])
  (:import [java.util.regex Pattern]))

(defn glob->regex
  "\"src/**/*.hx\" -> regex. `**/` matches zero or more directories."
  [glob]
  (re-pattern
   (apply str
          (for [tok (re-seq #"\*\*/|\*\*|\*|\?|[^*?]+" (str glob))]
            (case tok
              "**/" "(?:.*/)?"
              "**" ".*"
              "*" "[^/]*"
              "?" "[^/]"
              (Pattern/quote tok))))))

(defn- filter-glob
  "The `paths` matching `glob`."
  [paths glob]
  (let [re (glob->regex glob)]
    (filterv #(re-matches re %) paths)))

(defn- read-project-text
  "Text of `path` when it is a listed project file and a text file, else nil."
  [file-set path]
  (when (contains? file-set (str path))
    (try
      (let [f (repo/safe-resolve path)]
        (when (repo/text-file? f) (slurp f)))
      (catch Exception _ nil))))

(defn- line-matcher
  "Predicate on a line: regex `pattern` finds a match, or a literal string occurs."
  [pattern]
  (if (instance? Pattern pattern)
    #(re-find pattern %)
    (let [s (str pattern)] #(str/includes? % s))))

(defn- grep-paths
  "[{:path :line :text}] for each line of `paths` matching `pattern`.
  `lines-fn` returns a file's lines, or nil for unreadable files."
  [lines-fn pattern paths]
  (let [hit? (line-matcher pattern)]
    (vec (for [p paths
               [i line] (map-indexed vector (lines-fn p))
               :when (hit? line)]
           {:path p :line (inc i) :text line}))))

(defn read-api
  "Map of symbol -> fn for the read-only helpers, over a fresh cache."
  []
  (let [all-files (delay (repo/list-project-files))
        file-set (delay (set @all-files))
        files (fn ([] @all-files)
                ([glob] (filter-glob @all-files glob)))
        slurp* (memoize #(read-project-text @file-set %))
        lines* (memoize (fn [path] (some-> (slurp* path) str/split-lines vec)))
        grep* (partial grep-paths lines*)]
    {'files files
     'exists? (fn [path] (contains? @file-set (str path)))
     'slurp-file slurp*
     'lines lines*
     'grep (fn ([pattern] (grep* pattern @all-files))
             ([pattern glob] (grep* pattern (files glob))))
     'find-symbol (fn [path sym]
                    (some-> (slurp* path)
                            (locate/find-symbol (repo/language-for path) sym)))
     'link (fn ([path] (str path))
             ([path anchor] (str path "#" anchor)))}))

(defn context
  "Fresh SCI context with the read-only helpers referred into `user`."
  []
  (sci/init {:namespaces {'user (read-api)}}))

(defn sidecar-source
  "Contents of a file under .sunlit/ (e.g. \"checks/shapes.clj\").
  Refuses paths that escape the sidecar directory."
  [rel]
  (let [root (.getCanonicalFile (repo/sidecar-file))
        f (.getCanonicalFile (io/file root (str/replace (str rel) #"^\.sunlit/" "")))]
    (when-not (.startsWith (.toPath f) (.toPath root))
      (throw (ex-info "Check file must be inside .sunlit/" {:path rel})))
    (when-not (.isFile f)
      (throw (ex-info (str "Check file not found: .sunlit/" (repo/relativize f)) {:path rel})))
    (slurp f)))

(defn eval-fn
  "Evaluate user code to a function: `code` is a form (e.g. an inline
  `(fn [coords] …)`) or a string naming a file under .sunlit/ whose last
  form evaluates to the function."
  [code]
  (let [ctx (context)
        v (if (string? code)
            (sci/eval-string* ctx (sidecar-source code))
            (sci/eval-form ctx code))]
    (when-not (fn? v)
      (throw (ex-info "Check code must evaluate to a function" {:value (pr-str v)})))
    v))
