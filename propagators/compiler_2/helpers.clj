(ns propagators.compiler-2.helpers
  "Construction helpers and default operator environment for compile-2."
  (:refer-clojure :exclude [* + - /])
  (:require [clojure.core :as core]
            [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.compiler-2.context :as context]
            [propagators.compiler-2.env :as env]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.arithmetic.behavior :as behavior-arithmetic]
            [propagators.stdlib.prop :as stdlib-prop])
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

(def contextual-operator-key ::contextual-operator?)
(def application-activate-key ::application-activate)
(def output-selector-key ::output-selector)

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
      dependency/unwrap))

(defn primitive-operator
  "Compile-2-local primitive wrapper that waits for partial inputs."
  [f]
  (with-meta
    (fn [network arg-ids out-id]
      (let [[prop-id network']
            ((prop/construct-propagator
              (prop/concrete-propagator
               (fn [_inputs _outputs current-net]
                (let [values (mapv #(unwrap-compiler-value
                                      (net/network-cell-strongest current-net %))
                                   arg-ids)]
                  (if (apply value/any-unusable-values? values)
                    []
                    [(message out-id (apply f values))]))))
              arg-ids
              [out-id])
             network)]
        [network' [prop-id] out-id]))
    {application-activate-key
     (fn [current-net _context-id arg-ids out-id]
       (if-not (prop/concrete-inputs? current-net arg-ids)
         []
         (let [values (mapv #(unwrap-compiler-value
                               (net/network-cell-strongest current-net %))
                            arg-ids)]
           (if (apply value/any-unusable-values? values)
             []
             [(message out-id (apply f values))]))))}))

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

(defn- bi-sync-operator []
  (with-meta
    (fn [network arg-ids _out-id]
      (let [[a b] (vec arg-ids)]
        (when-not (and a b (= 2 (count arg-ids)))
          (throw (ex-info "<-> expects exactly two arguments" {:arg-ids arg-ids})))
        (let [[a->b network'] ((stdlib-prop/id a b) network)
              [b->a network''] ((stdlib-prop/id b a) network')]
          [network'' [a->b b->a] b])))
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
         (let [a-value (net/network-cell-strongest current-net a)
               b-value (net/network-cell-strongest current-net b)]
           (cond-> []
             (not (value/unusable? a-value)) (conj (message b a-value))
             (not (value/unusable? b-value)) (conj (message a b-value))))))}))

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
      (env/bind-at '<-> (bi-sync-operator) 0)))
