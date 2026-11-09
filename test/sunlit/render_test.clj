(ns sunlit.render-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sunlit.render :as render]))

(deftest underscores-survive-markdown
  (testing "wiki link labels with underscores are not italicized"
    (let [html (:html (render/render-markdown "See [[md_to_reveal_outliner]]" "page:t"))]
      (is (str/includes? html "md_to_reveal_outliner"))
      (is (not (str/includes? html "<em>")))
      (is (not (str/includes? html "<i>")))))

  (testing "inline code escapes underscores instead of italicizing"
    (let [html (:html (render/render-markdown "Use `md_to_reveal_outliner` here." "page:t"))]
      (is (str/includes? html "<code>"))
      (is (or (str/includes? html "md_to_reveal_outliner")
              (str/includes? html "md&#95;to&#95;reveal&#95;outliner")))
      (is (not (str/includes? html "<i>to</i>"))))))
