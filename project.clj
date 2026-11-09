(defproject sunlit "0.1.0-SNAPSHOT"
  :description "Sunlit Clearing — local wiki-like explorer for code repositories"
  :url "https://github.com/future-office-labs/sunlit"
  :license {:name "MIT"}
  :dependencies [[org.clojure/clojure "1.11.4"]
                 [org.clojure/clojurescript "1.11.132"]
                 [reagent "1.2.0"]
                 [cljsjs/react "17.0.2-0"]
                 [cljsjs/react-dom "17.0.2-0"]
                 [http-kit "2.8.0"]
                 [ring/ring-core "1.12.2"]
                 [ring/ring-defaults "0.5.0"]
                 [compojure "1.7.1"]
                 [cheshire "5.13.0"]
                 [markdown-clj "1.12.3"]
                 [hiccup "1.0.5"]
                 [org.babashka/sci "0.8.41"]
                 [rewrite-clj "1.1.47"]
                 [org.clojure/tools.cli "1.1.230"]
                 [org.clojure/tools.reader "1.4.2"]
                 [com.bhauman/figwheel-main "0.2.18"]]
  :plugins [[lein-cljsbuild "1.1.8"]
            [lein-cloverage "1.2.4"]]
  :source-paths ["src/clj"]
  :test-paths ["test"]
  :resource-paths ["resources"]
  :main ^:skip-aot sunlit.core
  :target-path "target/%s"
  :clean-targets ^{:protect false} ["resources/public/js/compiled"
                                    :target-path]
  :cljsbuild
  {:builds
   [{:id "dev"
     :source-paths ["src/cljs"]
     :compiler {:main sunlit.client
                :output-to "resources/public/js/compiled/app.js"
                :output-dir "resources/public/js/compiled/out"
                :asset-path "/js/compiled/out"
                :optimizations :whitespace
                :pretty-print true}}
    {:id "prod"
     :source-paths ["src/cljs"]
     :compiler {:main sunlit.client
                :output-to "resources/public/js/compiled/app.js"
                :optimizations :advanced
                :pretty-print false
                :externs ["resources/externs/browser-libs.js"]}}]}
  :profiles
  {:uberjar {:aot :all
             :prep-tasks ["compile" ["cljsbuild" "once" "prod"]]
             :uberjar-name "clearing.jar"}
   :dev {:source-paths ["src/clj" "src/cljs" "dev"]
         :resource-paths ["target" "resources"]}}
  :aliases
  {"fig" ["run" "-m" "figwheel.main" "--" "-b" "dev" "-r"]
   "cljs" ["cljsbuild" "once" "dev"]
   "cljs-prod" ["cljsbuild" "once" "prod"]
   "coverage" ["cloverage" "--ns-exclude-regex" "sunlit\\.client.*"]})
