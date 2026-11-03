(ns codestory.repo
  "Filesystem access for the watched repository."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files LinkOption]
           [java.nio.file.attribute BasicFileAttributes]))

(def ^:private skip-dir-names
  #{".git" ".svn" ".hg" "node_modules" ".venv" "venv" "__pycache__"
    ".pytest_cache" ".cpcache" ".lsp" ".clj-kondo" "target"
    ".shadow-cljs" ".idea" ".eyeball"})

(def ^:private text-extensions
  #{".clj" ".cljs" ".cljc" ".edn" ".py" ".hx" ".hxml" ".cpp" ".cc" ".cxx"
    ".c" ".h" ".hpp" ".hh" ".js" ".jsx" ".ts" ".tsx" ".java" ".md" ".txt"
    ".json" ".yaml" ".yml" ".toml" ".xml" ".html" ".css" ".scss" ".sh"
    ".bash" ".zsh" ".rs" ".go" ".rb" ".php" ".sql" ".gradle" ".properties"
    ".cfg" ".ini" ".wcn"})
(def ^:private text-basenames
  #{"makefile" "dockerfile" "gemfile" "rakefile" "license" "readme"
    "changelog" "project.clj" "deps.edn" "bb.edn" "build.boot"
    ;; Dotfiles: extension-less config / ignore / env files (no last-dot
    ;; extension, so they must be matched by whole name, not text-extensions).
    ".gitignore" ".gitattributes" ".gitmodules" ".hgignore"
    ".dockerignore" ".npmignore" ".slugignore"
    ".editorconfig" ".clang-format" ".prettierrc" ".eslintrc" ".babelrc"
    ".npmrc" ".yarnrc" ".nvmrc" ".node-version" ".python-version"
    ".ruby-version" ".tool-versions" ".env" ".env.example" ".makefile"})

(def ^:dynamic *repo-root* nil)

(def sidecar-dirname
  "Per-repo Sunlit Clearing data directory (annotations, pages, cache, venv)."
  ".sunlit")

