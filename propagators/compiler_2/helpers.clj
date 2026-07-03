(ns propagators.compiler-2.helpers
  "Construction helpers and default operator environment for compile-2."
  (:refer-clojure :exclude [* + - /])
  (:require [clojure.core :as core]
            [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.compiler-2.closure-value :as closure-value]
            [propagators.compiler-2.context :as context]
            [propagators.compiler-2.env :as env]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.compound-object.network-slot :as network-slot]
            [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.datastructures.tms :as tms]
            [propagators.ids :as ids]
            [propagators.message :refer [message message-id message-value]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.arithmetic.behavior :as behavior-arithmetic])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(defn stable-node-id [& seed]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str seed) StandardCharsets/UTF_8))))

(defn node-id [{:keys [seed path]} role]
  (stable-node-id seed path role))

(defn ensure-cell [network id]
  (if (contains? (net/net-env network) id)
    network
    (nb/install-cell network id)))

(defn strongest-or-nothing
  [network id]
  (if (contains? (net/net-env network) id)
    (net/network-cell-strongest network id)
    value/nothing))

(defn seed-cell [network id v]
  (nb/seed-cell (ensure-cell network id) id v))

(defn new-cell
  ([state role] (new-cell state role value/nothing))
  ([{:keys [net] :as state} role v]
   (let [id (node-id state role)]
     [(assoc state :net (if (value/nothing? v)
                          (ensure-cell net id)
                          (seed-cell net id v)))
      (env/cell-binding id)])))

(defn child [state segment]
  (update state :path conj segment))

(defn add-props [state prop-ids]
  (update state :props into prop-ids))

