(ns propagators.compiler.compiler.declarations
  "Pure compiler-2 declarations, separate from traversal strategy."
  (:require [propagators.infra.cells.value :as value]
            [propagators.compiler.lowering.application :as compiler-app]
            [propagators.compiler.language.ast :as ast]
            [propagators.compiler.model.closure-value :as closure-value]
            [propagators.compiler.model.context :as context]
            [propagators.compiler.model.env :as env]
            [propagators.compiler.compiler.basis :as h]
            [propagators.compiler.compiler.dispatch :as dispatch]
            [propagators.compiler.model.operator-value :as operator-value]
            [propagators.compiler.operators.call-graph :as call-graph]
            [propagators.compiler.common.core :as common]
            [propagators.infra.datastructures.compound-object :as obj]
            [propagators.infra.ids :as ids]
            [propagators.infra.stdlib.prop :as stdlib-prop]))

(defn- application-operator-label
  [operator-ast]
  (cond
    (= :symbol (ast/type operator-ast))
    (str (ast/name operator-ast))

    :else
    (str (ast/type operator-ast))))

(defn- install-call-publisher
  [state app-id operator-id operator-ast]
  (if-let [caller-id (:application/caller state)]
    (let [[prop-id network]
          ((call-graph/p:application-call
            caller-id app-id operator-id
            (application-operator-label operator-ast))
           (:net state))]
      (-> state
          (assoc :net network)
          (h/add-props [prop-id])))
    state))

(defn argument-cell-ids
  [arg-bindings]
  (let [arg-ids (mapv env/binding-id arg-bindings)]
    (if (every? ids/node-id? arg-ids)
      arg-ids
      (throw
       (ex-info "Application arguments must compile to cells"
                {:arguments arg-bindings})))))

(defn declare-application-context
  [state expression]
  (let [[result-state result-binding]
        (h/new-cell state :result)
        result-id (env/binding-id result-binding)
        application-id
        (h/stable-node-id (:seed result-state)
                          (:path result-state)
                          :application
                          result-id)
        operator-ast (ast/ast (ast/operator expression))
        [context-state context-binding]
        (h/new-cell result-state
                    :context
                    (context/context-value (:env result-state)
                                           application-id
                                           operator-ast))]
    [(assoc context-state
            :context-id (env/binding-id context-binding)
            :application/app-id application-id
            :application/operator-ast operator-ast)
     result-id]))

(defn declare-operator-cell
  [state operator-binding argument-ids fallback-result-id]
  (if-let [operator-id (env/binding-id operator-binding)]
    {:state state
     :operator-id operator-id
     :result-id fallback-result-id}
    (if (or (operator-value/operator-closure? operator-binding)
            (fn? operator-binding))
      (let [[prepared operator-cell]
            (common/install-operator-object state operator-binding)]
        {:state prepared
         :operator-id (env/binding-id operator-cell)
         :result-id (h/output-id operator-binding
                                 argument-ids
                                 fallback-result-id)})
      (throw
       (ex-info "Application operator is not callable"
                {:operator operator-binding})))))

(defn declare-application-topology
  [state operator-binding argument-bindings fallback-result-id]
  (let [argument-ids (argument-cell-ids argument-bindings)
        {:keys [state operator-id result-id]}
        (declare-operator-cell state operator-binding
                               argument-ids fallback-result-id)
        prepared (assoc state :application/arg-ids argument-ids)
        [installed result-binding]
        (compiler-app/install-application prepared operator-id
                                          argument-ids result-id)
        published
        (install-call-publisher installed
                                (:application/app-id installed)
                                operator-id
                                (:application/operator-ast installed))]
    [published result-binding]))

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

