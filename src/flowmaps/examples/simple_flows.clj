(ns flowmaps.examples.simple-flows)

;; flow defs for examples. work in progress.

(def my-network {:components {:comp1 10
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
                               [:adder :simple-plus-10]]
                 :canvas {:adder-one {:x 732 :y 764 :h 255 :w 240}
                          :add-one {:x 1123 :y 785 :h 199 :w 280}
                          :adder-one/in {:x 430 :y 760 :h 255 :w 240}
                          :comp2 {:x 175 :y 434 :h 147 :w 238}
                          :comp3 {:x 136 :y 791 :h 203 :w 251}
                          :adder/in1 {:x 486 :y 160 :h 187 :w 244}
                          :comp1 {:x 198 :y 168 :h 146 :w 216}
                          :adder {:x 862 :y 307 :h 225 :w 278}
                          :simple-plus-10 {:x 1255 :y 327 :h 194 :w 307}
                          :adder/in2 {:x 490 :y 426 :h 183 :w 239}}})

(def network1 {:components {:comp0 12 ;(fn [] @starter) ;12 ;#(rando %) ;12
                           ;:done :done
                            :comp1 {:fn #(+ % 1)
                                    :x 105 :y 105
                                    :w 180 :h 120}
                            :comp2 #(- % 10)
                            :comp3 #(+ % 1)
                            :comp4 #(+ % 10)
                            :comp8 #(+ % 1)
                            :multi #(let [x (* % 2)]
                                      (if (> x 1000) :done x))
                            :comp13 #(+ % 100)
                            :comp55 #(/ % 2.2)
                            :comp10 45
                            :sleep #(do (Thread/sleep 2500) %) ;; sleep and then pass
                            :comp11 #(+ % 10)
                            :comp12 #(- % 1)}
               :connections [[:comp0 :comp1]
                             [:comp10 :comp8] ;; future
                             [:comp8 :done]
                             [:comp1 :comp2]
                             [:comp2 :comp3]
                             [:comp3 :comp4]
                             [:comp2 :comp11]
                             [:comp11 :comp12]
                             [:comp4 :multi]
                             [:multi :sleep]
                             [:sleep :comp55]
                            ;[:sleep :comp1] ;; recur loop start
                            ;[:comp12 :multi]
                             [:comp12 :comp13]
                            ;[:comp13 :done]
                             ]})

(def looping-net {:components {:comp1 10
                               :comp2 20
                               :comp3 [133 45]
                               :tester '(fn [x] (+ 8 x))
                               :simple-plus-10 {:fn #(+ 10 %)}
                               :add-one {:fn #(+ % 1)}
                               :add-one2 {:fn #(+ 1 %)}
                               :add-one3 {:fn #(+ 1 %)}
                               :counter {:fn #(count %)
                                         :view (fn [x]
                                                 [:re-com/box :child (str x " loops")
                                                  :align :center :justify :center
                                                  :padding "10px"
                                                  :style {:color "#50a97855"
                                                          :font-weight 700
                                                          :font-size "100px"}])}
                               :conjer {:fn (fn [x]
                                              (defonce vv (atom []))
                                              (do (swap! vv conj x) @vv))
                                        :view (fn [x]
                                                [:vega-lite {:data {:values (map-indexed (fn [index value]
                                                                                           {:index index
                                                                                            :value value}) x)}
                                                             :mark {:type "bar"
                                                                    :color "#60a9eb"}
                                                             :encoding {:x {:field "index" :type "ordinal"
                                                                            :title "index of conj pass"
                                                                            :axis {:labelColor "#ffffff77"
                                                                                   :ticks false
                                                                                   :titleColor "#ffffff"
                                                                                   :gridColor "#ffffff11"
                                                                                   :labelFont "Poppins" :titleFont "Poppins"
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
                                                                      :view {:stroke "#00000000"}}} {:actions false}])}
                               :add-one4 {:fn #(do (Thread/sleep 120) (+ 45 %))
                                          :cond {:condicane2 #(> % 800)}}
                               :display-val {:fn (fn [x] x)
                                             :view (fn [x]
                                                     [:re-com/box :child (str x)
                                                      :align :center :justify :center
                                                      :padding "10px"
                                                      :style {:color "#D7B4F3"
                                                              :font-weight 700
                                                              :font-size "80px"}])}
                               :whoops {:fn #(str % ". YES.")}
                               :condicane {:fn #(str % " condicane!")}
                               :condicane2 {:fn #(str "FINAL VAL: " % " DONE")}
                               :baddie #(str % " is so bad!")
                               :baddie2 {:fn #(+ % 10)}
                               :adder {:fn +
                                       :inputs [:in1 :in2]}}
                  :connections [[:comp1 :adder/in1]
                                [:comp2 :adder/in2]
                                [:adder :simple-plus-10]
                                [:condicane2 :whoops]
                                [:add-one4 :add-one2]
                                [:add-one4 :display-val]
                                [:add-one4 :conjer]
                                [:conjer :counter]
                                [:whoops :done]
                                [:simple-plus-10 :add-one]
                                [:add-one :add-one2]
                                [:add-one2 :add-one3]
                                [:add-one3 :add-one4]]
                  :canvas {:conjer {:x 1217 :y 848 :h 371 :w 650}
                           :whoops {:x 1627 :y 1337 :h 188 :w 310}
                           :add-one {:x 1609 :y 506 :h 173 :w 220}
                           :comp2 {:x 100 :y 430 :h 255 :w 240}
                           :adder/in1 {:x 430 :y 100 :h 255 :w 240}
                           :comp1 {:x 100 :y 100 :h 255 :w 240}
                           :condicane2 {:x 1228 :y 1297 :h 181 :w 253}
                           :counter {:x 1981 :y 915 :h 225 :w 725}
                           :add-one4 {:x 386 :y 809 :h 255 :w 240}
                           :adder {:x 839 :y 254 :h 255 :w 240}
                           :add-one2 {:x 198 :y 1181 :h 255 :w 240}
                           :simple-plus-10 {:x 1199 :y 315 :h 255 :w 240}
                           :add-one3 {:x 596 :y 1196 :h 255 :w 240}
                           :display-val {:x 771 :y 699 :h 179 :w 320}
                           :adder/in2 {:x 430 :y 430 :h 255 :w 240}}})