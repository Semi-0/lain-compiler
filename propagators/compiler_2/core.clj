(ns propagators.compiler-2.core
  "Generic compiler core for compile-2.

  Compilation expands the runtime network with cells, slot declarations, and
  propagators. Network closure values are data; application evaluation is owned
  by `propagators.compiler-2.application/p:apply-closure`.
  "
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.application :as compiler-app]
            [propagators.compiler-2.ast :as ast]
            [propagators.compiler-2.closure-value :as closure-value]
            [propagators.compiler-2.env :as env]
            [propagators.compiler-2.helpers :as h]
            [propagators.compiler-2.parser :as parser]
            [propagators.compiler-common.core :as common]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def compiler-result-key common/compiler-result-key)
(def compiler-props-key common/compiler-props-key)
(def compiler-applications-key common/compiler-applications-key)

(declare g:compile)

(defn- install-application-propagator
  [state app-id operator-ast operator-id result-id context-id]
  (common/install-application-propagator state
                                         compiler-app/p:apply-application
                                         app-id
                                         operator-ast
                                         operator-id
                                         result-id
                                         context-id))

(defmulti g:advance
  (fn [_binding _state] :default))

(defmethod g:advance :default
  [binding _state]
  binding)

(defmulti g:apply
  (fn [operator-binding _operand-forms _calling-env _state _out-id]
    (cond
      (fn? operator-binding) :primitive
      (env/binding-id operator-binding) :cell
      :else :unsupported)))

(defmethod g:apply :primitive
  [operator-binding operand-forms _calling-env state out-id]
  (let [app-id (:application/app-id state)
        operator-ast (:application/operator-ast state)
        context-id (:context-id state)
        [state' arg-bindings] (common/compile-args g:compile state operand-forms)
        arg-ids (mapv env/binding-id arg-bindings)]
    (when-not (every? some? arg-ids)
      (throw (ex-info "application arguments must compile to cells"
                      {:args arg-bindings})))
    (let [[state'' args-binding] (common/install-argument-object state' arg-ids)
          args-id (env/binding-id args-binding)
          [state''' operator-binding'] (common/install-operator-object state''
                                                                       operator-binding)
          operator-id (env/binding-id operator-binding')
          result-id (h/output-id operator-binding arg-ids out-id)]
      [(-> state'''
           (assoc :application/args-id args-id
                  :application/arg-ids arg-ids
                  :application/lowering :primitive)
           (install-application-propagator
            app-id
            operator-ast
            operator-id
            result-id
            context-id))
       (env/cell-binding result-id)])))

(defmethod g:apply :cell
  [operator-binding operand-forms _calling-env state out-id]
  (let [app-id (:application/app-id state)
        operator-ast (:application/operator-ast state)
        context-id (:context-id state)
        [state' arg-bindings] (common/compile-args g:compile state operand-forms)
        arg-ids (mapv env/binding-id arg-bindings)]
    (when-not (every? some? arg-ids)
      (throw (ex-info "application arguments must compile to cells"
                      {:args arg-bindings})))
    (let [[state'' args-binding] (common/install-argument-object state' arg-ids)
          args-id (env/binding-id args-binding)
          operator-id (env/binding-id operator-binding)]
      [(-> state''
           (assoc :application/args-id args-id
                  :application/arg-ids arg-ids
                  :application/lowering :closure-cell)
           (install-application-propagator
            app-id
            operator-ast
            operator-id
            out-id
            context-id))
       (env/cell-binding out-id)])))

(defmethod g:apply :unsupported
  [operator-binding _operand-forms _calling-env _state _out-id]
  (throw (ex-info "application operator is not callable"
                  {:operator operator-binding})))

(defn- compile-application [state op args]
  (common/compile-application g:compile g:advance g:apply state op args))

(defn- compile-network [state inputs output body]
  (let [lexical-env (:env state)
        lexical-scope (env/scope-id lexical-env)
        closure-object (closure-value/closure-object lexical-env
                                                      body
                                                      inputs
                                                      output
                                                      lexical-scope)
        [state-with-env lexical-env-binding] (h/new-cell state
                                                         :closure-env
                                                         lexical-env)
        lexical-env-id (env/binding-id lexical-env-binding)
        [state-with-closure closure-binding] (h/new-cell state-with-env
                                                         :closure
                                                         closure-object)
        closure-id (env/binding-id closure-binding)
        [env-slot-prop n']
        ((obj/p:legacy-slot closure-value/closure-env-slot lexical-env-id closure-id)
         (:net state-with-closure))]
    [(-> state-with-closure
         (assoc :net n')
         (h/add-props [env-slot-prop]))
     (env/compound-binding closure-id)]))

(defmulti g:compile
  (fn [expr _env _state]
    (common/expression-kind expr)))

(defmethod g:compile :literal
  [expr _env state]
  (h/new-cell state :literal (ast/value expr)))

(defmethod g:compile :symbol
  [expr _env state]
  (common/compile-symbol state (ast/name expr)))

(defmethod g:compile :sequence
  [expr _env state]
  (common/compile-seq g:compile state (ast/body expr)))

(defmethod g:compile :let-cell
  [expr _env state]
  (common/compile-let-cell g:compile
                           state
                           (ast/names expr)
                           (ast/body expr)))

(defmethod g:compile :network
  [expr _env state]
  (compile-network state (ast/inputs expr) nil (ast/body expr)))

(defmethod g:compile :compound
  [expr _env state]
  (compile-network state (ast/inputs expr) (ast/output expr) (ast/body expr)))

(defmethod g:compile :application
  [expr _env state]
  (compile-application state (ast/operator expr) (ast/args expr)))

(defn compile-expr
  "Compile AST data into a network value and result cell."
  ([expr] (compile-expr expr (h/default-env)))
  ([expr env] (compile-expr expr env {}))
  ([expr env {:keys [net seed path]
              :or {net net/empty-net path []}}]
   (let [seed (or seed (ids/new-node-id))
         [state result] (g:compile expr
                                   env
                                   {:net net
                                    :env env
                                    :seed seed
                                    :path path
                                    :props []
                                    :applications []})]
     (common/compiled-map state result))))

(defn compile-source
  "Parse and compile one compiler-2 source string."
  ([source]
   (compile-expr (parser/parse-string source)))
  ([source env]
   (compile-expr (parser/parse-string source) env))
  ([source env opts]
   (compile-expr (parser/parse-string source) env opts)))

(defn compiled-result [compiled-net]
  (net/network-dict-entry compiled-net compiler-result-key))

(defn compiled-props [compiled-net]
  (net/network-dict-entry compiled-net compiler-props-key))

(defn compiled-applications [compiled-net]
  (net/network-dict-entry compiled-net compiler-applications-key))

(defn p:compile-expr
  "Compile source/env cells into a compiled network cell."
  [expr-id env-id out-id]
  (prop/construct-propagator
   (prop/concrete-propagator
    (fn [_inputs _outputs network]
     (let [expr (net/network-cell-strongest network expr-id)
           env (net/network-cell-strongest network env-id)]
       (let [compiled (compile-expr expr
                                    env
                                    {:net network
                                     :seed [:compile-2 expr-id env-id]})]
         [(message out-id (:net compiled))]))))
   [expr-id env-id]
   [out-id]))
