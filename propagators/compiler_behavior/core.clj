(ns propagators.compiler-behavior.core
  "Behavior-valued compiler parallel to compiler-2.

  Values produced by this compiler are behavior histories. Closure declarations
  are behavior values whose retained point history stores compiler-2
  closure-info versions.
  "
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.compiler-2.ast :as ast]
            [propagators.compiler-2.closure-value :as closure-value]
            [propagators.compiler-2.env :as env]
            [propagators.compiler-2.helpers :as h]
            [propagators.compiler-2.parser :as parser]
            [propagators.compiler-behavior.application :as behavior-app]
            [propagators.compiler-common.core :as common]
            [propagators.datastructures.behavior :as behavior]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def compiler-result-key common/compiler-result-key)
(def compiler-props-key common/compiler-props-key)
(def compiler-applications-key common/compiler-applications-key)

(def closure-reducer-id
  (behavior/retained-value-reducer-id :compiler-behavior/closure))

(declare g:compile)

(defn- compile-timestamp
  [state]
  (or (:timestamp state) 0))

(defn- literal-behavior
  [state v]
  (let [timestamp (compile-timestamp state)]
    (behavior/constant-value
     timestamp
     v
     #{[:compiler-behavior/literal (:seed state) (:path state) timestamp]})))

(defn- closure-behavior
  [state closure-info]
  (let [timestamp (compile-timestamp state)]
    (behavior/retained-value
     :compiler-behavior/closure
     timestamp
     closure-info
     #{[:compiler-behavior/closure (:seed state) (:path state) timestamp]})))

(defn- new-behavior-cell
  "Create a seeded behavior cell whose strongest is the behavior summary.

  `h/new-cell` seeds content and strongest with the same value, which is fine for
  compiler-2 current values but wrong for behavior histories. Behavior cells need
  content = retained history and strongest = latest summary.
  "
  [{:keys [net] :as state} role v]
  (let [id (h/node-id state role)
        net' (-> net
                 (h/ensure-cell id)
                 (net/assoc-net-cell id (cell/cell v (behavior/strongest-value v))))]
    [(assoc state :net net')
     (env/cell-binding id)]))

(defn- install-application-propagator
  [state app-id operator-ast operator-id result-id context-id]
  (common/install-application-propagator
   state
   behavior-app/p:apply-behavior-application
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

(defn- compile-network
  [state inputs output body]
  (let [lexical-env (:env state)
        lexical-scope (env/scope-id lexical-env)
        closure-object (closure-value/closure-object lexical-env
                                                      body
                                                      inputs
                                                      output
                                                      lexical-scope)
        [state-with-closure closure-binding]
        (new-behavior-cell state
                           :closure
                           (closure-behavior state closure-object))]
    [state-with-closure (env/compound-binding (env/binding-id closure-binding))]))

(defmulti g:compile
  (fn [expr _env _state]
    (common/expression-kind expr)))

(defmethod g:compile :literal
  [expr _env state]
  (new-behavior-cell state
                     :literal
                     (literal-behavior state
                                       (ast/value expr))))

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
  (common/compile-application g:compile
                              g:advance
                              g:apply
                              state
                              (ast/operator expr)
                              (ast/args expr)))

(defn compile-expr
  "Compile AST data into a behavior-valued network and result cell."
  ([expr] (compile-expr expr (h/behavior-env)))
  ([expr env] (compile-expr expr env {}))
  ([expr env {:keys [net seed path timestamp]
              :or {net net/empty-net path [] timestamp 0}}]
   (let [seed (or seed (ids/new-node-id))
         [state result] (g:compile expr
                                   env
                                   {:net net
                                    :env env
                                    :seed seed
                                    :path path
                                    :timestamp timestamp
                                    :props []
                                    :applications []})]
     (common/compiled-map state result))))

(defn compile-source
  "Parse and compile one behavior compiler source string."
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
  "Compile source/env cells into a behavior compiled network cell."
  [expr-id env-id out-id]
  (prop/construct-propagator
   (prop/concrete-propagator
    (fn [_inputs _outputs network]
     (let [expr (net/network-cell-strongest network expr-id)
           env (net/network-cell-strongest network env-id)]
       (let [compiled (compile-expr expr
                                    env
                                    {:net network
                                     :seed [:compile-behavior expr-id env-id]
                                     :timestamp 0})]
         [(message out-id (:net compiled))]))))
   [expr-id env-id]
   [out-id]))
