(ns sunlit.plugins.gallery
  "Built-in plugin: render a thumbnail grid of the image files in a directory.
  Auto-attaches when a directory containing images is opened, and is also
  usable as a ```plugin gallery fence with an optional :dir param."
  (:require [clojure.string :as str]
            [sunlit.clj-source :as src]
            [sunlit.plugins.image :as image]
            [sunlit.repo :as repo]
            [hiccup.core :as hiccup]))

(def ^:private max-thumbs 200)

(defn gallery-images
  "Direct child image files of a directory (repo-relative path, \"\" = root).
  Returns a vector of {:name :path :size}, sorted by name; nil on error."
  [rel]
  (try
    (let [dir (repo/safe-resolve (or rel ""))]
      (when (.isDirectory dir)
        (->> (.listFiles dir)
             (remove nil?)
             (filter #(.isFile %))
             (filter #(image/image-path? (.getName %)))
             (sort-by #(str/lower-case (.getName %)))
             (map (fn [f]
                    {:name (.getName f)
                     :path (repo/relativize f)
                     :size (.length f)}))
             vec)))
    (catch Exception _
      nil)))

(defn run
  [{:keys [path params]}]
  (let [rel (or (some-> (:dir params) str str/trim)
                (some-> path str str/trim)
                "")
        all (or (gallery-images rel) [])
        shown (vec (take max-thumbs all))
        truncated? (> (count all) max-thumbs)]
    (if (empty? shown)
      {:ok false
       :error (str "No images in directory: " rel)
       :html (hiccup/html
              [:div.plugin-error
               [:p "No images in this directory."]])}
      {:ok true
       :html (hiccup/html
              [:div.gallery.plugin-panel
               [:div.gallery-head
                [:h3 (str "Images (" (count all) ")")]
                (when truncated?
                  [:span.meta (str "showing first " max-thumbs)])]
               [:div.gallery-grid
                (for [{:keys [name path size]} shown]
                  [:a.gallery-thumb {:href (src/file-href path) :title path}
                   [:figure
                    [:img {:src (image/artifact-url path)
                           :alt name
                           :loading "lazy"}]
                    [:figcaption
                     [:span.gallery-name name]
                     [:span.gallery-size (str size " B")]]]])]])
       :data {:directory rel
              :total (count all)
              :images (mapv #(select-keys % [:name :path :size]) shown)}})))

(def plugin
  {:name "gallery"
   :description (str "Renders a thumbnail grid of image files in a directory. "
                     "Auto-attaches when viewing a directory that contains images; "
                     "also usable via a ```plugin gallery fence with a :dir param.")
   :run run})
