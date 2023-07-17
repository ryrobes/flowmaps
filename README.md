# flowmaps
### A Flow Based Programming Clojure Micro-Framework with canvas based "visual debugger"

"The Values Must Flow!"

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.ryrobes/flowmaps.svg)](https://clojars.org/org.clojars.ryrobes/flowmaps) ![example workflow](https://github.com/ryrobes/flowmaps/actions/workflows/clojure.yml/badge.svg)

![rabbit web ui](https://app.rabbitremix.com/gh-sample2.png)

## backend "flow runner" features
 - fully uses core.async channels for concurrency, etc
 - multi-input blocks (all inputs will wait automatically)
 - conditional pathing
 - a simple map-based interface
 - ability for a "result value" of the entire flow to "ship out" of the flow to an external channel or atom into the rest of your application

## front-end "rabbit debugger" features
- canvas based placement and arrangement to "see" your blocks (fns) and how they relate to each other via their connections (async channels)
- watching the flow "play out" visually
- "hijacking" channels to send arbitrary values to them and watch the resulting chain-reactions
- optional flow "views" (as in screenshot above), ignored if rabbit isn't up, but gives you the ability to write hiccup, re-com, and vega specs to render the values as they flow through the system
- simple gantt chart timeline to better understand flow parallelism

## 

![looping blocks and views](https://app.rabbitremix.com/gh-looped4.gif)

Example of blocks that have a singular purpose of displaying data - but only when viewed through the UI - these "front end views" will get ignored when running headlessly.

## 

# Super simple flow example

![rabbit web ui](https://app.rabbitremix.com/gh-sample1.png)

WIP - Full(er) Readme coming tomorrow. 

Flows are defined by a single map indicating the functions involved, their "ports", and how they "flow" together. The flow creates core.async channels and flows the values through the functions.

Super simple sample usage:

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
