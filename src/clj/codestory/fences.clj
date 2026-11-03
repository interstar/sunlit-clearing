(ns codestory.fences
  "Write-back of plugin fence params.

  Plugins never modify the watched repository, but a plugin may rewrite the
  EDN body of its *own* ```plugin fence in the sidecar (a wiki page or a file
  annotation). A plugin opts in by providing `:update (fn [params action])`,
  which returns the new params map.

  Documents are addressed by the same id the renderer uses as plugin context:
  \"page:<Name>\" for wiki pages, otherwise a repo-relative path whose
  annotation holds the fence. Fences are addressed by their 0-based index
  among all ```plugin fences in that document."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [codestory.annotations :as ann]
            [codestory.pages :as pages]
            [codestory.plugins :as plugins]
            [codestory.render :as render]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as parser]
            [rewrite-clj.zip :as z]))

;; ---------------------------------------------------------------------------
;; EDN formatting

(def ^:private preferred-key-order
  [:title :dims :rows :cols :pattern :manual])

(defn- ordered-keys [m]
  (let [ks (set (keys m))
        front (filter ks preferred-key-order)
        rest-ks (sort-by str (remove (set preferred-key-order) ks))]
    (concat front rest-ks)))

(defn- format-val
  "pr-str, but maps and vectors of collections go one entry per line,
  aligned under the opening bracket at column `col`."
  [v col]
  (let [pad (str "\n" (apply str (repeat (inc col) \space)))]
    (cond
      (and (map? v) (> (count v) 1))
      (str "{"
           (str/join pad
                     (for [[k x] v
                           :let [ks (pr-str k)]]
                       (str ks " " (format-val x (+ col 1 (count ks) 1)))))
           "}")

      (and (vector? v) (seq v) (every? coll? v))
      (str "[" (str/join pad (map pr-str v)) "]")

      :else (pr-str v))))

(defn format-params
  "Readable EDN for a fresh fence body: one top-level key per line."
  [params]
  (if (empty? params)
    "{}"
    (str "{"
         (str/join "\n "
                   (for [k (ordered-keys params)
                         :let [ks (pr-str k)]]
                     (str ks " " (format-val (get params k) (+ 1 (count ks) 1)))))
         "}")))

;; ---------------------------------------------------------------------------
;; Structure-preserving rewrite

(defn- key-loc
  "Zipper loc of key `k` among the top-level entries of map loc `zmap`."
  [zmap k]
  (loop [loc (z/down zmap)]
    (when loc
      (if (= k (z/sexpr loc))
        loc
        (recur (some-> loc z/right z/right))))))

(defn- value-node [v key-col key-len]
  ;; key-col is 1-based; the value starts after "<key> "
  (parser/parse-string (format-val v (+ (dec key-col) key-len 1))))

(defn- comparable
  "Regex literals compare by identity; compare them by source instead."
  [x]
  (walk/postwalk #(if (instance? java.util.regex.Pattern %) [::regex (str %)] %) x))

(defn- reparse [loc]
  (z/of-string (z/root-string loc) {:track-position? true}))

(defn rewrite-body
  "Turn fence body `body` (EDN for `old`) into EDN for `new`, rewriting only
  the top-level entries that changed, so formatting, comments and inline
  code elsewhere in the fence survive. Falls back to `format-params` when
  the body is not a single map."
  [body old new]
  (let [zmap (try (z/of-string (or body "") {:track-position? true})
                  (catch Exception _ nil))]
    (if-not (and zmap (= :map (z/tag zmap)))
      (format-params new)
      (let [changed (filter #(not= (comparable (get old %)) (comparable (get new %)))
                            (distinct (concat (keys old) (keys new))))
            zmap (reduce
                  (fn [zmap k]
                    (let [kl (key-loc zmap k)]
                      (cond
                        ;; removed: drop value then key (and the whitespace before them)
                        (and kl (not (contains? new k)))
                        (-> kl z/right z/remove reparse
                            (key-loc k) z/remove reparse)

                        ;; changed: replace just the value node
                        kl
                        (let [[_ col] (z/position kl)]
                          (-> kl z/right
                              (z/replace (value-node (get new k) col (count (pr-str k))))
                              reparse))

                        ;; added: new line at the end of the map
                        :else
                        (-> zmap
                            (z/append-child* (n/newlines 1))
                            (z/append-child* (n/spaces 1))
                            (z/append-child* (n/token-node k))
                            (z/append-child* (n/spaces 1))
                            (z/append-child* (value-node (get new k) 2 (count (pr-str k))))
                            reparse))))
                  zmap
                  changed)]
        ;; belt and braces: never write something that reads back differently
        (let [out (z/root-string zmap)]
          (if (= (comparable new) (comparable (render/parse-plugin-params out)))
            out
            (format-params new)))))))

;; ---------------------------------------------------------------------------
;; Documents

(defn- page-doc? [doc] (str/starts-with? (str doc) "page:"))

(defn read-doc
  "Markdown of the sidecar document `doc` (\"\" when missing)."
  [doc]
  (if (page-doc? doc)
    (:markdown (pages/read-page (subs doc 5)))
    (:markdown (ann/read-annotation doc))))

(defn- write-doc! [doc markdown]
  (if (page-doc? doc)
    (pages/write-page! (subs doc 5) markdown)
    (ann/write-annotation! doc markdown)))

(defn nth-fence
  "{:name :body :start :end} for fence `index` (body span), or nil."
  [markdown index]
  (let [m (re-matcher render/plugin-fence-re (or markdown ""))]
    (loop [i 0]
      (when (.find m)
        (if (= i index)
          {:name (.group m 1)
           :body (.group m 2)
           :start (.start m 2)
           :end (.end m 2)}
          (recur (inc i)))))))

(defn replace-fence-body
  "Replace the EDN body of fence `index` so it reads as `params`, keeping
  the original text of unchanged entries."
  [markdown index params]
  (if-let [{:keys [start end body]} (nth-fence markdown index)]
    (let [old (render/parse-plugin-params body)
          new-body (str/trimr (rewrite-body (str/trim body) old params))]
      (str (subs markdown 0 start) new-body "\n" (subs markdown end)))
    (throw (ex-info "Plugin fence not found" {:index index}))))

(defn update-fence!
  "Apply plugin `pname`'s :update fn to fence `index` of `doc` and save.
  Returns {:ok true :params new-params :markdown md}."
  [doc index pname action]
  (let [plugin (plugins/get-plugin pname)
        update-fn (:update plugin)
        md (read-doc doc)
        fence (nth-fence md index)]
    (cond
      (nil? plugin) (throw (ex-info (str "Unknown plugin: " pname) {}))
      (nil? update-fn) (throw (ex-info (str "Plugin cannot edit its fence: " pname) {}))
      (nil? fence) (throw (ex-info "Plugin fence not found" {:doc doc :index index}))
      (not= (:name fence) (str pname))
      (throw (ex-info "Fence belongs to a different plugin — reload the page"
                      {:expected pname :found (:name fence)}))
      :else
      (let [params (render/parse-plugin-params (:body fence))
            _ (when (:raw params)
                (throw (ex-info "Fence body is not valid EDN; fix it by hand first" {})))
            new-params (update-fn params action)
            new-md (replace-fence-body md index new-params)]
        (write-doc! doc new-md)
        {:ok true :params new-params :markdown new-md}))))
