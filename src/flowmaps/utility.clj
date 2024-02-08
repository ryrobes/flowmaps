(ns flowmaps.utility
  (:refer-clojure :exclude [abs update-vals update-keys])
  (:require [puget.printer :as puget]
            [flowmaps.db :as db]
            ;[clojure.set :as cset]
            [talltale.core :as tales]
            [chime.core :as chime]
            [clojure.spec.test.alpha :as stest]
            [clojure.spec.alpha :as s]
            [clojure.walk :as walk]
            [clojure.pprint :as pprint]
            [clojure.string :as cstr])
  (:import [java.lang.management ManagementFactory]
           java.time.Instant
           java.time.LocalTime
           java.time.LocalDate
           [java.time LocalTime Duration Instant ZonedDateTime ZoneId Period DayOfWeek]
           [java.text SimpleDateFormat]
           [java.util Date TimeZone Calendar]
           java.time.format.DateTimeFormatter))

(defn bytes-to-mb [bytes]
  (int (/ bytes (Math/pow 1024 2))))

;; simple jvm stats for debug

(defn get-memory-usage []
  (let [memory-bean (ManagementFactory/getMemoryMXBean)
        memory-usage->map (fn [m]
                            {:init (bytes-to-mb (.getInit m))
                             :used (bytes-to-mb (.getUsed m))
                             :committed (bytes-to-mb (.getCommitted m))
                             :max (bytes-to-mb (.getMax m))})]
    {:heap-usage (memory-usage->map (.getHeapMemoryUsage memory-bean))
     :non-heap-usage (memory-usage->map (.getNonHeapMemoryUsage memory-bean))}))

(defn get-thread-count []
  (let [thread-bean (ManagementFactory/getThreadMXBean)]
    {:total-started (.getTotalStartedThreadCount thread-bean)
     :peak (.getPeakThreadCount thread-bean)
     :current (.getThreadCount thread-bean)
     :daemon (.getDaemonThreadCount thread-bean)}))

;; odds and ends

(defn time-seq [v]
  (let [{:keys [days minutes seconds hours weeks months at starts tz]
         :or {tz (ZoneId/systemDefault)}} (apply hash-map v)
        zone-id (if (instance? String tz) (ZoneId/of tz) tz)
        starting-instant (if starts
                           (-> (LocalDate/parse starts)
                               (.atTime (if at (LocalTime/of (quot at 100) (mod at 100)) (LocalTime/MIDNIGHT)))
                               (.atZone zone-id)
                               .toInstant)
                           (Instant/now))]
    (cond
      days
      (if at
        (chime/periodic-seq
         (-> starting-instant
             (.plus (Period/ofDays days)))
         (Period/ofDays days))
        (chime/periodic-seq starting-instant (Period/ofDays days)))

      hours
      (if at
        (chime/periodic-seq
         (-> (LocalTime/of (quot at 100) (mod at 100))
             (.adjustInto (ZonedDateTime/now zone-id))
             .toInstant)
         (Duration/ofHours hours))
        (chime/periodic-seq (Instant/now) (Duration/ofHours hours)))

      minutes
      (if at
        (chime/periodic-seq
         (-> (LocalTime/of (quot at 100) (mod at 100))
             (.adjustInto (ZonedDateTime/now zone-id))
             .toInstant)
         (Duration/ofMinutes minutes))
        (chime/periodic-seq (Instant/now) (Duration/ofMinutes minutes)))

      seconds
      (if at
        (chime/periodic-seq
         (-> (LocalTime/of (quot at 100) (mod at 100))
             (.adjustInto (ZonedDateTime/now zone-id))
             .toInstant)
         (Duration/ofSeconds seconds))
        (chime/periodic-seq (Instant/now) (Duration/ofSeconds seconds)))

      weeks
      (if at
        (chime/periodic-seq
         (-> starting-instant
             (.plus (Period/ofWeeks weeks)))
         (Period/ofWeeks weeks))
        (chime/periodic-seq starting-instant (Period/ofWeeks weeks)))

      months
      (if at
        (chime/periodic-seq
         (-> (LocalDate/parse starts)
             (.atTime (if at (LocalTime/of (quot at 100) (mod at 100)) (LocalTime/MIDNIGHT)))
             (.atZone zone-id)
             (.plus (Period/ofMonths months))
             .toInstant)
         (Duration/ofDays 30)) ; approx
        (chime/periodic-seq starting-instant (Duration/ofDays 30)))

      :else
      (throw (IllegalArgumentException. "Unsupported time unit")))))

