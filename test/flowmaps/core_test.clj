(ns flowmaps.core-test
  (:require [clojure.test :refer :all]
            [clojure.string :as cstr]
            [clojure.core.async :as async]
            [flowmaps.utility :as ut]
            [flowmaps.db :as db]
            [flowmaps.core :refer :all]))

;; end to end flow testing with output channels and :done blocks.

(def simple-flow {:components {:comp1 10
                               :comp2 20
                               :comp3 [133 45]
                               :simple-plus-10 #(+ 10 %)
                               :add-one #(+ 1 %)
                               :adder-one {:fn #(apply + %)
                                           :inputs [:in]}
                               :adder {:fn +
                                       :inputs [:in1 :in2]}}
                 :connections [[:comp1 :adder/in1]
                               [:comp2 :adder/in2]
                               [:comp3 :adder-one/in]
                               [:adder-one :add-one]
                               [:adder :simple-plus-10]
                               [:simple-plus-10 :done]]
                 :canvas {:adder-one {:x 732 :y 764 :h 255 :w 240}
                          :add-one {:x 1123 :y 785 :h 199 :w 280}
                          :adder-one/in {:x 430 :y 760 :h 255 :w 240}
                          :comp2 {:x 100 :y 430 :h 255 :w 240}
                          :comp3 {:x 136 :y 791 :h 203 :w 251}
                          :adder/in1 {:x 430 :y 100 :h 255 :w 240}
                          :comp1 {:x 100 :y 100 :h 255 :w 240}
                          :adder {:x 862 :y 307 :h 225 :w 278}
                          :simple-plus-10 {:x 1255 :y 327 :h 194 :w 307}
                          :adder/in2 {:x 430 :y 430 :h 255 :w 240}}})

(def sub-flow {:description "Flow within a flow"
               :components {:comp1 10
                            :comp2 20
                            :comp3 [133 45]
                            :simple-plus-10 #(+ 10 %)
                            :subflow {:components {:comp1 10
                                                   :comp2 20
                                                   :simple-plus-10 #(+ 10 %)
                                                   :adder {:fn +
                                                           :inputs [:in1 :in2]}}
                                      :connections [[:comp1 :adder/in1]
                                                    [:comp2 :adder/in2]
                                                    [:adder :simple-plus-10]
                                                    [:simple-plus-10 :done]]
                                      :canvas {:comp1 {:x 100 :y 100 :h 255 :w 240 :view-mode "input"}
                                               :adder/in1 {:x 518 :y 133 :h 202 :w 225 :view-mode "data"}
                                               :comp2 {:x 100 :y 430 :h 255 :w 240 :view-mode "input"}
                                               :adder/in2 {:x 496 :y 459 :h 202 :w 269 :view-mode "data"}
                                               :adder {:x 971 :y 358 :h 255 :w 240 :view-mode "data"}
                                               :simple-plus-10 {:x 1310 :y 298 :h 507 :w 669 :view-mode "data"}}}
                            :add-one #(+ 1 %)
                            :add-hundred #(+ 100 %)
                            :adder-one {:fn #(apply + %)
                                        :inputs [:in]}
                            :adder {:fn +
                                    :inputs [:in1 :in2]}}
               :connections [[:comp1 :adder/in1]
                             [:comp2 :adder/in2]
                             [:comp3 :adder-one/in]
                             [:adder-one :add-one]
                             [:adder :simple-plus-10]
                             [:add-one :subflow/comp2]
                             [:simple-plus-10 :subflow/comp1]
                             [:subflow :add-hundred]
                             [:add-hundred :done]]
               :canvas {:adder-one {:x 791 :y 764 :h 255 :w 240 :view-mode "data"}
                        :add-hundred {:x 2493 :y 694 :h 255 :w 240 :view-mode "data"}
                        :add-one {:x 1123 :y 785 :h 209 :w 250 :view-mode "data"}
                        :subflow {:x 1979 :y 619 :h 332 :w 335 :view-mode "data"}
                        :adder-one/in {:x 449 :y 789 :h 196 :w 225 :view-mode "data"}
                        :comp2 {:x 100 :y 430 :h 255 :w 240 :view-mode "input"}
                        :comp3 {:x 97 :y 791 :h 203 :w 251 :view-mode "input"}
                        :adder/in1 {:x 430 :y 100 :h 255 :w 240 :view-mode "data"}
                        :comp1 {:x 100 :y 100 :h 255 :w 240 :view-mode "input"}
                        :adder {:x 862 :y 307 :h 225 :w 278 :view-mode "data"}
                        :simple-plus-10 {:x 1260 :y 363 :h 220 :w 250 :view-mode "data"}
                        :subflow/comp2 {:x 1608 :y 843 :h 161 :w 208 :view-mode "data"}
                        :adder/in2 {:x 430 :y 430 :h 255 :w 240 :view-mode "data"}
                        :subflow/comp1 {:x 1627 :y 548 :h 166 :w 217 :view-mode "data"}}})

