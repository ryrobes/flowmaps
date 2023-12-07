(ns flowmaps.web
  (:refer-clojure :exclude [abs update-vals update-keys])
  (:require [clojure.string :as cstr]
            [flowmaps.utility :as ut]
            [flowmaps.db :as db]
            [flowmaps.rest :as rest]
            [clojure.edn :as edn]
            [websocket-layer.core :as wl]
            [websocket-layer.network :as net]
            [clojure.core.async :as async]
            [io.pedestal.http :as http]
            [io.pedestal.http.body-params :as body-params]
            [ring.util.response :as ring-resp]
            [ring.adapter.jetty9 :as jetty])
  (:gen-class))

(defn web-handler [_] ;; "fake" placeholder
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    "<html><head></head><body>no one will ever see this</body></html>"})

(def ws-endpoints
  {"/ws" (net/websocket-handler {:encoding :edn})})

(def ring-options
  {:port                 3000
   :join?                false
   :async?               true
   :websockets           ws-endpoints
   :allow-null-path-info true})

(defonce websocket? (atom false))

(defonce websocket-server (delay (jetty/run-jetty #'web-handler ring-options))) ;; delay and @call?

;;; super simple pedestal cfg for serving static SPA re-frame root...
(defn static-root [_] (ring-resp/content-type (ring-resp/resource-response "index.html" {:root "public"}) "text/html"))
(def common-interceptors [(body-params/body-params) http/html-body])

(def routes #{["/" :get (conj common-interceptors `static-root)]
              ["/flowpoint/:flow-id/:point-id" :post (conj common-interceptors `rest/flow-point-push)]
              ["/flow-value-push/:flow-id" :post (conj common-interceptors `rest/flow-value-push)]})

(def service {:env :prod
              ::http/routes routes
              ::http/allowed-origins {:creds false :allowed-origins (constantly true)}
              ::http/secure-headers {:content-security-policy-settings {:object-src "none"}}
              ;::http/secure-headers {:content-security-policy-settings {;:default-src "*"
              ;                                                          ;:script-src "*"
              ;                                                          :frame-ancestors "*"}
              ;                       :x-frame-options "ALLOW"}
              ::http/resource-path "/public"
              :max-threads 50
              ::http/type :jetty
              ::http/host "0.0.0.0"
              ::http/port 8000
              ::http/container-options {:h2c? true
                                        :h2? false
                                        :ssl? false}})

(defonce runnable-service (http/create-server service))

(def web-server (atom nil))

(defn create-web-server! []
  (ut/ppln [:*web (format "starting web ui @ http://localhost:%d" 8000) "🐇" ])
  (reset! web-server (future (http/start runnable-service))))

(defn destroy-web-server! []
  (ut/ppln [:*web (format "stopping web server @ %d" 8000)])
  (reset! web-server nil))

(defn start! [] ;; start the web server and websocket server
  (.start @websocket-server)
  (create-web-server!)
  (reset! websocket? true))

(defn stop! [] ;; stop the web server and websocket server
  (ut/ppln [:*websocket (format "stopping websocket server @ %d" 3000)])
  (.stop @websocket-server)
  (destroy-web-server!)
  (reset! websocket? false))

(def queue-atom (atom clojure.lang.PersistentQueue/EMPTY))

(defmethod wl/handle-subscription :external-editing [{:keys [kind client-id]}] ;; default subscription server->client push "queue"...
  (let [results (async/chan)]
    (async/go-loop []
      (async/<! (async/timeout 500)) ;; 600-800 seems ideal
      (if-let [item (ut/dequeue! queue-atom)]
        (when (async/>! results item)
          (recur))
        (recur)))
    results))

;; (def queue-atom-alt (atom clojure.lang.PersistentQueue/EMPTY))

;; (defmethod wl/handle-subscription :result-history [{:keys [kind client-id]}]
;;   (let [results (async/chan)]
;;     (async/go-loop []
;;       (async/<! (async/timeout 500))
;;       (if-let [item (ut/dequeue! queue-atom-alt)]
;;         (when (async/>! results item)
;;           (recur))
;;         (recur)))
;;     results))

(defn format-channel-history [flow-id]
  (vec (for [{:keys [channel start end path data-type value dest dbgn type]}
             (into [] ;(get @db/channel-history2 flow-id)
                   (into (get @db/channel-history flow-id)
                         (get @db/fn-history flow-id)))
             :let [endv (if (= start end) (+ end 1) end)]] ;; 5ms boost for gantt visibility, update docs
         (merge {:channel (str channel)
                 :path (str path)
                 :ms (- end start)
                 :data-type data-type
                 :value (ut/limited value flow-id)
                 :type type
                 :dest (str dest)
                 :raw-end endv
                 :raw-start start
                 :start (ut/ms-to-iso8601 start)
                 :end (ut/ms-to-iso8601 endv)}
                (if dbgn {:dbgn dbgn} {})))))

(defmethod wl/handle-request :channel-gantt-data [{:keys [flow-id]}] ;; channel-history data for flow-id X, UI used in multiple ways 
  (let [data (format-channel-history flow-id)]
    (when (not (empty? data)) ;; don't want to overwrite UI w []
      [flow-id [flow-id :channel-history] data])))

(defmethod wl/handle-request :get-network-map [{:keys [flow-id]}] ;; working flow-map for flow-id X
  [flow-id [flow-id :network-map] (get @db/working-data flow-id)])

(defmethod wl/handle-request :get-waffle-data [{:keys [waffle-day order-by limit search]
                                                :or {limit 15 
                                                     order-by :time-asc}}] ;; for the waffle chart "airflow-esque" modal
  (let [today (ut/today-yyyymmdd)
        waffle-start (if (nil? waffle-day) (ut/date-str-to-unix today) (ut/date-str-to-unix waffle-day))
        waffle-end (+ waffle-start 86399999)
        waffle-data (into {} (for [[flow-id wv] @db/waffle-data]
                               {flow-id (vec (sort-by :start wv))}))
        live-channels (into {} (for [[k v] @db/channels-atom
                                     :let [chs (vec (keys v))]
                                     :when (not (empty? chs))]
                                 {k (vec (keys v))}))
        order-fn (case order-by
                   :time-asc (fn [v] (apply min (map :start (val v))))
                   :time-desc (fn [v] (apply min (map :start (val v))))
                   :alpha-asc identity
                   :alpha-desc identity
                   :live (juxt (fn [x] (some #(= % (key x)) (keys live-channels))) (fn [v] (apply min (map :start (val v))))))
        waffle-data-limited (into {} (for [[k v] waffle-data
                                           :when (if (not (empty? search)) (cstr/includes? k search) true)]
                                       {k (filter #(and (>= (get % :start) waffle-start)
                                                        (<= (get % :end) waffle-end)) v)}))
        waffle-data-pruned (into {} (for [[k v] waffle-data-limited ;; remove empty flow-ids
                                          :when #_{:clj-kondo/ignore [:not-empty?]}
                                          (not (empty? v))]
                                      {k v}))
        waffle-data-pruned-ordered (if (some #(= % order-by) [:time-desc :alpha-desc :live])
                                     (take limit (doall (reverse (sort-by order-fn waffle-data-pruned))))
                                     (take limit (doall (sort-by order-fn waffle-data-pruned))))
        timestamps (distinct (flatten (apply conj (for [[_ v] waffle-data]
                                                    (for [vv v] [(get vv :start)
                                                                 (get vv :end)])))))
        ;; live-channels (into {} (for [[k v] @db/channels-atom] 
        ;;                          {k (vec (for [[ck cv] v
        ;;                                   :when (ut/chan-open? cv)] ck))}))

        days (into {}
                   (->> timestamps
                        (map ut/unix-to-date)
                        (map ut/date-to-ymd)
                        (group-by identity)
                        (map (fn [[k v]]
                               {k (count v)}))))]
    ;(ut/ppln [waffle-day waffle-start waffle-end])
    ;(ut/ppln [:live-channels live-channels])
    [nil [:waffles] {:waffles waffle-data-pruned-ordered
                     :running live-channels
                     :schedules (vec (for [{:keys [flow-id override schedule]} @db/live-schedules]
                                       {:schedule schedule
                                        :flow-id flow-id
                                        :override override}))
                     :filter-start waffle-start
                     :filter-end waffle-end
                     :time-data days}]))

(defmethod wl/handle-request :get-flow-maps [_]
  [nil [:flow-maps] @db/working-data])

(defmethod wl/handle-request :get-block-dump [{:keys [flow-id waffle-day]}] ;; entire model for the UI for a particular flow-id (dump instead of drip)
  (let [waffle-start (if (nil? waffle-day) (ut/date-str-to-unix (ut/today-yyyymmdd)) (ut/date-str-to-unix waffle-day))
        waffle-end (+ waffle-start 86399999)
        rewind (get-in @db/working-data [flow-id :rewinds] 0)
        rewind-limit? (not (= rewind 0))
        channel-history (format-channel-history flow-id)
        ;channel-history (if rewind-limit? (take-last rewind (sort-by :raw-start channel-history)) channel-history)
        channel-history (try (if rewind-limit? (take-last rewind (sort-by #(get % :raw-start 0) channel-history)) channel-history)
                             (catch Throwable _ channel-history)) ;; TODO - diagnose hiccups here
        block-dump (merge (get @db/block-dump flow-id)
                          {:channel-history channel-history})
        ;block-dump-filtered (filter #(and (>= (get % :start) waffle-start)
        ;                                  (<= (get % :end) waffle-end)) block-dump)
        block-dump-filtered (into {}
                                  (for [[k v] block-dump]
                                    {k (cond ;(some #(= % k) [:blocks :network-map]) v ;; do nothing
                                         (= k :block-history) (into {} (for [[kk vv] v]
                                                                         {kk (filter #(and (>= (get % :start) waffle-start)
                                                                                           (try (let [ch (edn/read-string (get % :channel))]
                                                                                                  (if (vector? ch)
                                                                                                    (not (nil? (first ch)))
                                                                                                    true)) (catch Exception _ true)) ;; function "channel"
                                                                                           (<= (get % :end) waffle-end)) vv)}))
                                         (= k :ts-history) (vec (filter #(and (>= % waffle-start) (<= % waffle-end)) v))
                                         (= k :ts-history2) [] ;; deprecated key
                                         (= k :channel-history) (filter #(and (>= (get % :raw-start) waffle-start)
                                                                              (try (let [ch (edn/read-string (get % :channel))]
                                                                                     (if (vector? ch)
                                                                                       (not (nil? (first ch)))
                                                                                       true)) (catch Exception _ true)) ;; function "channel"
                                                                              (<= (get % :raw-end) waffle-end)) v)
                                         :else v)}))]
    ;(ut/ppln (keys block-dump))
    [flow-id [flow-id] block-dump-filtered]))

(defmethod wl/handle-request :push-channel-value [{:keys [channels flow-id value]}] ;; ui trying to push specific channel value
  (rest/push-channel-value channels flow-id value))

;; (defmethod wl/handle-request :push-live-flow [{:keys [value]}] ;; live eval and send it
;;   (let [evl (read-string value)
;;         ost (with-out-str (read-string value))]
;;     (ut/ppln [:incoming-live-flow value evl])
;;     [:live-flow-return [:live-flow-return] {:return (pr-str evl)
;;                                             :output (pr-str ost)}]))
