(ns flowmaps.utility
  (:require [puget.printer :as puget]
            [flowmaps.db :as db]
            [clojure.set :as cset]
            [talltale.core :as tales]
            [debux.core :as dx]
            [clojure.spec.test.alpha :as stest]
            [clojure.spec.alpha :as s]
            [clojure.walk :as walk]
            [clojure.pprint :as pprint]
            [clojure.string :as cstr])
  (:import java.time.Instant
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

(defn limit-elements [x]
  (try (cond
         (map? x) x ;; dont limit maps elements at all 
         (instance? clojure.lang.IPending x) (doall (take db/sample-limit x))
         :else x)
       (catch Exception _ x)))

(defn limited [x]
  (letfn [(limited-helper [x]
            (cond
              (map? x) (into (empty x) (map (fn [[k v]] [k (limited-helper v)])) x)
              (coll? x) (into (empty x) (take db/sample-limit (map limited-helper x)))
              (and (not (string? x)) (not (keyword? x)) (not (number? x)))
              (try (doall (take db/sample-limit x)) (catch Exception _ x))
              :else x))]
    (limited-helper x)))

(defn limited-t [x]
  (walk/postwalk limited x))

(defn nf [i]
  (pprint/cl-format nil "~:d" i))

(defmacro timed-exec [expr] ;; expression based, not fn based
  `(let [start# (System/currentTimeMillis)
         result# ~expr
         end# (System/currentTimeMillis)]
     {:result result#
      :fn-start start#
      :fn-end end#
      :elapsed-ms (- end# start#)}))

;; (defmacro timed-exec-with-dbgn [expr]
;;   `(let [dbgn-output# (with-out-str (debux.core/dbgn ~expr))
;;          exec-result# (timed-exec ~expr)]
;;      (assoc exec-result# :dbgn-output dbgn-output#)))

(defn ppln [x]
  (puget/with-options {:width 330}
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


