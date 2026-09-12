(ns propagators.compiler.cps-core
  "Canonical stack-safe compiler-2 implementation."
  (:require [propagators.compiler.lowering.application :as application]
            [propagators.compiler.compiler.handlers :as handlers]
            [propagators.compiler.compiler.dispatch :as dispatch]
            [propagators.compiler.compiler.predicates :as predicates]
            [propagators.compiler.compiler.rewrite :as rewrite]
            [propagators.compiler.compiler.basis :as h]
            [propagators.compiler.language.parser :as parser]
            [propagators.compiler.model.env :as env]
            [propagators.compiler.model.operator-value :as operator-value]
            [propagators.compiler.common.cps :as cps]
            [propagators.compiler.common.core :as common]
            [propagators.infra.ids :as ids]
            [propagators.infra.message :refer [message]]
            [propagators.infra.network :as net]
            [propagators.infra.propagator :as prop]))

(def compiler-result-key common/compiler-result-key)
(def compiler-props-key common/compiler-props-key)
(def compiler-applications-key common/compiler-applications-key)

(def compiler-dispatch
  (cps/compose-rules
   (cps/on predicates/literal? handlers/compile-literal)
   (cps/on predicates/symbol? handlers/compile-symbol)
   (cps/on predicates/sequence? handlers/compile-sequence)
   (cps/on predicates/let-cell?
           (cps/transform-expr rewrite/let-cell->let handlers/compile-let))
   (cps/on predicates/let? handlers/compile-let)
   (cps/on predicates/when-topology? handlers/compile-when-topology)
   (cps/on predicates/network? handlers/compile-network)
   (cps/on predicates/compound?
           (cps/transform-expr rewrite/compound->network
                               handlers/compile-network))
   (cps/on predicates/def-net?
           (cps/transform-expr rewrite/def-net->def handlers/compile-def))
   (cps/on predicates/def-constraint?
           handlers/compile-def-constraint)
   (cps/on predicates/definition? handlers/compile-def)
   handlers/compile-application))

(def compile* (dispatch/install-default-compiler!
               (cps/make-compiler compiler-dispatch)))
(def default-compiler compile*)

(defn- prepare-environment
  [network seed compiler-env]
  (cond
    (ids/node-id? compiler-env)
    {:net (h/ensure-cell network compiler-env)
     :env-id compiler-env
     :prop-ids []}

    :else
    (env/import-environment-topology
     network
     (h/stable-node-id :compiler-2 :root-env seed)
     compiler-env
     operator-value/canonical-callable)))

(defn compile-expr
  ([expr] (compile-expr expr (h/default-env)))
  ([expr compiler-env] (compile-expr expr compiler-env {}))
  ([expr compiler-env {:keys [net seed path compiler]
                       :or {net net/empty-net path []}
                       :as opts}]
   (let [seed (or seed (ids/new-node-id))
         compile* (or compiler default-compiler)
         {:keys [net env-id prop-ids]}
         (prepare-environment net seed compiler-env)
         [state result]
         (compile* {:net net
                    :env env-id
                    :seed seed
                    :path path
                    :props prop-ids
                    :applications []
                    :compiler compile*
                    :application/caller (:application/caller opts)
                    :block/premise-context (:block/premise-context opts)
                    :reuse-existing-bindings?
                    (:reuse-existing-bindings? opts)}
                   expr)]
     (common/compiled-map state result))))

(defn compile-source
  ([source] (compile-expr (parser/parse-string source)))
  ([source compiler-env]
   (compile-expr (parser/parse-string source) compiler-env))
  ([source compiler-env opts]
   (compile-expr (parser/parse-string source) compiler-env opts)))

(defn compiled-result [compiled-net]
  (net/network-dict-entry compiled-net compiler-result-key))

(defn compiled-props [compiled-net]
  (net/network-dict-entry compiled-net compiler-props-key))

(defn compiled-applications [compiled-net]
  (net/network-dict-entry compiled-net compiler-applications-key))

(defn p:compile-expr
  [expr-id env-id out-id]
  (prop/construct-propagator
   (prop/concrete-propagator
    (fn [_inputs _outputs network]
      (let [expr (net/network-cell-strongest network expr-id)
            compiler-env (net/network-cell-strongest network env-id)
            compiled (compile-expr expr compiler-env
                                   {:net network
                                    :seed [:compile-2 expr-id env-id]})]
        [(message out-id (:net compiled))])))
   [expr-id env-id]
   [out-id]))

(defn p:execute-sub-env
  ([parent-env-id expr-id out-id]
   (p:execute-sub-env parent-env-id expr-id [] (ids/new-node-id) out-id))
  ([parent-env-id expr-id child-env-id out-id]
   (p:execute-sub-env parent-env-id expr-id [] child-env-id out-id))
  ([parent-env-id expr-id watch-ids child-env-id out-id]
   (application/p:execute-sub-env-with default-compiler
                                       parent-env-id expr-id watch-ids
                                       child-env-id out-id)))
