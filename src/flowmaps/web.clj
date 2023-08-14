(ns flowmaps.web
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
            [io.pedestal.http :as server]
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

(defonce websocket-server (jetty/run-jetty #'web-handler ring-options))

;;; super simple pedestal cfg for serving static SPA re-frame root...
(defn static-root [_] (ring-resp/content-type (ring-resp/resource-response "index.html" {:root "public"}) "text/html"))
(def common-interceptors [(body-params/body-params) http/html-body])

(def routes #{["/" :get (conj common-interceptors `static-root)]
              ["/flow-value-push/:flow-id" :post (conj common-interceptors `rest/flow-value-push)]})

(def service {:env :prod
              ::http/routes routes
              ::http/allowed-origins {:creds false :allowed-origins (constantly true)}
              ::http/secure-headers {:content-security-policy-settings {:object-src "none"}}
              ::http/resource-path "/public"
              :max-threads 50
              ::http/type :jetty
              ::http/host "0.0.0.0"
              ::http/port 8888
              ::http/container-options {:h2c? true
                                        :h2? false
                                        :ssl? false}})

(defonce runnable-service (http/create-server service))

(def web-server (atom nil))

(defn create-web-server! []
  (ut/ppln [:*web (format "starting web ui @ http://localhost:%d" 8888) "🐇" ])
  (reset! web-server (future (http/start runnable-service))))

(defn destroy-web-server! []
  (ut/ppln [:*web (format "stopping web server @ %d" 8888)])
  (reset! web-server nil))

(defn start! [] ;; start the web server and websocket server
  (.start websocket-server)
  (create-web-server!)
  (reset! websocket? true))

(defn stop! [] ;; stop the web server and websocket server
  (ut/ppln [:*websocket (format "stopping websocket server @ %d" 3000)])
  (.stop websocket-server)
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
             (into (get @db/channel-history2 flow-id)
                   (into (get @db/channel-history flow-id)
                         (get @db/fn-history flow-id)))
             :let [endv (if (= start end) (+ end 1) end)]] ;; 5ms boost for gantt visibility, update docs
         (merge {:channel (str channel)
                 :path (str path)
                 :ms (- end start)
                 :data-type data-type
                 :value (ut/limited value)
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

(defmethod wl/handle-request :get-waffle-data [_] ;; for the waffle chart "airflow-esque" modal
  (let [waffle-data (into {} (for [[flow-id wv] @db/waffle-data]
                               {flow-id (vec (sort-by :start wv))}))]
    [nil [:waffles] waffle-data]))

(defmethod wl/handle-request :get-flow-maps [_]
  [nil [:flow-maps] @db/working-data])

(defmethod wl/handle-request :get-block-dump [{:keys [flow-id]}] ;; entire model for the UI for a particular flow-id (dump instead of drip)
  (let [channel-history (format-channel-history flow-id)
        block-dump (merge (get @db/block-dump flow-id)
                          {:channel-history channel-history})]
    [flow-id [flow-id] block-dump]))

(defmethod wl/handle-request :push-channel-value [{:keys [channels flow-id value]}] ;; ui trying to push specific channel value
  (rest/push-channel-value channels flow-id value))

;; (defmethod wl/handle-request :push-live-flow [{:keys [value]}] ;; live eval and send it
;;   (let [evl (read-string value)
;;         ost (with-out-str (read-string value))]
;;     (ut/ppln [:incoming-live-flow value evl])
;;     [:live-flow-return [:live-flow-return] {:return (pr-str evl)
;;                                             :output (pr-str ost)}]))
