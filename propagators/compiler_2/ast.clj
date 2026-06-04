(ns propagators.compiler-2.ast
  "Plain-data AST constructors for compile-2."
  (:require [propagators.datastructures.compound-object :as obj]))

(defn lit [v] {:ast/type :literal :ast/value v})
(defn sym [s] {:ast/type :symbol :ast/name s})

(declare ast)

(defn app [op & args]
  {:ast/type :apply
   :ast/operator (ast op)
   :ast/args (mapv ast args)})

(defn app->
  "Application with an explicit output cell expression."
  [op args out]
  {:ast/type :apply
   :ast/operator (ast op)
   :ast/args (mapv ast args)
   :ast/output (ast out)})

(defn do* [& body]
  {:ast/type :do
   :ast/body (mapv ast body)})

(defn let-cell [names body]
  {:ast/type :let-cell
   :ast/names (vec names)
   :ast/body (ast body)})

(defn compound [{:keys [inputs output]} body]
  {:ast/type :compound
   :ast/inputs (vec inputs)
   :ast/output output
   :ast/body (ast body)})

(defn let-compound [name compound-expr body]
  {:ast/type :let-compound
   :ast/name name
   :ast/value (ast compound-expr)
   :ast/body (ast body)})

(defn ast [x]
  (cond
    (and (map? x) (contains? x :ast/type)) x
    (symbol? x) (sym x)
    :else (lit x)))

(defn object->map [x]
  (let [o (obj/compound-object x)]
    (into {}
          (map (fn [k] [k (obj/slot-value o k)]))
          (obj/public-slot-keys o))))

(defn ast-map [expr]
  (object->map (ast expr)))