(defn dequeue! [queue]
  (let [item (peek @queue)]
    (swap! queue pop)
    item))

(defn unix-to-date [timestamp]
  (java.util.Date. timestamp))

(defn today-yyyymmdd []
  (let [cal (Calendar/getInstance)
        fmt (SimpleDateFormat. "yyyy-MM-dd")]
    (.format fmt (.getTime cal))))

(defn date-str-to-unix [date-str]
  (let [fmt (SimpleDateFormat. "yyyy-MM-dd")
        date (.parse fmt date-str)]
    (.getTime date)))

(defn date-to-ymd [date] ;; blargh
  (let [cal (Calendar/getInstance)]
    (.setTime cal date)
    (str (.get cal Calendar/YEAR) "-"
         (inc (.get cal Calendar/MONTH)) "-" ; +1 since jan = 0
         (.get cal Calendar/DATE))))

(defn generate-name []
  (let [quals ["of-the" "hailing-from" "banned-from" "of" "exiled-from" "from"]
        names [(tales/quality) (rand-nth [(tales/shape) (tales/color)]) (tales/animal) (rand-nth quals) (tales/landform)]]
    (cstr/replace (clojure.string/join "-" names) " " "-")))

(defn limit-elements [x]
  (try (cond
         (map? x) x ;; dont limit maps elements at all 
         (instance? clojure.lang.IPending x) (doall (take db/sample-limit x))
         :else x)
       (catch Exception _ x)))

(defn hide-secret [flow-id x] ;; TODO, upcoming secrets, this just hides from the UI for now
  (try
    (walk/postwalk-replace (get @db/hidden-values flow-id) x) (catch Exception _ x)))

(defn limited [x flow-id]
  (let [flow-limit (get-in @db/working-data [flow-id :limit] db/sample-limit)]
    (hide-secret flow-id
                 (letfn [(limited-helper [x]
                           (cond
                             (map? x) (into (empty x) (map (fn [[k v]] [k (limited-helper v)])) x)
                             (coll? x) (into (empty x) (take flow-limit (map limited-helper x)))
                             (and (not (string? x)) (not (keyword? x)) (not (number? x)))
                             (try (doall (take flow-limit x)) (catch Exception _ x))
                             :else x))]
                   (limited-helper x)))))

(defn limited-t [x]
  (walk/postwalk limited x))

(defn nf [i]
  (pprint/cl-format nil "~:d" i))

(defn kvpaths
  ([m] (kvpaths [] m ()))
  ([prev m result]
   (reduce-kv (fn [res k v] (if (associative? v)
                              (let [kp (conj prev k)]
                                (kvpaths kp v (conj res kp)))
                              (conj res (conj prev k))))
              result
              m)))

(defn keypaths
  ([m] (keypaths [] m ()))
  ([prev m result]
   (reduce-kv (fn [res k v] (if (associative? v)
                              (keypaths (conj prev k) v res)
                              (conj res (conj prev k))))
              result
              m)))

