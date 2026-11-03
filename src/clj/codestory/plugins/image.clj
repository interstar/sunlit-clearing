(ns codestory.plugins.image
  "Built-in plugin: render raster/vector image files (png, jpg, gif, webp, svg,
  bmp, ico, avif) inline. Auto-attaches when viewing an image file and is also
  usable as a ```plugin image fence with an optional :file param."
  (:require [clojure.string :as str]
            [codestory.repo :as repo]
            [hiccup.core :as hiccup]))

(def image-extensions
  #{".png" ".jpg" ".jpeg" ".gif" ".webp" ".svg" ".bmp" ".ico" ".avif"})

(defn- extension [name]
  (let [s (str name)
        i (str/last-index-of s ".")]
    (when (and i (pos? i))
      (str/lower-case (subs s i)))))

(defn image-path?
  "True when the (possibly repo-relative) path looks like an image file."
  [path]
  (boolean (some-> path str str/lower-case extension image-extensions)))

(defn artifact-url
  "Repo-relative URL served by the /api/artifacts/* endpoint."
  [rel]
  (str "/api/artifacts/"
       (-> (str rel)
           (str/replace #"^/+" "")
           (str/replace #"\\" "/")
           (java.net.URLEncoder/encode "UTF-8")
           (str/replace "+" "%20")
           (str/replace "%2F" "/"))))

(defn run
  [{:keys [path params]}]
  (let [target (some-> (or (:file params) path) str str/trim)]
    (cond
      (str/blank? target)
      {:ok false
       :error "No file path"
       :html (hiccup/html
              [:div.plugin-error
               [:p "The image plugin needs a file path. Use "
                [:code "```plugin image\n{:file \"assets/pic.png\"}\n```"]
                " on a page, or open an image file directly."]])}

      (not (image-path? target))
      {:ok false
       :error (str "Not an image file: " target)
       :html (hiccup/html
              [:div.plugin-error
               [:p "Not an image file: " [:code target]]])}

      :else
      (let [f (try (repo/safe-resolve target) (catch Exception _ nil))
            exists? (and f (.isFile f))
            src (artifact-url target)
            size (when exists? (.length f))]
        {:ok true
         :html (hiccup/html
                [:figure.plugin-panel.image-viewer
                 [:img.image-viewer-img
                  {:src src :alt target :loading "lazy"}]
                 [:figcaption.image-viewer-caption
                  [:code target]
                  (when (some? size)
                    (str " · " size " bytes"))
                  (when-not exists?
                    " · not found")]])
         :data {:path target
                :size size
                :exists (boolean exists?)
                :src src}}))))

(def plugin
  {:name "image"
   :description (str "Renders PNG, JPEG, GIF, WebP, SVG, BMP, ICO and AVIF files inline. "
                     "Auto-attaches when an image file is opened; also usable via a "
                     "```plugin image fence with an optional :file param.")
   :run run})
