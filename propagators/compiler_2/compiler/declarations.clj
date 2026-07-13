(ns propagators.compiler-2.compiler.declarations
  "Pure compiler-2 declarations, separate from traversal strategy."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.runtime.application :as compiler-app]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.runtime.closure-frame :as closure-frame]
            [propagators.compiler-2.model.closure-value :as closure-value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.runtime.lexical-application :as lexical-application]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.compiler-2.runtime.retained-application :as retained-application]
            [propagators.compiler-common.cps :as cps]
            [propagators.compiler-common.core :as common]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :as ids]))

(defn- install-application-propagator
  [state compile* app-id operator-ast operator-id result-id context-id]
  (common/install-application-propagator state
                                         (or (:application-installer state)
                                             (partial retained-application/p:apply-application-with
                                                      compile*))
                                         app-id
                                         operator-ast
                                         operator-id
                                         result-id
                                         context-id))

(defn- install-known-operator
  [state install app-id operator-ast operator-id arg-ids result-id context-id]
  (let [[network prop-ids installed-out-id]
        (install (:net state) arg-ids result-id)
        state' (-> state
                   (assoc :net network)
                   (h/add-props prop-ids))]
    [(common/record-application-ir state'
                                   app-id
                                   operator-ast
                                   operator-id
                                   installed-out-id
                                   context-id)
     (env/cell-binding installed-out-id)]))

(defn declare-direct-operator-application
  [compile* operator-binding operand-forms state out-id]
  ((operator-value/operator-direct-installer operator-binding)
   (assoc state :compiler compile*) operand-forms out-id))

(defn- argument-ids
  [arg-bindings]
  (let [arg-ids (mapv env/binding-id arg-bindings)]
    (when-not (every? some? arg-ids)
      (throw (ex-info "application arguments must compile to cells"
                      {:args arg-bindings})))
    arg-ids))

(defn declare-operator-application-bindings
  [compile* operator-binding arg-bindings state out-id]
  (let [app-id (:application/app-id state)
        operator-ast (:application/operator-ast state)
        context-id (:context-id state)
        arg-ids (argument-ids arg-bindings)]
    (let [[state'' args-binding] (common/install-argument-object state arg-ids)
          args-id (env/binding-id args-binding)
          [state''' operator-binding'] (common/install-operator-object state''
                                                                       operator-binding)
          operator-id (env/binding-id operator-binding')
          result-id (h/output-id operator-binding arg-ids out-id)
          prepared (assoc state'''
                          :application/args-id args-id
                          :application/arg-ids arg-ids
                          :application/lowering :primitive)
          install (and (operator-value/operator-closure? operator-binding)
                       (not (operator-value/operator-contextual? operator-binding))
                       (operator-value/operator-static-installer operator-binding))]
      (if install
        (install-known-operator prepared install app-id operator-ast operator-id
                                arg-ids result-id context-id)
        [(install-application-propagator prepared compile* app-id operator-ast
                                         operator-id result-id context-id)
         (env/cell-binding result-id)]))))

