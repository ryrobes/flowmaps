(ns flowmaps.examples.simple-flows
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as cstr]
            [clojure.walk :as walk]
            [debux.core :as dx]
            [clj-http.client :as client]
            [clojure.data.json :as json]
            [flowmaps.utility :as ut]))

;; flow defs for examples. work in progress.
(def openai-calls
  {:description "a simple HTTP loop using OpenAI API endpoint that keeps adding to chat history"
   :components {:prompt "Top O' the morning!"
                :openai-api-key (System/getenv "OAI_KEY")
                :ai-ask {:fn (fn [prompt openai-api-key history]
                               (let [question {:role "user"
                                               :content (str prompt)}]
                                 (defonce last-prompt (atom nil))
                                 (reset! last-prompt prompt) ;; to keep the loop from running amok (see :pre-when? below)
                                 {:question question
                                  :history (vec (conj history question))
                                  :answer (clojure.walk/keywordize-keys
                                           (clojure.data.json/read-str
                                            (get (clj-http.client/post
                                                  "https://api.openai.com/v1/chat/completions"
                                                  {:body (clojure.data.json/write-str
                                                          {:model "gpt-4" ; "gpt-3.5-turbo"
                                                           :messages (vec (conj history question))})
                                                   :headers {"Content-Type" "application/json"
                                                             "Authorization" (str "Bearer " openai-api-key)}
                                                   :socket-timeout 300000
                                                   :connection-timeout 300000
                                                   :content-type :json
                                                   :accept :json}) :body)))}))
                         :pre-when? (fn [prompt _ _] ;; pre-when gets the same payload as the main fn
                                      (not (= prompt @last-prompt)))
                         :inputs [:prompt :openai-api-key :history]}
                :last-response (fn [x] x) ;; just to have a data block for the UI
                :memory {:fn (fn [{:keys [question history answer]}]
                               (let [aa (get-in answer [:choices 0 :message])]
                                 (conj history aa)))
                         :speak (fn [x] (str (get (last x) :content))) ;; if in Rabbit, and ElevenLabs KEY, read the answer
                         :starter [{:role "system" ;; ""bootstrap"" history with sys prompt
                                    :content "You are a helpful assistant, you responses will be framed as if you are Buffy from the 1992 film."}]}}
                   ;; canned REST / sub-flow endpoints 
   :points {"question" [[:prompt :ai-ask/prompt] ;; (channel to insert into)
                        [:ai-ask :memory]]} ;; (channel to snatch out of downstream)
                            ;; ^^ send question, get answer (keeps convo state)
   :hide [:openai-api-key]
   :connections [[:prompt :ai-ask/prompt]
                 [:openai-api-key :ai-ask/openai-api-key]
                 [:ai-ask :memory]
                 [:ai-ask :last-response]
                 [:memory :ai-ask/history]]
   :canvas {:ai-ask/openai-api-key {:x 430 :y 430 :h 255 :w 240 :view-mode "text" :hidden? true}
            :ai-ask/prompt {:x 430 :y 100 :h 255 :w 240 :view-mode "text" :hidden? true}
            :ai-ask/history {:x 466 :y 962 :h 350 :w 894 :view-mode "text" :hidden? true}
            :ai-ask {:x 815 :y 347 :h 522 :w 434 :view-mode "text"}
            :memory {:x -199 :y 750 :h 369 :w 538 :view-mode "data"}
            :openai-api-key {:x -243 :y 505 :h 141 :w 565 :view-mode "input"}
            :prompt {:x -136 :y 145 :h 212 :w 415 :view-mode "input"}
            :last-response {:x 1383 :y 380 :h 755 :w 818 :view-mode "data"}
            "just-the-answer" {:inputs [[:last-response [:text [:map [:v :answer :choices 0 :message :content]]]]]
                               :x 2585 :y 984 :h 215 :w 400}
            "just-the-question" {:inputs [[:last-response [:text [:map [:v :question :content]]]]]
                                 :x 2575 :y 577 :h 215 :w 400}}})

