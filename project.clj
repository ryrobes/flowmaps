(defproject com.ryrobes/flowmaps "0.2-SNAPSHOT"
  :description "FlowMaps: Flow Based Programming Micro-Framework for Clojure"
  :url "https://github.com/ryrobes/flowmaps"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.3"] ;; 1.11.1 gave warnings TODO
                 [io.pedestal/pedestal.service "0.5.10"]
                 [io.pedestal/pedestal.jetty "0.5.10"]
                 [org.slf4j/slf4j-nop "1.7.32"]
                 [talltale "0.5.8"  ;; exclusions recommended by `lein deps :tree`
                  :exclusions [org.clojure/spec.alpha org.clojure/core.specs.alpha]]
                 [mvxcvi/puget "1.3.2"]
                 ;[clojure-term-colors "0.1.0"]
                 ;[org.clojars.rutledgepaulv/websocket-layer "0.1.10"]
                 [com.fasterxml.jackson.core/jackson-core "2.14.0-rc1"] ;; for websocket-layer
                 ;[spyscope "0.1.6"]
                 [org.clojars.rutledgepaulv/websocket-layer "0.1.10" ;; exclusions recommended by `lein deps :tree`
                  :exclusions [joda-time com.cognitect/transit-java org.clojure/core.memoize
                               clj-time com.cognitect/transit-clj org.clojure/tools.analyzer
                               org.clojure/tools.analyzer.jvm]]
                 [org.clojure/core.async "1.6.673" ;; exclusions recommended by `lein deps :tree`
                  :exclusions [org.clojure/core.cache org.clojure/core.memoize org.clojure/tools.reader
                               org.clojure/tools.analyzer org.clojure/tools.analyzer.jvm
                               org.clojure/data.priority-map]]]
  ;:main ^:skip-aot flowmaps.core ;;; comment out for lib use
  :repl-options {:init-ns flowmaps.core})
