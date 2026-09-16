(ns propagators.compiler.lowering.sub-environment
  "Compile and execute a child lexical frame, publishing its topology and result."
  (:require [propagators.infra.cells.value :as value]
            [propagators.compiler.compiler.basis :as h]
            [propagators.compiler.compiler.dispatch :as dispatch]
            [propagators.compiler.model.env :as env]
            [propagators.compiler.lowering.activation :as activation]
            [propagators.compiler.lowering.topology-effects :as topology-effects]
            [propagators.infra.ids :as ids]
            [propagators.infra.message :refer [message]]
            [propagators.infra.network :as net]
            [propagators.infra.propagator :as prop]))

(def execute-sub-env-props-key :compiler-2/execute-sub-env-props)

(defn- cell-content-or-nothing
  [network id]
  (if (contains? (net/net-env network) id)
    (net/network-cell-content network id)
    value/nothing))

(defn- declare-child-environment
  [network parent-env-id child-env-id]
  (let [declared (env/declare-child network parent-env-id child-env-id [])]
    [(:props declared) (:net declared)]))

(defn- compile-expr
  [compile* expr child-env network seed props]
  (compile*
   {:net network
    :env child-env
    :seed seed
    :path []
    :props (vec props)
    :applications []
    :compiler compile*}
   expr))

(defn- install-result-boundary
  [network child-env-id result-id out-id]
  (let [prop-id (h/stable-node-id :compiler-2
                                  :execute-sub-env
                                  child-env-id
                                  :result-boundary)
        prepared (reduce h/ensure-cell network [result-id out-id])]
    ((prop/construct-propagator
      prop-id
      :compiler-2/execute-sub-env-result
      (prop/concrete-propagator
       (fn [_inputs _outputs current-net]
         [(message out-id
                   (net/network-cell-content current-net result-id))]))
      [result-id]
      [out-id])
     prepared)))

(defn execute-sub-env-messages-with
  [compile* parent-env-id expr-id child-env-id out-id network]
  (let [expr (h/strongest-or-nothing network expr-id)]
    (if (value/unusable? expr)
      []
      (let [[env-props with-child]
            (declare-child-environment network parent-env-id child-env-id)
            [state result]
            (compile-expr compile* expr child-env-id with-child
                          [:compiler-2/execute-sub-env
                          parent-env-id expr-id child-env-id out-id]
                          env-props)
            result-id (env/binding-id result)
            [boundary-id connected]
            (cond
              (ids/node-id? result-id)
              (install-result-boundary (:net state)
                                       child-env-id result-id out-id)

              :else
              (throw
               (ex-info "Sub-environment body declared no result cell"
                        {:child-env-id child-env-id
                         :result result})))
            props (conj (vec (:props state)) boundary-id)
            after-body (activation/run-network connected [] props)]
        (topology-effects/network-diff network after-body props)))))

(defn execute-sub-env-messages
  [parent-env-id expr-id child-env-id out-id network]
  (execute-sub-env-messages-with dispatch/default-compiler
                                 parent-env-id expr-id child-env-id out-id
                                 network))

(defn p:execute-sub-env-with
  [compile* parent-env-id expr-id watch-ids child-env-id out-id]
  (let [watch-ids (vec watch-ids)
        inputs (into [expr-id] watch-ids)
        outputs [child-env-id out-id]
        activate (fn [_inputs _outputs network]
                   (execute-sub-env-messages-with compile*
                                                  parent-env-id
                                                  expr-id
                                                  child-env-id
                                                  out-id
                                                  network))]
    (fn [network]
      (let [network* (reduce h/ensure-cell network (into inputs outputs))
            [prop-id n] ((prop/construct-propagator :compiler-2/execute-sub-env
                                                    activate inputs outputs)
                         network*)]
        [prop-id
         (net/update-net-dict-entry n
                                    execute-sub-env-props-key
                                    (fnil conj #{})
                                    prop-id)]))))

(defn p:execute-sub-env
  "Compile and run one expression in a child compiler-2 env.

  `watch-ids` is the minimal v1 reactivity hook: pass external cells that should
  re-trigger this transient execution when their strongest values change.
  "
  ([parent-env-id expr-id out-id]
   (p:execute-sub-env parent-env-id expr-id [] (ids/new-node-id) out-id))
  ([parent-env-id expr-id child-env-id out-id]
   (p:execute-sub-env parent-env-id expr-id [] child-env-id out-id))
  ([parent-env-id expr-id watch-ids child-env-id out-id]
   (p:execute-sub-env-with dispatch/default-compiler
                           parent-env-id expr-id watch-ids child-env-id out-id)))
