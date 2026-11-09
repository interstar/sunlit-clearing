(ns codestory.plugins.matrix
  "Built-in plugin: a grid for matrix-shaped problems (types × operations,
  apps × targets × concerns …). Each cell says where that combination is
  implemented.

  ```plugin matrix
  {:title \"Shapes\"
   :dims {:type [\"circle\" \"square\"]
          :operation [\"area\" \"draw\"]}
   :pattern \"handlers/{type}/{operation}.clj\"
   :manual [[\"circle\" \"draw\" \"src/render.clj#draw-circle\"]
            [\"square\" \"draw\" :n/a]]}
  ```

  - `:dims`    ordered map (or vector of pairs) of dimension -> values;
               `:auto` derives the values from files matching `:pattern`.
  - `:pattern` string or vector of strings tried in order. `{dim}` binds a
               dimension, `*` / `**` are globs, and `#anchor` (which may use
               `{dim}` too) points at a symbol or line inside the file.
               A pattern binding only some dims (e.g. a `default/{operation}`
               file) fills cells as \"inherited\".
  - `:check`   user code deciding each cell, for criteria spread over many
               files: an inline `(fn [coords] …)` or a path to a file under
               .sunlit/ (e.g. \"checks/shapes.clj\") whose last form is the
               fn. `coords` is `{:type \"circle\" …}`. Return `{:status :ok}`
               or `{:status :missing :warn \"…\"}`, optionally with
               `:links [\"path#anchor\" …]` and `:note`; true/false/nil also
               work. Runs in SCI with read-only repo helpers
               (codestory.sandbox). Beats :pattern, loses to :manual.
  - `:manual`  `[coord1 coord2 … value]` per cell, coords in `:dims` order;
               value is a link string, a status keyword (`:n/a`, `:todo`,
               `:done`), or `{:link :status :note}`. Manual beats pattern.
  - `:rows` / `:cols` choose the axes; remaining dims become slices (tabs).

  The grid UI edits `:manual`, `:rows` and `:cols` in the fence itself
  (see codestory.fences); the repository is never modified."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [codestory.locate :as locate]
            [codestory.repo :as repo]
            [codestory.sandbox :as sandbox]
            [hiccup.core :as hiccup :refer [h]])
  (:import [java.util.regex Pattern]))

;; ---------------------------------------------------------------------------
;; Config normalisation

(defn- ->str
  "Name-ish string for keywords (keeping any namespace: :n/a -> \"n/a\")."
  [x]
  (cond
    (nil? x) nil
    (keyword? x) (subs (str x) 1)
    :else (str x)))