(def my-network {:description "a simple example flow: addition and integers"
                 :components {:comp1 10
                              :comp2 20
                              :comp3 [133 45]
                              :simple-plus-10 {:fn #(+ 10 %)
                                               :speak (fn [x]
                                                        (str "Hey genius! I figured out your brain-buster of a math problem... It's " x))}
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
                 :canvas {:adder-one {:x 1009 :y 765 :h 207 :w 297 :view-mode "data"}
                          :add-one {:x 1400 :y 754 :h 247 :w 440 :view-mode "data"}
                          :adder-one/in {:x 650 :y 746 :h 255 :w 240 :view-mode "data"}
                          :comp2 {:x 249 :y 423 :h 142 :w 155 :view-mode "data"}
                          :comp3 {:x 283 :y 771 :h 203 :w 251 :view-mode "input"}
                          :adder/in1 {:x 486 :y 160 :h 192 :w 242 :view-mode "data"}
                          :comp1 {:x 252 :y 204 :h 127 :w 148 :view-mode "input"}
                          :adder {:x 875 :y 332 :h 207 :w 198 :view-mode "data"}
                          :simple-plus-10
                          {:x 1189 :y 307 :h 224 :w 239 :view-mode "data"}
                          :adder/in2 {:x 490 :y 426 :h 201 :w 244 :view-mode "data"}}})

(def my-network-input {:description "a simple example flow: addition and integers (with explicit input tests)"
                       :components {:comp1 10
                                    :comp2 20
                                    :comp3 [133 45]
                                    :simple-plus-10 #(+ 10 %)
                                    :add-one #(+ 1 %)
                                    :input [:text-input 45] ; w default, overrides comp1
                                    :adder-one {:fn #(apply + %)
                                                :inputs [:in]}
                                    :adder {:fn +
                                            :inputs [:in1 :in2]}}
                       :connections [[:comp1 :adder/in1]
                                     [:comp2 :adder/in2]
                                     [:input :adder/in1]
                                     [:comp3 :adder-one/in]
                                     [:adder-one :add-one]
                                     [:adder :simple-plus-10]]
                       
                       :canvas {:adder-one {:x 1009 :y 765 :h 207 :w 297}
                                :add-one {:x 1400 :y 754 :h 247 :w 440}
                                :adder-one/in {:x 650 :y 746 :h 255 :w 240}
                                :comp2 {:x 249 :y 423 :h 142 :w 155}
                                :comp3 {:x 283 :y 771 :h 203 :w 251}
                                :adder/in1 {:x 486 :y 160 :h 192 :w 242}
                                :comp1 {:x 252 :y 204 :h 127 :w 148}
                                :adder {:x 887 :y 317 :h 207 :w 198}
                                :simple-plus-10 {:x 1189 :y 307 :h 224 :w 239}
                                :input {:x 166 :y -42 :h 196 :w 258}
                                :adder/in2 {:x 490 :y 426 :h 201 :w 244}}})

;; just testing some odd stuff
(def odd-even {:description "simple example of conditional pathways"
               :components {:int1 10
                            :int2 21
                            :adder {:fn + ;; notice that adder has no "traditional" connections, just a bool set of condis
                                    :inputs [:in1 :in2]
                                    :cond {:odd? #(odd? %) ;; 2 bool conditional dyn outs with no "real" output flow
                                           :even? #(even? %)}}
                            :odd? (fn [x] (when (odd? x) "odd!"))
                            :even? (fn [x] (when (even? x) "even!"))
                            :display-val {:fn (fn [x] x)
                                          :view (fn [x] [:re-com/box
                                                         :align :center :justify :center
                                                         :style {:font-size "105px"
                                                                 :color "orange"
                                                                 :font-family "Sansita Swashed"}
                                                         :child (str x)])}}
               :connections [[:int1 :adder/in1]
                             [:int2 :adder/in2]
                             [:odd? :display-val]
                             [:even? :display-val]
                             [:display-val :done]]
               :canvas {:int1 {:x 100 :y 100 :h 255 :w 240 :view-mode "data"}
                        :adder/in1 {:x 546 :y 239 :h 150 :w 201 :view-mode "data"}
                        :int2 {:x 92 :y 459 :h 255 :w 240 :view-mode "data"}
                        :adder/in2 {:x 551 :y 445 :h 150 :w 196 :view-mode "data"}
                        :odd? {:x 1275 :y 262 :h 185 :w 260 :view-mode "data"}
                        :display-val {:x 1755 :y 345 :h 233 :w 329 :view-mode "view"}
                        :even? {:x 1273 :y 557 :h 176 :w 281 :view-mode "data"}
                        :adder {:x 834 :y 329 :h 255 :w 240 :view-mode "data"}}})

(def looping-net {:components {:comp1 10
                               :comp2 20.1
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
                                                          :font-family "Sansita Swashed"
                                                          :font-weight 700
                                                          :font-size "110px"}])}
                               :conjer {:fn (fn [x]
                                              (defonce vv (atom []))
                                              (do (swap! vv conj x) @vv))
                                        :view (fn [x]
                                                [:vega-lite {:data {:values (map-indexed (fn [index value]
                                                                                           {:index index
                                                                                            :value value}) x)}
                                                             :mark {:type "bar"
                                                                    :color "#60a9eb66"}
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
                                                                                   :labelFont "Poppins"
                                                                                   :titleFont "Poppins"
                                                                                   :labelFontSize 9
                                                                                   :labelLimit 180
                                                                                   ;;:labelFontStyle {:color "blue"}
                                                                                   :domainColor "#ffffff11"}}}
                                                             :padding {:top 15 :left 15}
                                                             :width "container"
                                                             :height :height-int
                                                             :background "transparent"
                                                             :config {:style {"guide-label" {:fill "#ffffff77"}
                                                                              "guide-title" {:fill "#ffffff77"}}
                                                                      :view {:stroke "#00000000"}}} {:actions false}])}
                               :add-one4 {:fn #(do ;(Thread/sleep 100)
                                                   (+ 45 %))
                                          :cond {:condicane2 #(> % 800)}}
                            ;;    :display-val {:fn (fn [x] x)
                            ;;                  :view (fn [x]
                            ;;                          [:re-com/box :child (str x)
                            ;;                           :align :center :justify :center
                            ;;                           :padding "10px"
                            ;;                           :style {:color "#D7B4F3"
                            ;;                                   :font-weight 700
                            ;;                                   :font-size "80px"}])}
                               :display-val {:fn (fn [x] {:recent-val x
                                                          :test-map? true
                                                          :random-int (rand-int 123)
                                                          :vec [true false 1 3 4 4.234234 "bang!"]
                                                          ;:random-str (str "omg-" (rand-int 123) "!")
                                                          })}
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
                  :colors :Paired ;:Spectral
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
                           :display-val {:x 776 :y 600 :h 509 :w 358} ;{:x 771 :y 699 :h 179 :w 320}
                           :adder/in2 {:x 430 :y 430 :h 255 :w 240}}})

