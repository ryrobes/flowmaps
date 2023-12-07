(ns flowmaps.db
  (:refer-clojure :exclude [abs update-vals update-keys])
  (:require 
  ;[duratom.core :as da]
   [clojure.java.shell :as shell]))

;; issues with duratom crashing on startup / not playing nice with other libraries, removing for now: 12/6/23
;; (defn datom
;;   ([initial-val name] (datom initial-val name :memory))
;;   ([initial-val name type & [opts]]
;;    (when (= type :file)
;;      (if (string? opts)
;;        (shell/sh "/bin/bash" "-c" (str "mkdir -p ./data/" opts "/"))
;;        (shell/sh "/bin/bash" "-c" "mkdir -p ./data/")))
;;      (case type
;;        :memory (atom initial-val) ;; default reg OG atom
;;        :file (da/duratom :local-file
;;                          :file-path (if (string? opts)
;;                                       (str "./data/" opts "/" name ".datom")
;;                                       (str "./data/" name ".datom"))
;;                          :init initial-val)
;;        :postgres (da/duratom :postgres-db
;;                              :db-config "any db-spec as understood by clojure.java.jdbc"
;;                              :table-name (str name "_datom")
;;                              :row-id 0
;;                              :init initial-val)
;;        :sqlite (da/duratom :sqlite-db
;;                            :db-config "any db-spec as understood by clojure.java.jdbc"
;;                            :table-name (str name "_datom")
;;                            :row-id 0
;;                            :init initial-val)
;;        :s3 (da/duratom :aws-s3
;;                        :credentials "as understood by amazonica"
;;                        :bucket "my_bucket"
;;                        :key (str name "_datom")
;;                        :init initial-val)
;;        :redis (da/duratom :redis-db
;;                           :db-config "any db-spec as understood by carmine"
;;                           :key-name "my:key"
;;                           :init initial-val))))

;; (defn datom [init name]
;;   ;(atom init)
;;   (da/duratom :local-file
;;               :file-path (str "./data/" name ".datom")
;;               :init init))

(def results-atom (atom {})); (datom {} "results-atom")) ;; basically a log
(def resolved-paths (atom {})); (datom {} "resolved-paths"))
(def channel-history (atom {})); (datom {} "channel-history"))
;(def channel-history2 (atom {})) ;; waiting time
(def fn-history (atom {})); (datom {} "fn-history"))
(def block-dump (atom {})); (datom {} "block-dump"))
;(def web-push-history (datom [] "web-push-history"))
(def chains-completed (atom {})); (datom [] "chains-completed"))
;(def port-accumulator (datom {} "port-accumulator"))
(def block-defs (atom {})); (datom {} "block-defs"))
(def hidden-values (atom {})); (datom {} "hidden-values"))
(def waffle-data (atom {}));  (datom {} "waffle-data"))

(def port-accumulator (atom {}))
(defonce channels-atom (atom {})) ;; ref for putting things on later via REPL or whatever.
(defonce condi-channels-atom (atom {})) ;; ref for putting things on later via REPL or whatever.
(defonce working-data (atom {}))
(defonce live-schedules (atom []))
(defonce sample-limit 20) ;; to keep huge seqs from getting to console print and web-ui
(defonce rewinds 0) ;; limit to history events for UI / logging

;; (defn re-init [type & [opts]] ;; redefine our atoms as duratoms and transfer data over (if possible)
;;   (alter-var-root #'results-atom (constantly (datom @results-atom "results-atom" type opts)))
;;   (alter-var-root #'resolved-paths (constantly (datom @resolved-paths "resolved-paths" type opts)))
;;   (alter-var-root #'channel-history (constantly (datom @channel-history "channel-history" type opts)))
;;   ;; channel-history2
;;   (alter-var-root #'fn-history (constantly (datom @fn-history "fn-history" type opts)))
;;   (alter-var-root #'block-dump (constantly (datom @block-dump "block-dump" type opts)))
;;   ;(alter-var-root #'web-push-history (constantly (datom @web-push-history "web-push-history" type opts)))
;;   (alter-var-root #'chains-completed (constantly (datom @chains-completed "chains-completed" type opts)))
;;   ;; port-accumulator
;;   (alter-var-root #'block-defs (constantly (datom @block-defs "block-defs" type opts)))
;;   (alter-var-root #'hidden-values (constantly (datom @hidden-values "hidden-values" type opts)))
;;   (alter-var-root #'waffle-data (constantly (datom @waffle-data "waffle-data" type opts))))