(defn- dim-pairs [d]
  (cond
    (map? d) (seq d)
    (and (sequential? d) (every? #(and (sequential? %) (= 2 (count %))) d)) d
    (sequential? d) (partition 2 d)
    :else nil))

(defn dims
  "[{:name \"type\" :values [\"circle\" …]}] in declaration order.
  :values is nil for an :auto dimension."
  [params]
  (vec (for [[k v] (dim-pairs (:dims params))]
         {:name (->str k)
          :values (when (sequential? v) (vec (distinct (map ->str v))))})))

(defn normalize-status [s]
  (let [s (some-> s ->str str/trim str/lower-case)]
    (case s
      (nil "" "auto") nil
      ("n/a" "na" "skip" "none") "n/a"
      ("todo" "wip") "todo"
      ("done" "ok") "done"
      s)))

(defn- manual-value [v]
  (cond
    (string? v) {:link v}
    (keyword? v) {:status (normalize-status v)}
    (map? v) (cond-> {}
               (not (str/blank? (str (:link v)))) (assoc :link (str (:link v)))
               (normalize-status (:status v)) (assoc :status (normalize-status (:status v)))
               (not (str/blank? (str (:note v)))) (assoc :note (str (:note v))))
    :else {}))

(defn- manual-entries
  "{coords-map value-map} plus a list of malformed entries."
  [params dim-names]
  (let [n (count dim-names)]
    (reduce (fn [acc e]
              (if (and (sequential? e) (= (count e) (inc n)))
                (assoc-in acc [:entries (zipmap dim-names (map ->str (butlast e)))]
                          (manual-value (last e)))
                (update acc :bad conj e)))
            {:entries {} :bad []}
            (:manual params))))

;; ---------------------------------------------------------------------------
;; Patterns

(def ^:private slot-re #"\{([^}]+)\}")

(defn compile-pattern [pat]
  (let [[file-part anchor] (locate/split-anchor pat)
        tokens (re-seq #"\{([^}]+)\}|\*\*|\*|[^{*]+|\{" file-part)
        slots (keep second tokens)
        re-str (apply str (for [[tok slot] tokens]
                            (cond
                              slot "([^/]+)"
                              (= tok "**") ".*"
                              (= tok "*") "[^/]*"
                              :else (Pattern/quote tok))))]
    {:source pat
     :file file-part
     :anchor anchor
     :re (re-pattern re-str)
     :slots (mapv str/trim slots)
     :anchor-slots (mapv (comp str/trim second) (re-seq slot-re (or anchor "")))}))

(defn- substitute [template coords]
  (str/replace template slot-re (fn [[_ n]] (get coords (str/trim n) ""))))

(defn match-file
  "Coords bound by compiled pattern `cp` for `path`, or nil."
  [cp path]
  (when-let [m (re-matches (:re cp) path)]
    (let [groups (if (string? m) [] (rest m))]
      (reduce (fn [acc [slot v]]
                (if (and (contains? acc slot) (not= (get acc slot) v))
                  (reduced nil)
                  (assoc acc slot v)))
              {}
              (map vector (:slots cp) groups)))))

(defn- pattern-list [params]
  (let [p (:pattern params)]
    (cond
      (string? p) [p]
      (sequential? p) (vec (filter string? p))
      :else [])))

;; ---------------------------------------------------------------------------
;; Evaluation

(defn- cartesian [dims]
  (reduce (fn [acc {:keys [name values]}]
            (for [a acc v values] (assoc a name v)))
          [{}]
          dims))

(defn- auto-cell
  "First pattern that resolves `coords`: {:link :status :others} or
  {:status \"missing\" :detail …} when only a symbol lookup failed."
  [{:keys [compiled matches dim-names locate-fn]} coords]
  (let [results
        (for [[cp ms] (map vector compiled matches)
              :let [hits (filter (fn [{c :coords}]
                                   (every? (fn [[k v]] (= v (get coords k))) c))
                                 ms)]
              :when (seq hits)]
          (let [path (:path (first hits))
                anchor (some-> (:anchor cp) (substitute coords))
                loc (when anchor (locate-fn (str path "#" anchor)))
                bound (into (set (:slots cp)) (:anchor-slots cp))
                full? (every? bound dim-names)]
            (if (and anchor (not (:found loc)))
              {:status "missing"
               :detail (str "`" anchor "` not found in " path)}
              {:link (if anchor (str path "#" anchor) path)
               :status (if full? "done" "inherited")
               :others (dec (count hits))
               :pattern (:source cp)})))]
    (or (first (filter :link results))
        (first results))))

(defn- expected-path [compiled coords]
  (when-let [cp (first compiled)]
    (when-not (str/includes? (:file cp) "*")
      (cond-> (substitute (:file cp) coords)
        (:anchor cp) (str "#" (substitute (:anchor cp) coords))))))

(def ^:private check-statuses #{"done" "missing" "todo" "n/a" "inherited"})

(defn normalize-check-result
  "User :check return value -> {:status :links :detail :note}."
  [r]
  (cond
    (true? r) {:status "done"}
    (or (nil? r) (false? r)) {:status "missing"}
    (map? r)
    (let [warn (some-> (:warn r) str)
          status (or (normalize-status (:status r)) (if warn "missing" "done"))
          links (->> (concat (when (:link r) [(:link r)]) (:links r))
                     (filter some?) (map str) distinct vec)]
      (if (check-statuses status)
        (cond-> {:status status}
          (seq links) (assoc :links links)
          warn (assoc :detail warn)
          (:note r) (assoc :note (str (:note r))))
        {:status "error" :detail (str "check returned unknown status " (pr-str (:status r)))}))
    :else {:status "error" :detail (str "check returned " (pr-str r))}))

(defn- keyword-coords [coords]
  (into {} (map (fn [[k v]] [(keyword k) v]) coords)))

(defn- resolve-cell [{:keys [manual locate-fn compiled check manual-mode] :as ctx} coords]
  (let [m (get manual coords)
        decided? (or (:status m) (:link m))
        auto (auto-cell ctx coords)
        ;; a manual status or link settles the cell, so the check is skipped
        chk (when (and check (not decided?)) (check (keyword-coords coords)))
        loc (when (:link m) (locate-fn (:link m)))
        link-ok? (and loc (:exists loc) (or (nil? (:symbol loc)) (:found loc)))
        links (vec (distinct (remove nil? (concat [(:link m)] (:links chk) [(:link auto)]))))
        link (first links)
        status (cond
                 (:status m) (:status m)
                 (:link m) (if link-ok? "done" "broken")
                 chk (:status chk)
                 auto (:status auto)
                 ;; no :pattern and no :check: a checklist, so open cells
                 ;; are still to do rather than missing
                 manual-mode "todo"
                 :else "missing")
        detail (cond
                 (and (:link m) (not link-ok?))
                 (str (:link m) (if (:exists loc) " — symbol not found" " — file not found"))
                 (:link m) nil
                 chk (:detail chk)
                 :else (:detail auto))
        note (or (:note m) (:note chk))]
    (cond-> {:coords coords :status status}
      link (assoc :link link)
      (> (count links) 1) (assoc :links links)
      note (assoc :note note)
      m (assoc :manual (select-keys m [:link :status :note]))
      (and (not (:link m)) auto (:link auto)) (assoc :auto_link (:link auto))
      detail (assoc :detail detail)
      (pos? (or (:others auto) 0)) (assoc :others (:others auto))
      (and (= status "missing") (not chk)) (assoc :expected (expected-path compiled coords)))))

(defn evaluate
  "Pure-ish evaluation of a matrix config against `files` (repo-relative
  paths). Returns the model used for both HTML and agent :data.
  `check`, when given, is (fn [keyword-coords] normalized-result)."
  [params files locate-fn & [{:keys [check check-error]}]]
  (let [declared (dims params)
        dim-names (mapv :name declared)
        compiled (mapv compile-pattern (pattern-list params))
        matches (mapv (fn [cp]
                        (vec (keep #(when-let [c (match-file cp %)] {:path % :coords c})
                                   files)))
                      compiled)
        unknown-slots (->> compiled
                           (mapcat #(concat (:slots %) (:anchor-slots %)))
                           (remove (set dim-names))
                           distinct)
        auto-values (fn [n]
                      (->> matches (apply concat) (keep #(get-in % [:coords n]))
                           distinct sort vec))
        resolved-dims (mapv (fn [d] (assoc d :auto (nil? (:values d))
                                           :values (or (:values d) (auto-values (:name d)))))
                            declared)
        {manual :entries bad :bad} (manual-entries params dim-names)
        manual-mode (and (empty? compiled) (nil? check))
        ctx {:compiled compiled :matches matches :dim-names dim-names
             :locate-fn locate-fn :manual manual :check check
             :manual-mode manual-mode}
        cells (mapv #(resolve-cell ctx %) (cartesian resolved-dims))
        known (into {} (map (juxt :name (comp set :values)) resolved-dims))
        unknown-dims (fn [coords]
                       (vec (for [[k v] coords :when (not (contains? (known k) v))] k)))
        all-matches (apply concat matches)
        ;; a file is used if any pattern reads it with known coordinates
        ;; (e.g. handlers/default/draw.clj via a fallback pattern)
        used (set (keep #(when (empty? (unknown-dims (:coords %))) (:path %)) all-matches))
        orphans (->> all-matches
                     (remove #(used (:path %)))
                     (map (fn [{:keys [path coords]}]
                            {:path path :coords coords :unknown (unknown-dims coords)}))
                     (reduce (fn [acc o] (if (some #(= (:path %) (:path o)) acc) acc (conj acc o))) []))
        stray-manual (vec (for [c (keys manual)
                                :when (some (fn [[k v]] (not (contains? (known k) v))) c)]
                            c))
        errors (cond-> []
                 check-error
                 (conj (str ":check failed to load: " check-error))
                 (empty? declared)
                 (conj "No :dims given.")
                 (seq unknown-slots)
                 (conj (str "Pattern uses unknown dimension(s): " (str/join ", " unknown-slots)))
                 (some #(and (:auto %) (empty? (:values %))) resolved-dims)
                 (conj (str ":auto dimension(s) found no values: "
                            (str/join ", " (map :name (filter #(and (:auto %) (empty? (:values %)))
                                                              resolved-dims)))))
                 (seq bad)
                 (conj (str "Ignored " (count bad) " :manual entr" (if (= 1 (count bad)) "y" "ies")
                            " with the wrong number of coordinates: " (pr-str (vec bad))))
                 (seq stray-manual)
                 (conj (str ":manual entries for coordinates not in :dims: " (pr-str stray-manual))))]
    {:dims resolved-dims
     :manual-mode manual-mode
     :cells cells
     :orphans orphans
     :errors errors
     :counts (frequencies (map :status cells))}))

(defn- layout [params resolved-dims]
  (let [by-name (into {} (map (juxt :name identity) resolved-dims))
        pick (fn [k] (get by-name (->str (get params k))))
        rows (or (pick :rows) (first resolved-dims))
        cols (or (let [c (pick :cols)] (when (not= c rows) c))
                 (first (remove #(= % rows) resolved-dims)))
        slices (vec (remove #(#{rows cols} %) resolved-dims))]
    {:rows rows :cols cols :slices slices}))

;; ---------------------------------------------------------------------------
;; HTML

(def ^:private status-order ["done" "inherited" "todo" "missing" "broken" "error" "n/a"])

(defn- status-class [s] (str "status-" (str/replace (or s "missing") "/" "")))

(defn- link-label [link]
  (let [[path anchor] (locate/split-anchor link)]
    (or anchor (last (str/split path #"/")))))

(defn- cell-title [{:keys [coords link status detail others expected note] :as cell}]
  (str/join "\n"
            (remove nil?
                    [(str/join " × " (vals coords))
                     (str "status: " status)
                     (when link (str "→ " (str/join "\n→ " (or (:links cell) [link]))))
                     (when expected (str "expected: " expected))
                     detail
                     (when others (str "+" others " more matching file(s)"))
                     note])))

(defn- cell-td [cell editable? checkable?]
  (let [{:keys [link status note manual auto_link]} cell]
    [:td {:class (str "matrix-cell " (status-class status) (when manual " manual"))
          :title (cell-title cell)
          :data-coords (json/generate-string (:coords cell))
          :data-link (or (:link manual) "")
          :data-auto-link (or auto_link "")
          :data-status (or (:status manual) "")
          :data-note (or note "")}
     (when checkable?
       [:input.matrix-check {:type "checkbox"
                             :title "Done"
                             :checked (when (= status "done") "checked")}])
     (if link
       (let [all (or (:links cell) [link])
             shown (take 4 all)]
         [:span.matrix-links
          (for [l shown
                :let [[path anchor] (locate/split-anchor l)]]
            [:a.matrix-link {:href (locate/file-href path anchor) :title l} (h (link-label l))])
          (when (> (count all) 4)
            [:span.matrix-more (h (str "+" (- (count all) 4) " more"))])])
       [:span.matrix-empty (h (if (= status "n/a") "n/a" status))])
     (when (and link (not= status "done"))
       [:span.matrix-tag (h status)])
     (when note [:div.matrix-note (h note)])
     (when editable?
       [:button.matrix-edit {:type "button" :title "Edit this cell"} "✎"])]))

(defn- grid-table [cells-by-coords {:keys [rows cols]} slice editable? checkable?]
  (let [col-values (if cols (:values cols) [nil])]
    [:table.matrix
     [:thead
      [:tr
       [:th.matrix-corner (h (str (:name rows) (when cols (str " \\ " (:name cols)))))]
       (for [c col-values] [:th.matrix-col (h (or c "where"))])]]
     [:tbody
      (for [r (:values rows)]
        [:tr
         [:th.matrix-row (h r)]
         (for [c col-values
               :let [coords (cond-> (assoc slice (:name rows) r)
                              cols (assoc (:name cols) c))]]
           (cell-td (get cells-by-coords coords) editable? checkable?))])]]))

(defn- summary-table [cells {:keys [rows cols]}]
  (let [col-values (if cols (:values cols) [nil])
        group (group-by (fn [{c :coords}] [(get c (:name rows)) (when cols (get c (:name cols)))])
                        cells)]
    [:table.matrix.matrix-summary
     [:thead
      [:tr
       [:th.matrix-corner (h (str (:name rows) (when cols (str " \\ " (:name cols)))))]
       (for [c col-values] [:th.matrix-col (h (or c "where"))])]]
     [:tbody
      (for [r (:values rows)]
        [:tr
         [:th.matrix-row (h r)]
         (for [c col-values
               :let [cs (get group [r c])
                     relevant (remove #(= "n/a" (:status %)) cs)
                     ok (count (filter #(#{"done" "inherited"} (:status %)) relevant))
                     total (count relevant)]]
           [:td {:class (str "matrix-cell "
                             (cond (zero? total) "status-na"
                                   (= ok total) "status-done"
                                   (zero? ok) "status-missing"
                                   :else "status-partial"))}
            (if (zero? total) "n/a" (str ok " / " total))])])]]))

(defn- axis-select [axis current resolved-dims]
  [:label.matrix-axis-label (h axis) " "
   [:select.matrix-axis {:data-axis axis}
    (for [{n :name} resolved-dims]
      [:option (cond-> {:value n} (= n (:name current)) (assoc :selected "selected")) (h n)])]])

(defn- counts-line [counts total]
  (str total " cells"
       (apply str (for [s status-order :let [n (get counts s)] :when n]
                    (str " · " n " " s)))))

(defn- slice-label [slice]
  (str/join " · " (map (fn [[k v]] (str k "=" v)) slice)))

(defn- issues-html [{:keys [cells orphans]}]
  (let [missing (filter #(#{"missing" "broken" "error"} (:status %)) cells)]
    (when (or (seq missing) (seq orphans))
      [:details.matrix-issues
       [:summary (h (str (count missing) " missing/broken · " (count orphans) " orphan file(s)"))]
       (when (seq missing)
         [:div
          [:h4 "Missing"]
          [:ul
           (for [{:keys [coords expected detail status]} missing]
             [:li [:code (h (str/join " × " (vals coords)))]
              (when (= status "broken") " (broken link)")
              (when (= status "error") " (check error)")
              (when expected [:span.muted " — expected " [:code (h expected)]])
              (when detail [:span.muted " — " (h detail)])])]])
       (when (seq orphans)
         [:div
          [:h4 "Orphans"]
          [:p.muted "Files matching the pattern whose coordinates are not in :dims."]
          [:ul
           (for [{:keys [path coords unknown]} orphans]
             [:li [:a {:href (locate/file-href path)} [:code (h path)]]
              [:span.muted (h (str " — unknown " (str/join ", " (map #(str % "=" (get coords %)) unknown))))]])]])])))

(def ^:private usage-html
  (hiccup/html
   [:div.plugin-error
    [:p "The matrix plugin needs " [:code ":dims"] ", e.g."]
    [:pre (h (str "{:dims {:type [\"circle\" \"square\"]\n"
                  "        :operation [\"area\" \"draw\"]}\n"
                  " :pattern \"handlers/{type}/{operation}.clj\"}"))]]))

(defn render-html [params model {:keys [doc fence-index]}]
  (let [{:keys [dims cells errors counts]} model
        {:keys [rows cols slices] :as lay} (layout params dims)
        editable? (and doc fence-index)
        by-coords (into {} (map (juxt :coords identity) cells))
        slice-coords (cartesian slices)
        tabs? (> (count slice-coords) 1)]
    (hiccup/html
     [:div (cond-> {:class "matrix-panel plugin-panel"}
             editable? (assoc :data-doc doc
                              :data-fence (str fence-index)
                              :data-dims (json/generate-string (mapv :name dims))))
      [:div.matrix-head
       [:h3 (h (or (:title params) (str/join " × " (map :name dims))))]
       [:span.meta (h (counts-line counts (count cells)))]
       (when (and editable? (> (count dims) 1))
         [:span.matrix-axes
          (axis-select "rows" rows dims)
          (axis-select "cols" cols dims)])]
      (for [e errors] [:p.error (h e)])
      (when (seq slices)
        [:div.matrix-tabs
         (for [[i s] (map-indexed vector slice-coords)]
           [:button {:type "button"
                     :class (str "matrix-tab" (when (zero? i) " active"))
                     :data-slice (str i)}
            (h (slice-label s))])
         (when tabs?
           [:button.matrix-tab {:type "button" :data-slice "summary"} "Summary"])])
      (for [[i s] (map-indexed vector slice-coords)]
        [:div (cond-> {:class "matrix-slice" :data-slice (str i)}
                (pos? i) (assoc :hidden "hidden"))
         (grid-table by-coords lay s editable? (and editable? (:manual-mode model)))])
      (when tabs?
        [:div.matrix-slice {:data-slice "summary" :hidden "hidden"}
         (summary-table cells lay)])
      (issues-html model)
      [:p.matrix-legend
       (for [s status-order]
         [:span {:class (str "matrix-key " (status-class s))} (h s)])]])))

(defn- text-summary [params {:keys [cells orphans errors counts]}]
  (str "# " (or (:title params) "Matrix") "\n\n"
       (counts-line counts (count cells)) "\n"
       (apply str (for [e errors] (str "\nError: " e)))
       (when-let [ms (seq (filter #(#{"missing" "broken" "error"} (:status %)) cells))]
         (str "\n\n## Missing\n"
              (apply str (for [{:keys [coords expected status detail]} ms]
                           (str "- " (str/join " × " (vals coords))
                                (when (= status "broken") " (broken link)")
                                (when (= status "error") " (check error)")
                                (when expected (str " — expected " expected))
                                (when detail (str " — " detail)) "\n")))))
       (when (seq orphans)
         (str "\n## Orphans\n"
              (apply str (for [{:keys [path]} orphans] (str "- " path "\n")))))))

(def check-budget-ms
  "Total time all :check calls of one matrix may take."
  10000)

(defn- check-runner
  "Compile the :check code into a guarded per-cell fn. Exceptions become
  \"error\" cells; once the time budget is spent the remaining cells are
  errors too (a runaway SCI loop cannot be interrupted, so its thread is
  abandoned)."
  [code]
  (let [f (sandbox/eval-fn code)
        deadline (+ (System/currentTimeMillis) check-budget-ms)
        expired (atom false)]
    (fn [coords]
      (let [remaining (- deadline (System/currentTimeMillis))]
        (if (or @expired (<= remaining 0))
          (do (reset! expired true)
              {:status "error" :detail (str ":check exceeded " check-budget-ms " ms for this matrix")})
          (let [fut (future
                      (try (normalize-check-result (f coords))
                           (catch Throwable e
                             {:status "error" :detail (str "check threw: " (.getMessage e))})))
                r (deref fut remaining ::timeout)]
            (if (= r ::timeout)
              (do (future-cancel fut)
                  (reset! expired true)
                  {:status "error" :detail (str ":check exceeded " check-budget-ms " ms for this matrix")})
              r)))))))

(defn run [{:keys [params path fence-index]}]
  (let [params (or params {})]
    (if (empty? (dims params))
      {:ok false :error "matrix: no :dims" :html usage-html}
      (let [locate-fn (memoize locate/locate)
            check-opts (when-let [code (:check params)]
                         (try {:check (check-runner code)}
                              (catch Exception e
                                (let [msg (.getMessage e)]
                                  {:check (constantly {:status "error" :detail msg})
                                   :check-error msg}))))
            model (evaluate params (repo/list-project-files) locate-fn check-opts)
            lay (layout params (:dims model))]
        {:ok true
         :html (render-html params model {:doc path :fence-index fence-index})
         :text (text-summary params model)
         :data {:title (:title params)
                :dims (mapv #(select-keys % [:name :values :auto]) (:dims model))
                :rows (get-in lay [:rows :name])
                :cols (get-in lay [:cols :name])
                :slices (mapv :name (:slices lay))
                :counts (:counts model)
                :cells (:cells model)
                :missing (vec (filter #(#{"missing" "broken" "error"} (:status %)) (:cells model)))
                :orphans (:orphans model)
                :errors (:errors model)}}))))

;; ---------------------------------------------------------------------------
;; Fence updates (called via codestory.fences)

(defn- shortest-value [{:keys [link status note]}]
  (let [link (when-not (str/blank? link) (str/trim link))
        status (normalize-status status)
        note (when-not (str/blank? note) (str/trim note))]
    (cond
      (and link (not status) (not note)) link
      (and status (not link) (not note)) (keyword status)
      (or link status note) (cond-> {}
                              link (assoc :link link)
                              status (assoc :status (keyword status))
                              note (assoc :note note))
      :else nil)))

(defn- coords-vector [params coords]
  (let [names (mapv :name (dims params))
        by-name (into {} (map (fn [[k v]] [(->str k) (->str v)]) coords))]
    (when-not (every? #(contains? by-name %) names)
      (throw (ex-info "Cell coordinates do not match :dims — reload the page"
                      {:coords coords :dims names})))
    (mapv by-name names)))

(defn update-params
  "Fence update actions from the grid UI:
  {:op \"set-cell\" :coords {dim v} :link :status :note}
  {:op \"clear-cell\" :coords {dim v}}
  {:op \"set-axes\" :rows dim :cols dim :changed \"rows\"|\"cols\"}"
  [params {:keys [op coords rows cols] :as action}]
  (case (str op)
    ("set-cell" "clear-cell")
    (let [cv (coords-vector params coords)
          n (count cv)
          kept (vec (remove #(and (sequential? %) (= (count %) (inc n))
                                  (= cv (mapv ->str (butlast %))))
                            (:manual params)))
          v (when (= "set-cell" (str op)) (shortest-value action))
          manual (cond-> kept v (conj (conj cv v)))]
      (assoc params :manual manual))

    "set-axes"
    (let [ds (dims params)
          names (set (map :name ds))
          old (layout params ds)
          old-rows (get-in old [:rows :name])
          old-cols (get-in old [:cols :name])
          rows (->str rows)
          cols (->str cols)
          ;; picking the other axis's dimension swaps the two axes
          [rows cols] (if (= rows cols)
                        (if (= "cols" (->str (:changed action)))
                          [old-cols cols]
                          [rows old-rows])
                        [rows cols])]
      (when-not (and (names rows) (names cols))
        (throw (ex-info "Unknown dimension" {:rows rows :cols cols})))
      (assoc params :rows (keyword rows) :cols (keyword cols)))

    (throw (ex-info (str "Unknown matrix action: " op) {:action action}))))

(def plugin
  {:name "matrix"
   :description (str "Grid for matrix-shaped code (types × operations, apps × targets × concerns). "
                     "Cells link to the file/function implementing each combination, filled by a "
                     "path :pattern like \"handlers/{type}/{operation}.clj\" and/or edited by hand "
                     "(stored in the fence's :manual list). Flags missing cells and orphan files.")
   :run run
   :update update-params})
