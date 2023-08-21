(ns flowmaps.db)

;; will be a persistent DB here at some point
;; for now it's just a handy place to keep all the (emphemeral) atoms

(defonce results-atom (atom {})) ;; basically a log
(defonce resolved-paths (atom {}))
(defonce channel-history (atom {}))
(defonce channel-history2 (atom {})) ;; waiting time
(defonce fn-history (atom {}))
(defonce block-dump (atom {}))
(defonce web-push-history (atom []))
(defonce chains-completed (atom []))
(defonce port-accumulator (atom {}))
(defonce block-defs (atom {}))
(defonce hidden-values (atom {}))
(defonce waffle-data (atom {}))
(defonce channels-atom (atom {})) ;; ref for putting things on later via REPL or whatever.
(defonce condi-channels-atom (atom {})) ;; ref for putting things on later via REPL or whatever.
(defonce working-data (atom {})) 
(defonce sample-limit 20) ;; to keep huge seqs from getting to console print and web-ui