(def test-flow-map-1 {:components {:comp1 10
                                   :comp2 20
                                   :comp3 [133 45]
                                   :tester '(fn [x] (+ 8 x))
                                   :simple-plus-10 {:fn #(+ 10 %)
                                                    :x 1222 :y 312}
                                   :add-one {:fn #(+ % 1)
                                             :x 1574 :y 452 :w 220 :h 173}
                                   :add-one2 {:fn #(+ 1 %)
                                              :x 367  :y 1492}
                                   :add-one3 {:fn #(+ 1 %)
                                              :x 839 :y 1481}
                                   :counter {:fn #(count %)
                                             :view (fn [x]
                                                     [:re-com/box :child (str x " loops")
                                                      :align :center :justify :center
                                                      :padding "10px"
                                                      :style {:color "yellow"
                                                              :font-weight 700
                                                              :font-size "100px"}])
                                             :h 225 :w 725 :x 1981 :y 915}
                                   :conjer {:fn (fn [x]
                                                  (defonce vvv (atom []))
                                                  (do (swap! vvv conj x) @vvv))
                                            :view (fn [x]
                                                    [:vega-lite {:data {:values (map-indexed (fn [index value]
                                                                                               {:index index
                                                                                                :value value}) x)}
                                                                 :mark {:type "bar" :color "#FC0FC0"}
                                                                 :encoding {:x {:field "index" :type "ordinal"
                                                                                :title "index of conjs pass"
                                                                                :axis {:labelColor "#ffffff77"
                                                                                       :ticks false
                                                                                       :titleColor "#ffffff"
                                                                                       :gridColor "#ffffff11"
                                                                                       :labelFont "Poppins" :titleFont "Poppins"
                                                                                                        ;:gridColor "#00000000"
                                                                                       :domainColor "#ffffff11"}}
                                                                            :y {:field "value" :type "quantitative"
                                                                                :title "random additive values"
                                                                                :axis {:labelColor "#ffffff77"
                                                                                       :titleColor "#ffffff"
                                                                                       :ticks false
                                                                                                         ;:gridColor "#00000000"
                                                                                       :gridColor "#ffffff11"
                                                                                       :labelFont "Poppins" :titleFont "Poppins" :labelFontSize 9 :labelLimit 180
                                                                                                        ;;:labelFontStyle {:color "blue"}
                                                                                       :domainColor "#ffffff11"}}}
                                                                 :padding {:top 15 :left 15}
                                                                 :width "container"
                                                                 :height :height-int
                                                                 :background "transparent"
                                                                 :config {:style {"guide-label" {:fill "#ffffff77"}
                                                                                  "guide-title" {:fill "#ffffff77"}}
                                                                          :view {:stroke "#00000000"}}} {:actions false}])
                                            :w 650 :h 371 :x 1217 :y 848}
                                   :add-one4 {:fn #(do (Thread/sleep 120) (+ 45 %))
                                              :x 595 :y 1087
                                              :cond {:condicane2 #(> % 200)}}
                                   :whoops {:fn #(str % ". YES.")
                                            :x 1627 :y 1337}
                                   :condicane {:fn #(str % " condicane!")
                                               :view (fn [x]
                                                       [:re-com/box
                                                        :child (str "conditional path that never gets run or seen" x)
                                                        :align :center :justify :center
                                                        :padding "10px"
                                                        :style {:color "darkcyan"
                                                                :font-weight 700
                                                                :font-size "20px"}])
                                               :x 2069 :y 1235 :h 255 :w 240}
                                   :condicane2 {:fn #(str "FINAL VAL: " % " DONE")
                                                :x 1210 :y 1268}
                                   :baddie #(str % " is so bad!")
                                   :baddie2 {:fn #(+ % 10)
                                             :x 1941 :y 589 :w 256 :h 167}
                                   :looper (fn [x] x)
                                   :adder {:fn +
                                           :y 254 :x 839
                                           :inputs [:in1 :in2]}}
                      :connections [[:comp1 :adder/in1] ;; channels directly to port
                                    [:comp2 :adder/in2]
                                    [:adder :simple-plus-10]
                                    [:condicane2 :whoops]
                                    [:add-one4 :add-one2]
                                    [:add-one4 :conjer]
                                    [:conjer :counter]
                                    [:whoops :done]
                                    [:simple-plus-10 :add-one]
                                    [:add-one :add-one2]
                                    [:add-one2 :add-one3]
                                    [:add-one3 :add-one4]]})

(defn flow-describe [flow-map]
  (into {} (for [[k v] flow-map] {k (count (keys v))})))

(def debug-tests? false)

(defn test-runner [flow-map flow-return flow-desc & [subflow-map]]
  (testing flow-desc
   (let [ch (async/chan 1)
         ch2 (async/chan 1)
         timeout (async/timeout 5000)]
     (ut/ppln (vec (remove nil? [:testing-flow flow-desc (flow-describe flow-map)
                                 (when subflow-map [:subflow-map subflow-map])])))
     (ut/ppln {:flow-id (ut/generate-name)
               :flow-hash (hash flow-map)
               ;:flow-map-body flow-map
               })
     (if subflow-map
       (flow flow-map {:debug? debug-tests?} [ch ch2] subflow-map)
       (flow flow-map {:debug? debug-tests?} [ch ch2]))
     (async/<!! ch)
     (let [[val _] (async/alts!! [ch timeout ch2])] ;; multiple done-chans wait
       (is (= flow-return val))))))

(deftest flow-map-channel-return-test2
  (test-runner simple-flow 40 
               "channel output - simple-flow"))

(deftest flow-map-channel-return-subflow
  (test-runner sub-flow 329
               "channel output - sub-flow"))

(deftest flow-map-channel-return-subflow-override1
  (test-runner simple-flow 6878 
               "channel output - simple-flow - subflow override edition" {:comp1 4545 :comp2 2323}))

(deftest flow-map-channel-return-test
  (test-runner test-flow-map-1 "FINAL VAL: 229 DONE. YES." 
               "looping logic flow with breakpoint and viewer blocks w block atoms"))

(deftest flow-map-atom-return-test
  (testing "atom output test"
    (let [a (atom nil)]
      (ut/ppln (vec (remove nil? [:testing-flow-atom-output ])))
      (flow test-flow-map-1 {:debug? debug-tests?} a)
      (while (nil? @a)
        (Thread/sleep 100)) ;; check the atom every 100 ms
      (is (= "FINAL VAL: 229 DONE. YES." @a)))))


