(ns flowmaps.db)

;; will be a persistent DB here at some point
;; for now it's just a handy place to keep all the (emphemeral) atoms

(defonce results-atom (atom {})) ;; basically a log
(defonce history-atom (atom []))
(defonce resolved-paths (atom []))
(defonce channel-log (atom {}))
(defonce channel-history (atom []))
(defonce chains-completed (atom []))
(defonce port-accumulator (atom {}))
(def channels-atom (atom {})) ;; ref for putting things on later via REPL or whatever.
(def condi-channels-atom (atom {})) ;; ref for putting things on later via REPL or whatever.
(def working-data (atom {}))