(defn declare-operator-application
  [compile* operator-binding operand-forms state out-id]
  (let [[state' arg-bindings] (common/compile-args compile* state operand-forms)]
    (declare-operator-application-bindings compile* operator-binding
                                           arg-bindings state' out-id)))

(defn- closure-output-symbols
  [output]
  (cond
    (nil? output) []
    (symbol? output) [output]
    (vector? output) output
    :else []))

(defn- hidden-return-symbol
  [state]
  (closure-value/implicit-return-symbol
   (ids/unwrap-node-id (h/node-id state :implicit-return))))

(defn- route-body-result
  [body output-sym]
  (let [route #(ast/app (ast/sym '->) % (ast/sym output-sym))]
    (if (= :sequence (ast/type body))
      (let [forms (vec (ast/body body))]
        (apply ast/sequence*
               (concat (butlast forms)
                       [(route (last forms))])))
      (route body))))

(defn normalize-closure-output
  [return-sym output body]
  (let [outputs (closure-output-symbols output)]
    (cond
      (nil? output)
      {:output [return-sym]
       :body (route-body-result body return-sym)}

      (= 1 (count outputs))
      {:output output
       :body (route-body-result body (first outputs))}

      :else
      {:output output
       :body body})))

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
                   (closure-value/closure-output closure-info))
          implicit-return? (closure-value/implicit-return-output?
                            (closure-value/closure-output closure-info))]
      (if (seq outputs)
        (cond
          (and implicit-return? (= (count arg-ids) (count inputs)))
          out-id

          (= (count arg-ids) (+ (count inputs) (count outputs)))
          (peek arg-ids)

          :else
          (throw (ex-info "network application requires explicit output cells"
                          {:inputs inputs
                           :outputs outputs
                           :arg-count (count arg-ids)})))
        out-id))
    out-id))

(defn- install-retained-closure-frame
  [compile* state operator-id closure-info arg-ids out-id]
  (when-let [{:keys [input-ids targets]}
             (compiler-app/closure-call-plan closure-info arg-ids out-id)]
    (let [frame-env (compiler-app/closure-body-env
                     (closure-value/closure-env closure-info)
                     (closure-value/closure-inputs closure-info)
                     targets
                     input-ids)
          [state' frame-binding] (h/new-cell state :closure-frame-env frame-env)
          frame-id (env/binding-id frame-binding)
          [prop-id network'] ((closure-frame/p:apply-closure-with
                               compile* operator-id frame-id)
                              (:net state'))
          result-id (if (seq (closure-output-symbols
                              (closure-value/closure-output closure-info)))
                      (second (first targets))
                      out-id)]
      [(-> state'
           (assoc :net network')
           (h/add-props [prop-id]))
       (env/cell-binding result-id)])))

(defn- install-retained-lexical-closure
  [compile* state operator-id arg-ids out-id]
  (let [network (h/ensure-cell (:net state) out-id)
        [prop-id network'] ((lexical-application/p:apply-lexical-closure-with
                             compile* operator-id arg-ids out-id)
                            network)]
    [(-> state
         (assoc :net network')
         (h/add-props [prop-id]))
     (env/cell-binding out-id)]))

(defn declare-retained-cell-application-bindings
  [compile* operator-binding arg-bindings state out-id]
  (let [arg-ids (argument-ids arg-bindings)
        operator-id (env/binding-id operator-binding)
        closure-info (known-closure-info (:net state) operator-id)]
      (if closure-info
        (or (install-retained-closure-frame compile* state operator-id closure-info
                                             arg-ids out-id)
            (throw (ex-info "retained closure arguments do not match closure"
                            {:operator-id operator-id :arg-ids arg-ids})))
        (install-retained-lexical-closure compile* state operator-id
                                          arg-ids out-id))))

(defn declare-retained-cell-application
  [compile* operator-binding operand-forms state out-id]
  (let [[state' arg-bindings] (common/compile-args compile* state operand-forms)]
    (declare-retained-cell-application-bindings compile* operator-binding
                                                arg-bindings state' out-id)))

(defn declare-runtime-cell-application-bindings
  [compile* operator-binding arg-bindings state out-id]
  (let [app-id (:application/app-id state)
        operator-ast (:application/operator-ast state)
        context-id (:context-id state)
        arg-ids (argument-ids arg-bindings)]
    (let [operator-id (env/binding-id operator-binding)
          [state'' args-binding] (common/install-argument-object state arg-ids)
          args-id (env/binding-id args-binding)
          result-id (closure-application-result-id state''
                                                   operator-id
                                                   arg-ids
                                                   out-id)]
      [(-> state''
           (assoc :application/args-id args-id
                  :application/arg-ids arg-ids
                  :application/lowering :closure-cell)
           (install-application-propagator compile* app-id operator-ast
                                           operator-id result-id context-id))
       (env/cell-binding result-id)])))

(defn declare-runtime-cell-application
  [compile* operator-binding operand-forms state out-id]
  (let [[state' arg-bindings] (common/compile-args compile* state operand-forms)]
    (declare-runtime-cell-application-bindings compile* operator-binding
                                               arg-bindings state' out-id)))

(defn resolve-cell-declarer
  [state]
  (let [declarer (:application/cell-declarer state)]
    (cond
      (or (nil? declarer) (= :runtime declarer))
      declare-runtime-cell-application

      (= :retained-frame declarer)
      declare-retained-cell-application

      (fn? declarer)
      declarer

      :else
      (throw (ex-info "unknown application cell declarer"
                      {:application/cell-declarer declarer})))))

(defn apply-operator
  [compile* operator-binding operand-forms _calling-env state out-id]
  (cond
    (and (operator-value/operator-closure? operator-binding)
         (operator-value/operator-direct-installer operator-binding))
    (declare-direct-operator-application compile* operator-binding
                                         operand-forms state out-id)

    (or (operator-value/operator-closure? operator-binding)
        (fn? operator-binding))
    (declare-operator-application compile* operator-binding operand-forms
                                  state out-id)

    (env/binding-id operator-binding)
    ((resolve-cell-declarer state) compile* operator-binding operand-forms
                                   state out-id)

    :else
    (throw (ex-info "application operator is not callable"
                    {:operator operator-binding}))))

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

(defn closure-locals
  [name closure-id]
  (if name {name (env/compound-binding closure-id)} {}))

(defn seed-closure-declaration
  [state closure-id closure-env-id lexical-env closure-object]
  (-> state
      (update :net h/seed-cell closure-env-id lexical-env)
      (update :net h/seed-cell closure-id closure-object)))

(defn attach-closure-environment
  [state closure-id closure-env-id]
  (let [[prop-id network]
        ((obj/p:slot closure-value/closure-env-slot closure-env-id closure-id)
         (:net state))]
    [(-> state
         (assoc :net network)
         (h/add-props [prop-id]))
     prop-id]))

(defn declare-closure
  ([state inputs output body]
   (declare-closure state nil inputs output body))
  ([state name inputs output body]
   (let [{closure-output :output closure-body :body}
         (normalize-closure-output (hidden-return-symbol state) output body)
         parent-env (:env state)
         closure-id (h/node-id state :closure)
         locals (closure-locals name closure-id)
         lexical-env (copied-env-value parent-env locals)
         closure-object (copied-closure-object lexical-env
                                               closure-body
                                               inputs
                                               closure-output)
         closure-env-id (h/node-id state :closure-env)
         closure-binding (env/compound-binding closure-id)
         seeded (seed-closure-declaration state
                                          closure-id
                                          closure-env-id
                                          lexical-env
                                          closure-object)
         [state' env-slot-prop] (attach-closure-environment seeded
                                                            closure-id
                                                            closure-env-id)]
     [(if name
        (remember-env-copy state'
                           parent-env
                           closure-env-id
                           closure-id
                           locals
                           closure-body
                           inputs
                           closure-output
                           env-slot-prop)
        state')
      closure-binding])))

(defn- existing-cell-binding
  [state name]
  (let [binding (env/lookup (:env state) name)]
    (when (env/cell-binding? binding)
      binding)))

(defn define-binding
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

(defn lower-let
  [expr]
  (let [bindings (ast/bindings expr)
        names (mapv first bindings)
        binding-forms
        (mapv (fn [[name value-expr]]
                (ast/app (ast/sym '->) value-expr (ast/sym name)))
              bindings)]
    (ast/let-cell names
                  (apply ast/sequence*
                         (concat binding-forms [(ast/body expr)])))))

(defn- constraint-env
  [lexical-env inputs arg-ids]
  (reduce (fn [scoped-env [sym id]]
            (env/bind-local scoped-env sym (env/cell-binding id)))
          (env/sub-env lexical-env)
          (map vector inputs arg-ids)))

(defn- constraint-argument-ids
  [name inputs arg-bindings]
  (let [arg-ids (mapv env/binding-id arg-bindings)]
    (when-not (every? some? arg-ids)
      (throw (ex-info "constraint arguments must compile to cells"
                      {:constraint name :args arg-bindings})))
    (when-not (= (count inputs) (count arg-ids))
      (throw (ex-info "constraint application has wrong arity"
                      {:constraint name
                       :inputs inputs
                       :arg-count (count arg-ids)})))
    arg-ids))

(defn- constraint-result
  [calling-state body-state arg-ids body-binding]
  [(assoc body-state :env (:env calling-state))
   (env/cell-binding (or (peek arg-ids)
                         (:binding/id body-binding)))])

(defn- constraint-operator
  [compile* name lexical-env inputs body]
  (operator-value/operator-closure
   {:name name
    :direct-compiler
    (fn [compile-k state operand-forms _out-id k]
      (cps/compile-args
       compile-k state operand-forms
       (fn [state' arg-bindings]
         (let [arg-ids (constraint-argument-ids name inputs arg-bindings)
               body-state (h/child
                           (assoc state'
                                  :env (constraint-env lexical-env inputs arg-ids))
                           [:constraint name])]
           (cps/call
            compile-k body-state body
            (fn [state'' body-binding]
              (let [[state''' result]
                    (constraint-result state' state'' arg-ids body-binding)]
                (cps/continue k state''' result))))))))
    :direct-installer
    (fn [state operand-forms _out-id]
      (let [[state' arg-bindings] (common/compile-args compile* state operand-forms)
            arg-ids (constraint-argument-ids name inputs arg-bindings)]
        (let [constraint-env (constraint-env lexical-env inputs arg-ids)
              [state'' body-binding] (compile*
                                      (h/child (assoc state' :env constraint-env)
                                               [:constraint name])
                                      body)]
          (constraint-result state' state'' arg-ids body-binding))))}))

(defn declare-constraint
  [compile* state expr]
  (let [operator (constraint-operator compile*
                                      (ast/name expr)
                                      (:env state)
                                      (ast/inputs expr)
                                      (ast/body expr))
        [state' result-binding] (h/new-cell state
                                            [:def-constraint (ast/name expr)]
                                            operator)]
    (define-operator-binding state' (ast/name expr) operator result-binding)))
