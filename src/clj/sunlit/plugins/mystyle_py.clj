(ns sunlit.plugins.mystyle-py
  "Long-running runner for the bundled mystyle-py (Python ast) linter."
  (:require [sunlit.mystyle :as mystyle]))

(def plugin
  (mystyle/linter-plugin
   :mystyle-py
   (str "Runs the bundled mystyle-py linter on the repository and lists structural "
        "style findings for Python sources: long functions, deep nesting, explicit "
        "global usage, fail-soft input, mutable default args, high parameter counts, "
        "context-manager candidates, type-hint coverage, oversized files, file-scope "
        "comments, and near-duplicates. Long-running with cache.")))
