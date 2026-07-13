(ns propagators.compiler-2.model.context
  "Implicit compiler-2 evaluation context values for contextual operators."
  (:require [propagators.compiler-2.model.env :as env]
            [propagators.datastructures.compound-object :as obj]))

(def scope-slot :context/scope)
(def chain-slot :context/chain)
(def application-slot :context/application)
(def operator-slot :context/operator)

(defn context-value
  [lexical-env application operator]
  (obj/compound-object
   {scope-slot (env/scope-id lexical-env)
    chain-slot (env/scope-chain lexical-env)
    application-slot application
    operator-slot operator}))

(defn scope [context]
  (obj/slot-value context scope-slot))

(defn chain [context]
  (obj/slot-value context chain-slot))

(defn application [context]
  (obj/slot-value context application-slot))

(defn operator [context]
  (obj/slot-value context operator-slot))

(defn dependency-source
  [context]
  {:dependency/type :compiler-2/application
   :context/scope (scope context)
   :context/chain (chain context)
   :context/application (application context)
   :context/operator (operator context)})


