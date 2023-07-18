# flowmaps
### A *Flow Based Programming* Micro-Framework for Clojure with canvas based debugger

[![Clojars Project](https://img.shields.io/clojars/v/com.ryrobes/flowmaps.svg?include_prereleases)](https://clojars.org/com.ryrobes/flowmaps)
 ![example workflow](https://github.com/ryrobes/flowmaps/actions/workflows/clojure.yml/badge.svg)

![looping blocks and views](https://app.rabbitremix.com/gh-looped4.gif)

Originally introduced by J. Paul Morrison in the 1970s, FBP (Flow Based Programming) is a programming approach that defines applications as networks of "black box" processes, which exchange data packets over predefined connections. These connections transmit this data via predefined input and output ports. FBP is inherently concurrent and promotes high levels of component reusability and separation of concerns.

This model aligns very well with Clojure's core.async and functional programming at large. Flowmaps is essentially a *mapping* of FBP concepts and ergonomics on top core.async. Allowing the user to be less concerned with *it* - just write the "meat" of their flows - and get it's performance and benefits by default.

While it's nomenclature may diverge from Morrison's FBP terminology to be more clear in a Clojure world - it is still very much FBP by heart.

Flow-maps also provides a rabbit-ui visualizer / debugger to help UNDERSTAND and SEE how these flows are executed, their parallelism (if any), and more importantly - interactive experimentation and iterative development. One of the benefits of FBP is the ability for teams to better comprehend large complex systems as drawn out as boxes and lines - I wanted to provide a "live" version of that.

# **I wanted to write core.async flows w/o a blindfold**


![rabbit web ui](https://app.rabbitremix.com/gh-sample2.png)

## back-end "flow runner" features
 - fully uses core.async channels for concurrency, etc
 - multi-input blocks (all inputs will wait automatically)
 - conditional pathing
 - a simple map-based interface
 - ability for a "result value" of the entire flow to "ship out" of the flow to an external channel or atom into the rest of your application

## (100% optional) front-end "rabbit debugger" features
- canvas based placement and arrangement to "see" your blocks (fns) and how they relate to each other via their connections (async channels)
- watching the flow "play out" visually
- "hijacking" channels to send arbitrary values to them and watch the resulting chain-reactions
- optional block "views" (as in screenshot above), gives you the ability to write hiccup, re-com, and vega specs to render the values as they flow through the system - very useful in debugging or just monitoring your flow as it runs
- simple gantt chart timeline to better understand flow parallelism

## Basic concepts

- **flow-map**
    - The flow-map a literal Clojure map that contains all the info needed to bootstrap up a flow (or core.async chain). There are only 2 *required* keys.
        - *:components* - a reference mapping of a literal clojure function to a keyword "block" name. Can just be a static value (which just gets passed), a single fn alone, or a map with a :fn key and various values - see more advanced options in the examples.
        - *:connections* - a simple vector of 1:1 connections between blocks. Each of these will become a dedicated channel (and often more channels will be automatically created to due to *implied* connections)
        ```clojure
        {:components {:static-val1 10
                      :plus-10 #(+ 10 %)}
         :connections [[:static-val :plus-10]]}
         ;; should create one channel and return 20 via output channel/atom if provided
        ```
- **flow-runner**
    - the *flowmaps.core/flow* function that bootstraps the channels, seeds their values, and begins execution. Called "flow runner" or "runner". Can optionally return the "final" values via a channel, atom (or separate reporting fn *flowmaps.core/flow-results* (see below)).
- **blocks**
    - can be called a component or block function, etc. It's a member of the :components key above and the most basic building block (heh) of a flow. many of my examples use anon function wrappers for this since it makes the definition of where inputs will go, straightforward. More complex examples with multi-arity mappings below. (we are essentially defining the "input" and "output" ports of the block)
- **connections / "lines"**
    - a dedicated channel that provides data / signal from one place to another. In the rabbit-ui each line is in fact a channel - and when pushing arbitrary values around (via the left-side channel panel) you are putting the value "on the line" as opposed to "in a block", which is an important distinction in large more complex flows
- **views**
    - and optional key inside a block mapping that contains a server-side function that produces a hiccup, re-com, vega, vega-lite data structure that the front-end will understand (see examples below). This function will be passed a single value - which is the return value of *that* block - meaning that the view is processed after the block function. Note: If the rabbit-ui isn't running, this :view does nothing.
- **rabbit**
    - The optional canvas based UI. I have a history of flow-based canvas UIs - they all revolve around this grand "Data Rabbit" concept I have been working on for years. Projects like this are a opportunity to apply those concepts to niche areas. Anytime I mention "rabbit", "rabbit-ui", "the debugger", "the visualizer"- this is what I am referring to.
- **canvas**
    - The rabbit canvas where the flow is laid out upon. :canvas is also an optional flow map key - see below for examples that contains the :x :y coordinates of the block in "rabbit space", but also the height and width of that block via :h :w. Again, if running headlessly in production - these values are ignored.
- **path / track**
    - Sometimes used to refer to a set of blocks and channels together. i.e. "branching path", "looping path". A track can also be a segment of the flow that is unconnected to the larger flow and runs it's blocks independently. 
- **sub-flow**
    - an entire flow that gets executed as a block (not implemented yet! but important to understand, conecptually)



## Examples

# Super simple flow example

![rabbit web ui](https://app.rabbitremix.com/gh-sample1.png)

Contains a multi-input block, a single-input but explicity defined block, a fn only block, and some static values.

Simple sample usage:

```clojure
(ns my-app.core
  (:require [flowmaps.core :as fm]
            [flowmaps.web :as fweb])
  (:gen-class))

(def first-flow {:components {:comp1 10 ;; static "starter value"
                              :comp2 20 ;; static "starter value"
                              :simple-plus-10 #(+ 10 %) ;; wrapping useful for single input blocks
                              :add-one #(+ 1 %)         ;; easy to point where the flowing val goes
                              :adder-one {:fn #(apply + %)
                                          :inputs [:in]}
                              :adder {:fn + ;; multi input uses "apply" after materializing inputs
                                      :inputs [:in1 :in2]}}
                 :connections [[:comp1 :adder/in1]
                               [:comp2 :adder/in2]
                               [:adder :simple-plus-10]]})

(fweb/start!) ;; starts up the rabbit-ui viewer at http://localhost:8888/ 
(fm/flow first-flow) ;; starts the flow, look upon ye rabbit and rejoice!
```

TODO Explain what this does exactly.

# Medium complexity example

![rabbit web ui](https://app.rabbitremix.com/gh-sample4.png)

A slight ramp up in complexity from the above flow. Contains a loop that exists via a conditional path, some blocks that contain views, a block with it's own atom/state, and provides canvas metadata to rabbit for a pre-defined layout.

```clojure
(ns my-app.core
  (:require [flowmaps.core :as fm]
            [flowmaps.web :as fweb])
  (:gen-class))

(def looping-net {:components {:comp1 10
                               :comp2 20
                               :comp3 [133 45] ;; static values (unless changed via an override - see below)
                               :simple-plus-10 {:fn #(+ 10 %)} ;; anon fns to easily "place" the input value explicitly
                               :add-one {:fn #(+ % 1)}
                               :add-one2 {:fn #(+ 1 %)}
                               :add-one3 {:fn #(+ 1 %)}
                               :counter {:fn #(count %)
                                         :view (fn [x] ;; simple "view" - materialized server-side, rendered by rabbit
                                                 [:re-com/box :child (str x " loops") ;; :re-com/box h-box v-box keywordized
                                                  :align :center :justify :center     ;; (just a re-com component)
                                                  :padding "10px"
                                                  :style {:color "#50a97855"
                                                          :font-weight 700
                                                          :font-size "100px"}])}
                               :conjer {:fn (fn [x] ;; block fn with it's own atom for collection values
                                              (defonce vv (atom []))
                                              (do (swap! vv conj x) @vv))
                                        :view (fn [x] ;; vega-lite via oz/vega-lite receives the OUTPUT of the fn above
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
                                                                                   :labelFont "Poppins" 
                                                                                   :titleFont "Poppins"
                                                                                   :domainColor "#ffffff11"}}
                                                                        :y {:field "value" :type "quantitative"
                                                                            :title "random additive values"
                                                                            :axis {:labelColor "#ffffff77"
                                                                                   :titleColor "#ffffff"
                                                                                   :ticks false
                                                                                   :gridColor "#ffffff11"
                                                                                   :labelFont "Poppins" 
                                                                                   :titleFont "Poppins" 
                                                                                   :labelFontSize 9 
                                                                                   :labelLimit 180
                                                                                   :domainColor "#ffffff11"}}}
                                                             :padding {:top 15 :left 15}
                                                             :width "container"
                                                             :height :height-int
                                                             :background "transparent"
                                                             :config {:style {"guide-label" {:fill "#ffffff77"}
                                                                              "guide-title" {:fill "#ffffff77"}}
                                                                      :view {:stroke "#00000000"}}} {:actions false}])}
                               :add-one4 {:fn #(do (Thread/sleep 120) (+ 45 %))
                                          :cond {:condicane2 #(> % 800)}} ;; a boolean fn that acts as conditional pathway
                               :display-val {:fn (fn [x] x) ;; this fn is just a pass-through, only used for rendering
                                             :view (fn [x]  ;; the values it passes here as re-com again
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
                  :canvas ;; used for rabbit placement. ignored otherwise.
                  {:conjer {:x 845 :y 891 :h 319 :w 428}
                   :whoops {:x 1482 :y 1318 :h 188 :w 310}
                   :add-one {:x 1456 :y 467 :h 173 :w 220}
                   :comp2 {:x 125 :y 450 :h 139 :w 237}
                   :adder/in1 {:x 424 :y 201 :h 175 :w 232}
                   :comp1 {:x 107 :y 210 :h 137 :w 227}
                   :condicane2 {:x 1168 :y 1311 :h 181 :w 253}
                   :counter {:x 1835 :y 1060 :h 226 :w 729}
                   :add-one4 {:x 377 :y 823 :h 232 :w 317}
                   :adder {:x 781 :y 275 :h 255 :w 240}
                   :add-one2 {:x 202 :y 1140 :h 187 :w 238}
                   :simple-plus-10 {:x 1103 :y 334 :h 255 :w 240}
                   :add-one3 {:x 542 :y 1120 :h 195 :w 255}
                   :display-val {:x 768 :y 662 :h 179 :w 320}
                   :adder/in2 {:x 430 :y 430 :h 175 :w 232}}})

(fweb/start!) ;; starts up the rabbit-ui viewer at http://localhost:8888/ 
(fm/flow looping-flow) ;; starts the flow, look upon ye rabbit and rejoice!

```

# flow fn options

The "flowmaps.core/flow" fn is what creates the channels and executes the flow by pushing static values on to the starter channels. Think of it as setting up the dominoes and tapping the first one. From there on out it's in the hands of the channel connections and the "flowmaps.core/process", which you shouldn't need to ever user directly.

```clojure
(flow flow-map ;; the map that defines the flow (see above examples and explanation)
      options  ;; currently only {:debug? true/false} defaults to true, false will silence the output
      opt-output-channel-or-atom ;; given an external channel or atom - will populate it once the flow
                                 ;; reaches a block called :done - out to the rest of your app or whatever
      value-overrides-map) ;; hijack some input values on static "starter" blocks {:block-target new-val}
                           ;; typically use for replacing existing values in a flow-map, it's technically
                           ;; replacing the entire block, so it could be a fn or a block map as well
```

Simple options example:

```clojure
(ns my-app.core
  (:require [flowmaps.core :as fm]
            [flowmaps.web :as fweb])
  (:gen-class))

(def my-flow {:components {:start1 10
                           :start2 20
                           :* {:fn * :inputs [:in1 :in2]}}
              :connections [[:start1 :*/in1]
                            [:start2 :*/in2]
                            [:* :done]]}) ;; note the :done meta block, so it knows what to "return"

(def res (atom nil)) ;; create an atom to hold the value (could also use a channel)
(fweb/start!) ;; starts up a rabbit at http://localhost:8888/ 

(fm/flow my-flow {:debug? false} res) ;; adding res atom as the eventual receiver of the final value / signal

@res
;; returns:  200

(fm/flow my-flow {:debug? false} res {:start1 44}) ;; will "override" the value of 10

@res
;; returns:  880

```

(above as shown in rabbit)

![rabbit web ui](https://app.rabbitremix.com/gh-sample5.png)

# flow-results fn
Work in progress. Iterates through the various "paths" that have been resolved and gives the last value of each of those steps.
Example:
```clojure
;; given the flow-map in the example above...

(fm/flow-results) ;; no params, will eventually require a flow-id key

;; returns: 
{:resolved-paths-end-values 
    ({[:start2 :*/in2 :* :done] ;;a singular "path" or "track"
              ([:start2 20] [:*/in2 {:port-in? true, :*/in2 20}] [:* 200] [:done 200])} ;; the results
     {[:start1 :*/in1 :* :done] 
              ([:start1 10] [:*/in1 {:port-in? true, :*/in1 10}] [:* 200] [:done 200])})}

```

# Quick Rabbit UI primer

![rabbit web ui primer 1](https://app.rabbitremix.com/gh-primer1.png) 
When the web ui is first booted up via...
```clojure
(fweb/start!) ;; flowmaps.web/start! or stop!
;; returns:   
[:*web "starting web ui @ http://localhost:8888" "🐇"]
```
...you will see a blank canvas that won't show anything until a flow is run. Obviously this data is not pushed out unless rabbit is running in your REPL or application.

![rabbit web ui primer 2](https://app.rabbitremix.com/gh-primer2.png)

Hit the *spacebar* to toggle the channel sidebar panel.

This left-side panel lists all the active channels. Each line between blocks is a channel. Hovering over a block or a channel will highlight that in the other - as well as directly *upstream* channels and blocks.

![rabbit web ui primer 4](https://app.rabbitremix.com/gh-primer4.png)

Value explorer - double-clicking on a block's titlebar will open up a side panel on the *right* and show the values or views associated with that block. Click anywhere on the canvas to dismiss.

![rabbit web ui primer 5](https://app.rabbitremix.com/gh-primer5.png)

Double clicking on the channel pill on the left sidebar to expand it. It will contain:
- a REPL command to push a value to that channel (copy-pasta all the way!)
- a set of the last 4 values that channel has "seen" (click on one of them to RESEND that value to the channel)
- a text box to send an *arbitrary* value to this channel

This is a great way to interact with the flow once it's been booted up. The channels are all still open and "running" so placing values upstream allows you to "watch" them flow back down through the system.

**More documentation WIP**


## TODO / ideas (as of 7/17/23, in no particular order)
- spec for flow-map data sent to the *flow* fn with useful errors
- :flow-id for each run to allow concurrent flows w/o clashing (will also fix a closing channel bug)
    - rabbit-ui will need to allow the user to choose which "loaded / running flow" to render and interact with (see "sub-flows" below)
- :cljs-view option for ClojureScript views that get compiled on the front-end instead of the back end. Opens up some more interactivity options for view blocks.
- subflows! essentially a block that is an entire other flow with some "input overrides" that flow in from the "parent flow". Almost like a visual function that can be examined using the rabbit-ui as it's own flow. (sub)flow-maps as blocks opens up a really interesting world of re-usability...
- flowmaps.io as an open "library" of block, flow, function snippets to add to your flows or play around with (actual way it will work TBD)
    - this will allow me to focus this repo on the core backend library instead of shoehorning all kinds of rando stuff in there that most people won't use, although I'm def not against the creation of "core primitive" block types for a basic set of CLJ fns shorthand that ships out of the box (perhaps even going as far as packaging SQL, JDBC, etc). TBD!
    - some simple examples
        - "Philips Hue block" to change your lights - read their statuses
        - OpenAI chat block - simplify the API call as much as possible
        - etc
- meta :connection pathways for errors. i.e. each block will have an implicit error output port :block-name/error that can be handled *in* the flow user-space instead of killing the flow chain (as it does currently)
- fix bugs! remove weirdness! (please open issues with example flows to repro when you find them)
- some shortcuts for (not) using :done blocks (optional *implied* done, instead of *explicitly linked*)
- rabbit-ui "block assembly". 
    - The ability to (given a set of starting block defs) use the UI to drag in blocks, connect them, alter their values, test the block functions (disconnectedly with arbitrary values if need be), and then copy-pasta that finished flow-map back to your application
        - *I say copy-pasta back because that way YOU are in control instead of me having some other system of persisted DB data that you need to contend with. Some optional feature like that may exist in the future (persistent flow-db w time-travel, etc), but the **simple way**, I think, is **always** just giving you the flow "source" to re-use as you see fit.
- rendering multi "input ports" differently? Currently rabbit shows them as their own blocks being injested - which is the easiest way to view and understand them, but conceptually they are just child nodes of the parent block and not their own entities... TBD
- (optional) built-in visualization of block fn spyscope execution data? TBD
- better ergonomics of the flow-map itself, just things that make it less verbose and slightly more flexible - reduce complexity, etc.
    - instead of having a static block defined as :my-input 15 - and then having a connection of [:my-input :my-fn-starts] why not just have a connection of [15 :my-fn-starts] and let the system create the implied :static-value-1 block with a 15 in it...
- render rowsets (vectors/lists of uniform maps) in a nice virtual table / grid component instead of nested map blocks in the UI
- better visualization of "tracks" - i.e. natural pathfinding of connected blocks
- a better pathfinding / coord generating layout algo. When Rabbit loads up a flow without any :canvas metadata it will attempt to lay the blocks out so they don't overlap and are somewhat grokable - but it is FAR from the ideal left->right spaced out layout that we want.

## License

Copyright © 2023 Ryan Robitaille (ryan.robitaille@gmail.com @ryrobes)

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
