(ns codestory.conventions
  "Project convention template: declared directory / file layout and document
  formats, checked deterministically against the watched repo (no LLM).

  Template precedence (first found wins):
    1. <repo>/.sunlit/template.edn  (emergency per-project override)
    2. ~/.sunlit/template.edn       (the shared standard, installed by scripts/install-local)
    3. resources/template.edn       (bundled fallback on the classpath)

  Template shape (EDN):
    {:title        \"Standard project layout\"
     :directories [{:path \"src\"    :required true  :description \"…\"}]
     :files       [{:path \"README.md\" :required true :description \"…\"}]
     :gitignores  [{:pattern \".sunlit/cache/\" :required true :description \"...\"}]
     :formats     [{:name \"issue\"
                    :match \"issues/*.md\"       ; glob
                    :tracker? true               ; also render as list
                    :description \"…\"
                    :fields [{:key \"status\" :required true :enum [\"open\" \"closed\"]}]}]}"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [codestory.clj-source :as src]
            [codestory.repo :as repo]
            [hiccup.core :as hiccup]))

;; ---------------------------------------------------------------------------
;; Template loading
;; ---------------------------------------------------------------------------

(defn user-template-file []
  (io/file (System/getProperty "user.home") ".sunlit" "template.edn"))

(defn repo-template-file []
  (repo/sidecar-file "template.edn"))

(defn- read-template-file [f]
  (when (and f (.isFile f))
    (try
      (let [data (edn/read-string (slurp f))]
        (when (map? data) data))
      (catch Exception _
        nil))))

(defn resource-template
  "Load the bundled resources/template.edn from the classpath. Returns nil when
  unavailable or unreadable."
  []
  (when-let [r (io/resource "template.edn")]
    (try
      (let [data (edn/read-string (slurp r))]
        (when (map? data) data))
      (catch Exception _
        nil))))

(defn load-template
  "Resolve the active convention template: repo-local override, then the shared
  ~/.sunlit/template.edn, then the bundled resource. Returns {} when none found."
  []
  (or (read-template-file (repo-template-file))
      (read-template-file (user-template-file))
      (resource-template)
      {}))

;; ---------------------------------------------------------------------------
;; Front-matter parsing (minimal YAML subset: key: value and [a, b] lists)
;; ---------------------------------------------------------------------------

(defn- strip-quotes [s]
  (let [s (str s)]
    (if (and (>= (count s) 2)
             (str/starts-with? s "\"")
             (str/ends-with? s "\""))
      (subs s 1 (dec (count s)))
      s)))

(defn- parse-value [v]
  (let [v (str/trim (str v))]
    (cond
      (str/blank? v) nil

      ;; YAML list [a, b] (also handles [])
      (and (str/starts-with? v "[") (str/ends-with? v "]"))
      (let [inner (subs v 1 (dec (count v)))]
        (->> (str/split inner #",")
             (map str/trim)
             (remove str/blank?)
             (map strip-quotes)
             vec))

      ;; integer scalars (e.g. id: 8)
      (re-matches #"-?\d+" v)
      (Long/parseLong v)

      :else (strip-quotes v))))

(defn- parse-meta-lines [lines]
  (reduce (fn [m line]
            (let [line (str/trimr line)]
              (if-let [[_ k v] (re-find #"^\s*([A-Za-z_][\w.-]*)\s*:\s*(.*)$" line)]
                (assoc m (str/lower-case k) (parse-value v))
                m)))
          {}
          lines))

(defn parse-front-matter
  "Split a leading `--- … ---` front-matter block from markdown content.
  Returns {:metadata {lower-key value} :body remaining-markdown}. When there is
  no front-matter, returns {:metadata {} :body content}."
  [content]
  (let [content (-> (or content "")
                    (str/replace "\r\n" "\n")
                    (str/replace "\r" "\n"))]
    (if (and (str/starts-with? content "---")
             (str/includes? content "\n"))
      (let [lines (str/split-lines content)
            after-open (rest lines)
            [meta-lines after-close] (split-with #(not= (str/trim %) "---") after-open)]
        (if (seq after-close)
          {:metadata (parse-meta-lines meta-lines)
           :body (str/join "\n" (rest after-close))}
          {:metadata {} :body content}))
      {:metadata {} :body content})))

;; ---------------------------------------------------------------------------
;; Glob matching (for :match patterns)
;; ---------------------------------------------------------------------------

(def ^:private regex-specials
  (set ".^$|+()[]{}\\"))

(defn glob->pattern
  "Compile a glob to a regex string. `*` matches within a path segment,
  `**` crosses separators, `?` matches one non-separator char."
  [glob]
  (let [g (-> (str glob) (str/replace "\\" "/"))
        n (count g)
        sb (StringBuilder.)]
    (loop [i 0]
      (if (>= i n)
        (.toString sb)
        (let [ch (.charAt g i)
              nxt (when (< (inc i) n) (.charAt g (inc i)))]
          (cond
            (and (= ch \*) (= nxt \*))
            (do (.append sb ".*") (recur (+ i 2)))

            (= ch \*)
            (do (.append sb "[^/]*") (recur (inc i)))

            (= ch \?)
            (do (.append sb "[^/]") (recur (inc i)))

            :else
            (do
              (when (contains? regex-specials ch)
                (.append sb \\))
              (.append sb ch)
              (recur (inc i)))))))))

(defn match-glob?
  "True when a repo-relative path matches the glob."
  [glob path]
  (let [p (-> (str path) (str/replace "\\" "/"))]
    (boolean (re-matches (re-pattern (glob->pattern glob)) p))))

;; ---------------------------------------------------------------------------
;; Conformance check
;; ---------------------------------------------------------------------------

(defn- filename-stem [path]
  (-> (str path)
      (str/replace "\\" "/")
      (str/split #"/")
      last
      (str/replace #"\.[^.]+$" "")))

(defn- first-heading [body]
  (when-let [m (re-find #"(?m)^#{1,6}\s+(.+?)\s*$" (or body ""))]
    (str/trim (second m))))

(defn- check-entry [entry present?]
  (let [required? (boolean (:required entry))]
    (assoc entry
           :present? present?
           :status (cond
                     present? :present
                     required? :missing
                     :else :optional-missing))))

(defn check-directory [spec]
  (let [f (try (repo/safe-resolve (:path spec)) (catch Exception _ nil))]
    (check-entry spec (and f (.isDirectory f)))))

(defn check-file [spec]
  (let [f (try (repo/safe-resolve (:path spec)) (catch Exception _ nil))]
    (check-entry spec (and f (.isFile f)))))

(defn- gitignore-file []
  (io/file (repo/repo-root) ".gitignore"))

(defn- gitignore-lines
  "Non-blank, non-comment lines of the repo's .gitignore (trimmed, CRLF-safe).
  Returns [] when there is no .gitignore."
  []
  (if (.isFile (gitignore-file))
    (->> (slurp (gitignore-file))
         (#(str/replace % "\r\n" "\n"))
         (#(str/replace % "\r" "\n"))
         str/split-lines
         (map str/trim)
         (remove #(or (str/blank? %) (str/starts-with? % "#"))))
    []))

(defn check-gitignore [spec]
  (let [lines (gitignore-lines)]
    (check-entry spec (boolean (some #(= % (:pattern spec)) lines)))))

(defn- basename [path]
  (-> (str path) (str/replace "\\" "/") (str/split #"/") last))

(defn- numeric-id [path]
  (when-let [m (re-find #"^(\d+)-" (or (basename path) ""))]
    (Long/parseLong (second m))))

(defn- field-problem [field metadata]
  (let [{:keys [key required enum multiple pattern]} field
        k (str/lower-case (str key))
        v (get metadata k)]
    (cond
      (and required (nil? v))
      {:key key :problem :missing}

      (and v enum multiple
           (seq (remove #(contains? (set enum) %) v)))
      {:key key :problem :invalid :value v :allowed enum}

      (and v enum (not multiple) (not (contains? (set enum) v)))
      {:key key :problem :invalid :value v :allowed enum}

      (and v pattern (not (re-find (re-pattern pattern) (str v))))
      {:key key :problem :invalid :value v :pattern pattern}

      :else nil)))

(defn validate-format-file
  "Validate one file against a format spec. Returns {:path :name :title :id
  :metadata :status :labels :created :closed :valid? :problems}."
  [format-spec path]
  (let [content (try (:content (repo/read-file path)) (catch Exception _ nil))
        {:keys [metadata body]} (parse-front-matter content)
        name (basename path)
        file-id (numeric-id path)
        id (get metadata "id")
        field-problems (vec (keep #(field-problem % metadata) (:fields format-spec)))
        filename-problem
        (when (and (:filename format-spec)
                   (not (re-find (re-pattern (:filename format-spec)) name)))
          {:key "filename" :problem :invalid
           :value name :pattern (:filename format-spec)})
        id-problem
        (when (and (some? id) (some? file-id) (not= (long id) file-id))
          {:key "id" :problem :mismatch :value id :filename-id file-id})
        problems (cond-> field-problems
                   filename-problem (conj filename-problem)
                   id-problem (conj id-problem))]
    {:path path
     :name name
     :title (or (get metadata "title") (first-heading body) (filename-stem path))
     :id id
     :metadata metadata
     :status (get metadata "status")
     :labels (let [l (get metadata "labels")] (cond (vector? l) l (some? l) [l] :else []))
     :created (get metadata "created")
     :closed (get metadata "closed")
     :valid? (empty? problems)
     :problems problems}))

(defn check-format [spec]
  (let [matched (filter #(match-glob? (:match spec) %) (repo/list-project-files))
        files (mapv #(validate-format-file spec %) matched)
        valid (filterv :valid? files)
        invalid (remove :valid? files)]
    {:name (:name spec)
     :description (:description spec)
     :match (:match spec)
     :tracker? (boolean (:tracker? spec))
     :matched_count (count files)
     :valid_count (count valid)
     :invalid_count (count invalid)
     :files files
     :invalid invalid}))

(defn issues-list
  "Derived lightweight issue tracker from the first format flagged :tracker?.
  The label vocabulary is derived from the issues present (not predefined), so
  each project's categories emerge naturally. Add :enum to the labels field in
  a per-project template to enforce a fixed vocabulary instead."
  [formats]
  (let [format (first (filter :tracker? formats))
        files (or (:files format) [])
        open (filterv #(= "open" (some-> (:status %) str/lower-case)) files)
        closed (filterv #(= "closed" (some-> (:status %) str/lower-case)) files)
        label-counts (->> files
                          (mapcat :labels)
                          frequencies
                          (sort-by (fn [[label n]] [(- n) label]))
                          vec)]
    (when format
      {:name (:name format)
       :match (:match format)
       :total (count files)
       :open (count open)
       :closed (count closed)
       :labels label-counts
       :items (->> files
                   (sort-by (fn [f]
                              [(if (= "open" (some-> (:status f) str/lower-case)) 0 1)
                               (let [n (:id f)] (if (number? n) n Long/MAX_VALUE))
                               (str/lower-case (or (:title f) (:path f)))])))})))

(defn- mushroom-info []
  (let [f (io/file (repo/repo-root) "MUSHROOM.edn")]
    (when (.isFile f)
      (try
        (let [data (edn/read-string (slurp f))]
          (when (map? data) data))
        (catch Exception _
          nil)))))

(defn check
  "Run the conformance check against the current repo root.
  With no args, resolves the active template; pass a template map to check
  against an explicit template (used by tests)."
  ([] (check (load-template)))
  ([tpl]
   (let [directories (mapv check-directory (:directories tpl))
         files (mapv check-file (:files tpl))
         formats (mapv check-format (:formats tpl))
         gitignores (mapv check-gitignore (:gitignores tpl))
         report {:title (:title tpl)
                 :directories directories
                 :files files
                 :formats formats
                 :gitignores gitignores
                 :issues (issues-list formats)
                 :mushroom (mushroom-info)}]
     (assoc report
            :ok? (and (every? #(= :present (:status %)) (filter :required directories))
                      (every? #(= :present (:status %)) (filter :required files))
                      (every? #(= :present (:status %)) (filter :required gitignores))
                      (every? #(zero? (:invalid_count %)) formats))
            :counts {:directories_required (count (filter :required directories))
                     :directories_present (count (filter #(and (:required %) (= :present (:status %))) directories))
                     :files_required (count (filter :required files))
                     :files_present (count (filter #(and (:required %) (= :present (:status %))) files))
                     :gitignores_required (count (filter :required gitignores))
                     :gitignores_present (count (filter #(and (:required %) (= :present (:status %))) gitignores))}))))

;; ---------------------------------------------------------------------------
;; HTML rendering
;; ---------------------------------------------------------------------------

(defn- dir-href [path]
  (str "#/dir/" (-> (str path)
                    (java.net.URLEncoder/encode "UTF-8")
                    (str/replace "+" "%20"))))

(defn- check-mark [present?]
  (if present? "✓" "✗"))

(defn- directories-table [entries]
  [:table.conv-table
   [:thead [:tr [:th "Status"] [:th "Directory"] [:th "Required"] [:th "Notes"]]]
   [:tbody
    (for [e entries]
      [:tr {:class (str "row-" (name (:status e)))}
       [:td.check (check-mark (:present? e))]
       [:td (if (:present? e)
              [:a {:href (dir-href (:path e))} [:code (:path e)]]
              [:code (:path e)])]
       [:td (if (:required e) "yes" "optional")]
       [:td.muted (or (:description e) "")]])]])

(defn- files-table [entries]
  [:table.conv-table
   [:thead [:tr [:th "Status"] [:th "File"] [:th "Required"] [:th "Notes"]]]
   [:tbody
    (for [e entries]
      [:tr {:class (str "row-" (name (:status e)))}
       [:td.check (check-mark (:present? e))]
       [:td [:a {:href (src/file-href (:path e))} [:code (:path e)]]]
       [:td (if (:required e) "yes" "optional")]
       [:td.muted (or (:description e) "")]])]])

(defn- gitignores-table [entries]
  [:table.conv-table
   [:thead [:tr [:th "Status"] [:th "Gitignore pattern"] [:th "Required"] [:th "Notes"]]]
   [:tbody
    (for [e entries]
      [:tr {:class (str "row-" (name (:status e)))}
       [:td.check (check-mark (:present? e))]
       [:td [:code (:pattern e)]]
       [:td (if (:required e) "yes" "optional")]
       [:td.muted (or (:description e) "")]])]])

(defn- problem-text [problem]
  (case (:problem problem)
    :missing (str "missing " (name (:key problem)))
    :mismatch (str (name (:key problem)) " is " (:value problem)
                   " but filename says " (:filename-id problem))
    :invalid (str (name (:key problem)) " invalid (got " (pr-str (:value problem)) ")")
    (pr-str problem)))

(defn- format-section [f]
  (let [invalid (:invalid f)]
    [:div.conv-format
     [:div.conv-format-head
      [:h4 [:code (:name f)]]
      [:span.muted (str (:match f) " — " (:valid_count f) " valid · "
                        (:invalid_count f) " invalid · "
                        (:matched_count f) " matched")]]
     (when (seq invalid)
       [:ul.conv-problems
        (for [file invalid]
          [:li
           [:a {:href (src/file-href (:path file))} [:code (:path file)]]
           " "
           [:span.muted (str/join ", " (map problem-text (:problems file)))]])])]))

(defn- issue-row [f]
  (let [status (or (:status f) "—")
        status-class (str "issue-status " (some-> (:status f) str/lower-case))]
    [:tr
     [:td [:span {:class status-class} status]]
     [:td (if (some? (:id f)) (:id f) "—")]
     [:td [:a {:href (src/file-href (:path f))} (or (:title f) (:path f))]]
     [:td (str/join ", " (or (:labels f) []))]]))

(defn- issues-section [issues]
  (let [items (:items issues)]
    [:div.conv-format
     [:div.conv-format-head
      [:h4 "Issues"]
      [:span.muted (str (:open issues) " open · " (:closed issues) " closed")]]
     (when (seq (:labels issues))
       [:p.muted
        "Labels: "
        (str/join ", " (map (fn [[label n]] (str label " (" n ")")) (:labels issues)))])
     (if (empty? items)
       [:p.muted (str "No issue files found under " (:match issues)
                      ". Create e.g. issues/0001-short-slug.md with YAML front matter.")]
       [:table.conv-table
        [:thead [:tr [:th "Status"] [:th "#"] [:th "Title"] [:th "Labels"]]]
        [:tbody
         (map issue-row items)]])]))

(defn ->html
  "Render the conformance report + issues tracker as one HTML string."
  [report]
  (hiccup/html
   [:div.conv-report
    [:div.conv-summary
     [:span {:class (str "conv-verdict " (if (:ok? report) "ok" "bad"))}
      (if (:ok? report) "Conventions satisfied" "Conventions not fully satisfied")]
     (let [c (:counts report)]
       [:span.muted
        (str (:directories_present c) "/" (:directories_required c)
             " required directories · "
             (:files_present c) "/" (:files_required c) " required files")])]
    (when-let [m (:mushroom report)]
      [:div.conv-mushroom
       [:span.muted "Project: "]
       [:code (or (:full_name m) (:id m) "?")]
       (when (:desc m) [:span.muted (str " — " (:desc m))])])
    [:h3 "Directories"]
    (directories-table (:directories report))
    [:h3 "Files"]
    (files-table (:files report))
    (when (seq (:gitignores report))
      (list
       [:h3 "Gitignore"]
       (gitignores-table (:gitignores report))))
    (when (seq (:formats report))
      (list
       [:h3 "Document formats"]
       (for [f (:formats report)]
         (format-section f))))
    (when-let [issues (:issues report)]
      (issues-section issues))]))