(defmacro timed-exec [expr] ;; expression based, not fn based
  `(let [start# (System/currentTimeMillis)
         result# ~expr
         end# (System/currentTimeMillis)]
     {:result result#
      :fn-start start#
      :fn-end end#
      :elapsed-ms (- end# start#)}))

(defn dissoc-in [map-in keypath]
  (let [base-kp (pop keypath)
        last-kp (last keypath)]
    (update-in map-in base-kp dissoc last-kp)))

(defn ppln [x]
  (puget/with-options {:width 330}
    (puget/cprint x)))

(defn pplno [_] nil) ;; TODO, goofy

(defn chan? [ch]
  (try (-> ch class .getName (= "clojure.core.async.impl.channels.ManyToManyChannel"))
       (catch Exception _ false)))

(defn unkeyword [k]
  (cstr/replace (str k) #":" "")
  ;(name k) ;; caused error? w namespaced keywords?
  )

(defn namespaced? [k]
  (and k (namespace k)))

(defn ms-to-iso8601 [ms]
  (let [instant (Instant/ofEpochMilli ms)
        formatter (DateTimeFormatter/ISO_INSTANT)]
    (.format formatter instant)))

(defn is-hiccup? [x] (cstr/includes? (str x) ":div")) ;; TODO, no need anymore?

(defn coll-of-maps? [x]
  (and (or (list? x)
           (vector? x))
       (not (empty? x)) (every? map? x)))

(defn has-nested-map-values? [x]
  (try (let [aa (some true?
                      (vec (if (and (or (list? x) (vector? x)) (not (empty? x)) (coll-of-maps? x))
                             (if (map? (first x))
                               (for [k (keys (first x))]
                                 (or (map?    (get (first x) k))
                                     (vector? (get (first x) k)))) false) false)))]
         (if aa true false)) (catch Exception _ true)))

(defn data-typer [x] ;; legacy data rabbit code. TODO revisit
  ;(let [x (if (instance? clojure.lang.IPending x) (doall (take 20 x)) x)] ;; if lazy, do a limited realize
  (cond   (or (and (vector? x) (clojure.string/includes? (str x) "#object"))
              (and (vector? x) (fn? (first x))) ;; ?
              (and (vector? x) (cstr/starts-with? (str (first x)) ":re-com"))
              (and (vector? x) (cstr/starts-with? (str (first x)) ":vega"))
              (and (vector? x) (is-hiccup? x))) "render-object"
          (string? x) "string"
          (boolean? x) "boolean"
          ;(and (vector? x) (string-or-hiccup? x)) "hiccup"
          ;(and (coll-of-maps? x)
          ;     (not (has-nested-map-values? x)))
          (coll-of-maps? x)
          "rowset"  ;;; TODO, revisit. convoluted logic
          (vector? x) "vector"
          (or (and (map? x)
                   (contains? x :classname)
                   (contains? x :subprotocol)
                   (contains? x :subname))
              (and (map? x)
                   (contains? x :dbtype)
                   (contains? x :dbname)
                   (contains? x :user))) "jdbc-conn"
          (map? x)  "map"
          (list? x) "list"
          (nil? x)  "nil"
          (int? x)  "integer"
          (set? x)  "set"
          (instance? clojure.lang.IPending x) "lazy"
          (keyword? x) "keyword"
          (float? x) "float"
          (ifn? x) "function"
          :else "unknown"));)

(defn gen-coords [blocks] ;; awful attempt at auto coord / layout placing - revist later
  (let [start-x 100
        start-y 100
        step-x 330  ; 250 + 80
        step-y 330  ; 250 + 80
        placed-blocks (atom {})
        max-y (atom start-y)
        queue (atom blocks)]

    (while (seq @queue)
      (let [[from to] (first @queue)]
        (swap! queue rest)

        (when (not (contains? @placed-blocks from))
          (do
            (swap! placed-blocks assoc from [start-x @max-y])
            (swap! max-y #(+ % step-y))))

        (when (not (contains? @placed-blocks to))
          (let [[from-x from-y] (@placed-blocks from)
                to-x (+ from-x step-x)
                to-y (if (= from-x to-x) (+ from-y step-y) from-y)]
            (do
              (swap! placed-blocks assoc to [to-x to-y])
              (when (> to-y @max-y) (swap! max-y #(+ % step-y))))))))

    (mapv (fn [[block [x y]]] [block x y]) @placed-blocks)))

(defn coords-map [connections]
  (into {}
        (for [[bid x y] (gen-coords connections)]
          {bid {:x x :y y}})))

;; spec explorations TODO, revisit

(def nspc ['flowmaps.core 'clojure.set])

(defn get-fn-specs [ns-sym]
  (->> (s/registry)
       (filter #(= (namespace (key %)) (name ns-sym)))
       (into {})))

(defn instrument-ns [ns-syms & [un?]] ;; nice 
  (doseq [ns-sym ns-syms]
    (->> (ns-publics ns-sym)
         keys
         (filter #(s/get-spec `%))
         (apply (if un?
                  stest/unstrument
                  stest/instrument)))))

(defn test-value [ns-syms args]
  (if (vector? args)
    (let [specs (mapcat get-fn-specs ns-syms)]
      (->> specs
           (map (fn [[k v]]
                  (let [args-spec (:args (s/spec v))]
                    [(str (namespace k) "/" (name k)) (not= (s/conform args-spec args) :clojure.spec.alpha/invalid)])))
           (into (sorted-map))))
    (println "The provided arguments should be a vector.")))