(defn set-repo-root! [path]
  (let [root (.getCanonicalFile (io/file path))]
    (alter-var-root #'*repo-root* (constantly root))))

(defn repo-root []
  (or *repo-root*
      (throw (ex-info "Repo root not set" {}))))

(defn sidecar-file
  "Path under <repo>/.sunlit/…"
  [& parts]
  (apply io/file (repo-root) sidecar-dirname parts))

(defn mushroom-full-name
  "Read :full_name from a MUSHROOM.edn at the repo root, when present.
  Returns nil if the file is missing, unreadable, or lacks a string :full_name."
  []
  (let [f (io/file (repo-root) "MUSHROOM.edn")]
    (when (.isFile f)
      (try
        (let [data (edn/read-string (slurp f))]
          (when (and (map? data) (string? (:full_name data)))
            (:full_name data)))
        (catch Exception _
          nil)))))

(defn repo-name
  "Display name for the repository: MUSHROOM.edn :full_name if present,
  otherwise the directory name."
  []
  (or (mushroom-full-name)
      (.getName (repo-root))))

(defn safe-resolve
  "Resolve a relative path under the repo root. Rejects escapes."
  [rel]
  (let [root (.getCanonicalFile (repo-root))
        cleaned (-> (or rel "")
                    (str/replace #"\\" "/")
                    (str/replace #"^/+" ""))
        ]
    (if (str/blank? cleaned)
      root
      (let [root-path (.toPath root)
            resolved (.normalize (.resolve root-path cleaned))]
        (when-not (.startsWith resolved root-path)
          (throw (ex-info "Path escapes repository" {:path rel})))
        (let [canon (.getCanonicalFile (.toFile resolved))]
          (when-not (.startsWith (.toPath canon) root-path)
            (throw (ex-info "Path escapes repository" {:path rel})))
          canon)))))

(defn relativize [file]
  (let [root (.getCanonicalFile (repo-root))
        f (.getCanonicalFile (io/file file))]
    (-> (.toPath root)
        (.relativize (.toPath f))
        str
        (str/replace #"\\" "/"))))

(def root-doc-names
  "Preferred top-level docs to surface on the tree page (matched case-insensitively)."
  ["README.md" "README.rst" "AGENTS.md" "ABOUT.md" "MUSHROOM.edn"])

(defn root-doc-entries
  "Return [{:name :path}] for preferred top-level docs that exist."
  []
  (let [root (repo-root)
        by-lower (into {}
                       (for [f (some-> root .listFiles seq)
                             :when (.isFile f)
                             :let [n (.getName f)]]
                         [(str/lower-case n) n]))]
    (vec
     (keep (fn [canonical]
             (when-let [actual (get by-lower (str/lower-case canonical))]
               {:name actual :path actual}))
           root-doc-names))))

(defn- skip-dir? [name]
  (or (contains? skip-dir-names name)
      (str/starts-with? name ".")))

(defn- extension [name]
  (let [i (str/last-index-of name ".")]
    (when (and i (pos? i))
      (str/lower-case (subs name i)))))

(defn text-file?
  "Heuristic: known extension/basename, small enough, no NUL in sample."
  ([file] (text-file? file 512000))
  ([file max-bytes]
   (let [name (str/lower-case (.getName file))
         ext (extension name)
         basen (str/lower-case (or (second (re-find #"^([^.]+)" name)) name))]
     (cond
       (not (.isFile file)) false
       (> (.length file) max-bytes) false
       (or (contains? text-extensions ext)
           (contains? text-basenames name)
           (contains? text-basenames basen))
       (try
         (with-open [in (io/input-stream file)]
           (let [buf (byte-array (min 8192 (.length file)))
                 n (.read in buf)]
             (not (some zero? (take (max n 0) buf)))))
         (catch Exception _ false))
       :else false))))

(defn language-for [path]
  (let [name (str/lower-case (or path ""))
        ext (extension name)]
    (case ext
      (".clj" ".cljc") "clojure"
      ".cljs" "clojure"
      ".edn" "clojure"
      ".py" "python"
      (".hx" ".hxml") "haxe"
      (".cpp" ".cc" ".cxx" ".hpp" ".hh") "cpp"
      (".c" ".h") "c"
      (".js" ".jsx") "javascript"
      (".ts" ".tsx") "typescript"
      ".java" "java"
      ".md" "markdown"
      (".json") "json"
      (".yml" ".yaml") "yaml"
      (".html" ".htm") "xml"
      ".css" "css"
      (".sh" ".bash" ".zsh") "bash"
      ".wcn" "wchnt"
      "plaintext")))
(defn- node-for [file]
  (let [name (.getName file)
        rel (relativize file)
        dir? (.isDirectory file)]
    (cond-> {:name name
             :path rel
             :type (if dir? "dir" "file")}
      (not dir?)
      (assoc :size (.length file)
             :text? (text-file? file)
             :language (language-for name)))))

(defn- walk-tree [dir depth]
  (when (and dir (.isDirectory dir) (pos? depth))
    (->> (.listFiles dir)
         (remove nil?)
         (remove #(and (.isDirectory %) (skip-dir? (.getName %))))
         (sort-by (fn [f] [(if (.isDirectory f) 0 1)
                          (str/lower-case (.getName f))]))
         (mapv (fn [f]
                 (let [base (node-for f)]
                   (if (.isDirectory f)
                     (assoc base :children (walk-tree f (dec depth)))
                     base)))))))

(defn build-tree
  "Build a nested tree of maps for UI rendering."
  ([] (build-tree (repo-root) 8))
  ([root max-depth]
   {:name (.getName (io/file root))
    :path ""
    :type "dir"
    :children (or (walk-tree (io/file root) max-depth) [])}))

(defn read-file [rel]
  (let [f (safe-resolve rel)]
    (when-not (.isFile f)
      (throw (ex-info "Not a file" {:path rel})))
    (let [size (.length f)
          text? (text-file? f)]
      {:path (relativize f)
       :name (.getName f)
       :size size
       :text? text?
       :language (language-for (.getName f))
       :content (when text?
                  (slurp f))})))

(defn dir-info
  "Info for a directory relative path: {:path :name :parent :children}.
  :children is a nested tree (same node shape as build-tree), so the directory
  page reuses the collapsible tree widget."
  [rel]
  (let [f (safe-resolve (or rel ""))]
    (when-not (.isDirectory f)
      (throw (ex-info "Not a directory" {:path rel})))
    (let [rel (relativize f)
          normalized (-> rel (str/replace "\\" "/") (str/replace #"/+$" ""))
          slash (str/last-index-of normalized "/")
          parent (when (and slash (pos? slash))
                   (subs normalized 0 slash))]
      {:path rel
       :type "dir"
       :name (if (str/blank? rel) (repo-name) (.getName f))
       :parent parent
       :children (or (walk-tree f 8) [])})))

(defn- walk-files [ext-set dir depth]
  (when (and dir (.isDirectory dir) (pos? depth))
    (mapcat (fn [f]
              (cond
                (and (.isDirectory f) (not (skip-dir? (.getName f))))
                (walk-files ext-set f (dec depth))

                (.isFile f)
                (let [ext (or (extension (.getName f)) "")]
                  (when (contains? ext-set ext)
                    [(relativize f)]))))
            (some-> (.listFiles dir) seq))))

(defn list-files
  "Walk the repo for files whose extension is in exts (e.g. .clj / .py).
  Skips ignored directories. Returns relative path strings sorted."
  ([exts] (list-files exts 32))
  ([exts max-depth]
   (vec (sort (walk-files (into #{} (map str/lower-case) exts)
                          (repo-root) max-depth)))))

(defn- walk-project-files [dir depth]
  (when (and dir (.isDirectory dir) (pos? depth))
    (mapcat (fn [f]
              (let [name (.getName f)]
                (cond
                  (and (.isDirectory f)
                       (not (skip-dir? name))
                       (not= name sidecar-dirname))
                  (walk-project-files f (dec depth))

                  (.isFile f)
                  [(relativize f)])))
            (some-> (.listFiles dir) seq))))

(defn list-project-files
  "Walk the repo for relative paths of all files, excluding the .sunlit sidecar
  and other ignored directories. Returns sorted relative path strings."
  ([] (list-project-files 32))
  ([max-depth]
   (vec (sort (walk-project-files (repo-root) max-depth)))))
