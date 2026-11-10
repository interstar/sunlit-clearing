(ns sunlit.plugins.mystyle-js
  "Long-running runner for the bundled mystyle-js (tree-sitter) linter."
  (:require [sunlit.mystyle :as mystyle]))

(def plugin
  (mystyle/linter-plugin
   :mystyle-js
   (str "Runs the bundled mystyle-js linter on the repository and lists structural "
        "style findings for JavaScript/TypeScript sources: long functions, deep "
        "nesting, parameter counts, var usage, eqeqeq, console.log leftovers, "
        "fail-soft input, module-level mutable state, try/using candidates, file-scope "
        "comments, near-duplicates, and TS any/as/@ts-ignore/type-coverage. Long-running "
        "with cache; requires tree-sitter Python packages.")))
