(ns codestory.client.api)

(defn- parse-body [text]
  (try
    (js->clj (.parse js/JSON text) :keywordize-keys true)
    (catch :default _
      {:ok false :error text})))

(defn get-json [url on-ok on-err]
  (-> (js/fetch url)
      (.then (fn [res] (.text res)))
      (.then (fn [text] (on-ok (parse-body text))))
      (.catch (fn [e] (on-err (str e))))))

(defn put-json [url body on-ok on-err]
  (-> (js/fetch url
                (clj->js {:method "PUT"
                          :headers {"Content-Type" "application/json"}
                          :body (.stringify js/JSON (clj->js body))}))
      (.then (fn [res] (.text res)))
      (.then (fn [text] (on-ok (parse-body text))))
      (.catch (fn [e] (on-err (str e))))))

(defn post-json [url body on-ok on-err]
  (-> (js/fetch url
                (clj->js {:method "POST"
                          :headers {"Content-Type" "application/json"}
                          :body (.stringify js/JSON (clj->js body))}))
      (.then (fn [res] (.text res)))
      (.then (fn [text] (on-ok (parse-body text))))
      (.catch (fn [e] (on-err (str e))))))
