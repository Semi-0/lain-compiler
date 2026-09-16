(ns propagators.compiler.operators.tms
  "Compiler-2 distributed TMS operators."
  (:require [propagators.infra.cells.value :as value]
            [propagators.compiler.lowering.application :as application]
            [propagators.compiler.model.closure-value :as closure-value]
            [propagators.compiler.model.env :as env]
            [propagators.compiler.compiler.basis :as h]
            [propagators.compiler.model.operator-value :as operator-value]
            [propagators.infra.datastructures.dependency :as dependency]
            [propagators.infra.datastructures.scope-source :as scope-source]
            [propagators.infra.datastructures.tms.distributed :as tms]
            [propagators.infra.core :as core]
            [propagators.infra.gur :as gur]
            [propagators.infra.message :refer [message]]
            [propagators.infra.network :as net]
            [propagators.infra.network-builder :as nb]
            [propagators.infra.propagator :as prop]))

(defn- unwrap-compiler-value
  [v]
  (-> v
      scope-source/unwrap
      tms/distributed-base-value
      dependency/unwrap))

(defn closure-output-symbols
  [output]
  (cond
    (nil? output) []
    (symbol? output) [output]
    (vector? output) output
    :else []))

(defn single-output-id
  [closure-info arg-ids out-id]
  (let [outputs (closure-output-symbols
                 (closure-value/closure-output closure-info))]
    (cond
      (closure-value/implicit-return-output?
       (closure-value/closure-output closure-info))
      out-id

      (= 1 (count outputs))
      (peek (vec arg-ids))

      :else
      out-id)))

