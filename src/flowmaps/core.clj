(ns flowmaps.core
  (:require [clojure.set :as cset]
            [flowmaps.utility :as ut]
            [flowmaps.web :as web]
            [flowmaps.db :as db]
            [clojure.walk :as walk]
            [clojure.string :as cstr]
            [flowmaps.examples.simple-flows :as fex]
            [clojure.core.async :as async]))

(defn find-keys-with-path [paths-map path-segment]
  (let [segment-length (count path-segment)]
    (reduce
     (fn [acc [k v]]
       (if (some (fn [path]
                   (some #(= path-segment (subvec path % (+ % segment-length)))
                         (range 0 (+ 1 (- (count path) segment-length))))) v)
         (conj acc k)
         acc)) []
     paths-map)))

(defn build-all-paths [node connections visited]
  (let [next-nodes (map second (filter #(= (first %) node) connections))]
    (if (node (set visited))
      [[[node :loop-to] (first visited)]]
      (if (empty? next-nodes)
        [[node]]
        (for [next next-nodes
              path (build-all-paths next connections (cons node visited))]
          (cons node path))))))

(defn build-paths-from-all-nodes [connections]
  (mapcat #(build-all-paths % connections []) (set (map first connections))))

(defn path-map [connections]
  (into {} (for [v (build-paths-from-all-nodes connections)]
             {(hash v) (list (vec 
                              ;(remove #{:loop-to} (flatten v))
                              (flatten v)
                              ))})))

(defn end-chain-msg [fp ff result the-path] ;; TODO better calc of pathfinding to derive natural end points w/o :done blocks
  (let [fp-vals (vec (for [f the-path] [f (get @db/results-atom f)]))
        res-paths (distinct @db/resolved-paths)
        chains (count res-paths)
        last-in-chain? (some #(= the-path %) res-paths)
        chains-run (+ (count (distinct @db/chains-completed)) 1)
        all-chains-done? (= chains chains-run)]
    (ut/ppln [the-path :chain-done! ff :done-hop-or-empty  :res-paths res-paths])

    (when last-in-chain?

      (do
        (ut/ppln [chains-run :chain-done :done-hop ff :steps (count fp-vals) :final-result result
                  :value-path fp-vals chains-run :of chains :flow-chains-completed])
        (swap! db/chains-completed conj the-path))

      (when (and all-chains-done? last-in-chain?)
        (ut/ppln [:all-chains-done! :resolved-paths (vec (distinct @db/resolved-paths))
                  :history (sort-by :ts @db/history-atom)
                  ff :fp fp :results-atom @db/results-atom])))))

(defn process [connections input-chs output-chs ff fp block-function opts-map done-ch]
  (let [web-push? (true? @web/websocket?)
        {:keys [debug?]} opts-map
        pp (if debug? ut/ppln ut/pplno) ;; TODO, grody
        channel-results (atom (into {} (map #(vector % nil) input-chs)))]
    (pp ["Starting processing " ff " with input channels: " input-chs " and output channels: " output-chs])
    (async/go-loop [pending-chs input-chs]
      (when (seq pending-chs)
        (let [[data ch] (async/alts! pending-chs)
              data-from (get data :sender)
              data-val (get data :value)
              next-hops (map second (filter #(= ff (first %)) connections))
              ;is-done-next? (or (= next-hops '(:done)) (empty? next-hops))
              triples [data-from ff (first next-hops)]
              t2 triples ; (vec (remove #(= % :done) (remove nil? triples)))
              poss-path-keys (find-keys-with-path fp t2)
              is-accumulating-ports? (true? (get data-val :port-in?))
              start (System/currentTimeMillis)
              the-path (try
                         (apply max-key count (apply concat (vals (select-keys fp poss-path-keys))))
                         (catch Exception e [e]))
              done-channel-push? (and (= ff :done) (ut/chan? done-ch))]

          (when (= ff :done)  ;; process any :done stuff, debug or not. ends this flow-chain-path basically
            (let [done-channel? (ut/chan? done-ch)
                  done-atom? (instance? clojure.lang.Atom done-ch)
                  channels (+ (count (keys @db/channels-atom)) (count (keys @db/condi-channels-atom)))]
              (cond done-atom? (reset! done-ch data-val)
                    done-channel? (async/>! done-ch data-val)
                    :else nil)
              (println (str data-val))
              (pp [:done-block-reached (cond done-atom? [:sending-to-atom done-ch]
                                             done-channel? [:sending-to-channel done-ch]
                                             :else [:ending-flow-chain (if debug?
                                                                         [:debug-keeping channels :channels-open]
                                                                         [:closing channels :channels])])])))

          (when is-accumulating-ports?
            (swap! db/port-accumulator assoc ff (merge (get @db/port-accumulator ff {}) data-val))) ;; ff is the TO...

          (swap! channel-results assoc ch data-val)
          (swap! db/channel-history conj (let [v (if (and (map? data-val) (get data-val :port-in?))
                                                   (first (vals (dissoc data-val :port-in?)))
                                                   data-val)]
                                           {:path the-path ;; PRE RECUR HISTORY PUSH.
                                            :channel [data-from ff]
                                            :start start
                                            :end (System/currentTimeMillis)
                                            :value v
                                            :data-type (ut/data-typer v)}))

          (if (or (and (not (ut/namespaced? ff))
                       (not (ut/namespaced? data-from)))
                  (not (some nil? (vals @channel-results))))
            ; ^^ check if we've received a value from every channel but only if multiple ins. otherwise default behavior
            (if (= data-val :done) ;; receiving :done keyword as a value is a poison pill to kill pathway
              (do (pp [:chain-done! ff :done-signal])
                  (end-chain-msg fp ff nil the-path))

              (let [block-fn-raw? (fn? block-function)
                    block-is-port? (and (not block-fn-raw?)
                                        (get block-function :fn)
                                        (ut/namespaced? ff))
                    inputs (get block-function :inputs [])
                    resolved-inputs (walk/postwalk-replace (get @db/port-accumulator ff) inputs)
                    fully-resolved? (= 0 (count (cset/intersection (set resolved-inputs) (set inputs))))
                    result (try
                             (cond (and fully-resolved?
                                        is-accumulating-ports?) (apply (get block-function :fn) resolved-inputs)
                                   block-fn-raw? (block-function data-val)
                                   block-is-port? {:port-in? true ff data-val} ;; just accumulating here?
                                   :else ((get block-function :fn) data-val))
                             (catch Exception e
                               (do (ut/ppln [:chain-dead! :exec-error-in ff :processing data-val
                                             :from data-from :block-fns block-function :error e])
                                   :done))) ;; should kill the chain
                    condis (into {}
                                 (for [[path f] (get block-function :cond)]
                                   {path (try (f result) (catch Exception e
                                                           (do (ut/ppln [:block ff :cond path :condi-error e])
                                                               false)))}))
                    condi-path (for [[k v] condis :when v] k)
                    condi-channels (vec (remove nil? (for [c condi-path] (get @db/condi-channels-atom [ff c]))))
                    result-map {:incoming data-val :result result
                                :port-accumulator (get @db/port-accumulator ff)
                                :block-function block-function
                                ;:channel-results @channel-results
                                :is-accumulating-ports? is-accumulating-ports?
                                :fully-resolved? fully-resolved?
                                :resolved-inputs resolved-inputs
                                :inputs inputs
                                :from data-from :to-this ff
                                :triple triples :t2 t2
                                :next next-hops
                                ;:poss-path-keys poss-path-keys
                                ;:poss-paths (apply concat (vals (select-keys fp poss-path-keys)))
                                :path the-path
                                :ts (System/currentTimeMillis)}
                    view (get block-function :view)
                    view-out (when (fn? view) (try (view result) (catch Exception e {:cant-eval-view-struct e :block ff})))
                    output-chs (remove nil? (cond ;; output channel, condis, or regular output switch
                                              done-channel-push? [done-ch]
                                              #_{:clj-kondo/ignore [:not-empty?]}
                                              (not (empty? condi-path))
                                              condi-channels
                                              :else output-chs))]

                (when (and web-push?
                           (not (= ff :done))) ;; :done block is hidden, so no pushing data for it.
                  (web/push [:blocks ff :body]
                            (str
                             (merge ;; needs to be strings to be sent to web ui safely
                              (cond (and fully-resolved?
                                         is-accumulating-ports?)
                                    {:v result
                                     :input resolved-inputs}
                                    (and block-is-port? (ut/namespaced? ff)) ;; only true input ports
                                    {:v data-val
                                     :port-of (first next-hops)}
                                    :else {:v result
                                           :input data-val})
                              (if #_{:clj-kondo/ignore [:not-empty?]}
                               (not (empty? condis)) {:cond condis} {})
                              (if view {:view view-out} {}))) (System/currentTimeMillis)))

                (swap! db/resolved-paths conj the-path) ;; all paths, use distinct 
                (swap! db/results-atom assoc ff result) ;; final / last value sent to block
                (swap! db/history-atom conj result-map) ;; everything map log

                (try (pp [:block ff [data-from ff] :has-received data-val :from data-from :has-output result])
                     (catch Exception e (ut/ppln [:?test? e])))

                (try (when #_{:clj-kondo/ignore [:not-empty?]}
                      (not (empty? output-chs))
                       (if (seq output-chs)
                         (doseq [oo output-chs]  ;; forward processed data to the output channels   
                           (try (async/>! oo (if done-channel-push? result ;; no map wrapper for return channel
                                                 {:sender ff :value result}))
                                (catch Exception e (ut/ppln [:caught-in-push-loop e :outputs (count output-chs)
                                                             :this oo :output-chs output-chs]))))
                         (async/>! output-chs {:sender ff :value result})))
                     (catch Exception e (ut/ppln [:caught-in-doseq-push-loop e :output-chs output-chs])))
                (if
                 (empty? pending-chs)
                  (end-chain-msg fp ff result the-path) ;; never runs in current form. remove.
                  (recur pending-chs))))
            (recur pending-chs)))))))



(defn flow [network-map & [opts-map done-ch subflow-map]]
  ;; procedural clean up - TODO when DB
  (reset! db/results-atom {})
  (reset! db/channel-history [])
  (reset! db/channel-log [])

  ;; channel clean up?
  (when #_{:clj-kondo/ignore [:not-empty?]}      ;; clean up old channels on new run, if 
        (and (not (empty? @db/channels-atom))    ;; - we have channels
             (not (true? @web/websocket?)) ;; - web ui is NOT running
             (not (get opts-map :debug? true))   ;; - debug is NOT enabled
             false)                              ;; bug with this TODO, critical (not that ppl couldn't clean their own channels from @db/channels-atom, but still!)
    (doseq [[k c] @db/channels-atom]             ;; * will be fixed with the concurrent flow / subflow / flow-id changes coming next
      (try (do (ut/ppln [:closing-channel k c])
        (async/close! c)) (catch Exception e (ut/ppln [:error-closing-channel e k c])))))

  (try 
    (let [{:keys [components connections]} network-map
          opts-map (merge {:debug? true} opts-map) ;; merge w defaults, pass as map to process
          {:keys [debug?]} opts-map
          pp (if debug? ut/ppln ut/pplno) ;; TODO, gross.
          web-push? (true? @web/websocket?)
          gen-connections (for [[_ f] connections
                                :let [base (ut/unkeyword (first (cstr/split (str f) #"/")))]
                                :when (cstr/includes? (str f) "/")]
                            [f (keyword base)])
          named-connections connections
          connections (vec (distinct (into connections gen-connections)))
          channels (into {} (for [conn connections] ;; TODO connection opts map for channel size, etc. Now def'd 1.
                              {conn (async/chan 1)}))
          components (assoc
                      (merge ;; filter out comps that have no connections, no need for them - TODO, kinda grody
                       (merge (into {} (for [[_ v] components
                                             :when (get v :cond)]
                                         (get v :cond)))
                              (into {}
                                    (flatten (for [[k v] components
                                                   :let [ins (get v :inputs)]
                                                   :when ins]
                                               (for [i ins]
                                                 {(keyword (str (ut/unkeyword k) "/"
                                                                (ut/unkeyword i))) {:fn :port}})))))
                       components) :done {:fn (fn [x] x)}) ;; add in 'implied' "blocks" (ports, condis, done, etc)
          condi-connections (vec (apply concat (for [[k v] components
                                                     :when (get v :cond)]
                                                 (for [c (keys (get v :cond))] [k c]))))
          ;; TODO - don't build implicit condi paths if the parent doesn't exist in the connection pathways
          condi-channels (into {} (for [conn condi-connections]
                                    {conn (async/chan 1)}))
          connections (vec (distinct (into connections condi-connections))) ;; add after channel maps
          coords-map (ut/coords-map connections)
          fp (path-map connections)
          ;chains (count (keys flow-paths-map))
          ;chains-run (count (cset/intersection (set (keys flow-paths-map)) (set (keys @results-atom))))
          ;chains-done? (= chains chains-run)
          sources (fn [to] (vec (distinct (for [[ffrom tto] connections
                                                :when (= tto to)] ffrom))))

          components (select-keys components (vec (distinct (flatten (apply conj connections))))) ;; no reason to bother with unlinked blocks for now
          input-mappings (into {} ;; mostly for web
                               (for [b (keys components)]
                                 {b (vec (remove #{b} (flatten (filter #(= (second %) b) connections))))}))
          started (atom #{})]
      (when web-push? (web/push [:blocks] {}))
      (reset! db/working-data {:connections named-connections
                               :gen-connections gen-connections
                               :components-list (keys components)
                               :condis condi-connections})

      (pp [:gen-connections gen-connections :connections connections :fp fp
           :comps components :coords-map coords-map :condi-connections condi-connections])

      (when web-push?
        (web/push [:network-map] @db/working-data))

      (when web-push?
        #_{:clj-kondo/ignore [:redundant-do]} ;; lol, it is, in fact, NOT redundant.
        (do
          (doseq [[bid v] components
                  :when (not (= bid :done))] ;; no reason to render :done as a block? redundant
            (web/push [:blocks bid] (merge {:y (or (get-in network-map [:canvas bid :y]) (get v :y (get-in coords-map [bid :y]))) ;; deprecated locations TODO
                                            :x (or (get-in network-map [:canvas bid :x]) (get v :x (get-in coords-map [bid :x]))) ;; deprecated locations
                                            :base-type :artifacts
                                            :width (or (get-in network-map [:canvas bid :w]) (get v :w 240)) ;; deprecated locations
                                            :height (or (get-in network-map [:canvas bid :h]) (get v :h 255)) ;; deprecated locations
                                            :type :text
                                            ;:hidden? (true? (= bid :done))
                                            :flowmaps-created? true
                                            :block-type "map2"}
                                           (if (get v :view)
                                             {:view-mode "view"
                                              :options [{:prefix ""
                                                         :suffix ""
                                                         :block-keypath [:view-mode]
                                                         :values ["data" "view"]}]} {}))))
          (doseq [[bid inputs] input-mappings
                  :when (not (= bid :done))] ;; not rendering :done blocks, so we sure as hell dont want to draw links to them
            (web/push [:blocks bid :inputs] (vec (conj
                                                  (vec (for [i inputs]
                                                         [i [:text [:no nil]]]))
                                                  [nil [:text [:paragraph 0]]]))))))

      (reset! db/channels-atom channels)
      (reset! db/condi-channels-atom condi-channels)
    ;(println flow-paths-map)
      (doseq [c connections] ;;; "boot" channels into history even if they never get used...
        (swap! db/channel-history conj {:path [:creating-channels :*]
                                        :channel c
                                        :start (System/currentTimeMillis)
                                        :end (System/currentTimeMillis)
                                        :value nil
                                        :data-type "boot"}))
      (doseq [from (keys components)]
        (let [subflow-override? (not (nil? (get subflow-map from)))
              ;fn-is-subflow? (true? (and (not (nil? (get (map? (get components from)) :connections))) ;; TODO subflow imp
              ;                           (not (nil? (get (map? (get components from)) :components)))))
              from-val (if subflow-override?
                         (get subflow-map from)
                         (get components from))
              out-conns (filter #(= from (first %)) connections)
              in-conns (filter #(= from (second %)) connections)
              in-condi-conns (filter #(= from (second %)) condi-connections)
              to-chans (map channels (filter #(= from (first %)) connections))]
          (pp (str "  ... Processing: " from))

          (if (and (not (fn? from-val)) ;; not a raw fn
                   (nil? (get from-val :fn)))
            #_{:clj-kondo/ignore [:redundant-let]}
            (let [] ;; assume some static value and pass it as is
              (when web-push? (web/push [:blocks from :body] (str {:v from-val})))
              (pp (vec (remove nil? [:block from :pushing-static-value from-val :to (vec (map last out-conns)) (when subflow-override? :subflow-value-override!)])))
              (swap! started conj from)
              (swap! db/results-atom assoc from from-val)
              (doseq [c to-chans] (async/put! c {:sender from :value from-val})))

          ;; is a fn block 
            (let [;in-chans (map channels in-conns)
                  in-chans (remove nil? (into (map channels in-conns) (map condi-channels in-condi-conns)))
                  ;condi-chans (map condi-channels in-condi-conns)
                  out-chans (map channels out-conns)
                  from-val (if (map? from-val) ;; need to fully qualify in/out port keywords
                             (merge from-val
                                    {:inputs (vec (for [i (get from-val :inputs)]
                                                    (keyword (str (ut/unkeyword from) "/"
                                                                  (ut/unkeyword i)))))})
                             from-val)
                  srcs (sources from)]
              (pp ["Creating channels for" from :-> srcs  :already-started (count (set @started)) :blocks])


              (let [] ;do  ;when parents-ready? ;; does it matter? the channels are unique...
                (pp ["Starting block " from " with channel(s): " in-chans])
               ; (Thread/sleep 1000) ;; just for debugging
                (swap! started conj from)
                (async/thread
                  (pp ["In thread for " from " reading from channel " in-chans])
                  (process connections in-chans out-chans from fp from-val opts-map done-ch))))))))
                (catch Exception e (ut/ppln {:flow-error e}))))

(defn flow-results [] ;; future pass flow-id
  (let [res (for [p (vec (distinct @db/resolved-paths))]
              {p (for [pp p]
                   [pp (get @db/results-atom pp)])})]
    (ut/ppln {:resolved-paths-end-values res})
    res))

;(web/start!)
;(web/stop!)
;(flow looping-net {} testatom) ;; return atom, requires a :done
;(flow looping-net {} (async/chan 1)) ;; return channel, requires a :done
;(flow my-network {} nil {:comp1 4545 :comp2 2323}) ;; override subflow values
;(flow fex/looping-net)
;(flow fex/my-network)
