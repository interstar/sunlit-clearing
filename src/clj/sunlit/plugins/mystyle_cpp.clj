(ns sunlit.plugins.mystyle-cpp
  "Long-running runner for the bundled mystyle-cpp (tree-sitter) linter."
  (:require [sunlit.mystyle :as mystyle]))

(def plugin
  (mystyle/linter-plugin
   :mystyle-cpp
   (str "Runs the bundled mystyle-cpp linter on the repository and lists structural "
        "style findings for C++ sources: long functions, long or numerous local lambdas, "
        "deep nesting, parameter counts, mutable global and static state, fail-soft "
        "fallbacks, raw new/delete, C-style casts, oversized files and near-duplicates. "
        "Long-running with cache; requires tree-sitter Python packages.")))
