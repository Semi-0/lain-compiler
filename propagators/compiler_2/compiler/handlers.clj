(ns propagators.compiler-2.compiler.handlers
  "Active continuation-based compiler-2 handlers."
  (:require [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.compiler.declarations :as declarations]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.runtime.lazy-topology :as lazy-topology]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.compiler-common.cps :as cps]
            [propagators.compiler-common.core :as common]))

(defn- finish
  [k [state binding]]
  (cps/continue k state binding))

(defn compile-literal
  [_compile-k state expr k]
  (finish k (h/new-cell state :literal (ast/value expr))))

(defn- compile-accessed-symbol
  [state sym k]
  (let [binding-id (h/node-id state [:lexical-access sym :binding])
        value-id (h/node-id state [:lexical-access sym :value])
        network (-> (:net state)
                    (h/ensure-cell binding-id)
                    (h/ensure-cell value-id))
        [access-props with-access]
        ((env/p:lexical-access-local-first sym (:env state) binding-id) network)
        [value-props compiled]
        ((env/p:binding-value binding-id value-id) with-access)]
    (cps/continue k
                  (-> state
                      (assoc :net compiled)
                      (h/add-props (into (vec access-props) value-props)))
                  (env/cell-binding value-id))))

(defn- compile-free-symbol
  [state sym k]
  (let [binding-id (h/node-id state [:free-symbol sym])
        declared (-> state
                     (update :net h/ensure-cell binding-id)
                     (declarations/reserve-fixed-local sym binding-id))]
    (cps/continue k declared (env/cell-binding binding-id))))

(defn compile-symbol
  [_compile-k state expr k]
  (let [sym (ast/name expr)
        {:keys [status binding/id]}
        (env/lexical-binding-status (:net state) sym (:env state))]
    (case status
      :found (cps/continue k state (env/cell-binding id))
      :missing (compile-free-symbol state sym k)
      (compile-accessed-symbol state sym k))))

(defn compile-sequence
  [compile-k state expr k]
  (cps/compile-seq compile-k state (ast/body expr) k))

(defn compile-let-cell
  [compile-k state expr k]
  (let [outer-env (:env state)
        [body-state _bindings]
        (declarations/declare-local-cells state :let-cell-env (ast/names expr))]
    (cps/call compile-k body-state (ast/body expr)
              (fn [state' binding]
                (cps/continue k (assoc state' :env outer-env) binding)))))

(defn compile-let
  [compile-k state expr k]
  (cps/call compile-k state (declarations/lower-let expr) k))

(defn compile-when-topology
  [compile-k state expr k]
  (let [base-path (:path state)]
    (cps/call
     compile-k (h/child state :condition) (ast/condition expr)
     (fn [state' condition-binding]
       (let [condition-id (env/binding-id condition-binding)]
         (when-not condition-id
           (throw (ex-info "when condition must compile to a cell"
                           {:condition condition-binding})))
         (finish k
                 (lazy-topology/install-when-topology-with
                  (:compiler state')
                  (assoc state' :path base-path)
                  condition-id
                  (ast/body expr))))))))

(defn compile-network-form
  [_compile-k state expr k]
  (finish k (declarations/declare-closure state
                                          (ast/inputs expr)
                                          nil
                                          (ast/body expr))))

(defn compile-compound
  [_compile-k state expr k]
  (finish k (declarations/declare-closure state
                                          (ast/inputs expr)
                                          (ast/output expr)
                                          (ast/body expr))))

(defn compile-def-net
  [_compile-k state expr k]
  (let [[state' binding]
        (declarations/declare-closure state
                                      (ast/name expr)
                                      (ast/inputs expr)
                                      (ast/output expr)
                                      (ast/body expr))]
    (finish k (declarations/define-binding state' (ast/name expr) binding))))

(defn compile-def-constraint
  [_compile-k state expr k]
  (finish k (declarations/declare-constraint (:compiler state) state expr)))

(defn compile-def
  [compile-k state expr k]
  (if-let [body (ast/body expr)]
    (cps/call
     compile-k (h/child state :body) body
     (fn [state' body-binding]
       (finish k (declarations/define-binding state' (ast/name expr) body-binding))))
    (let [[state' binding] (h/new-cell state [:def (ast/name expr)])]
      (finish k (declarations/define-binding state'
                                             (ast/name expr)
                                             binding)))))

(defn- built-in-cell-declaration
  [compile* operator-binding arg-bindings state out-id]
  (let [declarer (:application/cell-declarer state)]
    (cond
      (or (nil? declarer) (= :runtime declarer))
      (declarations/declare-runtime-cell-application-bindings
       compile* operator-binding arg-bindings state out-id)

      (= :retained-frame declarer)
      (declarations/declare-retained-cell-application-bindings
       compile* operator-binding arg-bindings state out-id)

      (fn? declarer)
      nil

      :else
      (declarations/resolve-cell-declarer state))))

(defn- declare-compiled-application
  [compile* operator-binding arg-bindings state out-id]
  (cond
    (or (operator-value/operator-closure? operator-binding)
        (fn? operator-binding))
    (declarations/declare-operator-application-bindings
     compile* operator-binding arg-bindings state out-id)

    (env/binding-id operator-binding)
    (or (built-in-cell-declaration compile* operator-binding
                                   arg-bindings state out-id)
        (throw (ex-info "custom cell declarer requires operand forms"
                        {:application/cell-declarer
                         (:application/cell-declarer state)})))

    :else
    (throw (ex-info "application operator is not callable"
                    {:operator operator-binding}))))

(defn- custom-cell-declarer?
  [operator-binding state]
  (and (env/binding-id operator-binding)
       (fn? (:application/cell-declarer state))))

(defn- known-operator
  "Use a presently known primitive declaration strategy without changing how
  ordinary symbol compilation exposes its canonical cell."
  [binding state]
  (if-let [id (env/binding-id binding)]
    (let [candidate (h/strongest-or-nothing (:net state) id)]
      (if (or (operator-value/operator-closure? candidate)
              (fn? candidate))
        candidate
        binding))
    binding))

(defn compile-application
  [compile-k state expr k]
  (let [base-path (:path state)
        op (ast/operator expr)
        operand-forms (ast/args expr)]
    (cps/call
     compile-k (h/child (common/with-path state base-path) :operator) op
     (fn [state' op-binding]
       (let [state' (common/with-path state' base-path)
             [state'' operator-binding out-id]
             (common/prepare-application known-operator
                                         state' op op-binding)
             compile* (:compiler state'')
             direct-compiler
             (and (operator-value/operator-closure? operator-binding)
                  (operator-value/operator-direct-compiler operator-binding))]
         (cond
           direct-compiler
           (direct-compiler compile-k state'' operand-forms out-id k)

           (and (operator-value/operator-closure? operator-binding)
                (operator-value/operator-direct-installer operator-binding))
           (finish k (declarations/declare-direct-operator-application
                      compile* operator-binding operand-forms state'' out-id))

           (custom-cell-declarer? operator-binding state'')
           (finish k ((:application/cell-declarer state'')
                      compile* operator-binding operand-forms state'' out-id))

           :else
           (cps/compile-args
            compile-k state'' operand-forms
            (fn [state''' arg-bindings]
              (finish k (declare-compiled-application
                         compile* operator-binding arg-bindings
                         (merge state'''
                                (select-keys state''
                                             [:context-id
                                              :application/app-id
                                              :application/operator-ast]))
                         out-id))))))))))
