(ns propagators.compiler.model.context
  "Implicit compiler-2 evaluation context values for contextual operators."
  (:require [propagators.compiler.model.env :as env]
            [propagators.infra.datastructures.compound-object :as obj]))

(def scope-slot :context/scope)
(def chain-slot :context/chain)
(def env-slot :context/env)
(def application-slot :context/application)
(def operator-slot :context/operator)

(defn context-value
  [environment-id application operator]
  (obj/compound-object
   {env-slot environment-id
    scope-slot environment-id
    chain-slot [environment-id]
    application-slot application
    operator-slot operator}))

(defn scope [context]
  (obj/slot-value context scope-slot))

(defn lexical-env [context]
  (obj/slot-value context env-slot))

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
