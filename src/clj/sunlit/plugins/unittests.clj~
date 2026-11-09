(ns codestory.plugins.unittests
  "Long-running unit-test helper built on the job cache."
  (:require [clojure.string :as str]
            [codestory.haxe :as haxe]
            [codestory.jobs :as jobs]
            [codestory.python :as py]
            [codestory.source :as source]
            [codestory.test-report :as report]))

(def ^:private test-langs [:clojure :python :haxe])

(defn- detect-lang []
  (let [counts (into {} (for [l test-langs]
                          [l (count (source/source-files l))]))
        ranked (sort-by (comp - counts) (filter #(pos? (counts % 0)) test-langs))]
    (or (first ranked) :clojure)))

(defn- lang-key [params]
  (if-let [l (:lang params)]
    (keyword l)
    (detect-lang)))

(defn- python-lang? [lang]
  (contains? #{:python :py} lang))

(defn- python-default-cmd [params]
  (let [target (py/discover-pytest-path (or (:path params) (:tests params)))
        base ["python3" "-m" "pytest" "-q"]]
    (py/with-analysis-python
     (if target (conj base target) base))))

(defn- default-cmd [lang params]
  (case lang
    :clojure ["lein" "test"]
    :cljs ["lein" "cljsbuild" "test"]
    :python (python-default-cmd params)
    :py (python-default-cmd params)
    :haxe (haxe/default-test-cmd params)
    nil))

(defn- resolve-cmd [params]
  (let [lang (lang-key params)
        cmd (or (:cmd params)
                (default-cmd lang params))]
    (when cmd
      (if (python-lang? lang)
        (py/with-analysis-python cmd)
        cmd))))

(defn- cache-id [params]
  (or (:id params)
      (str "unittests-" (name (lang-key params)))))

(defn- combined-output [cache]
  (str (:stdout cache) "\n" (:stderr cache)))

(defn run
  [{:keys [params]}]
  (let [id (cache-id params)
        lang (lang-key params)
        cmd (resolve-cmd params)
        cache (jobs/read-cache "unittests" id)
        summary (when (#{"ok" "failed"} (:status cache))
                  (report/parse-summary (combined-output cache)))]
    (jobs/render-panel
     {:plugin "unittests"
      :id id
      :title (or (:title params) (str "unittests/" (name lang)))
      :params (assoc params :id id :lang (name lang) :cmd cmd)
      :summary-html (report/summary-html summary)})))

(defn start!
  [{:keys [params]}]
  (let [id (cache-id params)
        lang (lang-key params)
        ensured (when (python-lang? lang) (py/ensure-project-editable!))]
    (cond
      (and ensured (not (:ok ensured)))
      {:ok false :error (str "unittests: " (:error ensured))}

      :else
      (let [cmd (resolve-cmd params)]
        (if (nil? cmd)
          {:ok false :error "unittests: provide :lang (:clojure|:python|:haxe) or :cmd"}
          (jobs/start! {:plugin "unittests" :id id :cmd cmd}))))))

(def plugin
  {:name "unittests"
   :description "Long-running unit-test runner. Auto-detects the language (clojure → lein test; python → pytest via .sunlit/venv, discovering tests/python, tests/, or test/; haxe → haxe test.hxml); override with :lang. Shows a headline pass/fail summary above the log. Optional :path/:tests, :hxml, :id, or :cmd."
   :long-running? true
   :run run
   :start! start!})