(defn- tms-state-messages
  [input-ids output-id network]
  (let [input-contents (mapv #(net/network-cell-content network %) input-ids)
        state-update (tms/distributed-state-update input-contents)]
    (cond
      (value/contradiction? state-update)
      [(message output-id value/contradiction)]

      (some? state-update)
      [(message output-id state-update)]

      :else
      [])))

(defn- p:tms-state
  [closure-id input-ids output-id]
  (prop/construct-propagator
   (h/stable-node-id :compiler-2/tms-state closure-id input-ids output-id)
   (fn [_inputs _outputs network]
     (tms-state-messages input-ids output-id network))
   input-ids
   [output-id]))

(defn- tms-closure-value
  [closure-id closure-info]
  (operator-value/operator-closure
   {:name "tms-closure-value"
    :application-installer
    (fn [network arg-ids out-id context-id]
      (let [input-count (count (closure-value/closure-inputs closure-info))
            input-ids (vec (take input-count arg-ids))
            output-id (single-output-id closure-info arg-ids out-id)
            prepared (nb/ensure-cell network output-id)
            [state-prop with-state]
            ((p:tms-state closure-id input-ids output-id) prepared)
            apply-effect
            (gur/apply-closure-effect closure-id
                                      (into [context-id] arg-ids)
                                      output-id)
            [_ installed]
            (core/eval-activation-result apply-effect with-state)]
        [installed [(:id apply-effect) state-prop] output-id]))}))

(defn tms-closure-operator []
  (operator-value/operator-closure
   {:name "tms-closure"
    :activate (fn [network _context-id arg-ids out-id]
                (let [[closure-id] (vec arg-ids)]
                  (when-not (and closure-id (= 1 (count arg-ids)))
                    (throw (ex-info "tms-closure expects one network closure"
                                    {:arg-ids arg-ids})))
                  (let [closure-value (h/strongest-or-nothing network closure-id)]
                    (let [closure-info
                          (application/callable-declaration closure-value)]
                      (if (or (value/unusable? closure-value)
                              (not (application/compiler-callable? closure-value))
                              (not (closure-value/closure-info? closure-info)))
                        []
                        [(message out-id
                                  (tms-closure-value closure-id
                                                     closure-info))])))))}))

(defn- single-output-call-plan
  [closure-id closure-info arg-ids out-id tag]
  (let [input-count (count (closure-value/closure-inputs closure-info))
        outputs (closure-output-symbols (closure-value/closure-output closure-info))
        arg-ids (vec arg-ids)]
    (cond
      (= 1 (count outputs))
      (when (= (count arg-ids) (inc input-count))
        (let [real-output-id (peek arg-ids)
              hidden-output-id (h/stable-node-id tag closure-id real-output-id arg-ids)]
          {:input-ids (subvec arg-ids 0 input-count)
           :real-output-id real-output-id
           :hidden-output-id hidden-output-id
           :inner-arg-ids (conj (subvec arg-ids 0 input-count)
                                hidden-output-id)}))

      (empty? outputs)
      (when (= (count arg-ids) input-count)
        (let [hidden-output-id (h/stable-node-id tag closure-id out-id arg-ids)]
          {:input-ids arg-ids
           :real-output-id out-id
           :hidden-output-id hidden-output-id
           :inner-arg-ids arg-ids}))

      :else nil)))

(defn- distributed-premise-output-update
  [claim-id source output-update input-contents premise epoch]
  (let [premise-state (tms/distributed-premise-update premise epoch true)
        projected (cond
                    (nil? output-update) value/nothing
                    (tms/distributed-value? output-update)
                    (tms/strongest-distributed-value output-update)
                    :else output-update)
        result (tms/distributed-base-value projected)]
    (cond
      (value/contradiction? output-update) value/contradiction

      (value/unusable? result)
      (tms/distributed-state-update
       (cond-> (conj (vec input-contents) premise-state)
         (tms/distributed-value? output-update) (conj output-update)))

      :else
      (let [premise-claim (tms/distributed-input-update
                           [claim-id :premise]
                           result
                           premise
                           epoch
                           source)]
        (tms/distributed-result-update
         claim-id
         result
         (cond-> [premise-claim]
           (tms/distributed-value? output-update) (conj output-update)))))))

(defn- cell-update-or-nil
  [network id]
  (let [content (when (contains? (net/net-env network) id)
                  (net/network-cell-content network id))
        strongest (h/strongest-or-nothing network id)]
    (cond
      (and (some? content)
           (not (value/unusable? content))) content
      (not (value/unusable? strongest)) strongest
      :else nil)))

(defn distributed-premise-output-messages
  [claim-id source input-ids premise epoch private-output-id real-output-id network]
  (let [premise (unwrap-compiler-value premise)
        epoch (unwrap-compiler-value epoch)
        output-update (cell-update-or-nil network private-output-id)]
    (if (or (value/unusable? premise)
            (value/unusable? epoch))
      []
      (let [update (distributed-premise-output-update
                    claim-id
                    source
                    output-update
                    (mapv #(net/network-cell-content network %) input-ids)
                    premise
                    epoch)]
        (cond-> []
          update (conj (message real-output-id update)))))))

(defn p:distributed-premise-output
  "Project a retained closure private output into a premise-supported TMS output."
  [claim-id source input-ids premise epoch private-output-id real-output-id]
  (let [input-ids (vec input-ids)
        inputs (conj input-ids private-output-id)
        activate (fn [_inputs _outputs network]
                   (distributed-premise-output-messages
                    claim-id
                    source
                    input-ids
                    premise
                    epoch
                    private-output-id
                    real-output-id
                    network))]
    (prop/construct-propagator
     (h/stable-node-id :compiler-2/distributed-premise-output
                       claim-id
                       private-output-id
                       real-output-id)
     activate
     inputs
     [real-output-id])))

(defn- distributed-premise-closure-value
  [closure-id closure-info premise epoch]
  (operator-value/operator-closure
   {:name "distributed-premise-closure-value"
    :application-installer
    (fn [network arg-ids out-id context-id]
      (let [call (single-output-call-plan
                  closure-id closure-info arg-ids out-id
                  :compiler-2/distributed-premise-closure)]
        (cond
          (nil? call)
          (throw
           (ex-info "Premise closure application has invalid arity"
                    {:closure-id closure-id
                     :argument-ids (vec arg-ids)}))

          :else
          (let [{:keys [input-ids real-output-id hidden-output-id
                        inner-arg-ids]}
                call
                claim-id [:compiler-2/distributed-premise-closure
                          real-output-id premise]
                prepared (nb/ensure-cell network hidden-output-id)
                [gate-id with-gate]
                ((p:distributed-premise-output
                  claim-id
                  claim-id
                  input-ids
                  premise
                  epoch
                  hidden-output-id
                  real-output-id)
                 prepared)
                apply-effect
                (gur/apply-closure-effect closure-id
                                          (into [context-id] inner-arg-ids)
                                          hidden-output-id)
                [_ installed]
                (core/eval-activation-result apply-effect with-gate)]
            [installed [(:id apply-effect) gate-id] real-output-id]))))}))

(defn distributed-premise-closure-operator []
  (operator-value/operator-closure
   {:name "distributed-premise-closure"
    :activate (fn [network _context-id arg-ids out-id]
                (let [[closure-id premise-id epoch-id] (vec arg-ids)]
                  (when-not (and closure-id premise-id epoch-id (= 3 (count arg-ids)))
                    (throw (ex-info "distributed-premise-closure expects closure, premise, and epoch"
                                    {:arg-ids arg-ids})))
                  (let [closure-value (h/strongest-or-nothing network closure-id)
                        closure-info (application/callable-declaration closure-value)
                        premise (h/strongest-or-nothing network premise-id)
                        epoch (h/strongest-or-nothing network epoch-id)]
                    (if (or (value/unusable? closure-value)
                            (not (application/compiler-callable? closure-value))
                            (not (closure-value/closure-info? closure-info))
                            (value/unusable? premise)
                            (value/unusable? epoch))
                      []
                      [(message out-id
                                (distributed-premise-closure-value closure-id
                                                                  closure-info
                                                                  premise
                                                                  epoch))]))))}))

(defn- premise-input-messages
  [network value-id premise-id epoch-id out-id]
  (let [v (unwrap-compiler-value (net/network-cell-strongest network value-id))
        premise (unwrap-compiler-value (net/network-cell-strongest network premise-id))
        epoch (unwrap-compiler-value (net/network-cell-strongest network epoch-id))]
    (if (or (value/unusable? v)
            (value/unusable? premise)
            (value/unusable? epoch))
      []
      [(message out-id
                (tms/distributed-input-update
                 [:compiler-2/premise-input out-id value-id premise]
                 v
                 premise
                 epoch
                 [:compiler-2/premise-input out-id]))])))

(defn- premise-content-input-messages
  [network value-id premise-id epoch-id out-id]
  (let [v (net/network-cell-content network value-id)
        premise (unwrap-compiler-value (net/network-cell-strongest network premise-id))
        epoch (unwrap-compiler-value (net/network-cell-strongest network epoch-id))]
    (if (or (value/unusable? v)
            (value/unusable? premise)
            (value/unusable? epoch))
      []
      [(message out-id
                (tms/distributed-input-update
                 [:compiler-2/premise-content-input out-id value-id premise]
                 v
                 premise
                 epoch
                 [:compiler-2/premise-content-input out-id]))])))

(defn- premise-state-messages
  [active? network premise-id epoch-id out-id]
  (let [premise (unwrap-compiler-value (net/network-cell-strongest network premise-id))
        epoch (unwrap-compiler-value (net/network-cell-strongest network epoch-id))]
    (if (or (value/unusable? premise)
            (value/unusable? epoch))
      []
      [(message out-id (tms/distributed-premise-update premise epoch active?))])))

(defn premise-input-operator []
  (operator-value/propagator-operator
   {:name "premise-input"
    :output-selector (fn [[_value-id _premise-id _epoch-id out-id] fallback-id]
                       [(or out-id fallback-id)])
    :input-selector (fn [arg-ids fallback-id _context-id]
                      (let [[value-id premise-id epoch-id explicit-out-id] (vec arg-ids)
                            out-id (or explicit-out-id fallback-id)]
                        (when-not (and value-id premise-id epoch-id out-id
                                       (<= 3 (count arg-ids) 4))
                          (throw (ex-info "premise-input expects value, premise, epoch, and optional output"
                                          {:arg-ids arg-ids})))
                        [value-id premise-id epoch-id]))
    :activate (fn [network inputs outputs _context-id]
                (let [[value-id premise-id epoch-id] inputs
                      [out-id] outputs]
                  (premise-input-messages network
                                          value-id
                                          premise-id
                                          epoch-id
                                          out-id)))}))

(defn premise-content-input-operator []
  (operator-value/propagator-operator
   {:name "premise-content-input"
    :output-selector (fn [[_value-id _premise-id _epoch-id out-id] fallback-id]
                       [(or out-id fallback-id)])
    :input-selector (fn [arg-ids fallback-id _context-id]
                      (let [[value-id premise-id epoch-id explicit-out-id] (vec arg-ids)
                            out-id (or explicit-out-id fallback-id)]
                        (when-not (and value-id premise-id epoch-id out-id
                                       (<= 3 (count arg-ids) 4))
                          (throw (ex-info "premise-content-input expects value, premise, epoch, and optional output"
                                          {:arg-ids arg-ids})))
                        [value-id premise-id epoch-id]))
    :activate (fn [network inputs outputs _context-id]
                (let [[value-id premise-id epoch-id] inputs
                      [out-id] outputs]
                  (premise-content-input-messages network
                                                  value-id
                                                  premise-id
                                                  epoch-id
                                                  out-id)))}))

(defn premise-state-operator [active? name]
  (operator-value/propagator-operator
   {:name name
    :output-selector (fn [[_premise-id _epoch-id out-id] fallback-id]
                       [(or out-id fallback-id)])
    :input-selector (fn [arg-ids _fallback-id _context-id]
                      (let [[premise-id epoch-id out-id] (vec arg-ids)]
                        (when-not (and premise-id epoch-id out-id (= 3 (count arg-ids)))
                          (throw (ex-info (str name " expects premise, epoch, and output cell")
                                          {:arg-ids arg-ids})))
                        [premise-id epoch-id]))
    :activate (fn [network inputs outputs _context-id]
                (let [[premise-id epoch-id] inputs
                      [out-id] outputs]
                  (premise-state-messages active? network premise-id epoch-id out-id)))}))

(defn add-distributed-tms-bindings
  [bindings]
  (into (vec bindings)
        [['tms-closure (tms-closure-operator)]
         ['premise-closure (distributed-premise-closure-operator)]
         ['distributed-premise-closure (distributed-premise-closure-operator)]
         ['premise-input (premise-input-operator)]
         ['premise-content-input (premise-content-input-operator)]
         ['premise-believe (premise-state-operator true "premise-believe")]
         ['premise-retract (premise-state-operator false "premise-retract")]]))
