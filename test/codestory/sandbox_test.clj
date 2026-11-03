(ns codestory.sandbox-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [codestory.fences :as fences]
            [codestory.plugins.matrix :as matrix]
            [codestory.render :as render]
            [codestory.repo :as repo]
            [codestory.sandbox :as sandbox])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-repo [f]
  (let [dir (.toFile (Files/createTempDirectory "codestory-sandbox" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (doseq [[p content] {"src/app/Editor.hx" "class Editor {\n  #if js\n  function renderUi() {}\n  #end\n}\n"
                           "src/app/Viewer.hx" "class Viewer {\n  function renderUi() {}\n}\n"
                           "src/net/Http.hx" "class Http {}\n"
                           ".sunlit/checks/ui.clj" "(defn has-ui? [app]\n  (seq (grep \"function renderUi\" (str \"src/app/\" app \".hx\"))))\n\n(fn [{:keys [app]}]\n  (if (has-ui? app) {:status :ok} {:status :missing :warn \"no renderUi\"}))\n"}]
        (io/make-parents (io/file dir p))
        (spit (io/file dir p) content))
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))] (.delete file))))))

(deftest globs
  (is (re-matches (sandbox/glob->regex "src/**/*.hx") "src/app/Editor.hx"))
  (is (re-matches (sandbox/glob->regex "src/**/*.hx") "src/Top.hx"))
  (is (not (re-matches (sandbox/glob->regex "src/*.hx") "src/app/Editor.hx"))))

(deftest read-only-helpers
  (with-temp-repo
    (fn [_]
      (let [run (fn [code] ((sandbox/eval-fn (read-string (str "(fn [_] " code ")"))) nil))]
        (is (= ["src/app/Editor.hx" "src/app/Viewer.hx"] (run "(files \"src/app/*.hx\")")))
        (testing "the sidecar is invisible"
          (is (not-any? #(str/starts-with? % ".sunlit") (run "(files)")))
          (is (nil? (run "(slurp-file \".sunlit/checks/ui.clj\")"))))
        (is (= [{:path "src/app/Editor.hx" :line 2 :text "  #if js"}]
               (run "(grep #\"#if\\s+js\")")))
        (is (= 2 (count (run "(grep \"renderUi\" \"src/**/*.hx\")"))))
        (is (= 3 (:line (run "(find-symbol \"src/app/Editor.hx\" \"renderUi\")"))))
        (is (= "a.hx#f" (run "(link \"a.hx\" \"f\")")))
        (testing "no filesystem or interop escape hatches"
          (is (thrown? Exception (run "(slurp \"/etc/passwd\")")))
          (is (thrown? Exception (run "(spit \"x\" 1)")))
          (is (thrown? Exception (run "(java.io.File. \"x\")"))))))))

(deftest check-file-must-stay-in-sidecar
  (with-temp-repo
    (fn [_]
      (is (fn? (sandbox/eval-fn "checks/ui.clj")))
      (is (fn? (sandbox/eval-fn ".sunlit/checks/ui.clj")))
      (is (thrown-with-msg? Exception #"inside .sunlit" (sandbox/eval-fn "../src/net/Http.hx")))
      (is (thrown-with-msg? Exception #"must evaluate to a function" (sandbox/eval-fn '(+ 1 2)))))))

(defn- cell [model coords]
  (first (filter #(= coords (:coords %)) (:cells model))))

(defn- run-model [params]
  (:data (matrix/run {:params params})))

(deftest matrix-check-inline
  (with-temp-repo
    (fn [_]
      (let [params (read-string
                    "{:dims {:app [\"Editor\" \"Viewer\"] :target [\"js\" \"cpp\"]}
                      :check (fn [{:keys [app target]}]
                               (let [hits (grep (re-pattern (str \"#if\\\\s+\" target)) (str \"src/app/\" app \".hx\"))]
                                 (if (seq hits)
                                   {:status :ok :links (map #(link (:path %) (:line %)) hits)}
                                   {:status :missing :warn (str \"no #if \" target \" block\")})))}")
            m (run-model params)]
        (is (= "done" (:status (cell m {"app" "Editor" "target" "js"}))))
        (is (= "src/app/Editor.hx#2" (:link (cell m {"app" "Editor" "target" "js"}))))
        (let [c (cell m {"app" "Viewer" "target" "cpp"})]
          (is (= "missing" (:status c)))
          (is (= "no #if cpp block" (:detail c)))
          (is (nil? (:expected c))))))))

(deftest matrix-check-file-results-and-errors
  (with-temp-repo
    (fn [_]
      (let [m (run-model {:dims {:app ["Editor" "Viewer" "Ghost"]} :check "checks/ui.clj"})]
        (is (= ["done" "done" "missing"] (mapv :status (:cells m))))
        (is (= "no renderUi" (:detail (cell m {"app" "Ghost"})))))
      (testing "manual beats check"
        (let [m (run-model {:dims {:app ["Ghost"]} :check "checks/ui.clj" :manual [["Ghost" :n/a]]})]
          (is (= "n/a" (:status (first (:cells m)))))))
      (testing "throwing, bad return values and load errors become error cells"
        (is (= "error" (:status (first (:cells (run-model {:dims {:a ["x"]} :check '(fn [_] (throw (ex-info "boom" {})))}))))))
        (is (= "error" (:status (first (:cells (run-model {:dims {:a ["x"]} :check '(fn [_] {:status :bogus})}))))))
        (let [m (run-model {:dims {:a ["x"]} :check "checks/nope.clj"})]
          (is (= "error" (:status (first (:cells m)))))
          (is (some #(str/includes? % "not found") (:errors m)))))
      (testing "true/false shorthand"
        (is (= ["done" "missing"]
               (mapv :status (:cells (run-model {:dims {:a ["x" "y"]} :check '(fn [{:keys [a]}] (= a "x"))})))))))))

(deftest matrix-check-time-budget
  (with-redefs [matrix/check-budget-ms 300]
    (let [m (run-model {:dims {:a ["x" "y"]} :check '(fn [_] (loop [] (recur)))})]
      (is (= ["error" "error"] (mapv :status (:cells m))))
      (is (str/includes? (:detail (first (:cells m))) "exceeded")))))

(deftest read-eval-is-off-in-fences
  (is (contains? (render/parse-plugin-params "#=(java.lang.System/exit 1)") :raw)))

(deftest rewrite-preserves-code-and-comments
  (let [body (str "{:dims {:app [\"a\" \"b\"]} ; the apps\n"
                  " ;; decides each cell\n"
                  " :check (fn [{:keys [app]}]\n"
                  "          (seq (grep #\"x\\s+y\" app)))\n"
                  " :manual [[\"a\" \"src/a.hx\"]]}")
        old (render/parse-plugin-params body)
        new (assoc old :manual [["a" "src/a.hx"] ["b" :n/a]])
        out (fences/rewrite-body body old new)]
    (is (str/includes? out "; the apps"))
    (is (str/includes? out ";; decides each cell"))
    (is (str/includes? out ":check (fn [{:keys [app]}]\n          (seq (grep #\"x\\s+y\" app)))"))
    (is (str/includes? out ":manual [[\"a\" \"src/a.hx\"]\n          [\"b\" :n/a]]"))
    (testing "removing the first key and adding a new one"
      (let [out2 (fences/rewrite-body body old (-> old (dissoc :dims) (assoc :rows :app)))]
        (is (not (str/includes? out2 ":dims")))
        (is (str/includes? out2 ";; decides each cell"))
        (is (str/ends-with? out2 ":rows :app}"))
        (is (= :app (:rows (render/parse-plugin-params out2))))))))
