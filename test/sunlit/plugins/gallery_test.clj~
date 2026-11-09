(ns codestory.plugins.gallery-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [codestory.plugins.gallery :as gallery]
            [codestory.render :as render]
            [codestory.repo :as repo])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-repo [f]
  (let [dir (.toFile (Files/createTempDirectory "codestory-gallery" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (.mkdirs (io/file dir "assets" "sub"))
      (spit (io/file dir "assets" "a.png") "png")
      (spit (io/file dir "assets" "b.jpg") "jpg")
      (spit (io/file dir "assets" "notes.txt") "text")
      (spit (io/file dir "assets" "sub" "c.png") "png")
      (.mkdirs (io/file dir "empty"))
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(deftest gallery-images-lists-direct-children-only
  (with-temp-repo
    (fn [_]
      (let [images (gallery/gallery-images "assets")]
        (is (= ["a.png" "b.jpg"] (mapv :name images)))
        (is (= ["assets/a.png" "assets/b.jpg"] (mapv :path images)))
        (is (not-any? #(= "c.png" (:name %)) images))))))

(deftest gallery-images-empty-for-no-images
  (with-temp-repo
    (fn [_]
      (is (empty? (gallery/gallery-images "empty")))
      (is (empty? (gallery/gallery-images "does-not-exist"))))))

(deftest run-renders-thumbnails
  (with-temp-repo
    (fn [_]
      (let [result (gallery/run {:path "assets" :params {}})]
        (is (true? (:ok result)))
        (is (str/includes? (:html result) "gallery-grid"))
        (is (str/includes? (:html result) "gallery-thumb"))
        (is (str/includes? (:html result) "/api/artifacts/assets/a.png"))
        (is (str/includes? (:html result) "#/file/assets%2Fa.png"))
        (is (= 2 (get-in result [:data :total])))
        (is (= ["assets/a.png" "assets/b.jpg"]
               (mapv :path (get-in result [:data :images]))))))))

(deftest run-reports-no-images
  (with-temp-repo
    (fn [_]
      (let [result (gallery/run {:path "empty" :params {}})]
        (is (false? (:ok result)))
        (is (str/includes? (:error result) "No images"))))))

(deftest run-prefers-dir-param
  (with-temp-repo
    (fn [_]
      (let [result (gallery/run {:path "page:Something" :params {:dir "assets"}})]
        (is (true? (:ok result)))
        (is (str/includes? (:html result) "/api/artifacts/assets/a.png"))
        (is (= 2 (get-in result [:data :total])))))))

(deftest auto-attach-for-directories
  (with-temp-repo
    (fn [_]
      (is (= [{:name "gallery" :params {}}]
             (render/auto-plugin-fences-for-dir "assets")))
      (is (= [] (render/auto-plugin-fences-for-dir "empty")))
      (is (= [] (render/auto-plugin-fences-for-dir "does-not-exist"))))))
