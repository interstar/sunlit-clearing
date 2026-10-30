(ns codestory.python
  "Resolve which Python interpreter Sunlit Clearing analysis jobs should use."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [codestory.repo :as repo]))

(def tools-packages
  "Tool packages installed into .sunlit/venv (not project app deps)."
  ["pytest" "pytest-cov" "coverage"])

(defn- venv-python-file []
  (let [unix (repo/sidecar-file "venv" "bin" "python")
        win (repo/sidecar-file "venv" "Scripts" "python.exe")]
    (cond
      (.isFile unix) unix
      (.isFile win) win
      :else nil)))

(defn venv-python
  "Absolute path to .sunlit/venv python, or nil."
  []
  (some-> (venv-python-file) .getAbsolutePath))

(defn venv-exists? []
  (boolean (venv-python-file)))

(defn host-python
  "Interpreter used to bootstrap a venv (never the private venv itself)."
  []
  (or (System/getenv "CODESTORY_PYTHON") "python3"))

(defn analysis-python
  "Prefer private .sunlit/venv, then CODESTORY_PYTHON, then python3."
  []
  (or (venv-python)
      (System/getenv "CODESTORY_PYTHON")
      "python3"))

(defn with-analysis-python
  "Replace leading python/python3 in cmd with analysis-python."
  [cmd]
  (let [argv (mapv str cmd)]
    (if (and (seq argv) (#{"python" "python3"} (first argv)))
      (assoc argv 0 (analysis-python))
      argv)))

(defn discover-pytest-path
  "Pick a pytest target under the repo. Prefers tests/python, then tests/, then test/.
  Returns a relative path string, or nil to let pytest discover via config."
  ([] (discover-pytest-path nil))
  ([explicit]
   (let [explicit (some-> explicit str str/trim not-empty)]
     (if explicit
       explicit
       (let [root (repo/repo-root)
             candidates ["tests/python" "tests" "test"]]
         (some (fn [rel]
                 (let [f (io/file root rel)]
                   (when (or (.isDirectory f) (.isFile f))
                     rel)))
               candidates))))))

(defn- run-py!
  [argv]
  (apply sh/sh (concat (mapv str argv)
                       [:dir (str (repo/repo-root))
                        :env (-> (into {} (System/getenv)) (dissoc "CLASSPATH"))])))

(def ^:private probe-script
  "import importlib.util as u, sys
missing = []
for label, mod in [('pytest', 'pytest'), ('pytest-cov', 'pytest_cov'), ('coverage', 'coverage')]:
    if u.find_spec(mod) is None:
        missing.append(label)
print('OK' if not missing else 'MISSING ' + ','.join(missing))
print(sys.executable)
print(sys.version.split()[0])
")

(defn tool-status
  "Quick status map for the control panel."
  []
  (let [py (venv-python)
        host (host-python)]
    (if-not py
      {:venv false
       :venv_python nil
       :host_python host
       :tools_ok false
       :detail "No .sunlit/venv yet"}
      (let [{:keys [exit out err]}
            (sh/sh py "-c" probe-script
                   :dir (str (repo/repo-root))
                   :env (-> (into {} (System/getenv)) (dissoc "CLASSPATH")))
            lines (->> (str/split-lines (str out))
                       (map str/trim)
                       (remove str/blank?)
                       vec)
            head (or (first lines) "")
            tools-ok (and (zero? exit) (str/starts-with? head "OK"))]
        {:venv true
         :venv_python py
         :host_python host
         :tools_ok tools-ok
         :detail (if tools-ok
                   (str "Tools OK · Python " (or (get lines 2) "?"))
                   (str/trim (str head " " err)))}))))

(defn ensure-analysis-env!
  "Create .sunlit/venv if missing and install analysis tools (pytest, …).
  Returns {:ok true :python path} or {:ok false :error msg}."
  []
  (try
    (when-not (venv-exists?)
      (let [venv-dir (.getAbsolutePath (repo/sidecar-file "venv"))
            {:keys [exit out err]} (run-py! [(host-python) "-m" "venv" venv-dir])]
        (when-not (zero? exit)
          (throw (ex-info (str "Failed to create .sunlit/venv: "
                               (str/trim (str err "\n" out)))
                          {:exit exit})))))
    (let [py (or (venv-python)
                 (throw (ex-info "venv created but python binary missing" {})))
          st (tool-status)]
      (when-not (:tools_ok st)
        (let [cmd (into [py "-m" "pip" "install" "--upgrade" "pip"] tools-packages)
              {:keys [exit out err]} (run-py! cmd)]
          (when-not (zero? exit)
            (throw (ex-info (str "Failed to install analysis tools into .sunlit/venv: "
                                 (str/trim (str err "\n" out)))
                            {:exit exit})))))
      {:ok true :python py})
    (catch Exception e
      {:ok false :error (or (ex-message e) (.getMessage e))})))

(defn- python-package-root?
  []
  (let [root (repo/repo-root)]
    (or (.isFile (io/file root "pyproject.toml"))
        (.isFile (io/file root "setup.py"))
        (.isFile (io/file root "setup.cfg")))))

(def ^:private requirements-candidates
  "Repo-relative requirement files to install into the analysis venv (first-found order)."
  ["requirements.txt"
   "requirements-dev.txt"
   "requirements-test.txt"
   "requirements/dev.txt"
   "requirements/test.txt"])

(defn- existing-requirements-files
  []
  (let [root (repo/repo-root)]
    (filterv #(.isFile (io/file root %)) requirements-candidates)))

(defn- pip-install!
  [py argv]
  (let [{:keys [exit out err]} (run-py! (into [py "-m" "pip" "install"] argv))]
    (if (zero? exit)
      {:ok true}
      {:ok false
       :error (str "pip install failed (" (str/join " " argv) "): "
                   (str/trim (str err "\n" out)))})))

(defn ensure-project-editable!
  "Ensure analysis venv exists, install requirements*.txt when present, then
  `pip install -e .` if the repo is an installable Python package."
  []
  (try
    (let [ensured (ensure-analysis-env!)]
      (if-not (:ok ensured)
        ensured
        (let [py (:python ensured)
              reqs (existing-requirements-files)]
          (loop [files reqs]
            (if-let [f (first files)]
              (let [r (pip-install! py ["-r" f])]
                (if (:ok r)
                  (recur (rest files))
                  r))
              (if-not (python-package-root?)
                ensured
                (let [r (pip-install! py ["-e" "."])]
                  (if (:ok r)
                    ensured
                    r))))))))
    (catch Exception e
      {:ok false :error (or (ex-message e) (.getMessage e))})))
