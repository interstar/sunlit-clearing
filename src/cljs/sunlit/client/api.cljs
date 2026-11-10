(ns sunlit.client.api)

(defn- parse-body [text]
  (try
    (js->clj (.parse js/JSON text) :keywordize-keys true)
    (catch :default _
      {:ok false :error text})))

(defn- fetch-json
  "fetch `url` with `opts`, then call on-ok with the parsed JSON body, or on-err
  with the error text when the request itself fails."
  [url opts on-ok on-err]
  (-> (js/fetch url (clj->js opts))
      (.then (fn [res] (.text res)))
      (.then (fn [text] (on-ok (parse-body text))))
      (.catch (fn [e] (on-err (str e))))))

(defn- send-json [method url body on-ok on-err]
  (fetch-json url
              {:method method
               :headers {"Content-Type" "application/json"}
               :body (.stringify js/JSON (clj->js body))}
              on-ok
              on-err))

(defn get-json [url on-ok on-err]
  (fetch-json url {} on-ok on-err))

(defn put-json [url body on-ok on-err]
  (send-json "PUT" url body on-ok on-err))

(defn post-json [url body on-ok on-err]
  (send-json "POST" url body on-ok on-err))
