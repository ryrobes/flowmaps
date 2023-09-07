(ns flowmaps.rest
  (:refer-clojure :exclude [abs update-vals update-keys])
  (:require [clojure.string :as cstr]
            [clojure.edn :as edn]
            [flowmaps.db :as db]
            [flowmaps.utility :as ut]
            [clojure.core.async :as async]
            [io.pedestal.http :as http]))

(defn send-edn [content]
  (assoc (http/edn-response content) :headers {"Content-Type" "application/edn"}))

(defn send-file [content filename]
  (assoc (http/edn-response content) :headers {"Content-Type" "application/octet-stream"
                                               "Content-Disposition" "attachment"
                                               "filename" filename}))

(defn push! [flow-id block-id keypath values & [ts tse]] ;; for default subscription queue usage (soon deprecated due to channel-history?)
  (let [bd (try (get-in @db/block-defs [flow-id (nth keypath 2)]) (catch Exception _ nil))
        rewind (get-in @db/working-data [flow-id :rewinds])
        rewind-limit? (not (= rewind 0))
        tse (when tse (if (= ts tse) (+ tse 2) tse)) ;; add 2 ms for viz purposes! TODO document
        tsh-full (vec (sort (distinct (conj (conj (get-in @db/block-dump [flow-id :ts-history] []) ts) tse))))
        tsh (if rewind-limit? 
              ;(take-last rewind (vec tsh-full))
              (take rewind (reverse tsh-full))
              tsh-full)
        ;tsh tsh-full
        ]
    (swap! db/block-dump assoc-in keypath values) ;; populate block-dump atom incrementally
    (when (and ts tse) ;; supplemental dump keys - only want history entries, not rando key changes
      #_{:clj-kondo/ignore [:redundant-do]}
      (do  ;; ^ again. *not* redundant here.
        (swap! db/block-dump assoc-in [flow-id :block-history block-id]
               (conj
                (if rewind-limit? (vec (take-last rewind (sort-by :start (get-in @db/block-dump [flow-id :block-history block-id] []))))
                    (get-in @db/block-dump [flow-id :block-history block-id] []))
                {:start ts :end tse :body values}))
        (swap! db/block-dump assoc-in [flow-id :ts-history] tsh)))
    (when (and ts bd)
      (swap! db/waffle-data assoc flow-id
             (conj (if rewind-limit? (vec (take-last rewind (sort-by :start (get @db/waffle-data flow-id))))
                                  (get @db/waffle-data flow-id))
                   {:name (str (nth keypath 2)) :type (ut/data-typer (get values :v)) :start ts :end tse
                    :number (count (filter #(= (get % :name) (str (nth keypath 2))) (get @db/waffle-data flow-id)))})))
    ;(swap! db/web-push-history conj {:kp keypath :flow-id flow-id :values values :start ts :end tse :block-def bd :block-id (get keypath 2)})
    ;(swap! queue-atom conj [flow-id keypath values ts tse (str bd)]) ;; actual sub queue (deprecated?) 
    ))

(defn push-channel-value [channels flow-id value]
  (doall
   (doseq [channel-name channels]
     (let [start (System/currentTimeMillis)
           channel-name (if (string? channel-name)
                          (edn/read-string channel-name) channel-name) ;; front-end channel names are strings
           from (first channel-name)
           v value]
       (ut/ppln [:incoming-channel-push channel-name value])
       (push! flow-id from [flow-id :blocks from :body] {:v value} start (System/currentTimeMillis))
       (swap! db/channel-history update flow-id conj {:path [:pushed :from :web]
                                                      :type :channel
                                                      :channel channel-name
                                                      :dest (last channel-name)
                                                      :start start
                                                      :end (System/currentTimeMillis)
                                                      :value (ut/limited v flow-id)
                                                      :data-type (ut/data-typer v)})
       (swap! db/fn-history assoc flow-id (conj (get @db/fn-history flow-id []) {:block from :from :static
                                                                                 :path [:from :static from]
                                                                                 :value (ut/limited value flow-id)
                                                                                 :type :function
                                                                                 :dest from
                                                                                 :channel [from]
                                                                                 :data-type (ut/data-typer (ut/limited value flow-id))
                                                                                 :start start
                                                                                 :end (System/currentTimeMillis)
                                                                                 :elapsed-ms (- (System/currentTimeMillis) start)}))
       (async/put! (get-in @db/channels-atom [flow-id channel-name]) {:sender (last channel-name) :value value})
       [[:pushed flow-id channel-name] value]))))

;; test
;; curl -X POST -s -H "Content-Type: application/edn" -H "Accept: application/edn" -d '{:value 45 :channel [:int1 :adder/in1]}' http://localhost:8000/flow-value-push/odds-and-evens
;; curl -X POST -s -H "Content-Type: application/edn" -H "Accept: application/edn" -d '{:value 44 :channel [:int1 :adder/in1] :return [:display-val :done]}' http://localhost:8000/flow-value-push/odds-and-evens

(defn wait-for-event [channel flow-id start-time timeout-ms]
  (try
    (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
      (loop []
        (let [events (get @db/channel-history flow-id)
              event (first (filter #(and (= (get % :channel) channel)
                                         (>= (get % :end) start-time))
                                   events))]
          (cond
            event event ;(get event :value) ; Found the desired event, return it
            (> (System/currentTimeMillis) deadline)
            (throw (Exception. (str "Timeout waiting for event to hit channel " channel " in flow " flow-id)))
            :else (do (Thread/sleep 300) ; Wait for a short duration before polling again
                      (recur))))))
    (catch Exception e {:error (str e)})))

(defn flow-point-push [request]
  (try 
    (let [point-id (get-in request [:path-params :point-id])
        flow-id (get-in request [:path-params :flow-id])
        point-data (get-in @db/working-data [flow-id :points point-id])
        time-key (System/currentTimeMillis)
        value (get request :edn-params) ;(get-in request [:edn-params :value])
        channel (first point-data) ;(get-in request [:edn-params :channel])
        return-channel (last point-data) ;(get-in request [:edn-params :return])
        _ (push-channel-value [channel] flow-id value)
        return (wait-for-event return-channel flow-id time-key 30000)]
    (let [base {:flow-id flow-id ;; extra let to wait until return?
                :return-value (get return :value)
                :channel-sent-to channel
                :value-sent value
                :value value
                :return-channel return-channel}
          err-base {:flow-id flow-id
                    :return-value return
                    :channel-sent-to channel
                    :value-sent value
                    :error (get return :error)
                    :return-channel return-channel}
          error? (get return :error)]
      (send-edn (if error? err-base base))))
      (catch Exception e (send-edn {:error! (str e)})))) ;; TODO, full errors (cant find channel, cant find flow, cant find point)

(defn flow-value-push [request]
  (if (get-in request [:edn-params :return]) ;; are we picking up a channel val to return?
    (let [time-key (System/currentTimeMillis)
          value (get-in request [:edn-params :value])
          flow-id (get-in request [:path-params :flow-id])
          channel (get-in request [:edn-params :channel])
          return-channel (get-in request [:edn-params :return])
          _ (push-channel-value [channel] flow-id value)
          return (wait-for-event return-channel flow-id time-key 30000)]
      (let [base {:flow-id flow-id ;; extra let to wait until return?
                  :return-value (get return :value)
                  :channel-sent-to channel
                  :value-sent value
                  :value value
                  :return-channel return-channel}
            err-base {:flow-id flow-id
                      :return-value return
                      :channel-sent-to channel
                      :value-sent value
                      :error (get return :error)
                      :return-channel return-channel}
            error? (get return :error)]
        (send-edn (if error? err-base base))))
    (let [time-key (System/currentTimeMillis)
          value (get-in request [:edn-params :value])
          flow-id (get-in request [:path-params :flow-id])
          channel (get-in request [:edn-params :channel])]
      (push-channel-value [channel] flow-id value)
      (send-edn {:flow-id flow-id
                 ;:sent? true
                 ;:result result
                 ;:result2 result2
                 :channel-sent-to channel
                 :value-sent value}))))

  