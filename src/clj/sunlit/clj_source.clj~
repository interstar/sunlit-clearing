(ns codestory.clj-source
  "Shared helpers for deterministic extraction from Clojure source files
  (no eval): safe form reading, repo scanning, HTML escaping/links, and the
  shared 'structure-lang-box' extract panel renderer."
  (:require [clojure.string :as str]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as rt]
            [codestory.repo :as repo]
            [codestory.source :as source]
            [hiccup.core :as hiccup]))

(def clj-exts #{".clj" ".cljs" ".cljc"})

(defn html-escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn file-href [path]
  (str "#/file/" (-> (str path)
                     (java.net.URLEncoder/encode "UTF-8")
                     (str/replace "+" "%20"))))

(defn read-rel [path]
  (try
    (:content (repo/read-file path))
    (catch Exception _
      nil)))

(defn read-all-forms
  "Read top-level forms from a source string. Stops on EOF or read error."
  [content]
  (binding [reader/*default-data-reader-fn* (fn [_tag _val] nil)
            reader/*read-eval* false]
    (let [rdr (rt/string-push-back-reader (str content))]
      (loop [forms []]
        (let [form (try
                     (reader/read {:eof ::eof
                                   :read-cond :allow
                                   :features #{:clj :cljs :default}}
                                  rdr)
                     (catch Exception _
                       ::fail))]
          (cond
            (= form ::eof) forms
            (= form ::fail) forms
            :else (recur (conj forms form))))))))

(defn ns-name-from-forms [forms]
  (when-let [ns-form (first (filter #(and (seq? %) (= 'ns (first %))) forms))]
    (when (symbol? (second ns-form))
      (str (second ns-form)))))

(defn scan-clj
  "Walk src/ for Clojure files, parse each with `parse-file` (path content ->
  unit-or-nil), and collect `items-key` from each unit. Returns
  {:units [...] :items [...] :item_count n :file_count n} with items sorted
  by :ns then :name."
  [{:keys [parse-file items-key]} {:keys [max-depth] :or {max-depth 24}}]
  (let [files (source/source-files clj-exts max-depth)
        units (->> files
                   (keep (fn [p]
                           (when-let [c (read-rel p)]
                             (parse-file p c))))
                   vec)
        items (vec (mapcat items-key units))]
    {:units units
     :items (vec (sort-by (juxt :ns :name) items))
     :item_count (count items)
     :file_count (count units)}))

(defn extract-html
  "Render the shared 'structure-lang-box' extract panel: an empty state, or a
  count line plus a list of item blocks rendered by `block-fn`."
  [{:keys [plugin title singular section-title items block-fn count file-count]}]
  (let [n (or count 0)]
    (if (zero? n)
      (str "<div class=\"structure-lang-box structure-lang-empty\" data-plugin=\"" plugin "\">"
           "<div class=\"structure-lang-head\">"
           "<h3>" (html-escape title) "</h3>"
           "<p class=\"muted\">No " (html-escape title) " found in this repository.</p>"
           "</div></div>")
      (str "<div class=\"structure-lang-box\" data-plugin=\"" plugin "\">"
           "<div class=\"structure-lang-head\">"
           "<h3>" (html-escape title) "</h3>"
           "<p class=\"muted\">"
           (html-escape (str n " " (if (= 1 n) singular (str singular "s"))
                             " · " file-count " file" (when (not= 1 file-count) "s")))
           "</p></div>"
           (hiccup/html
            (list [:h4 section-title] (map block-fn items)))
           "</div>"))))
