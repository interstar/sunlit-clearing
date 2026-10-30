(ns codestory.structure-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [codestory.repo :as repo]
            [codestory.structure :as structure]
            [codestory.structure.parse :as parse])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-repo [f]
  (let [dir (.toFile (Files/createTempDirectory "codestory-struct" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(deftest parse-clojure-ns-and-types
  (let [src (str "(ns demo.app\n"
                 "  (:require [demo.core :as c]\n"
                 "            [clojure.string :as str]))\n\n"
                 "(defprotocol PFoo\n  (bar [this]))\n\n"
                 "(defrecord RFoo [x])\n\n"
                 "(defmulti handle :type)\n")
        u (parse/parse-clojure "src/demo/app.clj" src)]
    (is (= "demo.app" (:name u)))
    (is (= "clojure" (:lang u)))
    (is (some #{"demo.core"} (:requires u)))
    (is (some #{"clojure.string"} (:requires u)))
    (is (= #{:defprotocol :defrecord :defmulti}
           (set (map :kind (:types u)))))
    (is (= #{"PFoo" "RFoo" "handle"}
           (set (map :name (:types u)))))))

(deftest parse-python-classes
  (let [src (str "class Animal:\n    pass\n\n"
                 "class Dog(Animal):\n    def bark(self):\n        pass\n\n"
                 "def helper(x):\n    return x\n")
        u (parse/parse-python "pkg/animals.py" src)]
    (is (= "pkg.animals" (:name u)))
    (is (= ["Animal" "Dog"] (mapv :name (:classes u))))
    (is (= ["Animal"] (:bases (second (:classes u)))))
    (is (some #{"helper" "bark"} (:functions u)))))

(deftest parse-haxe-types
  (let [src (str "package demo.game;\n\n"
                 "import demo.util.Helpers;\n\n"
                 "class Player extends Actor implements IUpdatable {\n"
                 "  public function new() {}\n"
                 "}\n\n"
                 "interface IUpdatable {}\n\n"
                 "enum Direction { North; South; }\n")
        u (parse/parse-haxe "src/demo/game/Player.hx" src)]
    (is (= "demo.game" (:package u)))
    (is (= #{"Player" "IUpdatable" "Direction"}
           (set (map :name (:types u)))))
    (is (= #{"class" "interface" "enum"}
           (set (map :kind (:types u)))))
    (let [player (first (filter #(= "Player" (:name %)) (:types u)))]
      (is (some #{"Actor" "IUpdatable"} (:bases player))))))

(deftest parse-cpp-classes-and-includes
  (let [src (str "#include \"ControlRunnerBase.h\"\n"
                 "#include <vector>\n\n"
                 "class ControlRunnerBase {\n"
                 "public:\n  virtual void tick() = 0;\n"
                 "};\n\n"
                 "class GbloinkControlRunner final : public ControlRunnerBase {\n"
                 "public:\n  void tick() override {}\n"
                 "};\n\n"
                 "struct CoordinateMapper : private Base1, public util::Base2 {\n"
                 "};\n")
        u (parse/parse-cpp "src/GbloinkControlRunner.cpp" src)]
    (is (= "GbloinkControlRunner" (:name u)))
    (is (= ["ControlRunnerBase.h" "vector"] (:includes u)))
    (is (= ["ControlRunnerBase.h"] (:local-includes u)))
    (is (= #{"ControlRunnerBase" "GbloinkControlRunner" "CoordinateMapper"}
           (set (map :name (:types u)))))
    (let [g (first (filter #(= "GbloinkControlRunner" (:name %)) (:types u)))
          c (first (filter #(= "CoordinateMapper" (:name %)) (:types u)))]
      (is (= :class (:kind g)))
      (is (= ["ControlRunnerBase"] (:bases g)))
      (is (= :struct (:kind c)))
      (is (= ["Base1" "Base2"] (:bases c))))))

(deftest cpp-unit-name-strips-dirs-and-extensions
  (is (= "ControlThread" (parse/cpp-unit-name "bridge/native/ControlThread.h")))
  (is (= "juce_audio_basics" (parse/cpp-unit-name "juce_audio_basics/juce_audio_basics.h"))))

(deftest scan-clojure-only-src-not-generated
  (with-temp-repo
    (fn [dir]
      (let [src (io/file dir "src/demo/core.clj")
            gen (io/file dir "resources/public/js/compiled/out/cljs/reader.cljs")]
        (io/make-parents src)
        (io/make-parents gen)
        (spit src "(ns demo.core\n  (:require [cljs.reader :as r]))\n")
        (spit gen "(ns cljs.reader)\n")
        (let [summary (structure/scan-clojure {:max-depth 12})
              names (set (map :name (:units summary)))]
          (is (= #{"demo.core"} names))
          (is (not (contains? names "cljs.reader")))
          (is (zero? (:edge_count summary))))))))

(deftest mermaid-ns-graph-uses-edges
  (let [mmd (structure/mermaid-ns-graph [{:from "a.b" :to "a.c"}])]
    (is (re-find #"flowchart LR" mmd))
    (is (re-find #"a_b" mmd))
    (is (re-find #"a_c" mmd))))

(deftest mermaid-class-diagram-avoids-html-stereotypes
  (let [mmd (structure/mermaid-class-diagram
             [{:name "Dog" :kind "class" :bases ["Animal"]}
              {:name "Animal" :kind "class" :bases []}])]
    (is (re-find #"classDiagram" mmd))
    (is (re-find #"Dog" mmd))
    (is (not (re-find #"<<" mmd)))))

(deftest mermaid-class-diagram-skips-orphan-dump
  (let [types (mapv (fn [i] {:name (str "C" i) :kind "class" :bases []}) (range 40))
        mmd (structure/mermaid-class-diagram types)]
    (is (nil? mmd))))

(deftest mermaid-class-diagram-skips-external-bases
  (let [mmd (structure/mermaid-class-diagram
             [{:name "Row" :kind "class" :bases ["TypedDict"]}
              {:name "TypedDict" :kind "class" :bases []}])]
    ;; TypedDict is treated as external noise even if a same-named stub exists
    (is (nil? mmd))))

(deftest mermaid-package-overview-groups-modules
  (let [mmd (structure/mermaid-package-overview
             [{:name "src.app.panel" :classes [{:name "A"} {:name "B"}]}
              {:name "src.app.tabs" :classes [{:name "C"}]}
              {:name "src.domain.model" :classes [{:name "D"}]}])]
    (is (re-find #"flowchart TB" mmd))
    (is (re-find #"src\.app" mmd))
    (is (re-find #"2 classes" mmd))))

(deftest html-lang-escapes-mermaid
  (let [html (structure/->html-lang
              {:lang "python"
               :label "Python"
               :unit_count 12
               :type_count 3
               :edge_count 0
               :units (mapv (fn [i]
                              {:path (str "src/pkg/m" i ".py")
                               :name (str "src.pkg.m" i)
                               :classes [{:name (str "C" i) :bases []}]
                               :functions []})
                            (range 12))
               :types [{:lang "python" :kind "class" :name "Dog" :bases ["Animal"] :owner "src.pkg.m0" :path "a.py"}
                       {:lang "python" :kind "class" :name "Animal" :bases [] :owner "src.pkg.m1" :path "b.py"}]
               :edges []})]
    (is (re-find #"structure-lang-box" html))
    (is (re-find #"pre class=\"mermaid\"" html))
    (is (re-find #"Packages" html))
    ;; Must be entity-escaped inside the pre so << never becomes an HTML tag
    (is (not (re-find #"<pre class=\"mermaid\">[^<]*<<" html)))))
