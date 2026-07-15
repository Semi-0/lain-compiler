(ns propagators.compiler-2.operators.block-premise
  "Block-version premise primitives for additive compiler/runtime clients."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.datastructures.tms.distributed :as tms]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def binding-contexts-key :compiler-2/block-premise-binding-contexts)
(def application-dependence-key :compiler-2/application-dependence-cells)

(defn premise-id
  ([block-id version]
   [block-id version])
  ([client-id block-id version]
   [client-id block-id version]))

(defn premise-state-cell-id
  ([block-id version]
   (h/stable-node-id :compiler-2 :block-premise block-id version :state))
  ([client-id block-id version]
   (h/stable-node-id :compiler-2 :block-premise
                     client-id block-id version :state)))

(defn premise-context
  ([block-id version]
   {:block/id block-id
    :premise/id (premise-id block-id version)
    :premise/epoch version
    :premise/state-cell (premise-state-cell-id block-id version)})
  ([client-id block-id version]
   {:client/id client-id
    :block/id block-id
    :premise/id (premise-id client-id block-id version)
    :premise/epoch version
    :premise/state-cell (premise-state-cell-id client-id block-id version)}))

(defn active-update
  [{:premise/keys [id epoch]}]
  (tms/distributed-input-update
   [:compiler-2/block-premise-state id]
   true id epoch :compiler-2/block-premise-state))

(defn retract-update
  [{:premise/keys [id epoch]} next-epoch]
  (tms/distributed-premise-update id (max (inc (long epoch))
                                           (long next-epoch)) false))

(defn binding-contexts
  [network binding-id]
  (get (net/network-dict-entry network binding-contexts-key) binding-id #{}))

(defn record-binding-contexts
  [network binding-id contexts]
  (if (and binding-id (seq contexts))
    (net/update-net-dict-entry network
                               binding-contexts-key
                               update binding-id
                               (fnil into #{})
                               contexts)
    network))

(defn- semantic-base
  [v]
  (let [v (scope-source/unwrap v)]
    (if (tms/distributed-value? v)
      (-> v tms/strongest-distributed-value tms/distributed-base-value)
      v)))

(defn- support-version [contents]
  (->> contents
       (filter tms/distributed-value?)
       (mapcat tms/distributed-supports)
       (map (fn [support]
              [(tms/premise support)
               (tms/support-source support)
               (tms/support-kind-value support)]))
       set))

(defn support-update
  "Produce a TMS update supported by every context while retaining upstream
  distributed supports and premise-state evidence."
  [claim-id value-update source-content state-contents contexts]
  (let [base (semantic-base value-update)
        context-inputs
        (mapv (fn [{:premise/keys [id] :as context} state-content]
                (let [claim-content
                      (tms/distributed-content
                       {(tms/claim-slot-key [claim-id :block-premise id])
                        (tms/claim
                         [claim-id :block-premise id]
                         tms/distributed-proposition
                         base
                         [(tms/support
                           id
                           [:compiler-2/block-premise
                            (:premise/state-cell context)]
                           :block-premise)])})]
                  (if (tms/distributed-value? state-content)
                    (tms/merge-distributed-content claim-content state-content)
                    claim-content)))
              contexts state-contents)
        inputs (into [source-content] context-inputs)
        contexts-active? (every? (comp seq tms/distributed-supports)
                                 context-inputs)]
    (if (or (value/unusable? base) (not contexts-active?))
      (tms/distributed-state-update inputs)
      (or (tms/distributed-result-update
           [claim-id (support-version inputs)] base inputs)
          (first context-inputs)))))

(defn premise-messages
  [claim-id value-id contexts out-id network]
  (let [state-ids (mapv :premise/state-cell contexts)
        value-update (h/strongest-or-nothing network value-id)
        source-content (net/network-cell-content network value-id)
        state-contents (mapv #(net/network-cell-content network %) state-ids)]
    (if-let [update (support-update claim-id value-update source-content
                                    state-contents contexts)]
      [(message out-id update)]
      [])))

(defn p:block-premise
  "Gate one semantic value through one or more block-version premises."
  [claim-id value-id contexts out-id]
  (let [contexts (vec (into #{} contexts))
        state-ids (mapv :premise/state-cell contexts)]
    (prop/construct-propagator
     (h/stable-node-id :compiler-2/block-premise claim-id value-id out-id)
     :compiler-2/block-premise
     (fn [_inputs _outputs network]
       (premise-messages claim-id value-id contexts out-id network))
     (into [value-id] state-ids)
     [out-id])))

(defn application-dependence-cell-id
  [application-id]
  (h/stable-node-id :compiler-2 :application-premise application-id))

(defn p:application-dependence
  "Observe an application's raw result and premise cells in a separate
  dependency claim. The application result itself remains raw."
  [application-id value-id contexts out-id]
  (let [contexts (vec (into #{} contexts))
        state-ids (mapv :premise/state-cell contexts)]
    (prop/construct-propagator
     (h/stable-node-id :compiler-2/application-premise application-id out-id)
     :compiler-2/application-premise
     (fn [_inputs _outputs network]
       (let [value-update (h/strongest-or-nothing network value-id)
             source-content (net/network-cell-content network value-id)
             state-contents
             (mapv #(net/network-cell-content network %) state-ids)
             update (support-update [:application-premise application-id]
                                    value-update source-content
                                    state-contents contexts)]
         (if update [(message out-id update)] [])))
     (into [value-id] state-ids)
     [out-id])))

(defn record-application-dependence
  [network binding-id application-id contexts dependence-id]
  (-> network
      (record-binding-contexts binding-id contexts)
      (net/update-net-dict-entry application-dependence-key
                                 assoc application-id dependence-id)))
