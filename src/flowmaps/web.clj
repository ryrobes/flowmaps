(ns flowmaps.web
  (:require [clojure.string :as cstr]
            [flowmaps.utility :as ut]
            [flowmaps.db :as db]
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

(def routes #{["/" :get (conj common-interceptors `static-root)]})

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

(defmethod wl/handle-request :channel-gantt-data [{:keys [flow-id]}] ;; channel-history data for flow-id X, UI used in multiple ways 
  (let [data (vec (for [{:keys [channel start end path data-type value]} (get @db/channel-history flow-id)
                        :let [endv (if (= start end) (+ end 2) end)]] ;; temp 2ms boost for gantt visibility...
                    {:channel (str channel)
                     :path (str path)
                     :data-type data-type
                     :value value
                     :raw-end endv
                     :raw-start start
                     :start (ut/ms-to-iso8601 start)
                     :end (ut/ms-to-iso8601 endv)}))]
    (when (not (empty? data)) ;; don't want to overwrite UI w []
      [flow-id [flow-id :channel-history] data])))

(defmethod wl/handle-request :get-network-map [{:keys [flow-id]}] ;; working flow-map for flow-id X
  [flow-id [flow-id :network-map] (get @db/working-data flow-id)])

(defmethod wl/handle-request :get-waffle-data [_] ;; for the waffle chart "airflow-esque" modal
  (let [waffle-data (into {} (for [[flow-id wv] @db/waffle-data]
                               {flow-id (vec (sort-by :start wv))}))]
    [nil [:waffles] waffle-data]))

(defmethod wl/handle-request :push-channel-value [{:keys [kind channel-name flow-id value]}] ;; ui trying to push specific channel value
  (do (ut/ppln [:incoming-channel-push kind channel-name value])
    (async/put! (get-in @db/channels-atom [flow-id channel-name]) {:sender (last channel-name) :value value})
    [[:pushed flow-id channel-name] value]))

(defn push! [flow-id keypath values & [ts tse]] ;; for default subscription queue usage (soon deprecated due to channel-history?)
  (let [bd (try (get-in @db/block-defs [flow-id (nth keypath 2)]) (catch Exception _ nil))
        tse (when tse (if (= ts tse) (+ tse 2) tse))] ;; add 2 ms for viz purposes! TODO document
    (when (and ts bd) (swap! db/waffle-data assoc flow-id (conj (get @db/waffle-data flow-id) 
                                                                {:name (str (nth keypath 2)) :type (ut/data-typer (get values :v)) :start ts :end tse 
                                                                 :number (count (filter #(= (get % :name) (str (nth keypath 2))) (get @db/waffle-data flow-id)))
                                                                 })))
    (swap! db/web-push-history conj {:kp keypath :flow-id flow-id :values values :start ts :end tse :block-def bd :block-id (get keypath 2)})
    (swap! queue-atom conj [flow-id keypath values ts tse (str bd)])))
