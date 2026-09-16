(ns propagators.compiler.model.env.topology
  (:require [propagators.infra.cells.value :as value]
            [propagators.compiler.model.env.binding :as binding]
            [propagators.compiler.model.env.index :as index]
            [propagators.infra.core :as core]
            [propagators.infra.ids :as ids]
            [propagators.infra.install :as i]
            [propagators.infra.network-builder :as nb]
            [propagators.infra.propagator :as prop]))

(defn- declared-prop-ids [effects]
  (->> effects
       (tree-seq sequential? seq)
       (filter #(= :declare-prop
                   (or (:network-vm/op %) (:gur.flat/op %) (:op %))))
       (mapv :id)))

(defn- commit [ctx]
  (let [{:keys [effects] :as result} (i/result ctx)
        [_ network] (core/eval-activation-result result (:net ctx))]
    [(declared-prop-ids effects) network]))

(defn- p:extend-chain [chain-id scope-id out-id]
  ((prop/primitive-propagator
    :lexical-access/extend-chain
    (fn [chain scope]
      (if (or (value/unusable? chain) (value/unusable? scope))
        value/nothing
        (conj (vec chain) scope))))
   chain-id scope-id out-id))

(defn- p:inc-depth [depth-id out-id]
  ((prop/primitive-propagator
    :lexical-access/inc-depth
    (fn [depth]
      (if (value/unusable? depth) value/nothing (inc depth))))
   depth-id out-id))

(defn p:scope-frame
  ([parent-id child-id]
   (p:scope-frame parent-id child-id #{}))
  ([parent-id child-id local-names]
   (let [key [:compiler-2 :sub-env parent-id child-id]
         scope-id (binding/stable-node-id key :scope)
         parent-chain-id (binding/stable-node-id key :parent-chain)
         child-chain-id (binding/stable-node-id key :child-chain)
         parent-depth-id (binding/stable-node-id key :parent-depth)
         child-depth-id (binding/stable-node-id key :child-depth)
         local-names-id (binding/stable-node-id key :local-bindings)]
     (fn [network]
       (let [[props installed]
             (-> (i/context network key)
                 (i/slot binding/env-parent-key parent-id child-id)
                 (i/slot binding/env-scope-key scope-id child-id)
                 (i/slot binding/env-local-bindings-key local-names-id child-id)
                 (i/slot binding/env-scope-chain-key parent-chain-id parent-id)
                 (i/install :chain-extend p:extend-chain
                            parent-chain-id scope-id child-chain-id)
                 (i/slot binding/env-scope-chain-key child-chain-id child-id)
                 (i/slot binding/env-depth-key parent-depth-id parent-id)
                 (i/install :depth-inc p:inc-depth parent-depth-id child-depth-id)
                 (i/slot binding/env-depth-key child-depth-id child-id)
                 (i/tell scope-id [:env/child child-id])
                 (i/tell local-names-id (set local-names))
                 commit)]
         [props (index/declare-frame-addresses installed child-id scope-id
                                                child-chain-id parent-id)])))))

(defn p:root-frame
  ([env-id] (p:root-frame env-id #{}))
  ([env-id local-names]
   (let [key [:compiler-2 :root-env env-id]
         scope-id (binding/stable-node-id key :scope)
         chain-id (binding/stable-node-id key :chain)
         depth-id (binding/stable-node-id key :depth)
         local-names-id (binding/stable-node-id key :local-bindings)
         root-scope [:env/root env-id]]
     (fn [network]
       (let [[props installed]
             (-> (i/context (nb/ensure-cell network env-id) key)
                 (i/slot binding/env-scope-key scope-id env-id)
                 (i/slot binding/env-scope-chain-key chain-id env-id)
                 (i/slot binding/env-depth-key depth-id env-id)
                 (i/slot binding/env-local-bindings-key local-names-id env-id)
                 (i/tell scope-id root-scope)
                 (i/tell chain-id [root-scope])
                 (i/tell depth-id 0)
                 (i/tell local-names-id (set local-names))
                 commit)]
         [props (index/declare-frame-addresses installed env-id scope-id
                                                chain-id)])))))

(defn p:declare-canonical-local [sym env-id binding-id]
  (let [key [:compiler-2 :canonical-local env-id sym binding-id]
        slot-id (binding/stable-node-id key :slot)
        descriptor-id (binding/stable-node-id key :descriptor)]
    (fn [network]
      (let [[props installed]
            (-> (i/context network key)
                (i/slot sym slot-id env-id)
                (i/tell descriptor-id (binding/cell-binding binding-id))
                (i/slot binding/binding-value-key descriptor-id slot-id)
                commit)]
        [props (index/declare-binding-address installed env-id sym binding-id)]))))

(defn p:reserve-canonical-local
  ([sym env-id binding-id]
   (p:reserve-canonical-local sym env-id binding-id nil))
  ([sym env-id binding-id owner]
   (fn [network]
     (let [[props declared]
           ((p:declare-canonical-local sym env-id binding-id) network)]
       [props (index/reserve-binding-address declared env-id sym
                                             binding-id owner)]))))

(defn- declared-binding-id [env-id sym candidate]
  (or (binding/binding-id candidate)
      (binding/stable-node-id :declared-binding env-id sym)))

(defn declare-bindings [network env-id bindings]
  (when-not (ids/node-id? env-id)
    (throw (ex-info "Compiler environment must be a live environment cell"
                    {:environment env-id})))
  (reduce
   (fn [{:keys [net props] :as result} [sym candidate]]
     (let [id (declared-binding-id env-id sym candidate)
           net (if (binding/binding-id candidate)
                 (nb/ensure-cell net id)
                 (nb/seed-cell (nb/ensure-cell net id) id candidate))
           [new-props net] ((p:declare-canonical-local sym env-id id) net)]
       (assoc result :net net :props (into props new-props))))
   {:net (nb/ensure-cell network env-id) :env env-id :props []}
   bindings))

(defn declare-root [network env-id bindings]
  (let [bindings (vec bindings)
        [scope-props scoped]
        ((p:root-frame env-id (set (map first bindings))) network)
        declared (declare-bindings scoped env-id bindings)]
    {:net (:net declared) :env env-id
     :props (into (vec scope-props) (:props declared))}))

(defn declare-child [network parent-id child-id bindings]
  (when-not (and (ids/node-id? parent-id) (ids/node-id? child-id))
    (throw (ex-info "Child environments require live environment cells"
                    {:parent parent-id :child child-id})))
  (let [bindings (vec bindings)
        [scope-props scoped]
        ((p:scope-frame parent-id child-id (set (map first bindings))) network)
        declared (declare-bindings scoped child-id bindings)]
    {:net (:net declared) :env child-id
     :props (into (vec scope-props) (:props declared))}))
