(ns sunlit.sessions.sqlite
  "Read-only queries against SQLite stores that other apps own (Cursor's chat
  databases). Files are opened `immutable`, so a running app's locks and
  write-ahead log are left alone; at worst a query sees slightly stale data."
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.sql DriverManager PreparedStatement ResultSet]))

(defn- jdbc-url [^File f]
  ;; a file: URI, so spaces and other characters in the path are escaped
  (str "jdbc:sqlite:" (.toURI f) "?mode=ro&immutable=1"))

(defn- column-value
  "Column `i` of the current row; BLOBs come back as UTF-8 text."
  [^ResultSet rs i]
  (let [v (.getObject rs (int i))]
    (if (bytes? v) (String. ^bytes v StandardCharsets/UTF_8) v)))

(defn- result-rows [^ResultSet rs]
  (let [n (.getColumnCount (.getMetaData rs))]
    (loop [rows []]
      (if (.next rs)
        (recur (conj rows (mapv #(column-value rs %) (range 1 (inc n)))))
        rows))))

(defn query
  "Rows (vectors of column values) for `sql` with `params` against SQLite file
  `f`, opened read-only. Throws when the file or table can't be read."
  [^File f sql & params]
  (with-open [conn (DriverManager/getConnection (jdbc-url f))
              ^PreparedStatement stmt (.prepareStatement conn sql)]
    (doseq [[i p] (map-indexed vector params)]
      (.setObject stmt (int (inc i)) p))
    (with-open [rs (.executeQuery stmt)]
      (result-rows rs))))

(defn query-or-empty
  "`query`, or [] when the store is missing, locked or not the expected shape
  (stores of apps that aren't installed, or older versions of them)."
  [^File f sql & params]
  (if (.isFile f)
    (try
      (apply query f sql params)
      (catch java.sql.SQLException _ []))
    []))
