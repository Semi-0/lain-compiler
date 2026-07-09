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
            [propagators.compiler-2.lazy-topology :as lazy-topology]
            [propagators.compiler-2.operator-value :as operator-value]
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
      (operator-value/operator-closure? operator-binding) :primitive
      (fn? operator-binding) :primitive
      (env/binding-id operator-binding) :cell
      :else :unsupported)))

(defmethod g:apply :primitive
  [operator-binding operand-forms _calling-env state out-id]
  (if-let [direct-install (and (operator-value/operator-closure? operator-binding)
                               (operator-value/operator-direct-installer operator-binding))]
    (direct-install state operand-forms out-id)
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
         (env/cell-binding result-id)]))))

(defn- closure-output-symbols
  [output]
  (cond
    (nil? output) []
    (symbol? output) [output]
    (vector? output) output
    :else []))

(defn- known-closure-info
  [network id]
  (let [v (h/strongest-or-nothing network id)]
    (when (closure-value/closure-info? v)
      v)))

(defn- closure-application-result-id
  [state operator-id arg-ids out-id]
  (if-let [closure-info (known-closure-info (:net state) operator-id)]
    (let [inputs (closure-value/closure-inputs closure-info)
          outputs (closure-output-symbols
                   (closure-value/closure-output closure-info))]
      (if (seq outputs)
        (do
          (when-not (= (count arg-ids)
                       (+ (count inputs) (count outputs)))
            (throw (ex-info "network application requires explicit output cells"
                            {:inputs inputs
                             :outputs outputs
                             :arg-count (count arg-ids)})))
          (peek arg-ids))
        out-id))
    out-id))

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
          operator-id (env/binding-id operator-binding)
          result-id (closure-application-result-id state''
                                                   operator-id
                                                   arg-ids
                                                   out-id)]
      [(-> state''
           (assoc :application/args-id args-id
                  :application/arg-ids arg-ids
                  :application/lowering :closure-cell)
           (install-application-propagator
            app-id
            operator-ast
            operator-id
            result-id
            context-id))
       (env/cell-binding result-id)])))

(defmethod g:apply :unsupported
  [operator-binding _operand-forms _calling-env _state _out-id]
  (throw (ex-info "application operator is not callable"
                  {:operator operator-binding})))

(defn- compile-application [state op args]
  (common/compile-application g:compile g:advance g:apply state op args))

(defn- copied-env-value
  [parent-env locals]
  (env/bind-locals parent-env locals))

(defn- copied-closure-object
  [env body inputs output]
  (closure-value/closure-object env
                                body
                                inputs
                                output
                                (env/scope-id env)))

