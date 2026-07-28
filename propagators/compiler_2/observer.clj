(ns propagators.compiler-2.observer
  "Compiler-2 facade for generic, edge-free observers."
  (:require [propagators.observer :as observer]))

(defn p:observer
  "Construct a Compiler-2 observer using the standard network installer shape.

  Semantic compiler code supplies `sample`; installation and explicit sampling
  remain generic."
  ([sample]
   (observer/construct-observer :compiler-2/observer sample))
  ([name sample]
   (observer/construct-observer name sample))
  ([id name sample]
   (observer/construct-observer id name sample)))
