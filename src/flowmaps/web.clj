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
(defn home-page [_] (ring-resp/response "Hello World! Home!"))
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
  (reset! web-server
          (future (http/start runnable-service))))

(defn destroy-web-server! []
  (ut/ppln [:*web (format "stopping web server @ %d" 8888)])
  (reset! web-server
          nil))

(defn start! []
  (try (do
         ;(ut/ppln [:*websocket (format "starting websocket server @ %d" 3000)])
         (.start websocket-server)
         (create-web-server!)
         (reset! websocket? true)) (catch Exception e (println e))))

(defn stop! []
  (ut/ppln [:*websocket (format "stopping websocket server @ %d" 3000)])
  (.stop websocket-server)
  (destroy-web-server!)
  (reset! websocket? false))

(def queue-atom (atom clojure.lang.PersistentQueue/EMPTY))

(defmethod wl/handle-subscription :external-editing [{:keys [kind client-id]}]
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

(defmethod wl/handle-request :channel-gantt-data [_]
  [[:channel-history] (vec (for [{:keys [channel start end path data-type value]} @db/channel-history
                                 :let [endv (+ end 100)]] ;; 
                             {:channel (str channel)
                              :path (str path)
                              :data-type data-type
                              :value value
                              :raw-end endv
                              :raw-start start
                              :start (ut/ms-to-iso8601 start)
                              :end (ut/ms-to-iso8601 endv)}))])

(defmethod wl/handle-request :get-network-map [_]
  [[:network-map] @db/working-data])

(defmethod wl/handle-request :push-channel-value [{:keys [kind channel-name value]}]
  (do (ut/ppln [:incoming-channel-push kind channel-name value])
    (async/put! (get @db/channels-atom channel-name) {:sender :webpush :value value})
    [[:pushed channel-name] value]))

(defn push [keypath values & [ts]]
  (let [bd (try (get-in @db/block-defs [(nth keypath 1)]) (catch Exception _ nil))]
    (swap!
   ;(rand-nth [queue-atom-alt queue-atom])
     queue-atom
     conj [keypath values ts (str bd)])))

;; (defn push-alt [keypath values]
;;   (swap! queue-atom-alt conj [keypath values]))
