(ns propagators.compiler-2.model.application-value
  "Slot-backed retained IR for compiler-2 applications."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]))

(def application-operator-ast-slot :application/operator-ast)
(def application-operator-cell-slot :application/operator-cell)
(def application-args-slot :application/args)
(def application-arg-cells-slot :application/arg-cells)
(def application-output-slot :application/output)
(def application-context-slot :application/context)
(def application-lowering-slot :application/lowering)

(def application-slots
  #{application-operator-ast-slot
    application-operator-cell-slot
    application-args-slot
    application-arg-cells-slot
    application-output-slot
    application-context-slot
    application-lowering-slot})

(defn application-object
  [{:keys [operator-ast operator-cell args-id arg-ids output-id context-id lowering]}]
  (obj/compound-object
   {application-operator-ast-slot operator-ast
    application-operator-cell-slot (or operator-cell value/nothing)
    application-args-slot args-id
    application-arg-cells-slot (vec arg-ids)
    application-output-slot output-id
    application-context-slot context-id
    application-lowering-slot lowering}))

(defn application-info?
  [x]
  (and (not (value/unusable? x))
       (not (value/contradiction? x))
       (some? (obj/slot-value x application-operator-ast-slot))
       (some? (obj/slot-value x application-args-slot))
       (vector? (obj/slot-value x application-arg-cells-slot))
       (some? (obj/slot-value x application-output-slot))
       (some? (obj/slot-value x application-context-slot))
       (contains? #{:primitive :closure-cell}
                  (obj/slot-value x application-lowering-slot))))


