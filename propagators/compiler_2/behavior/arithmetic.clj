(ns propagators.compiler-2.behavior.arithmetic
  "Compiler-2 behavior arithmetic operator wrappers."
  (:require [propagators.compiler-2.operator-value :as operator-value]
            [propagators.datastructures.behavior.arithmetic :as behavior-arithmetic]))

(defn- arithmetic-operator
  [name messages-f op f]
  (operator-value/propagator-operator
   {:name name
    :input-selector (fn [arg-ids _fallback-id _context-id]
                      (let [arg-ids (vec arg-ids)]
                        (when (empty? arg-ids)
                          (throw (ex-info "behavior arithmetic requires at least one input"
                                          {:op op :arg-ids arg-ids})))
                        arg-ids))
    :activate (fn [current-net inputs outputs _context-id]
                (messages-f op f inputs (first outputs) current-net))}))

(defn distributed-behavior-operator
  "TMS-composed behavior wrapper; plain behavior arithmetic remains in helpers."
  [op f]
  (arithmetic-operator
   [:behavior/arithmetic op]
   behavior-arithmetic/distributed-behavior-messages
   op
   f))

(defn stable-distributed-behavior-operator
  "Behavior-TMS wrapper with stable claim reuse for rebuilt behavior values."
  [op f]
  (arithmetic-operator
   [:behavior/arithmetic-stable op]
   behavior-arithmetic/distributed-behavior-stable-messages
   op
   f))
