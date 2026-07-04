(ns propagators.compiler-2.ast
  "Slot-backed AST constructors for compile-2."
  (:refer-clojure :exclude [name type])
  (:require [propagators.datastructures.compound-object :as obj]))

(declare ast)

(def type-slot :ast/type)
(def value-slot :ast/value)
(def name-slot :ast/name)
(def operator-slot :ast/operator)
(def args-slot :ast/args)
(def body-slot :ast/body)
(def names-slot :ast/names)
(def inputs-slot :ast/inputs)
(def output-slot :ast/output)
(def bindings-slot :ast/bindings)

(def ast-slots
  #{type-slot
    value-slot
    name-slot
    operator-slot
    args-slot
    body-slot
    names-slot
    bindings-slot
    inputs-slot
    output-slot})

(defn ast-node?
  [x]
  (some? (obj/slot-value x type-slot)))

(defn- ast-object
  [m]
  (obj/compound-object m))

(defn lit [v] (ast-object {type-slot :literal value-slot v}))
(defn sym [s] (ast-object {type-slot :symbol name-slot s}))

(defn app [op & args]
  (ast-object {type-slot :apply
               operator-slot (ast op)
               args-slot (mapv ast args)}))

(defn sequence* [& body]
  (ast-object {type-slot :sequence
               body-slot (mapv ast body)}))

(defn let-cell [names body]
  (ast-object {type-slot :let-cell
               names-slot (vec names)
               body-slot (ast body)}))

(defn let* [bindings body]
  (ast-object {type-slot :let
               bindings-slot (vec bindings)
               body-slot (ast body)}))

(defn network [inputs body]
  (ast-object {type-slot :network
               inputs-slot (vec inputs)
               body-slot (ast body)}))

(defn compound [{:keys [inputs output]} body]
  (ast-object {type-slot :compound
               inputs-slot (vec inputs)
               output-slot output
               body-slot (ast body)}))

(defn def-net [name inputs output body]
  (ast-object {type-slot :def-net
               name-slot name
               inputs-slot (vec inputs)
               output-slot output
               body-slot (ast body)}))

(defn def-constraint [name inputs body]
  (ast-object {type-slot :def-constraint
               name-slot name
               inputs-slot (vec inputs)
               body-slot (ast body)}))

(defn def* [name body]
  (ast-object (cond-> {type-slot :def
                       name-slot name}
                (some? body) (assoc body-slot (ast body)))))

(defn def-cell [name inputs body]
  (ast-object {type-slot :def-cell
               name-slot name
               inputs-slot (vec inputs)
               body-slot (ast body)}))

(defn ast [x]
  (cond
    (ast-node? x) (obj/compound-object x)
    (symbol? x) (sym x)
    :else (lit x)))

(defn object->map [x]
  (let [o (obj/compound-object x)]
    (into {}
          (map (fn [k] [k (obj/slot-value o k)]))
          (obj/public-slot-keys o))))

(defn ast-map [expr]
  (object->map (ast expr)))

(defn slot
  [expr slot-key]
  (obj/slot-value (ast expr) slot-key))

(defn type [expr] (slot expr type-slot))
(defn value [expr] (slot expr value-slot))
(defn name [expr] (slot expr name-slot))
(defn operator [expr] (slot expr operator-slot))
(defn args [expr] (slot expr args-slot))
(defn body [expr] (slot expr body-slot))
(defn names [expr] (slot expr names-slot))
(defn bindings [expr] (slot expr bindings-slot))
(defn inputs [expr] (slot expr inputs-slot))
(defn output [expr] (slot expr output-slot))
