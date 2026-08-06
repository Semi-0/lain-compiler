(ns ^:deprecated propagators.compiler-2.predicate-core
  "Deprecated synchronous compiler shim; production uses `compiler-2.cps-core`."
  (:refer-clojure :exclude [symbol?])
  (:require [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.compiler.predicates :as predicates]
            [propagators.compiler-2.deprecated.core :as core]
            [propagators.compiler-2.deprecated.synchronous :as synchronous]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def compiler-result-key core/compiler-result-key)
(def compiler-props-key core/compiler-props-key)
(def compiler-applications-key core/compiler-applications-key)

(def literal? predicates/literal?)
(def symbol? predicates/symbol?)
(def sequence? predicates/sequence?)
(def let-cell? predicates/let-cell?)
(def let? predicates/let?)
(def when-topology? predicates/when-topology?)
(def network? predicates/network?)
(def compound? predicates/compound?)
(def def-net? predicates/def-net?)
(def def-constraint? predicates/def-constraint?)
(def definition? predicates/definition?)

(def compile-literal synchronous/compile-literal)
(def compile-symbol synchronous/compile-symbol)
(def compile-sequence synchronous/compile-sequence)
(def compile-let-cell synchronous/compile-let-cell)
(def compile-let synchronous/compile-let)
(def compile-when-topology synchronous/compile-when-topology)
(def compile-network-form synchronous/compile-network-form)
(def compile-compound synchronous/compile-compound)
(def compile-def-net synchronous/compile-def-net)
(def compile-def-constraint synchronous/compile-def-constraint)
(def compile-def synchronous/compile-def)
(def compile-application core/compile-application)

(defn advance-binding [binding _state] binding)

(def compiler-dispatch core/compiler-dispatch)
(def compile* core/default-compiler)
(def ^:deprecated default-compiler compile*)
(def compile-expr core/compile-expr)
(def compile-source core/compile-source)
(def compiled-result core/compiled-result)
(def compiled-props core/compiled-props)
(def compiled-applications core/compiled-applications)

(defn p:compile-expr-with
  [compile* expr-id env-id out-id]
  (prop/construct-propagator
   (prop/concrete-propagator
    (fn [_inputs _outputs network]
      (let [expr (net/network-cell-strongest network expr-id)
            compiler-env (net/network-cell-strongest network env-id)
            compiled (core/compile-expr expr compiler-env
                                        {:net network
                                         :seed [:compile-2 expr-id env-id]
                                         :compiler compile*})]
        [(message out-id (:net compiled))])))
   [expr-id env-id]
   [out-id]))

(defn p:compile-expr
  [expr-id env-id out-id]
  (p:compile-expr-with default-compiler expr-id env-id out-id))

(defn p:execute-sub-env
  ([parent-env-id expr-id out-id]
   (p:execute-sub-env parent-env-id expr-id [] (ids/new-node-id) out-id))
  ([parent-env-id expr-id child-env-id out-id]
   (p:execute-sub-env parent-env-id expr-id [] child-env-id out-id))
  ([parent-env-id expr-id watch-ids child-env-id out-id]
   (application/p:execute-sub-env-with default-compiler
                                       parent-env-id expr-id watch-ids
                                       child-env-id out-id)))
