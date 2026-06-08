(ns propagators.compiler-2.main
  "Compatibility facade for compiler-2."
  (:require [propagators.compiler-2.application-value :as application-value]
            [propagators.compiler-2.closure-value :as closure-value]
            [propagators.compiler-2.core :as core]))

(def compiler-result-key core/compiler-result-key)
(def compiler-props-key core/compiler-props-key)
(def compiler-applications-key core/compiler-applications-key)

(def closure-runtime-slot closure-value/closure-runtime-slot)
(def closure-env-slot closure-value/closure-env-slot)
(def closure-body-slot closure-value/closure-body-slot)
(def closure-inputs-slot closure-value/closure-inputs-slot)
(def closure-output-slot closure-value/closure-output-slot)
(def closure-scope-slot closure-value/closure-scope-slot)

(def application-operator-ast-slot
  application-value/application-operator-ast-slot)
(def application-operator-cell-slot
  application-value/application-operator-cell-slot)
(def application-args-slot application-value/application-args-slot)
(def application-arg-cells-slot application-value/application-arg-cells-slot)
(def application-output-slot application-value/application-output-slot)
(def application-context-slot application-value/application-context-slot)
(def application-lowering-slot application-value/application-lowering-slot)

(def g:compile core/g:compile)
(def g:apply core/g:apply)
(def g:advance core/g:advance)

(defn compile-expr
  ([expr] (core/compile-expr expr))
  ([expr env] (core/compile-expr expr env))
  ([expr env opts] (core/compile-expr expr env opts)))

(defn compile-source
  ([source] (core/compile-source source))
  ([source env] (core/compile-source source env))
  ([source env opts] (core/compile-source source env opts)))

(defn compiled-result [compiled-net]
  (core/compiled-result compiled-net))

(defn compiled-props [compiled-net]
  (core/compiled-props compiled-net))

(defn compiled-applications [compiled-net]
  (core/compiled-applications compiled-net))

(defn p:compile-expr [expr-id env-id out-id]
  (core/p:compile-expr expr-id env-id out-id))