;; chatGPT4 created flow 
(def ecommerce-flow
  {:description "sample flow created by GPT4 for testing purposes (ecommerce)"
   :components
   {:get-product {:id 1 ;; from a database
                  :name "A Cool Gadget"
                  :base-price 123.99}

    :calculate-discount {:fn (fn [{:keys [base-price]}]
                               (* base-price 0.1)) ; 10% discount
                         :view (fn [x] (str "Discount: " (format "%.2f" x)))}

    :apply-discount {:fn (fn [{:keys [base-price]} discount]
                           (- base-price discount))
                     :inputs [:base-price :discount]
                     :view (fn [x] (str "Price after discount: " (format "%.2f" x)))}

    :calculate-tax {:fn (fn [discounted-price]
                          (* discounted-price 0.2)) ; 20% tax
                    :view (fn [x] (str "Tax: " (format "%.2f" x)))}

    :final-price {:fn (fn [discounted-price tax]
                        (+ discounted-price tax))
                  :inputs [:discounted-price :tax]
                  :view (fn [x] (str "Final price: " (format "%.2f" x)))}}
   :colors :Oranges
   :connections [[:get-product :calculate-discount]
                 [:get-product :apply-discount/base-price]
                 [:calculate-discount :apply-discount/discount]
                 [:apply-discount :calculate-tax]
                 [:apply-discount :final-price/discounted-price]
                 [:calculate-tax :final-price/tax]]

   :canvas {:final-price {:x 2317 :y 502 :h 255 :w 240}
            :final-price/discounted-price {:x 1616 :y 225 :h 255 :w 240}
            :calculate-discount {:x 319 :y 172 :h 255 :w 240}
            :get-product {:x -60 :y 334 :h 255 :w 240}
            :final-price/tax {:x 1924 :y 652 :h 255 :w 240}
            :apply-discount/base-price {:x 489 :y 558 :h 255 :w 240}
            :calculate-tax {:x 1484 :y 543 :h 255 :w 240}
            :apply-discount/discount {:x 683 :y 146 :h 255 :w 240}
            :apply-discount {:x 1084 :y 372 :h 255 :w 240}}})


