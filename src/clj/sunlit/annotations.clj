(ns sunlit.annotations
  "Sidecar annotation store under .sunlit/annotations/."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sunlit.repo :as repo]))

(defn- annotations-root []
  (repo/sidecar-file "annotations"))

(defn- annotation-file
  "Mirror path: src/foo.clj -> .sunlit/annotations/src/foo.clj.md"
  [rel]
  (let [cleaned (-> (or rel "")
                    (str/replace #"\\" "/")
                    (str/replace #"^/+" "")
                    (str/replace #"/+$" ""))]
    (when (str/blank? cleaned)
      (throw (ex-info "Cannot annotate repository root" {:path rel})))
    (io/file (annotations-root) (str cleaned ".md"))))

(defn read-annotation [rel]
  (let [f (annotation-file rel)]
    (if (.isFile f)
      {:path rel
       :exists true
       :markdown (slurp f)}
      {:path rel
       :exists false
       :markdown ""})))

(defn write-annotation! [rel markdown]
  (let [f (annotation-file rel)]
    (io/make-parents f)
    (spit f (or markdown ""))
    (read-annotation rel)))

(defn ensure-store! []
  (.mkdirs (annotations-root))
  (.mkdirs (repo/sidecar-file "plugins")))
