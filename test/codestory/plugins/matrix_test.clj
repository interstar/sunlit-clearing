(ns codestory.plugins.matrix-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [codestory.fences :as fences]
            [codestory.pages :as pages]
            [codestory.plugins :as plugins]
            [codestory.plugins.matrix :as matrix]
            [codestory.render :as render]
            [codestory.repo :as repo])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-repo [f]
  (let [dir (.toFile (Files/createTempDirectory "codestory-matrix" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (doseq [p ["handlers/circle/area.clj"
                 "handlers/circle/draw.clj"
                 "handlers/square/area.clj"
                 "handlers/hexagon/area.clj"
                 "handlers/default/draw.clj"]]
        (io/make-parents (io/file dir p))
        (spit (io/file dir p) "(ns x)\n"))
      (spit (io/file dir "shapes.clj") "(ns shapes)\n(defn area-circle [])\n(defn draw-circle [])\n")
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))] (.delete file))))))

(defn- cell [model coords]
  (first (filter #(= coords (:coords %)) (:cells model))))

(defn- eval* [params]
  (matrix/evaluate params (repo/list-project-files) (memoize codestory.locate/locate)))

(def base
  {:dims {:type ["circle" "square" "triangle"] :operation ["area" "draw"]}
   :pattern "handlers/{type}/{operation}.clj"})

(deftest pattern-cells-gaps-and-orphans
  (with-temp-repo
    (fn [_]
      (let [m (eval* base)]
        (is (= "done" (:status (cell m {"type" "circle" "operation" "area"}))))
        (is (= "handlers/circle/area.clj" (:link (cell m {"type" "circle" "operation" "area"}))))
        (is (= "missing" (:status (cell m {"type" "square" "operation" "draw"}))))
        (is (= "handlers/square/draw.clj" (:expected (cell m {"type" "square" "operation" "draw"}))))
        (testing "files with coordinates outside :dims are orphans"
          (is (= #{"handlers/hexagon/area.clj" "handlers/default/draw.clj"}
                 (set (map :path (:orphans m))))))
        (is (empty? (:errors m)))))))

(deftest fallback-pattern-is-inherited
  (with-temp-repo
    (fn [_]
      (let [m (eval* (assoc base :pattern ["handlers/{type}/{operation}.clj"
                                           "handlers/default/{operation}.clj"]))]
        (is (= "done" (:status (cell m {"type" "circle" "operation" "draw"}))))
        (is (= "inherited" (:status (cell m {"type" "square" "operation" "draw"}))))
        (is (= "handlers/default/draw.clj" (:link (cell m {"type" "square" "operation" "draw"}))))
        (testing "a file used by the fallback pattern is not an orphan"
          (is (= ["handlers/hexagon/area.clj"] (mapv :path (:orphans m)))))))))

(deftest auto-dimension-values
  (with-temp-repo
    (fn [_]
      (let [m (eval* {:dims {:type :auto :operation ["area" "draw"]}
                      :pattern "handlers/{type}/{operation}.clj"})]
        (is (= ["circle" "default" "hexagon" "square"]
               (:values (first (:dims m)))))
        (is (empty? (:orphans m)))))))

(deftest anchors-in-patterns-resolve-symbols
  (with-temp-repo
    (fn [_]
      (let [m (eval* {:dims {:operation ["area" "draw"] :type ["circle" "square"]}
                      :pattern "shapes.clj#{operation}-{type}"})]
        (is (= "shapes.clj#area-circle" (:link (cell m {"operation" "area" "type" "circle"}))))
        (is (= "done" (:status (cell m {"operation" "draw" "type" "circle"}))))
        (let [c (cell m {"operation" "area" "type" "square"})]
          (is (= "missing" (:status c)))
          (is (str/includes? (:detail c) "area-square")))))))

(deftest manual-entries-override
  (with-temp-repo
    (fn [_]
      (let [m (eval* (assoc base :manual [["square" "draw" "shapes.clj#draw-circle"]
                                          ["triangle" "area" :n/a]
                                          ["triangle" "draw" {:link "nowhere.clj" :note "hm"}]
                                          ["circle" "area" {:note "fast path"}]
                                          ["bad"]]))]
        (is (= "done" (:status (cell m {"type" "square" "operation" "draw"}))))
        (is (= "n/a" (:status (cell m {"type" "triangle" "operation" "area"}))))
        (is (= "broken" (:status (cell m {"type" "triangle" "operation" "draw"}))))
        (testing "a note alone keeps the pattern link"
          (let [c (cell m {"type" "circle" "operation" "area"})]
            (is (= "done" (:status c)))
            (is (= "handlers/circle/area.clj" (:link c)))
            (is (= "fast path" (:note c)))))
        (is (some #(str/includes? % "wrong number") (:errors m)))))))

(deftest three-dimensional-render
  (with-temp-repo
    (fn [_]
      (let [params {:dims {:app ["a" "b"] :target ["js" "cpp"] :concern ["ui" "net"]}
                    :rows :concern :cols :app}
            html (:html (matrix/run {:params params :path "page:M" :fence-index 0}))]
        (is (str/includes? html "target=js"))
        (is (str/includes? html "Summary"))
        (is (str/includes? html "data-fence=\"0\""))
        (is (str/includes? html "concern \\ app"))))))

(deftest run-without-dims-explains-usage
  (let [r (matrix/run {:params {}})]
    (is (false? (:ok r)))
    (is (str/includes? (:html r) ":dims"))))

(deftest update-params-edits-manual
  (let [p {:dims {:type ["circle" "square"] :op ["area"]}
           :manual [["circle" "area" "a.clj"]]}]
    (is (= [["circle" "area" "b.clj#f"]]
           (:manual (matrix/update-params p {:op "set-cell" :coords {:type "circle" :op "area"}
                                             :link "b.clj#f" :status "" :note ""}))))
    (is (= [["circle" "area" "a.clj"] ["square" "area" :n/a]]
           (:manual (matrix/update-params p {:op "set-cell" :coords {:op "area" :type "square"}
                                             :status "n/a"}))))
    (is (= [["circle" "area" {:link "a.clj" :status :todo :note "later"}]]
           (:manual (matrix/update-params p {:op "set-cell" :coords {:type "circle" :op "area"}
                                             :link "a.clj" :status "todo" :note "later"}))))
    (testing "clearing the last entry keeps an explicit empty :manual"
      (is (= [] (:manual (matrix/update-params p {:op "clear-cell" :coords {:type "circle" :op "area"}})))))
    (is (thrown? Exception
                 (matrix/update-params p {:op "set-cell" :coords {:type "circle"} :link "x"})))))

(deftest update-params-axes-swap
  (let [p {:dims {:a ["1"] :b ["2"] :c ["3"]}}]
    (is (= [:b :a] ((juxt :rows :cols) (matrix/update-params p {:op "set-axes" :rows "b" :cols "b" :changed "rows"}))))
    (is (= [:b :a] ((juxt :rows :cols) (matrix/update-params p {:op "set-axes" :rows "a" :cols "a" :changed "cols"}))))
    (is (= [:c :b] ((juxt :rows :cols) (matrix/update-params p {:op "set-axes" :rows "c" :cols "b" :changed "rows"}))))))

(deftest fence-round-trip-through-page
  (with-temp-repo
    (fn [_]
      (plugins/init!)
      (pages/write-page! "Grid" (str "# Grid\n\n```plugin gallery\n{}\n```\n\n"
                                     "```plugin matrix\n{:dims {:type [\"circle\" \"square\"]\n :op [\"area\"]}}\n```\n\nafter\n"))
      (testing "fence index is the position among all plugin fences"
        (is (str/includes? (:html (render/render-markdown (:markdown (pages/read-page "Grid")) "page:Grid"))
                           "data-fence=\"1\"")))
      (fences/update-fence! "page:Grid" 1 "matrix"
                            {:op "set-cell" :coords {:type "square" :op "area"} :status "n/a"})
      (let [md (:markdown (pages/read-page "Grid"))
            params (:params (first (filter #(= "matrix" (:name %)) (render/plugin-fences md))))]
        (is (= [["square" "area" :n/a]] (:manual params)))
        (is (str/starts-with? md "# Grid\n\n```plugin gallery\n{}\n```"))
        (is (str/ends-with? md "```\n\nafter\n")))
      (testing "refuses to edit a fence owned by another plugin"
        (is (thrown? Exception (fences/update-fence! "page:Grid" 0 "matrix" {:op "clear-cell"})))))))

(deftest format-params-is-readable-and-reads-back
  (let [p {:manual [["a" "b" "x.clj"] ["c" "d" :n/a]]
           :dims {:type ["a" "c"] :operation ["b" "d"]}
           :title "T"}
        s (fences/format-params p)]
    (is (= p (read-string s)))
    (is (= (str "{:title \"T\"\n"
                " :dims {:type [\"a\" \"c\"]\n"
                "        :operation [\"b\" \"d\"]}\n"
                " :manual [[\"a\" \"b\" \"x.clj\"]\n"
                "          [\"c\" \"d\" :n/a]]}")
           s))))

(deftest manual-only-matrix-is-a-checklist
  (with-temp-repo
    (fn [_]
      (let [params {:dims {:task ["write" "review"] :who ["ann" "bo"]}
                    :manual [["write" "ann" :done]]}
            m (eval* params)]
        (is (:manual-mode m))
        (is (= "done" (:status (cell m {"task" "write" "who" "ann"}))))
        (testing "open cells are todo, not missing"
          (is (= "todo" (:status (cell m {"task" "review" "who" "bo"}))))
          (is (nil? (:expected (cell m {"task" "review" "who" "bo"})))))
        (let [html (:html (matrix/run {:params params :path "page:T" :fence-index 0}))]
          (is (= 4 (count (re-seq #"matrix-check" html))))
          (is (= 1 (count (re-seq #"checked=\"checked\"" html)))))
        (testing "no checkboxes when read-only, or when a pattern decides cells"
          (is (not (str/includes? (:html (matrix/run {:params params})) "matrix-check")))
          (is (not (str/includes? (:html (matrix/run {:params base :path "page:T" :fence-index 0}))
                                  "matrix-check"))))
        (testing "ticking a box round-trips through update-params"
          (is (= [["write" "ann" :done] ["review" "bo" :done]]
                 (:manual (matrix/update-params params {:op "set-cell" :coords {:task "review" :who "bo"}
                                                        :link "" :note "" :status "done"})))))))))

(deftest check-skipped-where-manual-decides
  (with-temp-repo
    (fn [_]
      (let [calls (atom [])
            m (matrix/evaluate {:dims {:a ["x" "y" "z"]}
                                :manual [["x" :n/a] ["z" {:note "just a note"}]]}
                               [] identity
                               {:check (fn [c] (swap! calls conj c) {:status "done"})})]
        (is (= [{:a "y"} {:a "z"}] @calls))
        (is (= ["n/a" "done" "done"] (mapv :status (:cells m))))
        (is (not (:manual-mode m)))))))
