(ns sunlit.plugins.mystyle-hx
  "Long-running runner for the bundled mystyle-haxe (haxeparser, native) linter."
  (:require [sunlit.mystyle :as mystyle]))

(def plugin
  (mystyle/linter-plugin
   :mystyle-hx
   (str "Runs the bundled mystyle-haxe linter (a native binary) on the repository and "
        "lists structural style findings for Haxe sources: long functions, multi-line "
        "local functions, deep nesting, parameter counts, mutable static state, fail-soft "
        "input, oversized files, and near-duplicates. Long-running with cache; uses the "
        "vendored scripts/mystyle-haxe, else mystyle-haxe on PATH.")))
