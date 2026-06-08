(ns propagators.compiler-common.core
  "Shared compile-expansion helpers for compiler namespaces.

  These functions allocate cells, compile subforms, and retain application IR.
  They deliberately do not decide what literals, closures, or applications mean.
  "
  (:require [propagators.compiler-2.application-value :as application-value]
            [propagators.compiler-2.ast :as ast]
            [propagators.compiler-2.context :as context]
            [propagators.compiler-2.env :as env]
            [propagators.compiler-2.helpers :as h]
            [propagators.datastructures.compound-object :as obj]
            [propagators.network :as net]))

(def compiler-result-key :compiler/result)
(def compiler-props-key :compiler/props)
(def compiler-applications-key :compiler/applications)

(defn with-path [state path]
  (assoc state :path path))

(defn expression-kind [expr]
  (let [type (:ast/type (ast/ast-map expr))]
    (case type
      :apply :application
      type)))

(defn compile-seq [compile-f state forms]
  (let [base-path (:path state)]
    (reduce
     (fn [[state _] [idx form]]
       (let [[state' result] (compile-f form
                                        (:env state)
                                        (h/child (with-path state base-path)
                                                 idx))]
         [(with-path state' base-path) result]))
     [state nil]
     (map-indexed vector forms))))

(defn compile-symbol [{:keys [env] :as state} sym]
  (if-let [value (env/lookup env sym)]
    [state value]
    (let [[state' binding] (h/new-cell state [:symbol sym])]
      [(assoc state' :env (env/bind-local env sym binding)) binding])))

(defn compile-args [compile-f state args]
  (let [base-path (:path state)]
    (reduce
     (fn [[state acc] [idx arg]]
       (let [[state' binding] (compile-f arg
                                         (:env state)
                                         (h/child (with-path state base-path)
                                                  [:arg idx]))]
         [(with-path state' base-path) (conj acc binding)]))
     [state []]
     (map-indexed vector args))))

(defn result-cell [state]
  (h/new-cell state :result))

(defn install-argument-object [state arg-ids]
  (h/new-cell state :args (obj/compound-object (vec arg-ids))))

(defn install-operator-object [state operator]
  (h/new-cell state :operator-value operator))

(defn record-application-ir
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

(defn install-application-propagator
  [state app-installer app-id operator-ast operator-id result-id context-id]
  (let [state' (record-application-ir state
                                      app-id
                                      operator-ast
                                      operator-id
                                      result-id
                                      context-id)
        [prop-id network']
        ((app-installer app-id
                        operator-id
                        (:application/args-id state')
                        (:application/arg-ids state')
                        context-id
                        result-id)
         (:net state'))]
    (-> state'
        (assoc :net network')
        (h/add-props [prop-id]))))

(defn compile-application
  [compile-f advance-f apply-f state op args]
  (let [base-path (:path state)
        [state' op-binding] (compile-f op
                                       (:env state)
                                       (h/child (with-path state base-path)
                                                :operator))
        state' (with-path state' base-path)
        operator-binding (advance-f op-binding state')
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
    (apply-f operator-binding
             args
             (:env state''')
             (assoc state'''
                    :context-id context-id
                    :application/app-id app-id
                    :application/operator-ast operator-ast)
             out-id)))

(defn compile-let-cell [compile-f state names body]
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
    (compile-f body
               scoped-env
               (h/child (assoc state' :env scoped-env) :body))))

(defn annotated-net [network result props env applications]
  (-> network
      (net/assoc-net-dict-entry compiler-result-key (env/binding-id result))
      (net/assoc-net-dict-entry compiler-props-key (vec props))
      (net/assoc-net-dict-entry :compiler/env env)
      (net/assoc-net-dict-entry compiler-applications-key
                                (vec applications))))

(defn compiled-map [state result]
  (let [network (annotated-net (:net state)
                               result
                               (:props state)
                               (:env state)
                               (:applications state))]
    {:net network
     :cell (env/binding-id result)
     :binding result
     :env (:env state)
     :props (:props state)
     :applications (:applications state)}))
