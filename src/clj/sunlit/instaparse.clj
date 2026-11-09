(ns sunlit.instaparse
  "Deterministic extract of Instaparse grammars from Clojure source (no eval)."
  (:require [clojure.string :as str]
            [sunlit.clj-source :as src]))

(def ^:private ebnf-rule-re
  #"(?m)^\s*<?[A-Za-z_][\w\-']*>?\s*=")

(defn- insta-symbol?
  "True for parser / defparser symbols, ideally from an instaparse ns."
  [sym]
  (when (symbol? sym)
    (let [n (name sym)
          ns (namespace sym)]
      (and (#{"parser" "defparser"} n)
           (or (nil? ns)
               (str/includes? (str/lower-case (str ns)) "insta"))))))

(defn ebnf-string?
  "Heuristic: string looks like an Instaparse / EBNF grammar body."
  [s]
  (and (string? s)
       (re-find ebnf-rule-re s)
       ;; Prefer multi-token grammars; single '=' line is enough if it has a rule
       (or (str/includes? s "\n")
           (re-find #"[A-Za-z_][\w\-']*\s*=" s))))

(defn- first-string-arg [args]
  (first (filter string? args)))

(defn- extract-from-defparser [form]
  (when (and (seq? form) (insta-symbol? (first form)) (= "defparser" (name (first form))))
    (let [name (second form)
          grammar (first-string-arg (nnext form))]
      (when (and (symbol? name) (ebnf-string? grammar))
        {:name (str name)
         :kind "defparser"
         :grammar grammar}))))

(defn- extract-from-parser-call [form]
  (when (and (seq? form) (insta-symbol? (first form)) (= "parser" (name (first form))))
    (let [grammar (first-string-arg (rest form))]
      (when (ebnf-string? grammar)
        {:kind "parser"
         :grammar grammar}))))

(defn- extract-from-def [form]
  (when (and (seq? form) (= 'def (first form)) (symbol? (second form)))
    (let [name (str (second form))
          body (last form)]
      (or (when-let [p (extract-from-parser-call body)]
            (assoc p :name name :kind "def+parser"))
          (when (and (ebnf-string? body)
                     (or (str/includes? (str/lower-case name) "grammar")
                         (str/includes? (str/lower-case name) "parser")))
            {:name name
             :kind "def"
             :grammar body})))))

(defn extract-grammars-from-forms
  "Return grammar maps {:name :kind :grammar} from top-level forms."
  [forms]
  (->> forms
       (keep (fn [form]
               (or (extract-from-defparser form)
                   (extract-from-def form))))
       vec))

(defn parse-file
  "Extract Instaparse grammars from one source file. Nil when none found."
  [path content]
  (let [forms (src/read-all-forms content)
        ns-name (src/ns-name-from-forms forms)
        grammars (extract-grammars-from-forms forms)]
    (when (seq grammars)
      {:path path
       :ns ns-name
       :grammars (mapv #(assoc % :path path :ns ns-name) grammars)})))

(defn scan
  "Scan src/ for Instaparse grammars."
  ([] (scan {}))
  ([opts]
   (let [{:keys [units items item_count file_count]}
         (src/scan-clj {:parse-file parse-file :items-key :grammars} opts)]
     {:grammar_count item_count
      :file_count file_count
      :grammars items
      :units units})))

(defn- grammar-block [g]
  [:div.extract-item
   [:div.extract-item-head
    [:code.extract-name (:name g)]
    [:span.meta
     " · " [:span.extract-kind (:kind g)]
     " · "
     [:a {:href (src/file-href (:path g))} [:code (:path g)]]]
    (when (:ns g)
      [:span.meta " · " [:code (:ns g)]])]
   [:pre.extract-source [:code (:grammar g)]]])

(defn ->html
  "HTML panel body for a scan summary."
  [summary]
  (src/extract-html
   {:plugin "instaparse"
    :title "Instaparse grammars"
    :singular "grammar"
    :section-title "Grammars"
    :items (:grammars summary)
    :block-fn grammar-block
    :count (:grammar_count summary)
    :file-count (:file_count summary)}))

(defn compute!
  "Scan and return a jobs cache payload."
  []
  (let [summary (scan)
        html (->html summary)]
    {:html html
     :summary {:grammar_count (:grammar_count summary)
               :file_count (:file_count summary)}
     :data {:grammars (mapv #(select-keys % [:name :kind :path :ns :grammar])
                            (:grammars summary))
            :grammar_count (:grammar_count summary)
            :file_count (:file_count summary)}
     :stdout (str "Instaparse scan: " (:grammar_count summary) " grammars in "
                  (:file_count summary) " files.")}))
