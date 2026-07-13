(ns propagators.compiler-behavior.core
  "Behavior-valued compiler parallel to compiler-2.

  Values produced by this compiler are behavior histories. Closure declarations
  are behavior values whose retained point history stores compiler-2
  closure-info versions.
  "
  (:require [propagators.cells.cell :as cell]
            [propagators.cells.value :as value]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.model.closure-value :as closure-value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.language.parser :as parser]
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

(declare g:compile default-compiler)

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

(defn declare-primitive-application
  [compile* operator-binding operand-forms state out-id]
  (let [app-id (:application/app-id state)
        operator-ast (:application/operator-ast state)
        context-id (:context-id state)
        [state' arg-bindings] (common/compile-args compile* state operand-forms)
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

(defn declare-cell-application
  [compile* operator-binding operand-forms state out-id]
  (let [app-id (:application/app-id state)
        operator-ast (:application/operator-ast state)
        context-id (:context-id state)
        [state' arg-bindings] (common/compile-args compile* state operand-forms)
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

(defn apply-operator
  [compile* operator-binding operand-forms _calling-env state out-id]
  (cond
    (fn? operator-binding)
    (declare-primitive-application compile* operator-binding operand-forms
                                   state out-id)

    (env/binding-id operator-binding)
    (declare-cell-application compile* operator-binding operand-forms state out-id)

    :else
    (throw (ex-info "application operator is not callable"
                    {:operator operator-binding}))))

(defmethod g:apply :primitive
  [operator-binding operand-forms calling-env state out-id]
  (apply-operator default-compiler operator-binding operand-forms calling-env
                  state out-id))

(defmethod g:apply :cell
  [operator-binding operand-forms calling-env state out-id]
  (apply-operator default-compiler operator-binding operand-forms calling-env
                  state out-id))

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

(defn compile-literal
  [_compile* state expr]
  (new-behavior-cell state
                     :literal
                     (literal-behavior state
                                       (ast/value expr))))

(def compile-symbol common/compile-symbol-expression)

(def compile-sequence common/compile-sequence-expression)

(def compile-let-cell common/compile-let-cell-expression)

(defn compile-network-form
  [_compile* state expr]
  (compile-network state (ast/inputs expr) nil (ast/body expr)))

(defn compile-compound
  [_compile* state expr]
  (compile-network state (ast/inputs expr) (ast/output expr) (ast/body expr)))

(def compile-application
  (common/application-handler g:advance apply-operator))

(defn- compile-via-multifn
  [_compile* state expr]
  (g:compile expr (:env state) state))

(def compiler-dispatch
  (common/compose-rules
   (common/on (common/expression-kind? :literal) compile-literal)
   (common/on (common/expression-kind? :symbol) compile-symbol)
   (common/on (common/expression-kind? :sequence) compile-sequence)
   (common/on (common/expression-kind? :let-cell) compile-let-cell)
   (common/on (common/expression-kind? :network) compile-network-form)
   (common/on (common/expression-kind? :compound) compile-compound)
   (common/on (common/expression-kind? :application) compile-application)
   compile-via-multifn))

(def default-compiler
  (common/make-compiler compiler-dispatch))

(defmacro ^:private define-compile-adapter
  [kind handler]
  `(defmethod g:compile ~kind
     [expr# env# state#]
     (~handler default-compiler (assoc state# :env env#) expr#)))

(define-compile-adapter :literal compile-literal)
(define-compile-adapter :symbol compile-symbol)
(define-compile-adapter :sequence compile-sequence)
(define-compile-adapter :let-cell compile-let-cell)
(define-compile-adapter :network compile-network-form)
(define-compile-adapter :compound compile-compound)
(define-compile-adapter :application compile-application)

(defn compile-expr
  "Compile AST data into a behavior-valued network and result cell."
  ([expr] (compile-expr expr (h/behavior-env)))
  ([expr env] (compile-expr expr env {}))
  ([expr env {:keys [net seed path timestamp compiler]
              :or {net net/empty-net path [] timestamp 0}}]
   (let [seed (or seed (ids/new-node-id))
         compile* (or compiler default-compiler)
         [state result] (compile* {:net net
                                   :env env
                                   :seed seed
                                   :path path
                                   :timestamp timestamp
                                   :props []
                                   :applications []}
                                  expr)]
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
