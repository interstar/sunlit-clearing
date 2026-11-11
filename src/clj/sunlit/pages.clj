(ns sunlit.pages
  "Free-titled wiki pages stored under .sunlit/pages/."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sunlit.repo :as repo]))

(def index-page-name "_index")

(defn- pages-root []
  (repo/sidecar-file "pages"))

(defn sanitize-name
  "Allow free titles; reject path separators and empty names."
  [name]
  (let [n (-> (str name)
              str/trim
              (str/replace #"\\" "-")
              (str/replace #"/" "-"))]
    (when (or (str/blank? n)
              (str/includes? n "..")
              (> (count n) 180))
      (throw (ex-info "Invalid page name" {:name name})))
    n))

(defn- page-file [name]
  (io/file (pages-root) (str (sanitize-name name) ".md")))

(defn exists?
  "Whether the repo has its own copy of the page."
  [name]
  (.isFile (page-file name)))

(def builtin-docs
  "Pages every repo has, served from docs/ (on the classpath) until the repo
  saves its own copy under .sunlit/pages/."
  {"Guide" "guide.md"
   "Writing plugins" "writing_plugins.md"})

(def ^:private doc-links
  {"guide.md" "#/page/Guide"
   "writing_plugins.md" "#/page/Writing%20plugins"})

(defn- app-links
  "docs/ Markdown with links between the guides pointed at their pages; other
  relative .md links (outside the watched repo) become plain text."
  [markdown]
  (str/replace markdown #"\[([^\]]+)\]\(([^)#\s]+\.md)(#[^)\s]*)?\)"
               (fn [[whole text target _]]
                 (if-let [page (doc-links target)]
                   (str "[" text "](" page ")")
                   (if (re-find #"^[a-z]+:" target) whole text)))))

(defn- builtin-markdown [n]
  (some-> (builtin-docs n) io/resource slurp app-links))

(defn read-page [name]
  (let [n (sanitize-name name)
        f (page-file n)]
    (if (.isFile f)
      {:name n
       :exists true
       :markdown (slurp f)}
      (if-let [md (builtin-markdown n)]
        {:name n
         :exists true
         :builtin true
         :markdown md}
        {:name n
         :exists false
         :markdown ""}))))

(defn write-page! [name markdown]
  (let [n (sanitize-name name)
        f (page-file n)]
    (io/make-parents f)
    (spit f (or markdown ""))
    (read-page n)))

(defn create-page!
  "Create only if missing. Optional initial markdown."
  [name & [markdown]]
  (let [n (sanitize-name name)]
    (if (exists? n)
      (assoc (read-page n) :created false)
      (assoc (write-page! n (or markdown (str "# " n "\n\n"))) :created true))))

(defn list-pages []
  (let [dir (pages-root)]
    (if-not (.isDirectory dir)
      (vec (sort (keys builtin-docs)))
      (->> (.listFiles dir)
           (filter #(and (.isFile %) (str/ends-with? (.getName %) ".md")))
           (map #(-> (.getName %) (str/replace #"\.md$" "")))
           (remove #(= % index-page-name))
           (concat (keys builtin-docs))
           distinct
           sort
           vec))))

(def ^:private index-seed
  (str "# Welcome\n\n"
       "Notes for this repository tree.\n\n"
       "Custom links: [[README|README.md]] · [[Architecture]] · [[Testing]] · [[Python tools]] · [[Guide]]\n\n"
       "```plugin overview\n"
       "{}\n"
       "```\n"))

(def ^:private architecture-seed
  (str "# Architecture\n\n"
       "Editorial overview for this repository. Language boxes below are "
       "**deterministic** extracts (parsed from source — no LLM).\n\n"
       "The `structure` plugin shows one box per language that is actually "
       "present. For larger repos, place `structure-clojure`, "
       "`structure-python`, `structure-javascript`, or `structure-haxe` on separate pages "
       "instead.\n\n"
       "```plugin structure\n"
       "{}\n"
       "```\n\n"
       "## Clojure schemas & grammars\n\n"
       "```plugin malli\n"
       "{}\n"
       "```\n\n"
       "```plugin instaparse\n"
       "{}\n"
       "```\n"))

(def ^:private testing-seed
  (str "# Testing\n\n"
       "Unit tests and coverage for this repo.\n\n"
       "For Python analysis tools / venv setup see [[Python tools]].\n\n"
       "## Clojure\n\n"
       "```plugin unittests\n"
       "{:lang :clojure :id \"clojure\"}\n"
       "```\n\n"
       "```plugin cloverage\n"
       "{}\n"
       "```\n\n"
       "## Python\n\n"
       "```plugin unittests\n"
       "{:lang :python :id \"python\"}\n"
       "```\n\n"
       "```plugin pycov\n"
       "{}\n"
       "```\n\n"
       "## Haxe\n\n"
       "Defaults look for `test.hxml` / `tests.hxml` / `test.interp.hxml`. "
       "Coverage uses `testCoverage.hxml` (or `coverage.hxml`) and expects "
       "`instrument` to write `lcov.info`.\n\n"
       "```plugin unittests\n"
       "{:lang :haxe :id \"haxe\"}\n"
       "```\n\n"
       "```plugin hxcov\n"
       "{}\n"
       "```\n"))

(defn- lint-fence [plugin]
  (str "```plugin " plugin "\n{:exclude []}\n```\n\n"))

(def ^:private lint-seed
  (str "# Lint\n\n"
       "Structural style findings from the mystyle linters. Each panel opens "
       "and closes; Rerun refreshes its cached result. Delete the fences for "
       "languages this repo doesn't use.\n\n"
       "`:exclude` lists directories to skip, on top of each linter's built-in "
       "list (VCS, build output, dependencies…). A bare name (`\".cache\"`) is "
       "skipped at any depth; a path (`\"panel-editor/.cache\"`) is relative to "
       "the repo root. Edit, save, then Rerun.\n\n"
       "## Clojure\n\n" (lint-fence "mystyle-clj")
       "## Python\n\n" (lint-fence "mystyle-py")
       "## JavaScript / TypeScript\n\n" (lint-fence "mystyle-js")
       "## C++\n\n" (lint-fence "mystyle-cpp")
       "## Haxe\n\n" (lint-fence "mystyle-hx")))

(def ^:private python-tools-seed
  (str "# Python tools\n\n"
       "Sunlit Clearing can keep a private virtualenv under "
       "`.sunlit/venv` for analysis tools (pytest, coverage, and the tree-sitter "
       "grammars the mystyle linters use). "
       "This is separate from any project or shared venv you use for app code.\n\n"
       "```plugin python-tools\n"
       "{}\n"
       "```\n"))

(defn- seed-pages! []
  (when-not (exists? index-page-name)
    (write-page! index-page-name index-seed))
  (when-not (exists? "Architecture")
    (write-page! "Architecture" architecture-seed))
  (when-not (exists? "Testing")
    (write-page! "Testing" testing-seed))
  (when-not (exists? "Lint")
    (write-page! "Lint" lint-seed))
  (when-not (exists? "Python tools")
    (write-page! "Python tools" python-tools-seed)))

(defn- migrate-index-links! []
  (when (exists? index-page-name)
    (let [md (:markdown (read-page index-page-name))]
      (cond
        (or (str/includes? md "src/clj/sunlit/core.clj")
            (str/includes? md "[[project.clj]]"))
        (write-page!
         index-page-name
         (-> md
             (str/replace #"Custom links:[^\n]*"
                          "Custom links: [[README|README.md]] · [[Architecture]] · [[Testing]] · [[Python tools]]")
             (str/replace #"(?ms)## Checks\n\n```plugin[\s\S]*$" "")))

        (and (str/includes? md "Custom links:")
             (not (str/includes? md "[[Architecture]]")))
        (write-page!
         index-page-name
         (str/replace md
                      #"Custom links:[^\n]*"
                      "Custom links: [[README|README.md]] · [[Architecture]] · [[Testing]] · [[Python tools]]"))))))

(defn- migrate-cloverage-fence! []
  (doseq [name [index-page-name "Testing"]]
    (when (exists? name)
      (let [md (:markdown (read-page name))]
        (when (and (str/includes? md "\"cloverage\"")
                   (str/includes? md "plugin run")
                   (not (str/includes? md "plugin cloverage")))
          (write-page!
           name
           (str/replace md
                        #"```plugin run\n\{\:id \"cloverage\" :cmd \[\"lein\" \"cloverage\"\]\}\n```"
                        "```plugin cloverage\n{}\n```")))))))

(defn- migrate-testing-haxe! []
  (when (exists? "Testing")
    (let [md (:markdown (read-page "Testing"))]
      (when (and (str/includes? md "## Python")
                 (not (str/includes? md "## Haxe")))
        (write-page!
         "Testing"
         (str md
              "\n## Haxe\n\n"
              "Defaults look for `test.hxml` / `tests.hxml` / `test.interp.hxml`. "
              "Coverage uses `testCoverage.hxml` (or `coverage.hxml`) and expects "
              "`instrument` to write `lcov.info`.\n\n"
              "```plugin unittests\n"
              "{:lang :haxe :id \"haxe\"}\n"
              "```\n\n"
              "```plugin hxcov\n"
              "{}\n"
              "```\n"))))))

(defn- migrate-architecture! []
  (when (exists? "Architecture")
    (let [md (:markdown (read-page "Architecture"))]
      (when (and (re-find #"plugin structure\s*\n" md)
                 (not (str/includes? md "structure-python")))
        (write-page! "Architecture" architecture-seed))))
  (when (exists? "Architecture")
    (let [md (:markdown (read-page "Architecture"))
          missing-malli? (not (str/includes? md "plugin malli"))
          missing-insta? (not (str/includes? md "plugin instaparse"))]
      (when (or missing-malli? missing-insta?)
        (write-page!
         "Architecture"
         (str md
              (when (and missing-malli? missing-insta?)
                "\n## Clojure schemas & grammars\n\n")
              (when missing-malli?
                "```plugin malli\n{}\n```\n\n")
              (when missing-insta?
                "```plugin instaparse\n{}\n```\n")))))))

(defn ensure-store! []
  (.mkdirs (pages-root))
  (seed-pages!)
  (migrate-index-links!)
  (migrate-cloverage-fence!)
  (migrate-testing-haxe!)
  (migrate-architecture!))
