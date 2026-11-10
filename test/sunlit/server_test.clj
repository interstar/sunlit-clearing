(ns sunlit.server-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [sunlit.server :as server]
            [sunlit.repo :as repo])
  (:import [java.io ByteArrayInputStream]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-repo [f]
  (let [dir (.toFile (Files/createTempDirectory "sunlit-server" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(defn- put! [uri query body]
  (let [res (server/app {:request-method :put
                         :uri uri
                         :query-string query
                         :headers {"content-type" "application/json"}
                         :body (ByteArrayInputStream. (.getBytes ^String body "UTF-8"))})]
    (assoc res :json (json/parse-string (:body res) true))))

(deftest saves-require-markdown
  (with-temp-repo
    (fn [dir]
      (let [note (io/file dir ".sunlit" "annotations" "foo.txt.md")
            page (io/file dir ".sunlit" "pages" "Notes.md")]
        (io/make-parents note)
        (io/make-parents page)
        (spit note "keep me")
        (spit page "keep me too")
        (doseq [[label body] [["no markdown key" "{\"text\": \"oops\"}"]
                              ["empty body" ""]
                              ["markdown not a string" "{\"markdown\": null}"]]]
          (testing label
            (let [r (put! "/api/annotation" "path=foo.txt" body)]
              (is (= 400 (:status r)))
              (is (false? (get-in r [:json :ok]))))
            (is (= 400 (:status (put! "/api/pages/Notes" nil body))))
            (is (= "keep me" (slurp note)))
            (is (= "keep me too" (slurp page)))))
        (testing "an explicit empty string still clears a note"
          (is (= 200 (:status (put! "/api/annotation" "path=foo.txt" "{\"markdown\": \"\"}"))))
          (is (= "" (slurp note))))))))
