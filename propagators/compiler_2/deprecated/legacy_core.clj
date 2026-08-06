(ns ^:deprecated propagators.compiler-2.deprecated.legacy-core
  "Archived legacy namespace shim; use `propagators.compiler-2.deprecated.core`
  for the synchronous compiler or `propagators.compiler-2.cps-core` for
  production compilation."
  (:require [propagators.compiler-2.deprecated.core :as deprecated]))

(def compiler-result-key deprecated/compiler-result-key)
(def compiler-props-key deprecated/compiler-props-key)
(def compiler-applications-key deprecated/compiler-applications-key)

(def g:compile deprecated/g:compile)
(def g:apply deprecated/g:apply)
(def g:advance deprecated/g:advance)

(def declare-direct-operator-application
  deprecated/declare-direct-operator-application)
(def declare-operator-application-bindings
  deprecated/declare-operator-application-bindings)
(def declare-operator-application deprecated/declare-operator-application)
(def normalize-closure-output deprecated/normalize-closure-output)
(def declare-retained-cell-application-bindings
  deprecated/declare-retained-cell-application-bindings)
(def declare-retained-cell-application
  deprecated/declare-retained-cell-application)
(def declare-runtime-cell-application-bindings
  deprecated/declare-runtime-cell-application-bindings)
(def declare-runtime-cell-application
  deprecated/declare-runtime-cell-application)
(def resolve-cell-declarer deprecated/resolve-cell-declarer)
(def apply-operator deprecated/apply-operator)
(def closure-locals deprecated/closure-locals)
(def seed-closure-declaration deprecated/seed-closure-declaration)
(def attach-closure-environment deprecated/attach-closure-environment)
(def declare-closure deprecated/declare-closure)
(def define-binding deprecated/define-binding)
(def lower-let deprecated/lower-let)

(def compile-literal deprecated/compile-literal)
(def compile-symbol deprecated/compile-symbol)
(def compile-sequence deprecated/compile-sequence)
(def compile-let-cell deprecated/compile-let-cell)
(def compile-let deprecated/compile-let)
(def compile-when-topology deprecated/compile-when-topology)
(def compile-network-form deprecated/compile-network-form)
(def compile-compound deprecated/compile-compound)
(def compile-def-net deprecated/compile-def-net)
(def compile-def-constraint deprecated/compile-def-constraint)
(def compile-def deprecated/compile-def)
(def compile-application deprecated/compile-application)

(def ^:deprecated compiler-dispatch deprecated/compiler-dispatch)
(def ^:deprecated default-compiler deprecated/default-compiler)
(def ^:deprecated compile-expr deprecated/compile-expr)
(def ^:deprecated compile-source deprecated/compile-source)
(def compiled-result deprecated/compiled-result)
(def compiled-props deprecated/compiled-props)
(def compiled-applications deprecated/compiled-applications)
(def ^:deprecated p:compile-expr deprecated/p:compile-expr)
