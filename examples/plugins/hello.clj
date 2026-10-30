;; Example Sunlit Clearing SCI plugin for a watched repo.
;; Copy into <repo>/.sunlit/plugins/hello.clj and restart Sunlit Clearing.
;;
;; Preferred shape — a map with :name, :description, and :run:

{:name "hello"
 :description "Demo project plugin: shows the current file path and repo root."
 :run (fn [{:keys [path params repo-root]}]
        {:html (str "<div class='plugin-panel'>"
                    "<h3>" (or (:title params) "Hello plugin") "</h3>"
                    "<p>File: <code>" path "</code></p>"
                    "<p>Repo: <code>" repo-root "</code></p>"
                    "</div>")})}

;; Alternatively call (codestory.plugin/define! {...}) or return a bare fn
;; (filename becomes the plugin name; description will be empty).
