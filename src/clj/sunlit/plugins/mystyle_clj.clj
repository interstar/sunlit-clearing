(ns sunlit.plugins.mystyle-clj
  "Long-running runner for the bundled mystyle-clj (babashka) linter."
  (:require [sunlit.mystyle :as mystyle]))

(def plugin
  (mystyle/linter-plugin
   :mystyle-clj
   (str "Runs the bundled mystyle-clj linter on the repository and lists structural "
        "style findings for Clojure sources: statefulness, fail-soft input, long "
        "functions, oversized files, near-duplicate s-exprs, and reader-conditionals. "
        "Long-running with cache; requires babashka ('bb') on PATH.")))
