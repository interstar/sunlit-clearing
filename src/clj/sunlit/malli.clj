(ns sunlit.malli
  "Deterministic extract of Malli schemas from Clojure source (no eval)."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [sunlit.clj-source :as src]))

(def ^:private malli-ops
  "Entry keywords commonly used as Malli schema heads."
  #{:map :map-of :vector :sequential :set :tuple :maybe :or :and :orn :andn
    :alt :altn :cat :catn :? :* :+ :repeat :schema :ref :=> :-> :function
    :multi :enum :fn :not :merge :union :intersection :any :nil :string :int
    :double :float :boolean :keyword :symbol :uuid :inst :qualified-keyword
    :qualified-symbol})

(defn- malli-schema-form?
  "Heuristic: does this value look like a Malli schema expression?"
  [form]
  (cond
    (contains? malli-ops form) true

    (and (vector? form) (seq form))
    (let [head (first form)]
      (or (contains? malli-ops head)
          (string? head) ; custom registry / class names
          (qualified-keyword? head)))

    (and (seq? form) (symbol? (first form)))
    (let [op (first form)]
      ;; m/schema, malli.core/schema, schema, …
      (and (= "schema" (name op))
           (or (nil? (next form))
               (malli-schema-form? (second form))
               (vector? (second form))
               (keyword? (second form)))))

    :else false))

(defn- unwrap-schema-call [form]
  (if (and (seq? form)
           (symbol? (first form))
           (= "schema" (name (first form)))
           (malli-schema-form? (second form)))
    (second form)
    form))

(defn- defschema-call? [form]
  (and (seq? form)
       (symbol? (first form))
       (#{"defschema" "defclass"} (name (first form)))))

(defn- extract-from-def [form]
  (when (and (seq? form) (= 'def (first form)) (symbol? (second form)))
    (let [name (second form)
          body (last form)
          schema? (or (malli-schema-form? body)
                      (and (str/includes? (str/lower-case (str name)) "schema")
                           (or (vector? body) (keyword? body))))]
      (when schema?
        {:name (str name)
         :kind "def"
         :form (unwrap-schema-call body)}))))

(defn- extract-from-defschema [form]
  (when (defschema-call? form)
    (let [name (second form)
          body (last form)]
      (when (symbol? name)
        {:name (str name)
         :kind (name (first form))
         :form (unwrap-schema-call body)}))))

(defn extract-schemas-from-forms
  "Return schema maps {:name :kind :form} from top-level forms."
  [forms]
  (->> forms
       (keep (fn [form]
               (or (extract-from-def form)
                   (extract-from-defschema form))))
       vec))

(defn- pprint-form [form]
  (str/trimr
   (with-out-str
     (binding [*print-length* nil
               *print-level* nil]
       (pprint/pprint form)))))

(defn parse-file
  "Extract Malli schemas from one source file. Nil when none found."
  [path content]
  (let [forms (src/read-all-forms content)
        ns-name (src/ns-name-from-forms forms)
        schemas (extract-schemas-from-forms forms)]
    (when (seq schemas)
      {:path path
       :ns ns-name
       :schemas (mapv (fn [s]
                        (assoc s
                               :path path
                               :ns ns-name
                               :source (pprint-form (:form s))))
                      schemas)})))

(defn scan
  "Scan src/ for Malli schemas."
  ([] (scan {}))
  ([opts]
   (let [{:keys [units items item_count file_count]}
         (src/scan-clj {:parse-file parse-file :items-key :schemas} opts)]
     {:schema_count item_count
      :file_count file_count
      :schemas items
      :units units})))

(defn- schema-block [s]
  [:div.extract-item
   [:div.extract-item-head
    [:code.extract-name (:name s)]
    [:span.meta
     " · "
     [:a {:href (src/file-href (:path s))} [:code (:path s)]]]
    (when (:ns s)
      [:span.meta " · " [:code (:ns s)]])]
   [:pre.extract-source [:code (:source s)]]])

(defn ->html
  "HTML panel body for a scan summary."
  [summary]
  (src/extract-html
   {:plugin "malli"
    :title "Malli schemas"
    :singular "schema"
    :section-title "Schemas"
    :items (:schemas summary)
    :block-fn schema-block
    :count (:schema_count summary)
    :file-count (:file_count summary)}))

(defn compute!
  "Scan and return a jobs cache payload."
  []
  (let [summary (scan)
        html (->html summary)]
    {:html html
     :summary {:schema_count (:schema_count summary)
               :file_count (:file_count summary)}
     :data {:schemas (mapv #(select-keys % [:name :kind :path :ns :source])
                           (:schemas summary))
            :schema_count (:schema_count summary)
            :file_count (:file_count summary)}
     :stdout (str "Malli scan: " (:schema_count summary) " schemas in "
                  (:file_count summary) " files.")}))
