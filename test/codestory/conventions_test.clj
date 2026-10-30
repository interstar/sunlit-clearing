(ns codestory.conventions-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [codestory.conventions :as conv]
            [codestory.repo :as repo])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-repo [f]
  (let [dir (.toFile (Files/createTempDirectory "codestory-conv" (into-array FileAttribute [])))]
    (try
      (repo/set-repo-root! dir)
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(deftest parse-front-matter-basic
  (let [{:keys [metadata body]}
        (conv/parse-front-matter
         "---\nid: 8\ntitle: \"if branches do not join\"\nstatus: open\nlabels: [front-end]\ncreated: 2026-10-17\n---\nBody\n")]
    (is (= 8 (get metadata "id")))
    (is (= "if branches do not join" (get metadata "title")))
    (is (= "open" (get metadata "status")))
    (is (= ["front-end"] (get metadata "labels")))
    (is (= "2026-10-17" (get metadata "created")))
    (is (= "Body" body))))

(deftest parse-front-matter-lists-and-empties
  (is (= ["front-end" "docs"]
         (get (:metadata (conv/parse-front-matter "---\nlabels: [front-end, docs]\n---\nx")) "labels")))
  (is (= []
         (get (:metadata (conv/parse-front-matter "---\nlabels: []\n---\nx")) "labels"))))

(deftest parse-front-matter-none
  (let [{:keys [metadata body]} (conv/parse-front-matter "# Hi\n")]
    (is (empty? metadata))
    (is (= "# Hi\n" body))))

(deftest parse-front-matter-crlf
  (let [{:keys [metadata]} (conv/parse-front-matter "---\r\nstatus: open\r\n---\r\nbody")]
    (is (= "open" (get metadata "status")))))

(deftest glob-matching
  (is (conv/match-glob? "issues/*.md" "issues/0001-foo.md"))
  (is (not (conv/match-glob? "issues/*.md" "issues/0001-foo.txt")))
  (is (not (conv/match-glob? "issues/*.md" "docs/0001-foo.md")))
  (is (conv/match-glob? "**/*.md" "a/b/c.md"))
  (is (conv/match-glob? "src/**/*.clj" "src/a/b.clj")))

(def ^:private valid-issue
  "---\nid: 1\ntitle: \"First bug\"\nstatus: open\nlabels: [front-end]\ncreated: 2026-10-17\n---\nBody\n")

(def ^:private missing-status-issue
  "---\nid: 2\ntitle: \"Second bug\"\nlabels: []\ncreated: 2026-10-18\n---\nBody\n")

(def ^:private bad-name-issue
  "---\nid: 3\ntitle: \"Third\"\nstatus: open\nlabels: []\ncreated: 2026-10-18\n---\nBody\n")

(def ^:private issue-format
  {:name "issue"
   :match "issues/*.md"
   :filename "^\\d{4}-[A-Za-z0-9._-]+\\.md$"
   :tracker? true
   :fields
   [{:key "id"      :required true}
    {:key "title"   :required true}
    {:key "status"  :required true :enum ["open" "closed"]}
    {:key "labels"  :required true :multiple true}
    {:key "created" :required true :pattern "^\\d{4}-\\d{2}-\\d{2}$"}]})

(def ^:private test-template
  {:title "Test standard"
   :directories [{:path "src"    :required true :description "Primary source"}
                 {:path "issues" :required true :description "Issue tracker"}]
   :files       [{:path "README.md" :required true :description "Readme"}]
   :formats     [issue-format]})

(deftest check-reports-matches-and-misses
  (with-temp-repo
    (fn [dir]
      (spit (io/file dir "README.md") "# Hi\n")
      (let [issues (io/file dir "issues")]
        (.mkdirs issues)
        (spit (io/file issues "0001-first-bug.md") valid-issue)
        (spit (io/file issues "0002-broken.md") missing-status-issue)
        (spit (io/file issues "badname.md") bad-name-issue))
      (let [report (conv/check test-template)
            fmt (first (:formats report))]
        (testing "template entries are checked"
          (is (some #(= "issues" (:path %)) (:directories report)))
          (is (some #(= "src" (:path %)) (:directories report)))
          (is (some #(= "README.md" (:path %)) (:files report))))
        (testing "directory statuses"
          (let [issues (first (filter #(= "issues" (:path %)) (:directories report)))
                src (first (filter #(= "src" (:path %)) (:directories report)))]
            (is (= :present (:status issues)))
            (is (= :missing (:status src)))))
        (testing "format validation"
          (is (= 3 (:matched_count fmt)))
          (is (= 1 (:valid_count fmt)))
          (is (= 2 (:invalid_count fmt)))
          (let [invalid (set (map :path (:invalid fmt)))]
            (is (contains? invalid "issues/0002-broken.md"))
            (is (contains? invalid "issues/badname.md"))))
        (testing "issues tracker derived view"
          (is (= 3 (:total (:issues report))))
          (is (= 2 (:open (:issues report))))
          (is (= 0 (:closed (:issues report)))))
        (testing "overall not ok because src is missing"
          (is (false? (:ok? report))))))))

(deftest labels-are-freeform-and-derived
  (with-temp-repo
    (fn [dir]
      (let [issues (io/file dir "issues")]
        (.mkdirs issues)
        (spit (io/file issues "0001-a.md")
              "---\nid: 1\ntitle: \"A\"\nstatus: open\nlabels: [banana, kiwi]\ncreated: 2026-10-17\n---\nBody\n")
        (spit (io/file issues "0002-b.md")
              "---\nid: 2\ntitle: \"B\"\nstatus: open\nlabels: [banana]\ncreated: 2026-10-18\n---\nBody\n"))
      (let [report (conv/check test-template)
            fmt (first (:formats report))]
        (testing "arbitrary labels are accepted (no enum in the default template)"
          (is (= 2 (:valid_count fmt)))
          (is (= 0 (:invalid_count fmt))))
        (testing "label vocabulary is derived from the issues"
          (let [lbl (into {} (:labels (:issues report)))]
            (is (= 2 (get lbl "banana")))
            (is (= 1 (get lbl "kiwi")))))))))

(deftest check-honours-repo-template-override
  (with-temp-repo
    (fn [dir]
      (.mkdirs (io/file dir "issues"))
      (.mkdirs (io/file dir ".sunlit"))
      (spit (io/file dir ".sunlit" "template.edn")
            "{:title \"Custom\" :directories [{:path \"issues\" :required true}] :files [] :formats []}")
      (let [report (conv/check)]
        (is (= "Custom" (:title report)))
        (is (= ["issues"] (mapv :path (:directories report))))
        (is (= [:present] (mapv :status (:directories report))))
        (is (:ok? report))))))

(deftest resource-template-loads
  (let [tpl (conv/resource-template)]
    (is (map? tpl))
    (is (seq (:directories tpl)))
    (is (seq (:formats tpl)))
    (let [fmt (first (:formats tpl))]
      (is (= "issue" (:name fmt)))
      (is (:filename fmt))
      (is (some #(= "status" (:key %)) (:fields fmt))))))

(deftest load-template-precedence
  (with-temp-repo
    (fn [dir]
      (let [user-file (io/file dir "user-tpl.edn")]
        (testing "resource fallback when no overrides exist"
          (with-redefs [conv/user-template-file (fn [] (io/file dir "missing.edn"))
                        conv/resource-template (constantly {:title "resource"})]
            (is (= "resource" (:title (conv/load-template))))))
        (testing "user template wins over resource"
          (spit user-file "{:title \"user\"}")
          (with-redefs [conv/user-template-file (fn [] user-file)
                        conv/resource-template (constantly {:title "resource"})]
            (is (= "user" (:title (conv/load-template))))))
        (testing "repo-local wins over user and resource"
          (.mkdirs (io/file dir ".sunlit"))
          (spit (io/file dir ".sunlit" "template.edn") "{:title \"repo\"}")
          (with-redefs [conv/user-template-file (fn [] user-file)
                        conv/resource-template (constantly {:title "resource"})]
            (is (= "repo" (:title (conv/load-template))))))))))

(deftest html-escapes-paths-and-shows-verdict
  (let [html (conv/->html
              {:title "T"
               :ok? false
               :counts {:directories_present 1 :directories_required 2
                        :files_present 0 :files_required 1}
               :mushroom {:full_name "Sunlit Clearing" :desc "a desc"}
               :directories [{:path "src" :required true :present? false :status :missing}]
               :files []
               :formats []
               :issues nil})]
    (is (re-find #"Conventions not fully satisfied" html))
    (is (re-find #"Sunlit Clearing" html))
    (is (re-find #"row-missing" html))))