;; chatGPT4 created flow
(def color-art-flow
  {:description "sample flow created by GPT4 for testing purposes (color art hiccup)"
   :components {:seed 45
                :generate-sequence
                {:fn (fn [n]
                       (map #(/ % n) (range n)))
                 :view (fn [x]
                         [:re-com/box :size "auto" :padding "6px"
                          :child (str "Generated sequence: " (pr-str x))])}
                :generate-colors
                {:fn (fn [sequence]
                       (map #(str "hsl(" (* % 360) ",100%,50%)") sequence))
                 :inputs [:sequence]
                 :view (fn [x]
                         [:re-com/box :size "auto" :padding "6px"
                          :child (str "Generated colors: " (pr-str x))])}
                :render-art
                {:fn (fn [colors]
                       [:re-com/h-box
                        :children (map (fn [color]
                                         [:div
                                          {:style
                                           {:background-color color
                                            :width "20px"
                                            :height :height-int}}]) colors)])
                 :inputs [:colors]
                 :view (fn [x]
                         [:re-com/box
                          :child x])}}
   :connections
   [[:seed :generate-sequence]
    [:generate-sequence :generate-colors/sequence]
    [:generate-colors :render-art/colors]]
   :canvas
   {:seed {:x 100 :y 100 :h 255 :w 240}
    :generate-sequence {:x 430 :y 100 :h 255 :w 240}
    :generate-colors/sequence
    {:x 760 :y 100 :h 291 :w 446}
    :generate-colors {:x 100 :y 430 :h 310 :w 288}
    :render-art/colors {:x 430 :y 430 :h 337 :w 476}
    :render-art {:x 986 :y 447 :h 245 :w 983}}})

;; chatGPT4 created flow
(def ecosystem-flow
  {:components
   {:init-population {:preys 100
                      :predators 10}

    :step-simulation {:fn (fn [{:keys [preys predators]}]
                            (let [eaten (min predators preys)
                                  new-preys (bigint (max (- (* 2 (- preys eaten)) predators) 0))
                                  new-predators (bigint (max (- (* 2 eaten) (int (/ predators 2))) 0))]
                              {:preys (min new-preys 1e9) ; add an upper limit
                               :predators (min new-predators 1e9)}))} ; add an upper limit

    :step-test {:fn (fn [x] x)
                :cond {:prepare-data (fn [{:keys [preys predators]}] (or (zero? preys) (zero? predators)))
                       :step-simulation (fn [{:keys [preys predators]}]
                                          (not (or (zero? preys) (zero? predators))))}}

    :ratios {:fn (fn [{:keys [preys predators]}] (format "%.2f" (float (/ predators preys))))
             :view (fn [x] [:re-com/box
                            :size "auto" :padding "6px"
                            :align :center :justify :center
                            :style {:font-size "33px"}
                            :child (str x)])}
    :prepare-data {:fn (fn [simulation]
                         (map-indexed (fn [index {:keys [preys predators]}]
                                        {:step index
                                         :preys preys
                                         :predators predators}) simulation))}

    ;; :render-graph {:view (fn [data]
    ;;                        (defonce dd (atom []))
    ;;                        (swap! dd conj {:value data :step #(apply max (map :step @dd))})
    ;;                        [:vega-lite {:data {:values @dd}
    ;;                                     :mark "line"
    ;;                                     :encoding {:x {:field "step"
    ;;                                                    :type "quantitative"}
    ;;                                                :y {:field "value"
    ;;                                                    :type "quantitative"}
    ;;                                                :color {:field "variable"
    ;;                                                        :type "nominal"}}
    ;;                                     :width "container"
    ;;                                     :height :height-int
    ;;                                     :background "transparent"
    ;;                                     :config {:style {"guide-label" {:fill "#ffffff77"}
    ;;                                                      "guide-title" {:fill "#ffffff77"}}
    ;;                                              :view {:stroke "#00000000"}}} {:actions false}])
    ;;               ; :inputs [:data]
    ;;                :fn (fn [x] x)}
    }

   :connections [[:init-population :step-simulation]
                 [:step-simulation :step-test]
                 [:step-simulation :ratios]
                 ;[:step-test :prepare-data]
                 ;[:ratios :render-graph]
                 ;[:prepare-data :render-graph/data]
                 ]
   :canvas {:init-population {:x 100 :y 100 :h 255 :w 240}
            :step-simulation {:x 430 :y 100 :h 322 :w 374}
            :step-test {:x 889 :y 87 :h 365 :w 452}
            :ratios {:x 898 :y 543 :h 165 :w 265}
            :prepare-data {:x 1454 :y 185 :h 443 :w 444}}})

(def etl-flow {:description "sample flow with ETL reading a database, transforming a resultset in Clojure, and inserting it into another database"
               :components ;; example of ETL with SQL extract, sending "large" dataset around to fns, and then back to SQL inserts (as opposed to all SQL w temp tables in DB, but sometimes you need to cross db connections)
               {:go :go
                :setup-db {:fn (fn [_] ;; ignoring actual sent value here for demo purposes (it's a trigger / signal)
                                 (try
                                   (let [db {:subprotocol "sqlite"
                                             :subname "/home/ryanr/mydata-transformed.db"}]
                                     (jdbc/db-do-commands db
                                                          ["CREATE TABLE IF NOT EXISTS offenses_by_district (\"DISTRICT\" VARCHAR(3) NULL, \"TOTAL_OFFENSES\" INTEGER NULL)"
                                                           "CREATE TABLE IF NOT EXISTS offenses_by_year (\"YEAR\" DECIMAL NULL, \"TOTAL_OFFENSES\" INTEGER NULL)"
                                                           "CREATE TABLE IF NOT EXISTS offenses_by_code (\"OFFENSE_CODE\" INTEGER NULL, \"TOTAL_OFFENSES\" INTEGER NULL)"
                                                           "CREATE TABLE IF NOT EXISTS offenses_by_date (\"ON_DATE\" VARCHAR(12) NULL, \"TOTAL_OFFENSES\" INTEGER NULL)"
                                                           "DELETE FROM offenses_by_district"
                                                           "DELETE FROM offenses_by_date"
                                                           "DELETE FROM offenses_by_code"
                                                           "DELETE FROM offenses_by_year"])
                                     :go) ;; send signal
                                   (catch Exception e {:error (str e)}))) ;; :error key (or :done keyword) will kill flow (ex of user-space error catch)
                           :view (fn [_] (str "DDL checked, tables truncated."))}
                :extract {:fn (fn [_] ;; ignoring actual sent value here for demo purposes (it's a trigger / signal)
                                (let [db {:subprotocol "sqlite"
                                          :subname "/home/ryanr/boston-crime-data.db"}]
                                  (jdbc/query db ["SELECT o.*, substring(occurred_on_date, 0, 11) as ON_DATE FROM offenses o"])))
                          :view (fn [x] (str "Extracted " (ut/nf (count x)) " rows"))}
                :transform-by-year {:fn (fn [data]
                                          (->> data
                                               (group-by :year)
                                               (mapv (fn [[year offenses]]
                                                       {:YEAR year
                                                        :TOTAL_OFFENSES (count offenses)}))))
                                    :inputs [:data]
                                    :view (fn [x] (str "Summarized " (ut/nf (count x)) " rows by year"))}
                :transform-by-district {:fn (fn [data]
                                              (->> data
                                                   (group-by :district)
                                                   (mapv (fn [[district offenses]]
                                                           {:DISTRICT district
                                                            :TOTAL_OFFENSES (count offenses)}))))
                                        :inputs [:data]
                                        :view (fn [x] (str "Summarized " (ut/nf (count x)) " rows by district"))}
                :transform-by-offense-code {:fn (fn [data]
                                                  (->> data
                                                       (group-by :offense_code)
                                                       (mapv (fn [[offense_code offenses]]
                                                               {:OFFENSE_CODE offense_code
                                                                :TOTAL_OFFENSES (count offenses)}))))
                                            :inputs [:data]
                                            :view (fn [x] (str "Summarized " (ut/nf (count x)) " rows by code"))}
                :transform-by-date {:fn (fn [data]
                                          (->> data
                                               (group-by :on_date)
                                               (mapv (fn [[on_date offenses]]
                                                       {:ON_DATE on_date ;(first (clojure.string/split occurred_on_date #" "))
                                                        :TOTAL_OFFENSES (count offenses)}))))
                                    :inputs [:data]
                                    :view (fn [x] (str "Summarized " (ut/nf (count x)) " rows by date"))}
                :load-by-year {:fn (fn [data]
                                     (let [db {:subprotocol "sqlite"
                                               :subname "/home/ryanr/mydata-transformed.db"}]
                                       (jdbc/insert-multi! db :offenses_by_year ["YEAR" "TOTAL_OFFENSES"]
                                                           (mapv (fn [{:keys [YEAR TOTAL_OFFENSES]}] [YEAR TOTAL_OFFENSES]) data))))
                               :inputs [:data]
                               :view (fn [x] (str "Loaded " (ut/nf (count x)) " rows into offenses_by_year"))}
                :load-by-district {:fn (fn [data]
                                         (let [db {:subprotocol "sqlite"
                                                   :subname "/home/ryanr/mydata-transformed.db"}]
                                           (jdbc/insert-multi! db :offenses_by_district ["DISTRICT" "TOTAL_OFFENSES"]
                                                               (mapv (fn [{:keys [DISTRICT TOTAL_OFFENSES]}] [DISTRICT TOTAL_OFFENSES]) data))))
                                   :inputs [:data]
                                   :view (fn [x] (str "Loaded " (ut/nf (count x)) " rows into offenses_by_district"))}
                :load-by-offense-code {:fn (fn [data]
                                             (let [db {:subprotocol "sqlite"
                                                       :subname "/home/ryanr/mydata-transformed.db"}]
                                               (jdbc/insert-multi! db :offenses_by_code ["OFFENSE_CODE" "TOTAL_OFFENSES"]
                                                                   (mapv (fn [{:keys [OFFENSE_CODE TOTAL_OFFENSES]}] [OFFENSE_CODE TOTAL_OFFENSES]) data))))
                                       :inputs [:data]
                                       :view (fn [x] (str "Loaded " (ut/nf (count x)) " rows into offenses_by_code"))}
                :load-by-date {:fn (fn [data]
                                     (let [db {:subprotocol "sqlite"
                                               :subname "/home/ryanr/mydata-transformed.db"}]
                                       (jdbc/insert-multi! db :offenses_by_date ["ON_DATE" "TOTAL_OFFENSES"]
                                                           (mapv (fn [{:keys [ON_DATE TOTAL_OFFENSES]}] [ON_DATE TOTAL_OFFENSES]) data))))
                               :inputs [:data]
                               :view (fn [x] (str "Loaded " (ut/nf (count x)) " rows into offenses_by_code"))}}

               :connections
               [[:go :setup-db]
                [:setup-db :extract]
                [:extract :transform-by-year/data]
                [:extract :transform-by-district/data]
                [:extract :transform-by-offense-code/data]
                [:extract :transform-by-date/data]
                [:transform-by-year :load-by-year/data]
                [:transform-by-district :load-by-district/data]
                [:transform-by-offense-code :load-by-offense-code/data]
                [:transform-by-date :load-by-date/data]
                [:load-by-offense-code :done]
                [:load-by-date :done]
                [:load-by-year :done]
                [:load-by-district :done]]
               :canvas {:load-by-offense-code {:x 2768 :y 749 :h 223 :w 462 :view-mode "view"}
                        :go {:x 260 :y 524 :h 186 :w 195 :view-mode "input"}
                        :transform-by-year {:x 1939 :y 459 :h 210 :w 335 :view-mode "view"}
                        :load-by-offense-code/data {:x 2322 :y 749 :h 261 :w 371 :view-mode "grid"}
                        :load-by-district/data {:x 2345 :y 73 :h 277 :w 364 :view-mode "grid"}
                        :load-by-date/data {:x 2331 :y 1073 :h 209 :w 362 :view-mode "grid"}
                        :transform-by-district {:x 1929 :y 94 :h 215 :w 355 :view-mode "view"}
                        :transform-by-date {:x 1922 :y 1065 :h 227 :w 294 :view-mode "view"}
                        :transform-by-offense-code {:x 1926 :y 760 :h 206 :w 353 :view-mode "view"}
                        :transform-by-year/data {:x 1365 :y 424 :h 304 :w 503 :view-mode "grid"}
                        :load-by-date {:x 2780 :y 1049 :h 240 :w 454 :view-mode "view"}
                        :setup-db {:x 553 :y 517 :h 196 :w 241 :view-mode "view"}
                        :load-by-district {:x 2785 :y 94 :h 218 :w 450 :view-mode "view"}
                        :load-by-year/data {:x 2332 :y 416 :h 259 :w 373 :view-mode "grid"}
                        :extract {:x 896 :y 514 :h 245 :w 300 :view-mode "view"}
                        :load-by-year {:x 2773 :y 357 :h 337 :w 491 :view-mode "data"}
                        :transform-by-offense-code/data {:x 1345 :y 774 :h 261 :w 528 :view-mode "grid"}
                        :transform-by-date/data {:x 1329 :y 1097 :h 241 :w 512 :view-mode "grid"}
                        :transform-by-district/data {:x 1365 :y 25 :h 326 :w 474 :view-mode "grid"}}})




