(ns codestory.locate-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [codestory.locate :as locate]
            [codestory.render :as render]
            [codestory.repo :as repo])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest anchors
  (is (= ["src/a.clj" "foo"] (locate/split-anchor "src/a.clj#foo")))
  (is (= ["src/a.clj" nil] (locate/split-anchor "src/a.clj")))
  (is (= {:line 42 :end 42} (locate/parse-anchor "42")))
  (is (= {:line 42 :end 42} (locate/parse-anchor "L42")))
  (is (= {:line 10 :end 20} (locate/parse-anchor "20-10")))
  (is (= {:symbol "draw-circle"} (locate/parse-anchor "draw-circle")))
  (is (nil? (locate/parse-anchor "  "))))

(deftest hrefs
  (is (= "#/file/src%2Fa.clj" (locate/file-href "src/a.clj")))
  (is (= "#/file/src%2Fa.clj?line=3-9" (locate/file-href "src/a.clj" "3-9")))
  (is (= "#/file/src%2Fa.clj?line=3" (locate/file-href "src/a.clj" "L3")))
  (is (= "#/file/src%2Fa.clj?sym=area%20%3Acircle"
         (locate/file-href "src/a.clj" "area :circle"))))

(deftest wiki-links-carry-anchors
  (let [html (:html (render/render-markdown "See [[src/a.clj#draw]] and [[x|README.md#12]]" "page:t"))]
    (is (str/includes? html "#/file/src%2Fa.clj?sym=draw"))
    (is (str/includes? html "#/file/README.md?line=12"))))

(def clj-src
  "(ns demo)

(defn helper [x] (str \"(\" x))

(defn- draw-circle
  \"Draws ) a circle\"
  [r]
  (let [d (* 2 r)]
    d))

(defmethod area :circle [s]
  (* 3.14 (:r s)))
")

(deftest clojure-symbols
  (testing "definition with block extent ignoring parens inside strings"
    (is (= {:line 5 :end 9 :exact true}
           (locate/find-symbol clj-src "clojure" "draw-circle"))))
  (testing "prefix of another name does not match"
    (is (= 3 (:line (locate/find-symbol clj-src "clojure" "helper")))))
  (testing "defmethod by name, and by literal dispatch text"
    (is (= 11 (:line (locate/find-symbol clj-src "clojure" "area"))))
    (is (= 11 (:line (locate/find-symbol clj-src "clojure" "area :circle")))))
  (testing "missing symbol"
    (is (nil? (locate/find-symbol clj-src "clojure" "nope")))))

(deftest python-symbols
  (let [src "import os\n\nclass Shape:\n    def area(self):\n        return 1\n\n    def draw(self):\n        pass\n\ndef area():\n    return 2\n"]
    (is (= {:line 3 :end 8 :exact true} (locate/find-symbol src "python" "Shape")))
    (is (= 10 (:line (locate/find-symbol src "python" "area"))))
    (testing "qualified lookup searches inside the container"
      (is (= {:line 4 :end 5 :exact true} (locate/find-symbol src "python" "Shape.area"))))))

(deftest brace-symbols
  (let [src "package a;\n\nclass Circle {\n  public function area():Float {\n    return 1.0;\n  }\n  var r:Float;\n}\n"]
    (is (= {:line 3 :end 8 :exact true} (locate/find-symbol src "haxe" "Circle")))
    (is (= {:line 4 :end 6 :exact true} (locate/find-symbol src "haxe" "area")))
    (is (= {:line 7 :end 7 :exact true} (locate/find-symbol src "haxe" "r"))))
  (let [src "int helper(int x);\n\nint helper(int x)\n{\n  return x;\n}\n"]
    (is (= {:line 3 :end 6 :exact true} (locate/find-symbol src "cpp" "helper")))))

(deftest locate-against-repo
  (let [dir (.toFile (Files/createTempDirectory "codestory-locate" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (.mkdirs (io/file dir "src"))
      (spit (io/file dir "src" "demo.clj") clj-src)
      (is (= {:path "src/demo.clj" :exists true :found true :line 2 :end 4}
             (locate/locate "src/demo.clj#2-4")))
      (is (:found (locate/locate "src/demo.clj#draw-circle")))
      (is (false? (:found (locate/locate "src/demo.clj#nope"))))
      (is (false? (:exists (locate/locate "src/missing.clj"))))
      (is (false? (:exists (locate/locate "../outside.clj"))))
      (finally
        (doseq [f (reverse (file-seq dir))] (.delete f))))))
