(ns propagators.compiler-2.model.closure-value
  "Slot-backed compiler-2 closure data.

  A compiler-2 closure is data only: it records the body AST, lexical
  environment, formal inputs, optional output name, and lexical scope metadata.
  Application behavior lives in `propagators.compiler-2.runtime.application`.
  "
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]))

(def closure-runtime-slot
  "Deprecated compatibility slot. Compiler-2 closure values must not contain it."
  :closure/runtime)

(def closure-env-slot :closure/env)
(def closure-body-slot :closure/body)
(def closure-inputs-slot :closure/inputs)
(def closure-output-slot :closure/output)
(def closure-scope-slot :closure/scope)

(def implicit-return-namespace "compiler-2.implicit-return")

(def closure-slots
  #{closure-env-slot
    closure-body-slot
    closure-inputs-slot
    closure-output-slot
    closure-scope-slot})

(defn slot-map
  [closure-info]
  (into {}
        (map (fn [slot-key] [slot-key (obj/slot-value closure-info slot-key)]))
        (obj/public-slot-keys closure-info)))

(defn closure-object
  [lexical-env body inputs output scope]
  (obj/compound-object
   {closure-env-slot lexical-env
    closure-body-slot body
    closure-inputs-slot (vec inputs)
    closure-output-slot output
    closure-scope-slot scope}))

(defn implicit-return-symbol
  [id]
  (symbol implicit-return-namespace (str id)))

(defn implicit-return-symbol?
  [x]
  (and (symbol? x)
       (= implicit-return-namespace (namespace x))))

(defn implicit-return-output?
  [output]
  (and (vector? output)
       (= 1 (count output))
       (implicit-return-symbol? (first output))))

(defn closure-env [closure-info]
  (obj/slot-value closure-info closure-env-slot))

(defn closure-body [closure-info]
  (obj/slot-value closure-info closure-body-slot))

(defn closure-inputs [closure-info]
  (obj/slot-value closure-info closure-inputs-slot))

(defn closure-output [closure-info]
  (obj/slot-value closure-info closure-output-slot))

(defn closure-scope [closure-info]
  (obj/slot-value closure-info closure-scope-slot))

(defn closure-info?
  [x]
  (and (not (value/unusable? x))
       (not (value/contradiction? x))
       (nil? (obj/slot-value x closure-runtime-slot))
       (not (value/unusable? (closure-env x)))
       (some? (closure-body x))
       (vector? (closure-inputs x))
       (some? (closure-scope x))))


