(ns propagators.compiler-2.core
  "Generic compiler core for compile-2.

  Compilation expands the runtime network with cells, slot declarations, and
  propagators. Network closure values are data; application evaluation is owned
  by `propagators.compiler-2.application/p:apply-closure`.
  "
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.application :as compiler-app]
            [propagators.compiler-2.application-value :as application-value]
            [propagators.compiler-2.ast :as ast]
            [propagators.compiler-2.closure-value :as closure-value]
            [propagators.compiler-2.context :as context]
            [propagators.compiler-2.env :as env]
            [propagators.compiler-2.helpers :as h]
            [propagators.compiler-2.parser :as parser]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def compiler-result-key :compiler/result)
(def compiler-props-key :compiler/props)
(def compiler-applications-key :compiler/applications)

(declare g:compile)

(defn- with-path [state path]
  (assoc state :path path))

(defn- compile-seq [state forms]
  (let [base-path (:path state)]
    (reduce
     (fn [[state _] [idx form]]
       (let [[state' result] (g:compile form
                                         (:env state)
                                         (h/child (with-path state base-path)
                                                  idx))]
         [(with-path state' base-path) result]))
     [state nil]
     (map-indexed vector forms))))

(defn- compile-symbol [{:keys [env] :as state} sym]
  (if-let [value (env/lookup env sym)]
    [state value]
    (let [[state' binding] (h/new-cell state [:symbol sym])]
      [(assoc state' :env (env/bind-local env sym binding)) binding])))

(defn- compile-args [state args]
  (let [base-path (:path state)]
    (reduce
     (fn [[state acc] [idx arg]]
       (let [[state' binding] (g:compile arg
                                         (:env state)
                                         (h/child (with-path state base-path)
                                                  [:arg idx]))]
         [(with-path state' base-path) (conj acc binding)]))
     [state []]
     (map-indexed vector args))))

(defn- result-cell [state]
  (h/new-cell state :result))

(defn- install-argument-object
  [state arg-ids]
  (h/new-cell state :args (obj/compound-object (vec arg-ids))))

(defn- install-operator-object
  [state operator]
  (h/new-cell state :operator-value operator))

(defn- record-application-ir
  [state app-id operator-ast operator-id result-id context-id]
  (let [application-object
        (application-value/application-object
         {:operator-ast operator-ast
          :operator-cell operator-id
          :args-id (:application/args-id state)
          :arg-ids (:application/arg-ids state)
          :output-id result-id
          :context-id context-id
          :lowering (:application/lowering state)})]
    (-> state
        (assoc :net (h/seed-cell (:net state) app-id application-object))
        (update :applications conj app-id))))

(defn- install-application-propagator
  [state app-id operator-ast operator-id result-id context-id]
  (let [state' (record-application-ir state
                                      app-id
                                      operator-ast
                                      operator-id
                                      result-id
                                      context-id)
        [prop-id network']
        ((compiler-app/p:apply-application app-id
                                           operator-id
                                           (:application/args-id state')
                                           (:application/arg-ids state')
                                           context-id
                                           result-id)
         (:net state'))]
    (-> state'
        (assoc :net network')
        (h/add-props [prop-id]))))

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
        [state' arg-bindings] (compile-args state operand-forms)
        arg-ids (mapv env/binding-id arg-bindings)]
    (when-not (every? some? arg-ids)
      (throw (ex-info "application arguments must compile to cells"
                      {:args arg-bindings})))
    (let [[state'' args-binding] (install-argument-object state' arg-ids)
          args-id (env/binding-id args-binding)
          [state''' operator-binding'] (install-operator-object state''
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
        [state' arg-bindings] (compile-args state operand-forms)
        arg-ids (mapv env/binding-id arg-bindings)]
    (when-not (every? some? arg-ids)
      (throw (ex-info "application arguments must compile to cells"
                      {:args arg-bindings})))
    (let [[state'' args-binding] (install-argument-object state' arg-ids)
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
  (let [base-path (:path state)
        [state' op-binding] (g:compile op
                                       (:env state)
                                       (h/child (with-path state base-path)
                                                :operator))
        state' (with-path state' base-path)
        operator-binding (g:advance op-binding state')
        [state'' out-binding] (result-cell state')
        out-id (env/binding-id out-binding)
        app-id (h/stable-node-id (:seed state'')
                                 (:path state'')
                                 :application
                                 out-id)
        operator-ast (ast/ast-map op)
        [state''' context-binding]
        (h/new-cell state''
                    :context
                    (context/context-value (:env state'')
                                           app-id
                                           operator-ast))
        context-id (env/binding-id context-binding)]
    (g:apply operator-binding
             args
             (:env state''')
             (assoc state'''
                    :context-id context-id
                    :application/app-id app-id
                    :application/operator-ast operator-ast)
             out-id)))

(defn- compile-let-cell [state names body]
  (let [base-path (:path state)
        child-env (env/sub-env (:env state))
        [state' scoped-env]
        (reduce
         (fn [[state scoped-env] [idx name]]
           (let [[state' binding] (h/new-cell (h/child
                                               (with-path state base-path)
                                               [:let-cell idx name])
                                              :cell)]
             [(with-path state' base-path)
              (env/bind-local scoped-env name binding)]))
         [state child-env]
         (map-indexed vector names))]
    (g:compile body
               scoped-env
               (h/child (assoc state' :env scoped-env) :body))))

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
        ((obj/p:slot closure-value/closure-env-slot lexical-env-id closure-id)
         (:net state-with-closure))]
    [(-> state-with-closure
         (assoc :net n')
         (h/add-props [env-slot-prop]))
     (env/compound-binding closure-id)]))

(defn- expression-kind [expr]
  (let [type (:ast/type (ast/ast-map expr))]
    (case type
      :apply :application
      type)))

(defmulti g:compile
  (fn [expr _env _state]
    (expression-kind expr)))

(defmethod g:compile :literal
  [expr _env state]
  (h/new-cell state :literal (:ast/value (ast/ast-map expr))))

(defmethod g:compile :symbol
  [expr _env state]
  (compile-symbol state (:ast/name (ast/ast-map expr))))

(defmethod g:compile :sequence
  [expr _env state]
  (compile-seq state (:ast/body (ast/ast-map expr))))

(defmethod g:compile :let-cell
  [expr _env state]
  (let [m (ast/ast-map expr)]
    (compile-let-cell state (:ast/names m) (:ast/body m))))

(defmethod g:compile :network
  [expr _env state]
  (let [m (ast/ast-map expr)]
    (compile-network state (:ast/inputs m) nil (:ast/body m))))

(defmethod g:compile :compound
  [expr _env state]
  (let [m (ast/ast-map expr)]
    (compile-network state (:ast/inputs m) (:ast/output m) (:ast/body m))))

(defmethod g:compile :application
  [expr _env state]
  (let [m (ast/ast-map expr)]
    (compile-application state (:ast/operator m) (:ast/args m))))

(defn- annotated-net [network result props env applications]
  (-> network
      (net/assoc-net-dict-entry compiler-result-key (env/binding-id result))
      (net/assoc-net-dict-entry compiler-props-key (vec props))
      (net/assoc-net-dict-entry :compiler/env env)
      (net/assoc-net-dict-entry compiler-applications-key
                                (vec applications))))

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
                                    :applications []})
         network (annotated-net (:net state)
                                result
                                (:props state)
                                (:env state)
                                (:applications state))]
     {:net network
      :cell (env/binding-id result)
      :binding result
      :env (:env state)
      :props (:props state)
      :applications (:applications state)})))

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
   (fn [_inputs _outputs network]
     (let [expr (net/network-cell-strongest network expr-id)
           env (net/network-cell-strongest network env-id)]
       (if (or (value/unusable? expr)
               (value/unusable? env))
         []
         (let [compiled (compile-expr expr
                                      env
                                      {:net network
                                       :seed [:compile-2 expr-id env-id]})]
           [(message out-id (:net compiled))]))))
   [expr-id env-id]
   [out-id]))
