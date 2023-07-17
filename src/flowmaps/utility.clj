(ns flowmaps.utility
  (:require [puget.printer :as puget]
            [clojure.set :as cset]
            [talltale.core :as tales]
            [clojure.spec.test.alpha :as stest]
            [clojure.spec.alpha :as s]
            [clojure.string :as cstr])
  (:import java.time.Instant
           ;java.time.ZoneId
           java.time.format.DateTimeFormatter))

;; odds and ends

(defn dequeue! [queue]
  (let [item (peek @queue)]
    (swap! queue pop)
    item))

(defn generate-name []
  (let [quals ["of-the" "hailing-from" "banned-from" "of" "exiled-from" "from"]
        names [(tales/quality) (rand-nth [(tales/shape) (tales/color)]) (tales/animal) (rand-nth quals) (tales/landform)]]
    (cstr/replace (clojure.string/join "-" names) " " "-")))

(defn ppln [x]
  (puget/with-options {:width 245}
    (puget/cprint x)))

(defn pplno [_] nil) ;; TODO, goofy

(defn chan? [x] 
  (try (-> x class .getName (= "clojure.core.async.impl.channels.ManyToManyChannel")) (catch Exception _ false)))

(defn unkeyword [k]
  (cstr/replace (str k) #":" "")
  ;(name k) ;; caused error? w namespaced keywords?
  )

(defn namespaced? [k]
  (not (nil? (namespace k))))

(defn ms-to-iso8601 [ms]
  (let [instant (Instant/ofEpochMilli ms)
        formatter (DateTimeFormatter/ISO_INSTANT)]
    (.format formatter instant)))

(defn is-hiccup? [x] (cstr/includes? (str x) ":div")) ;; TODO, no need anymore?

(defn data-typer [x] ;; legacy data rabbit code. TODO revisit
  (cond   (or (and (vector? x) (clojure.string/includes? (str x) "#object"))
              (and (vector? x) (fn? (first x))) ;; ?
              (and (vector? x) (cstr/starts-with? (str (first x)) ":re-com"))
              (and (vector? x) (cstr/starts-with? (str (first x)) ":vega"))
              (and (vector? x) (is-hiccup? x))) "render-object"
          (string? x) "string"
          (boolean? x) "boolean"
          ;(and (vector? x) (string-or-hiccup? x)) "hiccup"
          ;; (and (vector-of-maps? x)
          ;;      ;true
          ;;      (not (has-nested-map-values? x)))
          ;; "rowset"  ;;; TODO, revisit
          (vector? x) "vector"
          (or (and (map? x)
                   (contains? x :classname)
                   (contains? x :subprotocol)
                   (contains? x :subname))
              (and (map? x)
                   (contains? x :dbtype)
                   (contains? x :dbname)
                   (contains? x :user))) "jdbc-conn"
          (map? x) "map"
          (list? x) "list"
          (nil? x) "nil"
          (int? x) "integer"
          (set? x) "set"
          (= (str (type x)) "cljs.core/LazySeq") "lazy"
          ;(= clojure.core/LazySeq (type x)) "lazy"
          (keyword? x) "keyword"
          (float? x) "float"
          ;(and (ifn? x) (not (cstr/includes? x "("))) "unquoted"
          (ifn? x) "function"
          :else "unknown"))

(defn gen-coords [blocks] ;; awful attempt at auto coord / layout placing
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


