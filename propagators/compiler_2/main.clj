(ns propagators.compiler-2.main
  "Compatibility facade for compiler-2."
  (:require [propagators.compiler-2.model.application-value :as application-value]
            [propagators.compiler-2.operators.behavior :as behavior]
            [propagators.compiler-2.model.closure-value :as closure-value]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.deprecated.core :as core]
            [propagators.compiler-2.language.parser :as parser]))

(def compiler-result-key compiler/compiler-result-key)
(def compiler-props-key compiler/compiler-props-key)
(def compiler-applications-key compiler/compiler-applications-key)

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

(def compiler-dispatch compiler/compiler-dispatch)
(def compile* compiler/compile*)
(def default-compiler compiler/default-compiler)

(defn compile-expr
  ([expr] (compiler/compile-expr expr))
  ([expr env] (compiler/compile-expr expr env))
  ([expr env opts] (compiler/compile-expr expr env opts)))

(defn compile-source
  ([source] (compiler/compile-source source))
  ([source env] (compiler/compile-source source env))
  ([source env opts] (compiler/compile-source source env opts)))

(defn behavior-tms-env []
  (behavior/behavior-tms-env))

(defn compile-expr-with-behavior-tms
  ([expr] (compile-expr-with-behavior-tms expr {}))
  ([expr opts] (compiler/compile-expr expr (behavior-tms-env) opts)))

(defn compile-source-with-behavior-tms
  ([source] (compile-source-with-behavior-tms source {}))
  ([source opts]
   (compile-expr-with-behavior-tms (parser/parse-string source) opts)))

(defn compiled-result [compiled-net]
  (compiler/compiled-result compiled-net))

(defn compiled-props [compiled-net]
  (compiler/compiled-props compiled-net))

(defn compiled-applications [compiled-net]
  (compiler/compiled-applications compiled-net))

(defn p:compile-expr [expr-id env-id out-id]
  (compiler/p:compile-expr expr-id env-id out-id))

(defn p:execute-sub-env
  ([parent-env-id expr-id out-id]
   (compiler/p:execute-sub-env parent-env-id expr-id out-id))
  ([parent-env-id expr-id child-env-id out-id]
   (compiler/p:execute-sub-env parent-env-id expr-id child-env-id out-id))
  ([parent-env-id expr-id watch-ids child-env-id out-id]
   (compiler/p:execute-sub-env parent-env-id
                              expr-id
                              watch-ids
                              child-env-id
                              out-id)))
