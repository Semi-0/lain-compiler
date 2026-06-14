(ns propagators.compiler-behavior.main
  "Compatibility facade for the behavior compiler."
  (:require [propagators.compiler-behavior.application :as application]
            [propagators.compiler-behavior.core :as core]))

(def compiler-result-key core/compiler-result-key)
(def compiler-props-key core/compiler-props-key)
(def compiler-applications-key core/compiler-applications-key)
(def closure-reducer-id core/closure-reducer-id)
(def apply-behavior-application-props-key
  application/apply-behavior-application-props-key)

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
