(ns codestory.structure
  "Deterministic architectural extraction from source (no LLM).
  Per-language scans render as separate plugin boxes."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [codestory.clj-source :as src]
            [codestory.git :as git]
            [codestory.repo :as repo]
            [codestory.source :as source]
            [codestory.structure.parse :as parse]
            [hiccup.core :as hiccup]))

(def ^:private py-exts #{".py"})
(def ^:private js-exts #{".js" ".jsx" ".mjs" ".cjs" ".ts" ".tsx"})
(def ^:private hx-exts #{".hx"})
(def ^:private cpp-exts #{".h" ".hh" ".hpp" ".hxx" ".cpp" ".cc" ".cxx"})

(def ^:private max-diagram-nodes 28)
(def ^:private max-diagram-edges 36)
(def ^:private package-overview-min-units 10)

(def ^:private external-base-names
  "Bases we never draw — stdlib / typing noise that fans out huge diagrams."
  #{"object" "Exception" "BaseException" "type" "Object" "TypedDict" "dict"
    "list" "tuple" "set" "frozenset" "str" "int" "float" "bool" "bytes"
    "Protocol" "Enum" "IntEnum" "Flag" "IntFlag" "ABC" "ABCMeta" "Generic"
    "NamedTuple" "Callable" "Iterable" "Iterator" "Mapping" "Sequence"
    "MutableMapping" "MutableSequence" "Total" "total_False" "total_True"
    "ReactiveHTML" "Parameterized" "Viewer" "Viewable"})

(defn- mermaid-id [s]
  (-> (str s)
      (str/replace #"[^A-Za-z0-9_]" "_")
      (#(if (re-find #"^[0-9]" %) (str "n_" %) %))))

(defn- attr-json [x]
  (-> (json/generate-string x)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- mermaid-block
  "Embed Mermaid source safely for innerHTML (angle brackets must be escaped).
  `diagram` is {:source \"…\" :links [{:id :href}]}; links are attached client-side."
  [title diagram]
  (let [source (:source diagram)
        links (:links diagram)]
    (when-not (str/blank? source)
      (str "<div class=\"structure-diagram\">"
           "<h4>" (src/html-escape title) "</h4>"
           "<pre class=\"mermaid\""
           (when (seq links)
             (str " data-links=\"" (attr-json links) "\""))
           ">" (src/html-escape source) "</pre>"
           "</div>"))))

(defn mermaid-ns-graph
  "Mermaid flowchart of in-repo namespace requires. Caps size for readability.
  Returns {:source \"…\" :links [{:id :href}]} or nil when there is nothing to
  draw. `name->path` maps node names to repo-relative file paths for links."
  [edges name->path]
  (let [edges (vec (take max-diagram-edges edges))
        nodes (sort (set (mapcat (juxt :from :to) edges)))]
    (when (and (seq edges) (<= (count nodes) max-diagram-nodes))
      {:source (str "flowchart LR\n"
                    (->> nodes
                         (map (fn [n]
                                (str "  " (mermaid-id n) "[\"" n "\"]")))
                         (str/join "\n"))
                    "\n"
                    (->> edges
                         (map (fn [{:keys [from to]}]
                                (str "  " (mermaid-id from) " --> " (mermaid-id to))))
                         (str/join "\n")))
       :links (vec (keep (fn [n]
                           (when-let [p (get name->path n)]
                             {:id (mermaid-id n)
                              :href (src/file-href p)}))
                         nodes))})))

(defn- package-key
  "Roll a module owner/path up to a stable package bucket for overview diagrams."
  [unit]
  (let [owner (or (:name unit) "")
        parts (vec (remove str/blank? (str/split owner #"\.")))]
    (cond
      (>= (count parts) 3) (str/join "." (take 3 parts))
      (seq parts) (str/join "." parts)
      :else
      (let [p (-> (or (:path unit) "")
                  (str/replace #"\\" "/")
                  (str/replace #"/[^/]+$" "")
                  (str/replace #"/" "."))]
        (if (str/blank? p) "root" p)))))

(defn- package-dir [pkg]
  (if (= pkg "root") "" (str/replace pkg "." "/")))

(defn- dir-href [path]
  (str "#/dir/" (-> (str path)
                    (java.net.URLEncoder/encode "UTF-8")
                    (str/replace "+" "%20"))))

(defn mermaid-package-overview
  "TB flowchart of packages with class counts — readable for larger repos.
  Returns {:source :links} or nil. Package nodes link to their directory page."
  [units]
  (let [counts (->> units
                    (reduce (fn [m u]
                              (let [pkg (package-key u)
                                    n (count (or (:classes u) (:types u) []))]
                                (update m pkg (fnil + 0) n)))
                            {})
                    (sort-by (fn [[p n]] [(- n) p])))
        ;; Keep the busiest packages so the chart stays scannable
        counts (vec (take max-diagram-nodes counts))]
    (when (>= (count counts) 2)
      {:source (str "flowchart TB\n"
                    (->> counts
                         (map (fn [[p n]]
                                (str "  " (mermaid-id p)
                                     "[\"" p "\\n" n " class" (when (not= 1 n) "es") "\"]")))
                         (str/join "\n")))
       :links (mapv (fn [[p _n]]
                      {:id (mermaid-id p)
                       :href (dir-href (package-dir p))})
                    counts)})))

(defn mermaid-class-diagram
  "Inheritance-only classDiagram for in-repo bases. Skips when too large or empty.
  Returns {:source :links} or nil. Class nodes link to their defining file.

  Drawing every class as an orphan box (old behaviour) is unreadable past ~20
  types; Mermaid then squash-fits the SVG into a useless strip."
  [types]
  (let [type-names (set (map :name types))
        name->path (into {} (map (juxt :name :path) types))
        inherit (->> (for [t types
                           b (:bases t)
                           :when (and b
                                      (contains? type-names b)
                                      (not (contains? external-base-names b)))]
                       {:from (:name t) :to b})
                     distinct
                     vec)
        nodes (sort (set (mapcat (juxt :from :to) inherit)))]
    (when (and (seq inherit)
               (<= (count nodes) max-diagram-nodes)
               (<= (count inherit) max-diagram-edges))
      {:source (str "classDiagram\n"
                    (->> nodes
                         (map (fn [n] (str "  class " (mermaid-id n))))
                         (str/join "\n"))
                    "\n"
                    (->> inherit
                         (map (fn [{:keys [from to]}]
                                (str "  " (mermaid-id to) " <|-- " (mermaid-id from))))
                         (str/join "\n")))
       :links (vec (keep (fn [n]
                           (when-let [p (get name->path n)]
                             {:id (mermaid-id n)
                              :href (src/file-href p)}))
                         nodes))})))

(defn scan-clojure
  ([] (scan-clojure {}))
  ([{:keys [max-depth] :or {max-depth 24}}]
   (let [files (source/source-files src/clj-exts max-depth)
         units (->> files
                    (keep (fn [p]
                            (when-let [c (src/read-rel p)]
                              (parse/parse-clojure p c))))
                    vec)
         ns-names (set (map :name units))
         edges (->> units
                    (mapcat (fn [{:keys [name requires]}]
                              (for [r requires
                                    :when (contains? ns-names r)]
                                {:from name :to r})))
                    distinct
                    vec)
         types (vec
                (for [u units
                      t (:types u)]
                  {:lang "clojure"
                   :kind (name (:kind t))
                   :name (:name t)
                   :owner (:name u)
                   :path (:path u)
                   :bases []}))]
     {:lang "clojure"
      :label "Clojure"
      :units units
      :edges edges
      :types types
      :unit_count (count units)
      :type_count (count types)
      :edge_count (count edges)})))

(defn scan-python
  ([] (scan-python {}))
  ([{:keys [max-depth] :or {max-depth 24}}]
   (let [files (source/source-files py-exts max-depth)
         units (->> files
                    (keep (fn [p]
                            (when-let [c (src/read-rel p)]
                              (parse/parse-python p c))))
                    (remove #(and (empty? (:classes %))
                                  (empty? (:functions %))
                                  (str/ends-with? (:path %) "__init__.py")))
                    vec)
         types (vec
                (for [u units
                      c (:classes u)]
                  {:lang "python"
                   :kind "class"
                   :name (:name c)
                   :bases (:bases c)
                   :owner (:name u)
                   :path (:path u)}))]
     {:lang "python"
      :label "Python"
      :units units
      :edges []
      :types types
      :unit_count (count units)
      :type_count (count types)
      :edge_count 0})))

(defn scan-javascript
  ([] (scan-javascript {}))
  ([{:keys [max-depth] :or {max-depth 24}}]
   (let [files (source/source-files js-exts max-depth)
         units (->> files
                    (keep (fn [p]
                            (when-let [c (src/read-rel p)]
                              (let [u (parse/parse-javascript p c)]
                                (when (or (seq (:classes u)) (seq (:functions u)))
                                  u)))))
                    vec)
         types (vec
                (for [u units
                      c (:classes u)]
                  {:lang "javascript"
                   :kind "class"
                   :name (:name c)
                   :bases []
                   :owner (:name u)
                   :path (:path u)}))]
     {:lang "javascript"
      :label "JavaScript / TypeScript"
      :units units
      :edges []
      :types types
      :unit_count (count units)
      :type_count (count types)
      :edge_count 0})))

(defn scan-haxe
  ([] (scan-haxe {}))
  ([{:keys [max-depth] :or {max-depth 24}}]
   (let [files (git/keep-tracked (source/source-files hx-exts max-depth))
         units (->> files
                    (keep (fn [p]
                            (when-let [c (src/read-rel p)]
                              (parse/parse-haxe p c))))
                    vec)
         type-names (set (mapcat (fn [u] (map :name (:types u))) units))
         edges (->> units
                    (mapcat (fn [{:keys [name imports]}]
                              (for [imp imports
                                    :let [simple (parse/hx-simple-name imp)]
                                    :when (contains? type-names simple)]
                                {:from name :to simple})))
                    distinct
                    vec)
         types (vec
                (for [u units
                      t (:types u)]
                  {:lang "haxe"
                   :kind (:kind t)
                   :name (:name t)
                   :bases (:bases t)
                   :owner (:name u)
                   :path (:path u)}))]
     {:lang "haxe"
      :label "Haxe"
      :units units
      :edges edges
      :types types
      :unit_count (count units)
      :type_count (count types)
      :edge_count (count edges)})))

(defn scan-cpp
  ([] (scan-cpp {}))
  ([{:keys [max-depth] :or {max-depth 24}}]
   (let [files (git/keep-tracked (source/source-files cpp-exts max-depth))
         units (->> files
                    (keep (fn [p]
                            (when-let [c (src/read-rel p)]
                              (parse/parse-cpp p c))))
                    vec)
         unit-names (set (map :name units))
         edges (->> units
                    (mapcat (fn [{:keys [name local-includes]}]
                              (for [inc local-includes
                                    :let [target (parse/cpp-unit-name inc)]
                                    :when (and (not= target name)
                                               (contains? unit-names target))]
                                {:from name :to target})))
                    distinct
                    vec)
         types (vec
                (for [u units
                      t (:types u)]
                  {:lang "cpp"
                   :kind (name (:kind t))
                   :name (:name t)
                   :bases (:bases t)
                   :owner (:name u)
                   :path (:path u)}))]
     {:lang "cpp"
      :label "C/C++"
      :units units
      :edges edges
      :types types
      :unit_count (count units)
      :type_count (count types)
      :edge_count (count edges)})))

(def scanners
  {:clojure scan-clojure
   :python scan-python
   :javascript scan-javascript
   :haxe scan-haxe
   :cpp scan-cpp})

(defn- clj-row [u]
  [:tr
   [:td [:a {:href (src/file-href (:path u))} [:code (:path u)]]]
   [:td [:code (:name u)]]
   [:td (count (:requires u))]
   [:td (count (:types u))]])

(defn- py-row [u]
  [:tr
   [:td [:a {:href (src/file-href (:path u))} [:code (:path u)]]]
   [:td [:code (:name u)]]
   [:td (count (:classes u))]
   [:td (count (:functions u))]])

(defn- js-row [u]
  [:tr
   [:td [:a {:href (src/file-href (:path u))} [:code (:path u)]]]
   [:td (count (:classes u))]
   [:td (count (:functions u))]])

(defn- hx-row [u]
  [:tr
   [:td [:a {:href (src/file-href (:path u))} [:code (:path u)]]]
   [:td [:code (or (:package u) "—")]]
   [:td (count (:types u))]
   [:td (count (:imports u))]])

(defn- cpp-row [u]
  [:tr
   [:td [:a {:href (src/file-href (:path u))} [:code (:path u)]]]
   [:td [:code (:name u)]]
   [:td (count (:includes u))]
   [:td (count (:types u))]])

(defn- type-row [t]
  [:tr
   [:td [:code (:kind t)]]
   [:td [:a {:href (src/file-href (:path t))} [:code (:name t)]]]
   [:td [:code (:owner t)]]
   [:td [:a {:href (src/file-href (:path t))} [:code (:path t)]]]])

(defn- types-table [title summary]
  (when (seq (:types summary))
    (list
     [:h4 title]
     [:table.structure-table
      [:thead [:tr [:th "Kind"] [:th "Name"] [:th "Owner"] [:th "File"]]]
      [:tbody (map type-row (sort-by :name (:types summary)))]])))

(defn- units-table [title headers row-fn sort-key summary]
  (when (seq (:units summary))
    (list
     [:h4 title]
     [:table.structure-table
      [:thead [:tr (map (fn [h] [:th h]) headers)]]
      [:tbody (map row-fn (sort-by sort-key (:units summary)))]])))

(defn- tables-html [lang summary]
  (case lang
    :clojure
    (hiccup/html
     (units-table "Namespaces" ["File" "Namespace" "Requires" "Types"] clj-row :name summary)
     (types-table "Types" summary))

    :python
    (hiccup/html
     (units-table "Modules" ["File" "Module" "Classes" "Functions"] py-row :name summary)
     (types-table "Classes" summary))

    :javascript
    (hiccup/html
     (units-table "Modules" ["File" "Classes" "Functions"] js-row :path summary)
     (types-table "Classes" summary))

    :haxe
    (hiccup/html
     (units-table "Modules" ["File" "Package" "Types" "Imports"] hx-row :path summary)
     (types-table "Types" summary))

    :cpp
    (hiccup/html
     (units-table "Headers / Sources" ["File" "Unit" "Includes" "Types"] cpp-row :name summary)
     (types-table "Classes / Structs" summary))

    ""))

(defn ->html-lang
  "Render one language box. Returns nil when there is nothing to show."
  [summary]
  (when (and summary (pos? (or (:unit_count summary) 0)))
    (let [lang (keyword (:lang summary))
          label (:label summary)
          name->path (into {} (map (juxt :name :path) (:units summary)))
          ns-mmd (when (#{:clojure :haxe :cpp} lang)
                   (mermaid-ns-graph (:edges summary) name->path))
          pkg-mmd (when (and (#{:python :javascript} lang)
                             (>= (or (:unit_count summary) 0) package-overview-min-units))
                    (mermaid-package-overview (:units summary)))
          class-mmd (mermaid-class-diagram (:types summary))
          summary-line
          (case lang
            :clojure (str (:unit_count summary) " namespaces · "
                          (:type_count summary) " types · "
                          (:edge_count summary) " in-repo require edges")
            :python (str (:unit_count summary) " modules · "
                         (:type_count summary) " classes")
            :javascript (str (:unit_count summary) " modules · "
                             (:type_count summary) " classes")
            :haxe (str (:unit_count summary) " modules · "
                       (:type_count summary) " types · "
                       (:edge_count summary) " import edges")
            :cpp (str (:unit_count summary) " files · "
                      (:type_count summary) " classes/structs · "
                      (:edge_count summary) " include links")
            (str (:unit_count summary) " units"))
          diagram-title (case lang
                          :haxe "Import links (in-repo types)"
                          :cpp "Includes (in-repo)"
                          "Namespace requires (in-repo)")
          body (str (mermaid-block "Packages" pkg-mmd)
                    (mermaid-block diagram-title ns-mmd)
                    (mermaid-block "Inheritance (in-repo)" class-mmd)
                    (tables-html lang summary))]
      (str "<div class=\"structure-lang-box\" data-lang=\"" (name lang) "\">"
           "<div class=\"structure-lang-head\">"
           "<h3>" (src/html-escape label) "</h3>"
           "<p class=\"muted\">" (src/html-escape summary-line) "</p>"
           "</div>"
           body
           "</div>"))))

(defn empty-html-lang
  "Muted empty state when a language plugin runs but finds nothing."
  [lang label]
  (str "<div class=\"structure-lang-box structure-lang-empty\" data-lang=\"" (name lang) "\">"
       "<div class=\"structure-lang-head\">"
       "<h3>" (src/html-escape label) "</h3>"
       "<p class=\"muted\">No " (src/html-escape label) " source found in this repository.</p>"
       "</div></div>"))

(defn compute-lang!
  "Scan one language and return a jobs cache payload."
  [lang]
  (let [lang (keyword lang)
        scan-fn (get scanners lang)
        _ (when-not scan-fn
            (throw (ex-info (str "Unknown structure language: " lang) {:lang lang})))
        summary (scan-fn)
        html (or (->html-lang summary)
                 (empty-html-lang lang (:label summary (name lang))))]
    {:html html
     :summary {:lang (name lang)
               :unit_count (:unit_count summary)
               :type_count (:type_count summary)
               :edge_count (:edge_count summary)}
     :data {:lang (name lang)
            :unit_count (:unit_count summary)
            :type_count (:type_count summary)
            :edge_count (:edge_count summary)
            :units (mapv (fn [u] {:name (:name u) :path (:path u)}) (:units summary))
            :types (mapv (fn [t] {:kind (some-> (:kind t) name)
                                  :name (:name t)
                                  :owner (:owner t)
                                  :path (:path t)})
                         (:types summary))
            :edges (mapv #(select-keys % [:from :to]) (:edges summary))}
     :stdout (str "Scanned " (:label summary) ": "
                  (:unit_count summary) " units, "
                  (:type_count summary) " types/classes.")}))

;; ---- backward-compatible helpers used by tests / umbrella ----

(defn scan
  "Scan all known languages. Prefer per-language scan-* functions."
  ([] (scan {}))
  ([opts]
   (let [clojure (scan-clojure opts)
         python (scan-python opts)
         javascript (scan-javascript opts)
         haxe (scan-haxe opts)]
     {:clojure_units (:unit_count clojure)
      :python_units (:unit_count python)
      :javascript_units (:unit_count javascript)
      :haxe_units (:unit_count haxe)
      :type_count (+ (:type_count clojure) (:type_count python)
                     (:type_count javascript) (:type_count haxe))
      :edge_count (+ (:edge_count clojure) (:edge_count haxe))
      :clojure (:units clojure)
      :python (:units python)
      :javascript (:units javascript)
      :haxe (:units haxe)
      :edges (vec (concat (:edges clojure) (:edges haxe)))
      :types (vec (concat (:types clojure) (:types python)
                          (:types javascript) (:types haxe)))})))

(defn mermaid-ns-graph-summary [summary]
  (some-> (mermaid-ns-graph (:edges summary)
                            (into {} (map (juxt :name :path) (:units summary))))
          :source))
