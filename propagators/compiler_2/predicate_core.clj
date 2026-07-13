(ns propagators.compiler-2.predicate-core
  "Predicate-composed compiler-2 entrypoint with no generic dispatch.

  Declaration primitives still live beside the compatibility MultiFns in
  `compiler-2.core`; this namespace owns recursive selection and the active
  compile entrypoints."
  (:refer-clojure :exclude [symbol?])
  (:require [propagators.compiler-2.application :as application]
            [propagators.compiler-2.core :as core]
            [propagators.compiler-2.helpers :as h]
            [propagators.compiler-2.parser :as parser]
            [propagators.compiler-common.core :as common]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def compiler-result-key common/compiler-result-key)
(def compiler-props-key common/compiler-props-key)
(def compiler-applications-key common/compiler-applications-key)

(defn- kind? [kind expr]
  (= kind (common/expression-kind expr)))

(def literal? (partial kind? :literal))
(def symbol? (partial kind? :symbol))
(def sequence? (partial kind? :sequence))
(def let-cell? (partial kind? :let-cell))
(def let? (partial kind? :let))
(def when-topology? (partial kind? :when-topology))
(def network? (partial kind? :network))
(def compound? (partial kind? :compound))
(def def-net? (partial kind? :def-net))
(def def-constraint? (partial kind? :def-constraint))
(def definition? (partial kind? :def))
(def def-cell? (partial kind? :def-cell))

(defn advance-binding
  [binding _state]
  binding)

(def compile-application
  (common/application-handler advance-binding core/apply-operator))

(def compiler-dispatch
  (common/compose-rules
   (common/on literal? core/compile-literal)
   (common/on symbol? core/compile-symbol)
   (common/on sequence? core/compile-sequence)
   (common/on let-cell? core/compile-let-cell)
   (common/on let? core/compile-let)
   (common/on when-topology? core/compile-when-topology)
   (common/on network? core/compile-network-form)
   (common/on compound? core/compile-compound)
   (common/on def-net? core/compile-def-net)
   (common/on def-constraint? core/compile-def-constraint)
   (common/on definition? core/compile-def)
   (common/on def-cell? core/compile-def-cell)
   compile-application))

(def compile*
  (common/make-compiler compiler-dispatch))

(def default-compiler compile*)

(defn compile-expr
  "Compile AST data through predicate rules only."
  ([expr] (compile-expr expr (h/default-env)))
  ([expr compiler-env] (compile-expr expr compiler-env {}))
  ([expr compiler-env {:keys [net seed path compiler]
                       :or {net net/empty-net path []}
                       :as opts}]
   (let [seed (or seed (ids/new-node-id))
         compile* (or compiler default-compiler)
         [state result]
         (compile* {:net net
                    :env compiler-env
                    :seed seed
                    :path path
                    :props []
                    :applications []
                    :compiler compile*
                    :application-installer (:application-installer opts)
                    :application/cell-declarer
                    (:application/cell-declarer opts)
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

(defn p:compile-expr-with
  [compile* expr-id env-id out-id]
  (prop/construct-propagator
   (prop/concrete-propagator
    (fn [_inputs _outputs network]
      (let [expr (net/network-cell-strongest network expr-id)
            compiler-env (net/network-cell-strongest network env-id)
            compiled (compile-expr expr compiler-env
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
                                       parent-env-id
                                       expr-id
                                       watch-ids
                                       child-env-id
                                       out-id)))