(defn installer-operator [installer]
  (fn [network arg-ids out-id]
    (let [[prop-id network'] ((apply installer (conj (vec arg-ids) out-id))
                              network)]
      [network' [prop-id] out-id])))

(defn- prop-ids
  [x]
  (vec (if (sequential? x) x [x])))

(def contextual-operator-key ::contextual-operator?)
(def application-activate-key ::application-activate)
(def output-selector-key ::output-selector)

(defn- slot-messages
  [slot-key value-id collection-id network]
  ((network-slot/network-slot-activation slot-key value-id collection-id)
   nil
   nil
   network))

(defn- cons-operator
  []
  (with-meta
    (fn [network arg-ids out-id]
      (let [arg-ids (vec arg-ids)
            [head-id tail-id collection-id]
            (case (count arg-ids)
              2 [(first arg-ids) (second arg-ids) out-id]
              3 [(first arg-ids) (second arg-ids) (nth arg-ids 2)]
              (throw (ex-info "p:cons expects head+tail or head+tail+collection"
                              {:arg-ids arg-ids})))]
        (let [[ids network'] ((obj/p:cons head-id tail-id collection-id) network)]
          [network' (prop-ids ids) collection-id])))
    {output-selector-key
     (fn [arg-ids fallback-id]
       (if (= 3 (count arg-ids))
         (nth (vec arg-ids) 2)
         fallback-id))
     application-activate-key
     (fn [network _context-id arg-ids out-id]
       (let [arg-ids (vec arg-ids)
             [head-id tail-id collection-id]
             (case (count arg-ids)
               2 [(first arg-ids) (second arg-ids) out-id]
               3 [(first arg-ids) (second arg-ids) (nth arg-ids 2)]
               (throw (ex-info "p:cons expects head+tail or head+tail+collection"
                               {:arg-ids arg-ids})))]
         (into (slot-messages :car head-id collection-id network)
               (slot-messages :cdr tail-id collection-id network))))}))

(defn- accessor-operator
  [slot-key installer name]
  (with-meta
    (fn [network arg-ids out-id]
      (let [arg-ids (vec arg-ids)
            [value-id collection-id result-id]
            (case (count arg-ids)
              1 [out-id (first arg-ids) out-id]
              2 [(first arg-ids) (second arg-ids) (second arg-ids)]
              (throw (ex-info (str name " expects collection or value+collection")
                              {:arg-ids arg-ids})))]
        (let [[id network'] ((installer value-id collection-id) network)]
          [network' [id] result-id])))
    {output-selector-key
     (fn [arg-ids fallback-id]
       (if (= 2 (count arg-ids))
         (second (vec arg-ids))
         fallback-id))
     application-activate-key
     (fn [network _context-id arg-ids out-id]
       (let [arg-ids (vec arg-ids)
             [value-id collection-id]
             (case (count arg-ids)
               1 [out-id (first arg-ids)]
               2 [(first arg-ids) (second arg-ids)]
               (throw (ex-info (str name " expects collection or value+collection")
                               {:arg-ids arg-ids})))]
         (slot-messages slot-key value-id collection-id network)))}))

(defn contextual-operator?
  [operator]
  (true? (-> operator meta contextual-operator-key)))

(defn application-activate
  [operator]
  (-> operator meta application-activate-key))

(defn output-id
  [operator arg-ids fallback-id]
  (if-let [select-output (-> operator meta output-selector-key)]
    (select-output arg-ids fallback-id)
    fallback-id))

(defn- unwrap-compiler-value
  [v]
  (-> v
      scope-source/unwrap
      tms/distributed-base-value
      dependency/unwrap))

(declare dependency-sources)

(defn- wrap-primitive-result
  [claim-id result arg-values arg-contents]
  (let [sources (apply set/union #{} (map dependency-sources arg-values))]
    (let [result* (if (or (empty? sources)
                          (value/unusable? result))
                    result
                    (dependency/dependency-value result sources))]
      (or (tms/distributed-result-update claim-id result* arg-contents)
          result*))))

(defn- primitive-messages
  [f current-net arg-ids out-id]
  (let [arg-values (mapv #(net/network-cell-strongest current-net %) arg-ids)
        arg-contents (mapv #(net/network-cell-content current-net %) arg-ids)
        bases (mapv unwrap-compiler-value arg-values)]
    (cond
      (some value/contradiction? bases)
      []

      (some value/nothing? bases)
      (if-let [update (tms/distributed-state-update arg-contents)]
        [(message out-id update)]
        [])

      :else
      [(message out-id
                (wrap-primitive-result [:compiler-2/primitive out-id]
                                       (apply f bases)
                                       arg-values
                                       arg-contents))])))

(defn primitive-operator
  "Compile-2-local primitive wrapper that waits for partial inputs."
  [f]
  (with-meta
    (fn [network arg-ids out-id]
      (let [[prop-id network']
            ((prop/construct-propagator
              (fn [_inputs _outputs current-net]
                (primitive-messages f current-net arg-ids out-id))
              arg-ids
              [out-id])
             network)]
        [network' [prop-id] out-id]))
    {application-activate-key
     (fn [current-net _context-id arg-ids out-id]
       (primitive-messages f current-net arg-ids out-id))}))

(defn- dependency-sources
  [v]
  (let [scope-unwrapped (scope-source/unwrap v)
        scope-source (when (scope-source/scope-value? v)
                       {:dependency/type :compiler-2/scope-source
                        :scope/id (scope-source/source-scope v)
                        :scope/chain (scope-source/context-chain v)})]
    (cond-> (if (dependency/dependency-value? scope-unwrapped)
              (dependency/sources scope-unwrapped)
              #{})
      scope-source (conj scope-source))))

(defn contextual-primitive-operator
  "Primitive wrapper that reads the implicit compiler-2 context cell and emits
  a dependency value."
  [f]
  (with-meta
    (fn [network context-id arg-ids out-id]
      (let [inputs (into [context-id] arg-ids)
            [prop-id network']
            ((prop/construct-propagator
              (prop/concrete-propagator
               (fn [_inputs _outputs current-net]
                (let [context-value (net/network-cell-strongest current-net
                                                                context-id)
                      arg-values (mapv #(net/network-cell-strongest current-net %)
                                       arg-ids)
                      bases (mapv unwrap-compiler-value arg-values)
                      unusable-input? (or (value/unusable? context-value)
                                          (apply value/any-unusable-values?
                                                 bases))
                      base-result (if unusable-input?
                                    value/nothing
                                    (apply f bases))]
                  (if (or unusable-input?
                          (value/unusable? base-result))
                    []
                    [(message out-id
                              (dependency/dependency-value
                               base-result
                               (conj (apply set/union
                                            (map dependency-sources
                                                 arg-values))
                                     (context/dependency-source
                                      context-value))))]))))
              inputs
              [out-id])
             network)]
        [network' [prop-id] out-id]))
    {contextual-operator-key true
     application-activate-key
     (fn [current-net context-id arg-ids out-id]
       (let [inputs (into [context-id] arg-ids)]
         (if-not (prop/concrete-inputs? current-net inputs)
           []
           (let [context-value (net/network-cell-strongest current-net
                                                           context-id)
                 arg-values (mapv #(net/network-cell-strongest current-net %)
                                  arg-ids)
                 bases (mapv unwrap-compiler-value arg-values)
                 unusable-input? (apply value/any-unusable-values? bases)
                 base-result (if unusable-input?
                               value/nothing
                               (apply f bases))]
             (if (or unusable-input?
                     (value/unusable? base-result))
               []
               [(message out-id
                         (dependency/dependency-value
                          base-result
                          (conj (apply set/union
                                       (map dependency-sources
                                            arg-values))
                                (context/dependency-source
                                 context-value))))])))))}))

(defn behavior-operator
  "Compiler-2 operator wrapper for behavior-history stdlib operators."
  [op f]
  (with-meta
    (fn [network arg-ids out-id]
      (let [[prop-id network']
            ((apply (behavior-arithmetic/behavior-propagator op f)
                    (conj (vec arg-ids) out-id))
             network)]
        [network' [prop-id] out-id]))
    {application-activate-key
     (fn [current-net _context-id arg-ids out-id]
       (behavior-arithmetic/behavior-messages op f arg-ids out-id current-net))}))

(defn distributed-behavior-operator
  "TMS-composed behavior wrapper; behavior-operator remains the plain path."
  [op f]
  (with-meta
    (fn [network arg-ids out-id]
      (let [[prop-id network']
            ((apply (behavior-arithmetic/distributed-behavior-propagator op f)
                    (conj (vec arg-ids) out-id))
             network)]
        [network' [prop-id] out-id]))
    {application-activate-key
     (fn [current-net _context-id arg-ids out-id]
       (behavior-arithmetic/distributed-behavior-messages
        op
        f
        arg-ids
        out-id
        current-net))}))

(defn stable-distributed-behavior-operator
  "Behavior-TMS wrapper with stable claim reuse for rebuilt behavior values."
  [op f]
  (with-meta
    (fn [network arg-ids out-id]
      (let [[prop-id network']
            ((apply (behavior-arithmetic/distributed-behavior-stable-propagator op f)
                    (conj (vec arg-ids) out-id))
             network)]
        [network' [prop-id] out-id]))
    {application-activate-key
     (fn [current-net _context-id arg-ids out-id]
       (behavior-arithmetic/distributed-behavior-stable-messages
        op
        f
        arg-ids
        out-id
        current-net))}))

(defn- activation-messages
  [ret]
  (vec (if (map? ret) (:messages ret) ret)))

(defn- closure-output-symbols
  [output]
  (cond
    (nil? output) []
    (symbol? output) [output]
    (vector? output) output
    :else []))

(defn- premise-output-id
  [closure-info arg-ids out-id]
  (let [outputs (closure-output-symbols
                 (closure-value/closure-output closure-info))]
    (if (= 1 (count outputs))
      (peek (vec arg-ids))
      out-id)))

(defn- premise-closure-call-messages
  [closure-id closure-info premise storage-id network arg-ids out-id]
  (let [apply-messages (activation-messages
                        ((requiring-resolve
                          'propagators.compiler-2.application/closure-application-messages)
                         closure-id
                         nil
                         arg-ids
                         out-id
                         network))
        premise-out-id (premise-output-id closure-info arg-ids out-id)
        output-value (or (some (fn [m]
                                 (when (= premise-out-id (message-id m))
                                   (message-value m)))
                               apply-messages)
                         (strongest-or-nothing network premise-out-id))]
    (cond-> apply-messages
      (and (not (value/unusable? output-value))
           (not (value/unusable? premise)))
      (conj (message storage-id
                     (tms/premise-update tms/reducer-id premise 0 true))
            (message storage-id
                     (tms/claim-update
                      (tms/claim [:premise-closure premise premise-out-id]
                                 :answer
                                 output-value
                                 [(tms/support premise
                                               [:compiler-2/premise-closure
                                                premise-out-id]
                                               :premise-closure)])))))))

(defn- premise-closure-value
  [closure-id closure-value premise storage-id]
  (with-meta
    {:compiler-2/operator :premise-closure
     :closure-id closure-id
     :closure closure-value
     :premise premise
     :storage-id storage-id}
    {application-activate-key
     (fn [network _context-id arg-ids out-id]
       (premise-closure-call-messages closure-id
                                      closure-value
                                      premise
                                      storage-id
                                      network
                                      arg-ids
                                      out-id))}))

(defn- premise-closure-operator []
  (with-meta
    (fn [network _arg-ids out-id]
      [network [] out-id])
    {application-activate-key
     (fn [_network _context-id arg-ids out-id]
       (let [[closure-id premise-id storage-id] (vec arg-ids)]
         (when-not (and closure-id premise-id storage-id (= 3 (count arg-ids)))
           (throw (ex-info "premise-closure expects closure, premise, and tms storage"
                           {:arg-ids arg-ids})))
         (let [closure-value (strongest-or-nothing _network closure-id)
               premise (strongest-or-nothing _network premise-id)]
           (if (or (value/unusable? closure-value)
                   (value/unusable? premise))
             []
             [(message out-id
                       (premise-closure-value closure-id
                                              closure-value
                                              premise
                                              storage-id))]))))}))

(defn- tms-closure-call-messages
  [closure-id closure-info network arg-ids out-id]
  (let [apply-messages (activation-messages
                        ((requiring-resolve
                          'propagators.compiler-2.application/closure-application-messages)
                         closure-id
                         nil
                         arg-ids
                         out-id
                         network))
        input-count (count (closure-value/closure-inputs closure-info))
        input-contents (mapv #(net/network-cell-content network %)
                             (take input-count arg-ids))
        state-update (tms/distributed-state-update input-contents)
        output-id (premise-output-id closure-info arg-ids out-id)]
    (cond-> apply-messages
      (value/contradiction? state-update)
      (conj (message output-id value/contradiction))

      (and state-update (not (value/contradiction? state-update)))
      (conj (message output-id state-update)))))

(defn- tms-closure-value
  [closure-id closure-value]
  (with-meta
    {:compiler-2/operator :tms-closure
     :closure-id closure-id
     :closure closure-value}
    {application-activate-key
     (fn [network _context-id arg-ids out-id]
       (tms-closure-call-messages closure-id
                                  closure-value
                                  network
                                  arg-ids
                                  out-id))}))

(defn- tms-closure-operator []
  (with-meta
    (fn [network _arg-ids out-id]
      [network [] out-id])
    {application-activate-key
     (fn [network _context-id arg-ids out-id]
       (let [[closure-id] (vec arg-ids)]
         (when-not (and closure-id (= 1 (count arg-ids)))
           (throw (ex-info "tms-closure expects one network closure"
                           {:arg-ids arg-ids})))
         (let [closure-value (strongest-or-nothing network closure-id)]
           (if (or (value/unusable? closure-value)
                   (not (closure-value/closure-info? closure-value)))
             []
             [(message out-id
                       (tms-closure-value closure-id closure-value))]))))}))

(defn- single-output-call-plan
  [closure-id closure-info arg-ids out-id tag]
  (let [input-count (count (closure-value/closure-inputs closure-info))
        outputs (closure-output-symbols (closure-value/closure-output closure-info))
        arg-ids (vec arg-ids)]
    (cond
      (= 1 (count outputs))
      (when (= (count arg-ids) (inc input-count))
        (let [real-output-id (peek arg-ids)
              hidden-output-id (stable-node-id tag closure-id real-output-id arg-ids)]
          {:input-ids (subvec arg-ids 0 input-count)
           :real-output-id real-output-id
           :hidden-output-id hidden-output-id
           :inner-arg-ids (conj (subvec arg-ids 0 input-count)
                                hidden-output-id)}))

      (empty? outputs)
      (when (= (count arg-ids) input-count)
        (let [hidden-output-id (stable-node-id tag closure-id out-id arg-ids)]
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

(defn- distributed-premise-closure-call-messages
  [closure-id closure-info premise epoch network arg-ids out-id]
  (let [{:keys [input-ids real-output-id hidden-output-id inner-arg-ids]}
        (single-output-call-plan closure-id
                                 closure-info
                                 arg-ids
                                 out-id
                                 :compiler-2/distributed-premise-closure)]
    (if-not real-output-id
      []
      (let [apply-messages (activation-messages
                            ((requiring-resolve
                              'propagators.compiler-2.application/closure-application-messages)
                             closure-id
                             nil
                             inner-arg-ids
                             hidden-output-id
                             network))
            output-update (some (fn [m]
                                  (when (= hidden-output-id (message-id m))
                                    (message-value m)))
                                apply-messages)
            update (distributed-premise-output-update
                    [:compiler-2/distributed-premise-closure real-output-id]
                    [:compiler-2/distributed-premise-closure real-output-id]
                    output-update
                    (mapv #(net/network-cell-content network %) input-ids)
                    premise
                    epoch)]
        (cond-> []
          update (conj (message real-output-id update)))))))

(defn- distributed-premise-closure-value
  [closure-id closure-value premise epoch]
  (with-meta
    {:compiler-2/operator :distributed-premise-closure
     :closure-id closure-id
     :closure closure-value
     :premise premise
     :epoch epoch}
    {application-activate-key
     (fn [network _context-id arg-ids out-id]
       (distributed-premise-closure-call-messages closure-id
                                                 closure-value
                                                 premise
                                                 epoch
                                                 network
                                                 arg-ids
                                                 out-id))}))

(defn- distributed-premise-closure-operator []
  (with-meta
    (fn [network _arg-ids out-id]
      [network [] out-id])
    {application-activate-key
     (fn [network _context-id arg-ids out-id]
       (let [[closure-id premise-id epoch-id] (vec arg-ids)]
         (when-not (and closure-id premise-id epoch-id (= 3 (count arg-ids)))
           (throw (ex-info "distributed-premise-closure expects closure, premise, and epoch"
                           {:arg-ids arg-ids})))
         (let [closure-value (strongest-or-nothing network closure-id)
               premise (strongest-or-nothing network premise-id)
               epoch (strongest-or-nothing network epoch-id)]
           (if (or (value/unusable? closure-value)
                   (not (closure-value/closure-info? closure-value))
                   (value/unusable? premise)
                   (value/unusable? epoch))
             []
             [(message out-id
                       (distributed-premise-closure-value closure-id
                                                         closure-value
                                                         premise
                                                         epoch))]))))}))

(defn- execute-sub-env-operator []
  (with-meta
    (fn [network arg-ids out-id]
      (let [[expr-id parent-env-id & watch-ids] (vec arg-ids)
            child-env-id (stable-node-id :compiler-2 :execute-sub-env out-id)]
        (((requiring-resolve 'propagators.compiler-2.application/p:execute-sub-env)
          parent-env-id
          expr-id
          watch-ids
          child-env-id
          out-id)
         network)))
    {application-activate-key
     (fn [network _context-id arg-ids out-id]
       (let [[expr-id parent-env-id & _watch-ids] (vec arg-ids)
             child-env-id (stable-node-id :compiler-2 :execute-sub-env out-id)]
         ((requiring-resolve
           'propagators.compiler-2.application/execute-sub-env-messages)
          parent-env-id
          expr-id
          child-env-id
          out-id
          network)))}))

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

(defn- premise-input-operator []
  (with-meta
    (fn [network [value-id premise-id epoch-id out-id] _fallback-id]
      (let [[prop-id network']
            ((prop/construct-propagator
              (fn [_inputs _outputs current-net]
                (premise-input-messages current-net
                                        value-id
                                        premise-id
                                        epoch-id
                                        out-id))
              [value-id premise-id epoch-id]
              [out-id])
             network)]
        [network' [prop-id] out-id]))
    {output-selector-key
     (fn [[_value-id _premise-id _epoch-id out-id] fallback-id]
       (or out-id fallback-id))
     application-activate-key
     (fn [network _context-id [value-id premise-id epoch-id out-id] _fallback-id]
       (premise-input-messages network value-id premise-id epoch-id out-id))}))

(defn- premise-content-input-operator []
  (with-meta
    (fn [network [value-id premise-id epoch-id out-id] _fallback-id]
      (let [[prop-id network']
            ((prop/construct-propagator
              (fn [_inputs _outputs current-net]
                (premise-content-input-messages current-net
                                                value-id
                                                premise-id
                                                epoch-id
                                                out-id))
              [value-id premise-id epoch-id]
              [out-id])
             network)]
        [network' [prop-id] out-id]))
    {output-selector-key
     (fn [[_value-id _premise-id _epoch-id out-id] fallback-id]
       (or out-id fallback-id))
     application-activate-key
     (fn [network _context-id [value-id premise-id epoch-id out-id] _fallback-id]
       (premise-content-input-messages network value-id premise-id epoch-id out-id))}))

(defn- premise-state-operator [active? name]
  (with-meta
    (fn [network [premise-id epoch-id out-id] _fallback-id]
      (let [[prop-id network']
            ((prop/construct-propagator
              (fn [_inputs _outputs current-net]
                (premise-state-messages active?
                                        current-net
                                        premise-id
                                        epoch-id
                                        out-id))
              [premise-id epoch-id]
              [out-id])
             network)]
        [network' [prop-id] out-id]))
    {output-selector-key
     (fn [[_premise-id _epoch-id out-id] fallback-id]
       (or out-id fallback-id))
     application-activate-key
     (fn [network _context-id [premise-id epoch-id out-id] _fallback-id]
       (when-not out-id
         (throw (ex-info (str name " expects premise, epoch, and output cell")
                         {})))
       (premise-state-messages active? network premise-id epoch-id out-id))}))

(defn- sync-update
  [network id]
  (let [content (net/network-cell-content network id)
        strongest (net/network-cell-strongest network id)]
    (cond
      (tms/distributed-value? content)
      (tms/distributed-forward-update content)

      (not (value/unusable? strongest))
      strongest

      :else nil)))

(defn- sync-messages
  [network a b]
  (let [a-update (sync-update network a)
        b-update (sync-update network b)]
    (cond-> []
      a-update (conj (message b a-update))
      b-update (conj (message a b-update)))))

(defn- bi-sync-operator []
  (with-meta
    (fn [network arg-ids _out-id]
      (let [[a b] (vec arg-ids)]
        (when-not (and a b (= 2 (count arg-ids)))
          (throw (ex-info "<-> expects exactly two arguments" {:arg-ids arg-ids})))
        (let [[prop-id network']
              ((prop/construct-propagator
                (fn [_inputs _outputs current-net]
                  (sync-messages current-net a b))
                [a b]
                [a b])
               network)]
          [network' [prop-id] b])))
    {output-selector-key
     (fn [arg-ids fallback-id]
       (let [[_ b] (vec arg-ids)]
         (or b fallback-id)))
     application-activate-key
     (fn [current-net _context-id arg-ids _out-id]
       (let [[a b] (vec arg-ids)]
         (when-not (and a b (= 2 (count arg-ids)))
           (throw (ex-info "<-> expects exactly two arguments"
                           {:arg-ids arg-ids})))
         (sync-messages current-net a b)))}))

(defn- operator-env
  [operator-builder]
  (-> (obj/empty-compound-object)
      (env/set-depth 0)
      (env/bind-at '+ (operator-builder core/+) 0)
      (env/bind-at '- (operator-builder core/-) 0)
      (env/bind-at '* (operator-builder core/*) 0)
      (env/bind-at '/ (operator-builder core//) 0)
      (env/bind-at 'switch
                   (operator-builder
                    (fn [x enabled?]
                      (if enabled? x value/nothing)))
                   0)
      (env/bind-at 'p:cons (cons-operator) 0)
      (env/bind-at 'p:car (accessor-operator :car obj/p:car "p:car") 0)
      (env/bind-at 'p:cdr (accessor-operator :cdr obj/p:cdr "p:cdr") 0)
      (env/bind-at 'cons (cons-operator) 0)
      (env/bind-at 'car (accessor-operator :car obj/p:car "car") 0)
      (env/bind-at 'cdr (accessor-operator :cdr obj/p:cdr "cdr") 0)
      (env/bind-at 'execute-sub-env (execute-sub-env-operator) 0)
      (env/bind-at 'premise-closure (premise-closure-operator) 0)
      (env/bind-at 'tms-closure (tms-closure-operator) 0)
      (env/bind-at 'distributed-premise-closure
                   (distributed-premise-closure-operator)
                   0)
      (env/bind-at 'premise-input (premise-input-operator) 0)
      (env/bind-at 'premise-content-input (premise-content-input-operator) 0)
      (env/bind-at 'premise-believe (premise-state-operator true "premise-believe") 0)
      (env/bind-at 'premise-retract (premise-state-operator false "premise-retract") 0)
      (env/bind-at '<-> (bi-sync-operator) 0)))

(defn default-env []
  (operator-env primitive-operator))

(defn dependency-env []
  (operator-env contextual-primitive-operator))

(defn behavior-env []
  (-> (obj/empty-compound-object)
      (env/set-depth 0)
      (env/bind-at '+ (behavior-operator :+ core/+) 0)
      (env/bind-at '- (behavior-operator :- core/-) 0)
      (env/bind-at '* (behavior-operator :* core/*) 0)
      (env/bind-at '/ (behavior-operator :/ core//) 0)
      (env/bind-at 'execute-sub-env (execute-sub-env-operator) 0)
      (env/bind-at '<-> (bi-sync-operator) 0)))

(defn behavior-tms-env []
  (-> (obj/empty-compound-object)
      (env/set-depth 0)
      (env/bind-at '+ (stable-distributed-behavior-operator :+ core/+) 0)
      (env/bind-at '- (stable-distributed-behavior-operator :- core/-) 0)
      (env/bind-at '* (stable-distributed-behavior-operator :* core/*) 0)
      (env/bind-at '/ (stable-distributed-behavior-operator :/ core//) 0)
      (env/bind-at 'execute-sub-env (execute-sub-env-operator) 0)
      (env/bind-at 'distributed-premise-closure
                   (distributed-premise-closure-operator)
                   0)
      (env/bind-at 'premise-input (premise-input-operator) 0)
      (env/bind-at 'premise-content-input (premise-content-input-operator) 0)
      (env/bind-at 'premise-believe (premise-state-operator true "premise-believe") 0)
      (env/bind-at 'premise-retract (premise-state-operator false "premise-retract") 0)
      (env/bind-at '<-> (bi-sync-operator) 0)))