(defn- implicit-return-route-source
  [expr return-sym]
  (when (and (= :apply (ast/type expr))
             (= '-> (ast/name (ast/operator expr)))
             (= 2 (count (ast/args expr)))
             (= return-sym (ast/name (second (ast/args expr)))))
    (first (ast/args expr))))

(defn closure-semantic-body
  "Return a closure body without its compiler-generated implicit-return route."
  [closure-info]
  (let [body (closure-value/closure-body closure-info)
        output (closure-value/closure-output closure-info)]
    (if-not (closure-value/implicit-return-output? output)
      body
      (let [return-sym (first output)]
        (if (= :sequence (ast/type body))
          (let [forms (vec (ast/body body))]
            (if-let [source (implicit-return-route-source (peek forms)
                                                          return-sym)]
              (apply ast/sequence* (conj (pop forms) source))
              body))
          (or (implicit-return-route-source body return-sym)
              body))))))

(defn- install-env-topology
  [state installer]
  (let [[prop-ids network] (installer (:net state))]
    (-> state
        (assoc :net network)
        (h/add-props prop-ids))))

(defn declare-child-environment
  [state role local-names]
  (let [child-id (h/node-id state role)
        state' (update state :net h/ensure-cell child-id)]
    [(-> state'
         (install-env-topology
          (env/p:scope-frame (:env state) child-id local-names))
         (assoc :env child-id))
     child-id]))

(defn declare-local
  [state sym binding-id]
  (install-env-topology state
                        (env/p:declare-canonical-local
                         sym (:env state) binding-id)))

(defn declare-fixed-local
  [state sym binding-id]
  (install-env-topology state
                        (env/p:declare-canonical-local sym (:env state)
                                                       binding-id)))

(defn reserve-fixed-local
  [state sym binding-id]
  (install-env-topology state
                        (env/p:reserve-canonical-local sym (:env state)
                                                       binding-id
                                                       (:seed state))))

(defn declare-local-cells
  [state role names]
  (let [[state' child-id] (declare-child-environment state role names)]
    (reduce
     (fn [[state bindings] name]
       (let [binding-id (h/stable-node-id :compiler-2 :binding child-id name)
             state' (-> state
                        (update :net h/ensure-cell binding-id)
                        (reserve-fixed-local name binding-id))]
         [state' (assoc bindings name (env/cell-binding binding-id))]))
     [state' {}]
     names)))

(defn ^:deprecated closure-locals
  [name closure-id]
  (if name {name (env/compound-binding closure-id)} {}))

(defn ^:deprecated seed-closure-declaration
  [state closure-id closure-env-id lexical-env closure-object]
  (-> state
      (update :net h/seed-cell closure-env-id lexical-env)
      (update :net h/seed-cell closure-id closure-object)))

(defn ^:deprecated attach-closure-environment
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
         proposed-id (h/node-id state :closure)
         reserved-id (when name
                       (env/reserved-binding-id (:net state) (:env state) name
                                                (:seed state)))
         closure-id (or reserved-id proposed-id)
         state' (if (and name (nil? reserved-id))
                  (-> state
                      (update :net h/ensure-cell closure-id)
                      (reserve-fixed-local name closure-id))
                  state)
         closure-object (closure-value/closure-object (:env state')
                                                      closure-body
                                                      inputs
                                                      closure-output
                                                      (:env state'))
         declaration-id (h/stable-node-id :compiler-2 :closure-declaration
                                          closure-id)
         graph-id (call-graph/graph-cell-id closure-id)
         compile* (cond
                    (fn? (:compiler state'))
                    (:compiler state')

                    :else
                    dispatch/default-compiler)
         callable (compiler-app/closure-callable
                   compile*
                   [:compiler-2/closure declaration-id]
                   declaration-id
                   (:env state')
                   closure-object)
         closure-binding (env/cell-binding closure-id)
         declared (-> state'
                      (update :net h/seed-cell declaration-id closure-object)
                      (update :net h/ensure-cell graph-id)
                      (update :net h/seed-cell closure-id callable))]
     [declared
      closure-binding])))

(defn- fresh-definition-id
  [state name source-id]
  (h/stable-node-id :compiler-2 :definition (:env state) name source-id))

(defn- definition-target-id
  [state name source-id]
  (or (env/reserved-binding-id (:net state) (:env state) name (:seed state))
      (when (:reuse-existing-bindings? state)
        (env/local-binding-id (:net state) (:env state) name))
      (fresh-definition-id state name source-id)))

(defn- copy-binding-value
  [state source-id target-id]
  (if (= source-id target-id)
    state
    (let [[prop-id network] ((stdlib-prop/id source-id target-id) (:net state))]
      (-> state
          (assoc :net network)
          (h/add-props [prop-id])))))

(defn define-binding
  [state name binding]
  (let [source-id (env/binding-id binding)]
    (when-not source-id
      (throw (ex-info "definition must declare an addressed binding"
                      {:name name :binding binding})))
    (let [reserved-id (env/reserved-binding-id (:net state) (:env state) name
                                                (:seed state))
          reused-id (when (:reuse-existing-bindings? state)
                      (env/local-binding-id (:net state) (:env state) name))
          target-id (definition-target-id state name source-id)
          target-binding (if (env/compound-binding? binding)
                           (env/compound-binding target-id)
                           (env/cell-binding target-id))
          state' (-> state
                     (update :net h/ensure-cell target-id)
                     (copy-binding-value source-id target-id))
          declared (if (or reserved-id reused-id)
                     state'
                     (declare-fixed-local state' name target-id))
          consumed (update declared :net
                           env/consume-reserved-binding
                           (:env state) name target-id (:seed state))]
      [consumed target-binding])))

(defn- define-operator-binding
  [state name operator result-binding]
  (let [source-id (env/binding-id result-binding)
        reserved-id (env/reserved-binding-id (:net state) (:env state) name
                                              (:seed state))
        target-id (or reserved-id source-id)
        declared (if reserved-id
                   state
                   (reserve-fixed-local state name target-id))
        seeded (update declared :net h/seed-cell target-id operator)
        consumed (update seeded :net env/consume-reserved-binding
                         (:env state) name target-id (:seed state))]
    [consumed (env/cell-binding target-id)]))

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

(defn declare-constraint
  [_compile-k state expr]
  (let [compile*
        (cond
          (fn? (:compiler state))
          (:compiler state)

          :else
          dispatch/default-compiler)
        name (ast/name expr)
        inputs (ast/inputs expr)
        body (ast/body expr)
        semantic-body
        (cond
          (some? (peek inputs))
          (ast/sequence* body (ast/sym (peek inputs)))

          :else
          body)
        {closure-output :output closure-body :body}
        (normalize-closure-output (hidden-return-symbol state)
                                  nil
                                  semantic-body)
        proposed-id (h/node-id state [:def-constraint name])
        reserved-id (env/reserved-binding-id (:net state) (:env state) name
                                             (:seed state))
        closure-id (or reserved-id proposed-id)
        state'
        (cond
          (some? reserved-id)
          state

          :else
          (-> state
              (update :net h/ensure-cell closure-id)
              (reserve-fixed-local name closure-id)))
        closure-info (closure-value/closure-object (:env state')
                                                   closure-body
                                                   inputs
                                                   closure-output
                                                   (:env state'))
        declaration-id (h/stable-node-id :compiler-2
                                         :constraint-declaration
                                         closure-id)
        graph-id (call-graph/graph-cell-id closure-id)
        callable (compiler-app/constraint-callable
                  compile*
                  [:compiler-2/constraint declaration-id]
                  declaration-id
                  (:env state')
                  closure-info)
        declared (-> state'
                     (update :net h/seed-cell declaration-id closure-info)
                     (update :net h/ensure-cell graph-id)
                     (update :net h/seed-cell closure-id callable)
                     (update :net env/consume-reserved-binding
                             (:env state) name closure-id (:seed state)))]
    [declared (env/cell-binding closure-id)]))
