(ns sunlit.source
  "Discover where a project keeps its primary source code, so plugins don't
   each hardcode `src/`. Handles conventional layout (src/, lib/, source/) and
   flat layout (package dirs at the repo root), for any language."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [sunlit.repo :as repo]))

(def conventional-dirs
  "Preferred source directories, in order, when they hold code."
  ["src" "lib" "source"])

(def non-source-dirs
  "Top-level directories that are never the primary source: tests, docs,
   examples, build/generated output, vendored deps, assets."
  #{"test" "tests" "testing" "spec" "specs"
    "docs" "doc" "documentation" "manual"
    "examples" "example" "samples" "demo" "demos"
    "scripts" "tools" "dev"
    "resources" "assets" "static" "public"
    "fixtures" "bench" "benchmarks" "migrations" "db"
    "build" "dist" "out" "target" "generated" "obj"
    "vendor" "third_party" "site-packages" "node_modules"})

(def language-exts
  "Language keyword → file extensions used to locate source."
  {:clojure #{".clj" ".cljs" ".cljc"}
   :clojurescript #{".cljs"}
   :python #{".py"}
   :haxe #{".hx"}
   :javascript #{".js" ".jsx" ".mjs" ".cjs"}
   :typescript #{".ts" ".tsx"}
   :java #{".java"}
   :rust #{".rs"}
   :go #{".go"}
   :c #{".c" ".h"}
   :cpp #{".cpp" ".cc" ".cxx" ".hpp" ".hh" ".h"}
   :csharp #{".cs"}})

(defn- normalize-exts [lang-or-exts]
  (cond
    (keyword? lang-or-exts) (get language-exts lang-or-exts)
    (set? lang-or-exts) lang-or-exts
    (string? lang-or-exts) #{(str lang-or-exts)}
    (coll? lang-or-exts) (into #{} (map str) lang-or-exts)
    :else nil))

(defn- top-dir [rel]
  (let [rel (str rel)
        i (str/index-of rel "/")]
    (if i (subs rel 0 i) ".")))

(defn discover-source-dirs-from
  "Infer source directories from repo-relative file paths (pure)."
  [files]
  (let [tops (set (map top-dir files))
        code-tops (set/difference tops non-source-dirs)
        conventional (vec (filter code-tops conventional-dirs))
        flat (vec (sort (disj code-tops ".")))]
    (cond
      (seq conventional) conventional
      (seq flat) flat
      (contains? code-tops ".") ["."]
      :else [])))

(defn discover-source-dirs
  "Repo-relative directories holding primary source for `lang-or-exts`
   (a language keyword, an extension string, or a collection of extensions).
   Returns a vector of relative paths, possibly empty."
  [lang-or-exts]
  (if-let [exts (normalize-exts lang-or-exts)]
    (discover-source-dirs-from (repo/list-files exts))
    []))

(defn under-source?
  "True when relative path `rel` lies inside one of `dirs` (\".\" matches all)."
  [dirs rel]
  (let [rel (-> (str rel) (str/replace #"\\" "/"))]
    (boolean
     (some (fn [d]
             (let [d (-> (str d) (str/replace #"\\" "/") (str/replace #"/+$" ""))]
               (or (= d ".")
                   (= rel d)
                   (str/starts-with? rel (str d "/")))))
           dirs))))

(defn source-files
  "Repo-relative paths of source files for `lang-or-exts`, restricted to the
   discovered source directories. Mirrors repo/list-files' ignore rules."
  ([lang-or-exts] (source-files lang-or-exts 32))
  ([lang-or-exts max-depth]
   (if-let [exts (normalize-exts lang-or-exts)]
     (let [all (repo/list-files exts max-depth)
           dirs (discover-source-dirs-from all)]
       (filterv #(under-source? dirs %) all))
     [])))
