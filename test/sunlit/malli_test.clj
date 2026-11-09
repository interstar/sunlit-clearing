(ns sunlit.malli-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [sunlit.malli :as malli]
            [sunlit.repo :as repo])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-repo [f]
  (let [dir (.toFile (Files/createTempDirectory "sunlit-malli" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(def ^:private sample-src
  (str "(ns demo.schemas\n"
       "  (:require [malli.core :as m]))\n\n"
       "(def User\n"
       "  [:map\n"
       "   [:id :uuid]\n"
       "   [:name :string]])\n\n"
       "(def NameSchema :string)\n\n"
       "(def wrapped\n"
       "  (m/schema [:vector :int]))\n\n"
       "(def not-a-schema\n"
       "  {:a 1})\n\n"
       "(defschema Order\n"
       "  [:map [:sku :string] [:qty :int]])\n"))

(deftest extract-schemas-from-sample
  (let [unit (malli/parse-file "src/demo/schemas.clj" sample-src)
        names (set (map :name (:schemas unit)))]
    (is (= "demo.schemas" (:ns unit)))
    (is (= #{"User" "NameSchema" "wrapped" "Order"} names))
    (is (not (contains? names "not-a-schema")))
    (let [user (first (filter #(= "User" (:name %)) (:schemas unit)))]
      (is (str/includes? (:source user) ":map"))
      (is (str/includes? (:source user) ":uuid")))))

(deftest scan-only-src
  (with-temp-repo
    (fn [dir]
      (let [src (io/file dir "src/demo/schemas.clj")
            other (io/file dir "test/demo/schemas.clj")]
        (io/make-parents src)
        (io/make-parents other)
        (spit src sample-src)
        (spit other "(def Hidden [:map [:x :int]])\n")
        (let [summary (malli/scan {:max-depth 12})
              names (set (map :name (:schemas summary)))]
          (is (= 4 (:schema_count summary)))
          (is (= #{"User" "NameSchema" "wrapped" "Order"} names))
          (is (not (contains? names "Hidden")))
          (is (str/includes? (malli/->html summary) "Malli schemas"))
          (is (str/includes? (malli/->html summary) "User")))))))

(deftest empty-html
  (let [html (malli/->html {:schema_count 0 :file_count 0 :schemas []})]
    (is (str/includes? html "No Malli schemas"))))
