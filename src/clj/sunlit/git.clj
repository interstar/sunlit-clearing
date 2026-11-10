(ns sunlit.git
  "Read-only git status queries by shelling out to the git CLI.
  Safe to call when git is missing or the repo is not a work tree."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [sunlit.repo :as repo]))

(defonce ^:private cache (atom {:repo nil :ts 0 :map nil}))
(def ^:private cache-ttl-ms 5000)

(defn- run-git [& args]
  (let [{:keys [exit out err]}
        (apply sh/sh (concat ["git"] (mapv str args)
                             [:dir (str (repo/repo-root))
                              :env (-> (into {} (System/getenv)) (dissoc "CLASSPATH"))]))]
    {:ok (zero? exit) :out (str out) :err (str err)}))

(defn available?
  "True when git is on PATH and the watched repo is a git work tree."
  []
  (let [{:keys [ok out]} (run-git "rev-parse" "--is-inside-work-tree")]
    (and ok (str/includes? (str/trim out) "true"))))

(defn- git-lines
  "Set of output lines of a git command, or nil (after logging why) when it fails."
  [& args]
  (let [{:keys [ok out err]} (apply run-git args)]
    (if ok
      (->> (str/split-lines out)
           (map str/trim)
           (remove str/blank?)
           set)
      (binding [*out* *err*]
        (println (str "sunlit.git: `git " (str/join " " args) "` failed: " (str/trim err)))
        nil))))

(defn- fresh-status-map
  "Tracked/untracked/ignored path sets, or nil when git is unavailable or any
  listing fails. A failed listing must not read as \"no files tracked\", which
  would mark every file untracked; nil means status unknown."
  []
  (when (available?)
    (let [m {:tracked (git-lines "ls-files")
             :untracked (git-lines "ls-files" "--others" "--exclude-standard")
             :ignored (git-lines "ls-files" "--others" "-i" "--exclude-standard")}]
      (when (every? some? (vals m))
        m))))

(defn- status-map []
  (let [root (str (repo/repo-root))
        now (System/currentTimeMillis)
        {croot :repo cts :ts cmap :map} @cache]
    (if (and (= root croot) (< (- now cts) cache-ttl-ms))
      cmap
      (let [m (fresh-status-map)]
        (reset! cache {:repo root :ts now :map m})
        m))))

(defn- normalize-path [path]
  (let [p (-> (str path)
              (str/replace #"\\" "/")
              (str/replace #"^\./" "")
              (str/replace #"^/+" "")
              (str/replace #"/+$" ""))]
    (if (= p ".") "" p)))

(defn- status-for-map [sm path]
  (let [p (normalize-path path)]
    (cond
      (str/blank? p)
      (when (seq (:tracked sm)) :tracked)

      ;; exact membership first (fast for files)
      (contains? (:tracked sm) p) :tracked
      (contains? (:untracked sm) p) :untracked
      (contains? (:ignored sm) p) :ignored

      ;; otherwise: a directory — look for any descendant
      :else
      (let [prefix (str p "/")
            member? (fn [s] (boolean (some #(str/starts-with? % prefix) s)))]
        (cond
          (member? (:tracked sm)) :tracked
          (member? (:untracked sm)) :untracked
          (member? (:ignored sm)) :ignored
          :else nil)))))

(defn status-for
  "Git status keyword for a file or directory path:
  :tracked / :untracked / :ignored, or nil when git is unavailable/unknown."
  [path]
  (when-let [sm (status-map)]
    (status-for-map sm path)))

(defn tracked?
  "True when the file/directory at `path` is tracked (a directory counts as
  tracked when it contains at least one tracked file)."
  [path]
  (boolean (= :tracked (status-for path))))

(defn keep-tracked
  "Keep only git-tracked file paths (untracked/ignored are assumed generated).
  Returns the collection unchanged when git is unavailable."
  [paths]
  (if-let [sm (status-map)]
    (filterv #(contains? (:tracked sm) (normalize-path %)) paths)
    (vec paths)))

(defn annotate-tree
  "Assoc :git-status on every node of a repo tree (nested maps). Returns the
  tree unchanged when git is unavailable."
  [tree]
  (if-let [sm (status-map)]
    (letfn [(walk [node]
              (-> node
                  (assoc :git-status (status-for-map sm (:path node)))
                  (update :children #(mapv walk %))))]
      (walk tree))
    tree))

(defn annotate-entries
  "Assoc :git-status on a flat list of tree entries (files and dirs)."
  [entries]
  (if-let [sm (status-map)]
    (mapv #(assoc % :git-status (status-for-map sm (:path %))) entries)
    (mapv #(assoc % :git-status nil) entries)))