(defn- refresh-env-copies
  [state parent-env]
  (reduce
   (fn [state {:keys [source-scope env-id closure-id locals body inputs output
                      prop-id]}]
     (if (= source-scope (env/scope-id parent-env))
       (let [env' (copied-env-value parent-env locals)]
         (-> state
             (update :net h/seed-cell env-id env')
             (update :net h/seed-cell closure-id
                     (copied-closure-object env' body inputs output))
             (h/add-props [prop-id])))
       state))
   state
   (:compiler-2/env-copies state)))

(defn- remember-env-copy
  [state parent-env env-id closure-id locals body inputs output prop-id]
  (update state
          :compiler-2/env-copies
          conj
          {:source-scope (env/scope-id parent-env)
           :env-id env-id
           :closure-id closure-id
           :locals locals
           :body body
           :inputs inputs
           :output output
           :prop-id prop-id}))

(defn- compile-network
  ([state inputs output body]
   (compile-network state nil inputs output body))
  ([state name inputs output body]
   (let [parent-env (:env state)
         closure-id (h/node-id state :closure)
         self-binding (env/compound-binding closure-id)
         locals (if name {name self-binding} {})
         lexical-env (copied-env-value parent-env locals)
         lexical-scope (env/scope-id lexical-env)
         closure-object (copied-closure-object lexical-env body inputs output)
         closure-env-id (h/node-id state :closure-env)
         closure-binding (env/compound-binding closure-id)
         state-with-env (assoc state
                               :net (h/seed-cell (:net state)
                                                 closure-env-id
                                                 lexical-env))
         state-with-closure (assoc state-with-env
                                   :net (h/seed-cell (:net state-with-env)
                                                     closure-id
                                                     closure-object))
         [env-slot-prop n']
         ((obj/p:slot closure-value/closure-env-slot closure-env-id closure-id)
          (:net state-with-closure))
         state' (-> state-with-closure
                    (assoc :net n')
                    (h/add-props [env-slot-prop]))]
     [(if name
        (remember-env-copy state'
                           parent-env
                           closure-env-id
                           closure-id
                           locals
                           body
                           inputs
                           output
                           env-slot-prop)
        state')
      closure-binding])))

(defn- existing-cell-binding
  [state name]
  (let [binding (env/lookup (:env state) name)]
    (when (env/cell-binding? binding)
      binding)))

(defn- define-binding
  [state name binding]
  (if-let [existing (when (:reuse-existing-bindings? state)
                      (existing-cell-binding state name))]
    (let [existing-id (env/binding-id existing)
          source-id (env/binding-id binding)
          value (when source-id
                  (h/strongest-or-nothing (:net state) source-id))
          net' (if (and source-id
                        (not (value/unusable? value)))
                 (h/seed-cell (:net state) existing-id value)
                 (:net state))]
      [(-> state
           (assoc :net net'
                  :env (env/bind-local (:env state) name existing))
           (refresh-env-copies (env/bind-local (:env state) name existing)))
       existing])
    (let [env' (env/bind-local (:env state) name binding)]
      [(-> state
           (assoc :env env')
           (refresh-env-copies env'))
       binding])))

(defn- define-operator-binding
  [state name operator result-binding]
  (if-let [existing (when (:reuse-existing-bindings? state)
                      (existing-cell-binding state name))]
    (let [existing-id (env/binding-id existing)]
      [(assoc state
              :net (h/seed-cell (:net state) existing-id operator)
              :env (env/bind-local (:env state) name existing))
       existing])
    [(assoc state :env (env/bind-local (:env state) name operator))
     result-binding]))

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

(defmethod g:compile :let
  [expr _env state]
  (let [bindings (ast/bindings expr)
        names (mapv first bindings)
        binding-forms
        (mapv (fn [[name value-expr]]
                (ast/app (ast/sym '->) value-expr (ast/sym name)))
              bindings)]
    (common/compile-let-cell
     g:compile
     state
     names
     (apply ast/sequence*
            (concat binding-forms [(ast/body expr)])))))

(defmethod g:compile :when-topology
  [expr _env state]
  (let [base-path (:path state)
        [state' condition-binding] (g:compile (ast/condition expr)
                                              (:env state)
                                              (h/child state :condition))
        condition-id (env/binding-id condition-binding)]
    (when-not condition-id
      (throw (ex-info "when condition must compile to a cell"
                      {:condition condition-binding})))
    (lazy-topology/install-when-topology
     (assoc state' :path base-path)
     condition-id
     (ast/body expr))))

(defmethod g:compile :network
  [expr _env state]
  (compile-network state (ast/inputs expr) nil (ast/body expr)))

(defmethod g:compile :compound
  [expr _env state]
  (compile-network state (ast/inputs expr) (ast/output expr) (ast/body expr)))

(defmethod g:compile :def-net
  [expr _env state]
  (let [[state' closure-binding] (compile-network state
                                                   (ast/name expr)
                                                   (ast/inputs expr)
                                                   (ast/output expr)
                                                   (ast/body expr))]
    (define-binding state' (ast/name expr) closure-binding)))

(defn- constraint-operator
  [name lexical-env inputs body]
  (operator-value/operator-closure
   {:name name
    :direct-installer
    (fn [state operand-forms _out-id]
      (let [[state' arg-bindings] (common/compile-args g:compile state operand-forms)
            arg-ids (mapv env/binding-id arg-bindings)]
        (when-not (every? some? arg-ids)
          (throw (ex-info "constraint arguments must compile to cells"
                          {:constraint name
                           :args arg-bindings})))
        (when-not (= (count inputs) (count arg-ids))
          (throw (ex-info "constraint application has wrong arity"
                          {:constraint name
                           :inputs inputs
                           :arg-count (count arg-ids)})))
        (let [constraint-env
              (reduce (fn [scoped-env [sym id]]
                        (env/bind-local scoped-env sym (env/cell-binding id)))
                      (env/sub-env lexical-env)
                      (map vector inputs arg-ids))
              [state'' body-binding]
              (g:compile body
                         constraint-env
                         (h/child (assoc state' :env constraint-env)
                                  [:constraint name]))]
          [(assoc state'' :env (:env state'))
           (env/cell-binding (or (peek arg-ids)
                                 (:binding/id body-binding)))])))}))

(defmethod g:compile :def-constraint
  [expr _env state]
  (let [operator (constraint-operator (ast/name expr)
                                      (:env state)
                                      (ast/inputs expr)
                                      (ast/body expr))
        [state' result-binding] (h/new-cell state
                                            [:def-constraint (ast/name expr)]
                                            operator)]
    (define-operator-binding state' (ast/name expr) operator result-binding)))

(defmethod g:compile :def
  [expr _env state]
  (if-let [body (ast/body expr)]
    (let [[state' body-binding] (g:compile body
                                           (:env state)
                                           (h/child state :body))]
      (define-binding state' (ast/name expr) body-binding))
    (let [[state' binding] (h/new-cell state [:def (ast/name expr)])]
      (define-binding state' (ast/name expr) binding))))

(defmethod g:compile :def-cell
  [expr _env state]
  (let [[state' closure-binding] (compile-network state
                                                   (ast/name expr)
                                                   (ast/inputs expr)
                                                   nil
                                                   (ast/body expr))]
    (define-binding state' (ast/name expr) closure-binding)))

(defmethod g:compile :application
  [expr _env state]
  (compile-application state (ast/operator expr) (ast/args expr)))

(defn compile-expr
  "Compile AST data into a network value and result cell."
  ([expr] (compile-expr expr (h/default-env)))
  ([expr env] (compile-expr expr env {}))
  ([expr env {:keys [net seed path]
              :or {net net/empty-net path []}
              :as opts}]
   (let [seed (or seed (ids/new-node-id))
         [state result] (g:compile expr
                                   env
                                   {:net net
                                    :env env
                                    :seed seed
                                    :path path
                                    :props []
                                    :applications []
                                    :reuse-existing-bindings?
                                    (:reuse-existing-bindings? opts)})]
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
