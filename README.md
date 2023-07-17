# flowmaps
### A Flow Based Programming Clojure Micro-Framework with canvas based "visual debugger"

Originally introduced by J. Paul Morrison in the 1970s, FBP (Flow Based Programming) is a programming approach that defines applications as networks of "black box" processes, which exchange data packets over predefined connections. These connections transmit this data via predefined input and output ports. FBP is inherently concurrent and promotes high levels of component reusability and separation of concerns.

This model aligns highly with Clojure's core.async model. Flowmaps is essentially a *mapping* of FBP concepts and ergonomics on top core.async, while allowing the user to no be concerned with it at all - just write the "meat" of their flows - and get it's performance and benefits by default.

On top of that - flow-maps provides a rabbit-ui visualizer / debugger to help UNDERSTAND and SEE how these flows are executed, their parallelism (if any), and more importantly - interactive experimentation and iterative development.

# **I wanted to write core.async flows without a blindfold on.**

While it's nomenclature may diverge from Morrison's FBP terminology to be more clear in a Clojure world - it is still very much FBP by heart.

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.ryrobes/flowmaps.svg)](https://clojars.org/org.clojars.ryrobes/flowmaps) ![example workflow](https://github.com/ryrobes/flowmaps/actions/workflows/clojure.yml/badge.svg)

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
- optional flow "views" (as in screenshot above), ignored if rabbit isn't up, but gives you the ability to write hiccup, re-com, and vega specs to render the values as they flow through the system
- simple gantt chart timeline to better understand flow parallelism

## 

![looping blocks and views](https://app.rabbitremix.com/gh-looped4.gif)

Example of blocks that have a singular purpose of displaying data - but only when viewed through the UI - these "front end views" will get ignored when running headlessly. Also show in the canvas reacts to incoming values to make it easier to "see" how your flow is running and reacting to other blocks.

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

## TODO / ideas (as of 7/17/23, in no particular order)
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
