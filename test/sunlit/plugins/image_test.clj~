(ns codestory.plugins.image-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [codestory.plugins.image :as image]
            [codestory.render :as render]
            [codestory.repo :as repo])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-repo [f]
  (let [dir (.toFile (Files/createTempDirectory "codestory-image" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (.mkdirs (io/file dir "assets"))
      (spit (io/file dir "assets" "pic.png") "fake-png-bytes")
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(deftest image-path-detection
  (is (image/image-path? "assets/pic.png"))
  (is (image/image-path? "PHOTO.JPG"))
  (is (image/image-path? "logo.svg"))
  (is (image/image-path? "x.avif"))
  (is (not (image/image-path? "src/core.clj")))
  (is (not (image/image-path? "README.md")))
  (is (not (image/image-path? nil))))

(deftest run-renders-artifact-url
  (with-temp-repo
    (fn [_]
      (let [result (image/run {:path "assets/pic.png" :params {}})]
        (is (true? (:ok result)))
        (is (str/includes? (:html result) "image-viewer-img"))
        (is (str/includes? (:html result) "/api/artifacts/assets/pic.png"))
        (is (= "assets/pic.png" (get-in result [:data :path])))
        (is (true? (get-in result [:data :exists])))))))

(deftest run-prefers-file-param
  (with-temp-repo
    (fn [_]
      (let [result (image/run {:path nil :params {:file "assets/pic.png"}})]
        (is (true? (:ok result)))
        (is (str/includes? (:html result) "/api/artifacts/assets/pic.png"))))))

(deftest run-rejects-non-image
  (with-temp-repo
    (fn [_]
      (let [result (image/run {:path "src/core.clj" :params {}})]
        (is (false? (:ok result)))
        (is (str/includes? (:error result) "Not an image"))))))

(deftest auto-attach-for-images
  (is (= [{:name "image" :params {}}]
         (render/auto-plugin-fences "assets/pic.png")))
  (is (= [{:name "leiningen" :params {}}]
         (render/auto-plugin-fences "project.clj")))
  (is (= [] (render/auto-plugin-fences "src/core.clj"))))
