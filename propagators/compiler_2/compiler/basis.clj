(ns propagators.compiler-2.compiler.basis
  "Construction helpers and default operator environment for compile-2."
  (:refer-clojure :exclude [* + - /])
  (:require [clojure.core :as core]
            [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.compiler-2.model.context :as context]
            [propagators.compiler-2.compiler.dispatch :as compiler-dispatch]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.compiler-2.operators.call-graph :as call-graph]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.compound-object.network-slot :as network-slot]
            [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.event :as event]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.datastructures.tms.distributed :as tms]
            [propagators.ids :as ids]
            [propagators.layered :as layered]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.datastructures.behavior.core :as behavior]
            [propagators.datastructures.behavior.arithmetic :as behavior-arithmetic])
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
  (letfn [(plan [arg-ids out-id]
            (let [arg-ids (vec arg-ids)
                  [head-id tail-id collection-id]
                  (case (count arg-ids)
                    2 [(first arg-ids) (second arg-ids) out-id]
                    3 [(first arg-ids) (second arg-ids) (nth arg-ids 2)]
                    (throw (ex-info "p:cons expects head+tail or head+tail+collection"
                                    {:arg-ids arg-ids})))]
              {:head-id head-id
               :tail-id tail-id
               :collection-id collection-id}))]
    (operator-value/operator-closure
     {:name 'p:cons
      :output-selector (fn [arg-ids fallback-id]
                         (if (= 3 (count arg-ids))
                           (nth (vec arg-ids) 2)
                           fallback-id))
      :install (fn [network arg-ids out-id]
                 (let [{:keys [head-id tail-id collection-id]} (plan arg-ids out-id)
                       [ids network'] ((obj/p:cons head-id tail-id collection-id)
                                       network)]
                   [network' (prop-ids ids) collection-id]))
      :activate (fn [network _context-id arg-ids out-id]
                  (let [{:keys [head-id tail-id collection-id]} (plan arg-ids out-id)]
                    (into (slot-messages :car head-id collection-id network)
                          (slot-messages :cdr tail-id collection-id network))))})))

(def list-empty-marker :compiler-2/list-empty)

(defn- compile-direct-form
  [state form role]
  (let [compile* (compiler-dispatch/state-compiler state)
        [state' binding] (compile* (child state role) form)]
    [(assoc state' :path (:path state)) binding]))

(defn declare-list
  [state element-bindings out-id]
  (let [element-ids (mapv env/binding-id element-bindings)]
    (when-not (every? some? element-ids)
      (throw (ex-info "list elements must compile to cells"
                      {:bindings element-bindings})))
    (let [[state' empty-binding] (new-cell state :list-empty list-empty-marker)
          empty-id (env/binding-id empty-binding)
          [state'' cons-ids]
          (reduce
           (fn [[state ids] idx]
             (if (zero? idx)
               [state (conj ids out-id)]
               (let [[state' binding] (new-cell state [:list-cons idx])]
                 [state' (conj ids (env/binding-id binding))])))
           [state' []]
           (range (count element-ids)))
          tail-ids (conj (vec (rest cons-ids)) empty-id)
          [installed-prop-ids network']
          (reduce
           (fn [[acc-prop-ids network] [head-id tail-id collection-id]]
             (let [network (reduce ensure-cell network
                                   [head-id tail-id collection-id])
                   [ids network'] ((obj/p:cons head-id tail-id collection-id)
                                   network)]
               [(into acc-prop-ids (prop-ids ids)) network']))
           [[] (:net state'')]
           (map vector element-ids tail-ids cons-ids))]
      [(-> state''
           (assoc :net network')
           (add-props installed-prop-ids))
       (env/cell-binding out-id)])))

(defn- list-topology
  [network element-ids out-id]
  (let [state {:net network
               :seed [:compiler-2/list out-id]
               :path []
               :props []
               :applications []}
        [compiled _binding]
        (declare-list state (mapv env/cell-binding element-ids) out-id)]
    {:net (:net compiled)
     :props (:props compiled)}))

(defn list-application-effects
  "Declare one reactive cons topology for an ordinary runtime application."
  [network element-ids out-id]
  ((requiring-resolve
    'propagators.compiler-2.runtime.topology-effects/declare-once)
   network
   [:list out-id]
   out-id
   #(list-topology % element-ids out-id)))

(defn- compile-list-k
  [compile-k state operand-forms out-id k]
  (let [base-path (:path state)
        forms (vec operand-forms)]
    (letfn [(step [state idx bindings]
              (if (= idx (count forms))
                (let [[state' binding] (declare-list state bindings out-id)]
                  #(k state' binding))
                (fn []
                  (compile-k
                   (child state [:list-element idx])
                   (nth forms idx)
                   (fn [state' binding]
                     #(step (assoc state' :path base-path)
                            (inc idx)
                            (conj bindings binding)))))))]
      (step state 0 []))))

(defn list-operator
  []
  (operator-value/operator-closure
   {:name 'list
    :compiler-activate
    (fn [_compile* network _context-id arg-ids out-id]
      (list-application-effects network arg-ids out-id))
    :direct-compiler compile-list-k
    :direct-installer
    (fn [state operand-forms out-id]
      (let [[state' element-bindings]
            (reduce
             (fn [[state acc] [idx form]]
               (let [[state' binding] (compile-direct-form state
                                                           form
                                                           [:list-element idx])]
                 [state' (conj acc binding)]))
             [state []]
             (map-indexed vector operand-forms))]
        (declare-list state' element-bindings out-id)))}))

(defn- accessor-operator
  [slot-key installer name]
  (letfn [(plan [arg-ids out-id]
            (let [arg-ids (vec arg-ids)
                  [value-id collection-id result-id read?]
                  (case (count arg-ids)
                    1 [out-id (first arg-ids) out-id true]
                    2 [(first arg-ids) (second arg-ids) (second arg-ids) false]
                    (throw (ex-info (str name " expects collection or value+collection")
                                    {:arg-ids arg-ids})))]
              {:value-id value-id
               :collection-id collection-id
               :result-id result-id
               :read? read?}))]
    (operator-value/operator-closure
     {:name name
      :output-selector (fn [arg-ids fallback-id]
                         (if (= 2 (count arg-ids))
                           (second (vec arg-ids))
                           fallback-id))
      :install (fn [network arg-ids out-id]
                 (let [{:keys [value-id collection-id result-id read?]}
                       (plan arg-ids out-id)]
                   (if read?
                     (let [[cell-id prop-ids network']
                           (obj/install-slot-access network
                                                    slot-key
                                                    collection-id
                                                    value-id)]
                       [network' prop-ids cell-id])
                     (let [[id network'] ((installer value-id collection-id) network)]
                       [network' [id] result-id]))))
      :activate (fn [network _context-id arg-ids out-id]
                  (let [{:keys [value-id collection-id]} (plan arg-ids out-id)]
                    (slot-messages slot-key value-id collection-id network)))})))

(defn- slot-key-messages
  [network slot-key-id value-id collection-id]
  (let [slot-key (net/network-cell-strongest network slot-key-id)]
    (cond
      (value/nothing? slot-key) []
      (value/contradiction? slot-key) [(message value-id value/contradiction)
                                       (message collection-id value/contradiction)]
      :else (slot-messages slot-key value-id collection-id network))))

(defn slot-operator
  "Generic compiler-2 `p:slot`.

  `(p:slot k coll)` reads slot `k` into the expression result.
  `(p:slot k value coll)` connects `value` and `coll` bidirectionally."
  []
  (letfn [(plan [arg-ids out-id]
            (let [arg-ids (vec arg-ids)
                  [slot-key-id value-id collection-id result-id read?]
                  (case (count arg-ids)
                    2 [(first arg-ids) out-id (second arg-ids) out-id true]
                    3 [(first arg-ids) (second arg-ids) (nth arg-ids 2) (nth arg-ids 2) false]
                    (throw (ex-info "p:slot expects key+collection or key+value+collection"
                                    {:arg-ids arg-ids})))]
              {:slot-key-id slot-key-id
               :value-id value-id
               :collection-id collection-id
               :result-id result-id
               :read? read?}))]
    (operator-value/operator-closure
     {:name 'p:slot
      :output-selector (fn [arg-ids fallback-id]
                         (if (= 3 (count arg-ids))
                           (nth (vec arg-ids) 2)
                           fallback-id))
      :install (fn [network arg-ids out-id]
                 (let [{:keys [slot-key-id value-id collection-id result-id read?]}
                       (plan arg-ids out-id)
                       slot-key (net/network-cell-strongest network slot-key-id)]
                   (if (value/unusable? slot-key)
                     [network [] result-id]
                     (if read?
                       (let [[cell-id prop-ids network']
                             (obj/install-slot-access network
                                                      slot-key
                                                      collection-id
                                                      value-id)]
                         [network' prop-ids cell-id])
                       (let [[id network'] ((obj/p:slot slot-key
                                                       value-id
                                                       collection-id)
                                            network)]
                         [network' [id] result-id])))))
      :activate (fn [network _context-id arg-ids out-id]
                  (let [{:keys [slot-key-id value-id collection-id]}
                        (plan arg-ids out-id)]
                    (slot-key-messages network slot-key-id value-id collection-id)))})))

(defn contextual-operator?
  [operator]
  (if (operator-value/operator-closure? operator)
    (operator-value/operator-contextual? operator)
    (true? (-> operator meta contextual-operator-key))))

(defn application-activate
  [operator]
  (if (operator-value/operator-closure? operator)
    (operator-value/operator-activate operator)
    (-> operator meta application-activate-key)))

(defn output-id
  [operator arg-ids fallback-id]
  (if (operator-value/operator-closure? operator)
    (operator-value/operator-output-id operator arg-ids fallback-id)
    (if-let [select-output (-> operator meta output-selector-key)]
      (select-output arg-ids fallback-id)
      fallback-id)))

(defn- behavior-projection?
  [v]
  (and (contains? (obj/public-slot-keys v) behavior/base-layer)
       (contains? (obj/public-slot-keys v) behavior/summary-layer)))

(defn- unwrap-behavior-current
  [v]
  (cond
    (behavior-projection? v) (behavior/base-value v)
    (behavior/behavior-value? v) (unwrap-behavior-current
                                  (behavior/strongest-value v))
    :else v))

(defn- unwrap-event-current
  [v]
  (cond
    (event/event-projection? v)
    (let [values (vals (event/active-values v))]
      (cond
        (empty? values) value/nothing
        (apply = values) (first values)
        :else value/contradiction))

    (or (event/event-content? v)
        (event/event-fact? v))
    (unwrap-event-current (event/strongest-value v))

    :else v))

(defn- unwrap-compiler-value
  [v]
  (-> v
      scope-source/unwrap
      tms/distributed-base-value
      dependency/unwrap
      unwrap-event-current
      unwrap-behavior-current))

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

(defn- event-lift-message
  [claim-id f arg-contents bases]
  (when-let [update (event/lift claim-id f arg-contents bases)]
    (when-not (value/nothing? update)
      update)))

(defn- primitive-messages
  [f current-net arg-ids out-id]
  (let [arg-values (mapv #(net/network-cell-strongest current-net %) arg-ids)
        arg-contents (mapv #(net/network-cell-content current-net %) arg-ids)
        bases (mapv unwrap-compiler-value arg-values)
        support-version
        (->> arg-contents
             (filter tms/distributed-value?)
             (mapcat tms/distributed-supports)
             (map (fn [support]
                     [(tms/premise support)
                      (tms/support-source support)
                     (tms/support-kind-value support)]))
             set)
        claim-id [:compiler-2/primitive out-id support-version]]
    (cond
      (some value/contradiction? bases)
      []

      (some value/contradiction? arg-contents)
      []

      (some #(or (event/event-content? %)
                 (event/event-fact? %)
                 (event/event-projection? %))
            arg-contents)
      (if-let [update (event-lift-message claim-id f arg-contents bases)]
        [(message out-id update)]
        [])

      (some value/nothing? bases)
      (if-let [update (tms/distributed-state-update arg-contents)]
        [(message out-id update)]
        [])

      :else
      [(message out-id
                (wrap-primitive-result claim-id
                                       (apply f bases)
                                       arg-values
                                       arg-contents))])))

(defn primitive-operator
  "Compile-2-local primitive wrapper that waits for partial inputs."
  [f]
  (operator-value/propagator-operator
   {:name :primitive
    :activate (fn [current-net inputs outputs _context-id]
                (primitive-messages f current-net inputs (first outputs)))}))

(defn- dependency-sources
  [v]
  (let [scope-unwrapped (scope-source/unwrap v)]
    (if (dependency/dependency-value? scope-unwrapped)
      (dependency/sources scope-unwrapped)
      #{})))

(defn contextual-primitive-operator
  "Primitive wrapper that reads the implicit compiler-2 context cell and emits
  a dependency value."
  [f]
  (operator-value/propagator-operator
   {:name :contextual-primitive
    :contextual? true
    :input-selector (fn [arg-ids _fallback-id context-id]
                      (into [context-id] arg-ids))
    :activate (fn [current-net inputs outputs _context-id]
                (let [context-id (first inputs)
                      arg-ids (subvec (vec inputs) 1)
                      out-id (first outputs)]
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

(defn execute-sub-env-operator []
  (operator-value/operator-closure
   {:name 'execute-sub-env
    :compiler-activate
    (fn [compile* network _context-id arg-ids out-id]
      (let [[expr-id parent-env-id & watch-ids] (vec arg-ids)
            child-env-id (stable-node-id :compiler-2 :execute-sub-env out-id)]
        ((requiring-resolve
          'propagators.compiler-2.runtime.application/execute-sub-env-messages-with)
         compile*
         parent-env-id
         expr-id
         child-env-id
         out-id
         network)))
    :install (fn [network arg-ids out-id]
               (let [[expr-id parent-env-id & watch-ids] (vec arg-ids)
                     child-env-id (stable-node-id :compiler-2 :execute-sub-env out-id)]
                 (((requiring-resolve 'propagators.compiler-2.runtime.application/p:execute-sub-env)
                   parent-env-id
                   expr-id
                   watch-ids
                   child-env-id
                   out-id)
                  network)))
    :activate (fn [network _context-id arg-ids out-id]
                (let [[expr-id parent-env-id & _watch-ids] (vec arg-ids)
                      child-env-id (stable-node-id :compiler-2 :execute-sub-env out-id)]
                  ((requiring-resolve
                    'propagators.compiler-2.runtime.application/execute-sub-env-messages)
                   parent-env-id
                   expr-id
                   child-env-id
                   out-id
                   network)))}))

(defn- sync-update
  [network id]
  (let [content (net/network-cell-content network id)
        strongest (net/network-cell-strongest network id)]
    (cond
      (tms/distributed-value? content)
      (tms/distributed-forward-update content)

      ((requiring-resolve 'propagators.datastructures.behavior/behavior-value?)
       content)
      content

      (not (value/unusable? strongest))
      strongest

      :else nil)))

(defn- sync-messages
  [network a b]
  (layered/bidirectional-transport-messages sync-update network a b))

(declare forward-sync-messages)

(defn- chain-forward-sync-messages
  [network ids]
  (into []
        (mapcat (fn [[a b]]
                  (forward-sync-messages network a b)))
        (partition 2 1 ids)))

(defn- chain-sync-messages
  [network ids]
  (into []
        (mapcat (fn [[a b]]
                  (sync-messages network a b)))
        (partition 2 1 ids)))

(defn- forward-sync-messages
  [network a b]
  (layered/forward-transport-messages sync-update network a b))

(defn- sync-chain-ids
  [name arg-ids]
  (let [arg-ids (vec arg-ids)]
    (when (< (count arg-ids) 2)
      (throw (ex-info (str name " expects at least two arguments")
                      {:arg-ids arg-ids})))
    arg-ids))

(defn- sync-output-id
  [arg-ids fallback-id]
  [(or (peek (vec arg-ids)) fallback-id)])

(defn sync-operator []
  (operator-value/propagator-operator
   {:name '->
    :output-selector sync-output-id
    :input-selector (fn [arg-ids _fallback-id _context-id]
                      (sync-chain-ids '-> arg-ids))
    :activate (fn [current-net inputs _outputs _context-id]
                (chain-forward-sync-messages current-net inputs))}))

(defn- switch-ids
  [arg-ids fallback-id]
  (let [[value-id condition-id explicit-out-id] (vec arg-ids)
        out-id (or explicit-out-id fallback-id)]
    (when-not (and value-id condition-id out-id
                   (<= 2 (count arg-ids) 3))
      (throw (ex-info "switch expects value, condition, and optional output"
                      {:arg-ids arg-ids})))
    [value-id condition-id out-id]))

(defn- switch-messages
  [network value-id condition-id out-id]
  (let [condition (unwrap-compiler-value
                   (net/network-cell-strongest network condition-id))]
    (cond
      (value/contradiction? condition) [(message out-id value/contradiction)]
      (value/unusable? condition) []
      condition (forward-sync-messages network value-id out-id)
      :else [])))

(defn switch-operator []
  (operator-value/propagator-operator
   {:name 'switch
    :output-selector (fn [arg-ids fallback-id]
                       (let [[_value-id _condition-id explicit-out-id] (vec arg-ids)]
                         [(or explicit-out-id fallback-id)]))
    :input-selector (fn [arg-ids fallback-id _context-id]
                      (let [[value-id condition-id _out-id] (switch-ids arg-ids fallback-id)]
                        [value-id condition-id]))
    :activate (fn [current-net inputs outputs _context-id]
                (let [[value-id condition-id] inputs
                      [out-id] outputs]
                  (switch-messages current-net value-id condition-id out-id)))}))

(defn if-operator []
  (operator-value/propagator-operator
   {:name 'if
    :output-selector (fn [arg-ids fallback-id]
                       (let [[_condition-id _then-id _else-id explicit-out-id]
                             (vec arg-ids)]
                         [(or explicit-out-id fallback-id)]))
    :input-selector (fn [arg-ids _fallback-id _context-id]
                      (when-not (<= 3 (count arg-ids) 4)
                        (throw (ex-info "if expects condition, then, else, and optional output"
                                        {:arg-ids arg-ids})))
                      (subvec (vec arg-ids) 0 3))
    :activate (fn [current-net inputs outputs _context-id]
                (let [[condition-id then-id else-id] inputs
                      [out-id] outputs
                      condition (unwrap-compiler-value
                                 (net/network-cell-strongest current-net
                                                            condition-id))]
                  (cond
                    (value/contradiction? condition)
                    [(message out-id value/contradiction)]

                    (value/unusable? condition)
                    []

                    condition
                    (forward-sync-messages current-net then-id out-id)

                    :else
                    (forward-sync-messages current-net else-id out-id))))}))

(defn branch-operator []
  (operator-value/propagator-operator
   {:name 'branch
    :output-selector (fn [arg-ids _fallback-id]
                       (let [[_condition-id _then-in then-out _else-in else-out]
                             (vec arg-ids)]
                         (when-not (and then-out else-out (= 5 (count arg-ids)))
                           (throw (ex-info "branch expects condition, then-in, then-out, else-in, and else-out"
                                           {:arg-ids arg-ids})))
                         [then-out else-out]))
    :input-selector (fn [arg-ids _fallback-id _context-id]
                      (let [[condition-id then-in _then-out else-in _else-out]
                            (vec arg-ids)]
                        [condition-id then-in else-in]))
    :activate (fn [current-net inputs outputs _context-id]
                (let [[condition-id then-in else-in] inputs
                      [then-out else-out] outputs
                      condition (unwrap-compiler-value
                                 (net/network-cell-strongest current-net
                                                            condition-id))]
                  (cond
                    (value/contradiction? condition)
                    [(message then-out value/contradiction)
                     (message else-out value/contradiction)]

                    (value/unusable? condition)
                    []

                    condition
                    (forward-sync-messages current-net then-in then-out)

                    :else
                    (forward-sync-messages current-net else-in else-out))))}))

(defn- predicate-messages
  [network pred value-id out-id]
  (let [v (net/network-cell-strongest network value-id)]
    (cond
      (value/nothing? v)
      []

      (value/contradiction? v)
      [(message out-id (case pred
                         :contradiction true
                         :value false
                         false))]

      :else
      [(message out-id
                (case pred
                  :nothing false
                  :contradiction false
                  :value true
                  :symbol (core/symbol? v)
                  :string (core/string? v)
                  :number (core/number? v)
                  :boolean (or (true? v) (false? v))
                  :cell (ids/node-id? v)
                  :network (net/net? v)
                  :closure ((requiring-resolve
                             'propagators.compiler-2.model.closure-value/closure-info?)
                            v)
                  :behavior ((requiring-resolve
                              'propagators.datastructures.behavior/behavior-value?)
                             v)
                  :tms (tms/distributed-value? v)
                  false))])))

(defn predicate-operator
  [name pred]
  (operator-value/propagator-operator
   {:name name
    :output-selector (fn [arg-ids fallback-id]
                       (let [[_value-id explicit-out-id] (vec arg-ids)]
                         [(or explicit-out-id fallback-id)]))
    :input-selector (fn [arg-ids _fallback-id _context-id]
                      (let [[value-id _out-id] (vec arg-ids)]
                        (when-not (and value-id (<= 1 (count arg-ids) 2))
                          (throw (ex-info (str name " expects value and optional output")
                                          {:arg-ids arg-ids})))
                        [value-id]))
    :activate (fn [current-net inputs outputs _context-id]
                (predicate-messages current-net pred (first inputs) (first outputs)))}))

(defn bi-sync-operator []
  (operator-value/propagator-operator
   {:name '<->
    :output-selector sync-output-id
    :input-selector (fn [arg-ids _fallback-id _context-id]
                      (sync-chain-ids '<-> arg-ids))
    :activate (fn [current-net inputs _outputs _context-id]
                (chain-sync-messages current-net inputs))}))

(defn- bind-default-tms-operators
  [compiler-env]
  ((requiring-resolve
    'propagators.compiler-2.operators.tms/bind-distributed-tms-operators)
   compiler-env))

(defn- operator-env
  [operator-builder]
  (bind-default-tms-operators
   (-> (obj/empty-compound-object)
       (env/set-depth 0)
       (env/bind-at '+ (operator-builder core/+) 0)
       (env/bind-at '- (operator-builder core/-) 0)
       (env/bind-at '* (operator-builder core/*) 0)
       (env/bind-at '/ (operator-builder core//) 0)
       (env/bind-at '< (operator-builder core/<) 0)
       (env/bind-at '<= (operator-builder core/<=) 0)
       (env/bind-at '> (operator-builder core/>) 0)
       (env/bind-at '>= (operator-builder core/>=) 0)
       (env/bind-at '= (operator-builder core/=) 0)
       (env/bind-at 'not (operator-builder core/not) 0)
       (env/bind-at 'str (operator-builder core/str) 0)
       (env/bind-at 'switch (switch-operator) 0)
       (env/bind-at 'if (if-operator) 0)
       (env/bind-at 'branch (branch-operator) 0)
       (env/bind-at 'nothing? (predicate-operator 'nothing? :nothing) 0)
       (env/bind-at 'contradiction? (predicate-operator 'contradiction? :contradiction) 0)
       (env/bind-at 'value? (predicate-operator 'value? :value) 0)
       (env/bind-at 'symbol? (predicate-operator 'symbol? :symbol) 0)
       (env/bind-at 'string? (predicate-operator 'string? :string) 0)
       (env/bind-at 'number? (predicate-operator 'number? :number) 0)
       (env/bind-at 'boolean? (predicate-operator 'boolean? :boolean) 0)
       (env/bind-at 'cell? (predicate-operator 'cell? :cell) 0)
       (env/bind-at 'network? (predicate-operator 'network? :network) 0)
       (env/bind-at 'closure? (predicate-operator 'closure? :closure) 0)
       (env/bind-at 'behavior? (predicate-operator 'behavior? :behavior) 0)
       (env/bind-at 'tms? (predicate-operator 'tms? :tms) 0)
       (env/bind-at 'p:cons (cons-operator) 0)
       (env/bind-at 'list (list-operator) 0)
       (env/bind-at 'p:slot (slot-operator) 0)
       (env/bind-at 'p:car (accessor-operator :car obj/p:car "p:car") 0)
       (env/bind-at 'p:cdr (accessor-operator :cdr obj/p:cdr "p:cdr") 0)
       (env/bind-at 'cons (cons-operator) 0)
       (env/bind-at 'car (accessor-operator :car obj/p:car "car") 0)
       (env/bind-at 'cdr (accessor-operator :cdr obj/p:cdr "cdr") 0)
       (env/bind-at 'call-graph (call-graph/call-graph-operator) 0)
       (env/bind-at 'p:call-graph (call-graph/call-graph-operator) 0)
       (env/bind-at 'execute-sub-env (execute-sub-env-operator) 0)
       (env/bind-at '-> (sync-operator) 0)
       (env/bind-at '<-> (bi-sync-operator) 0))))

(defn default-env []
  (operator-env primitive-operator))

(defn dependency-env []
  (operator-env contextual-primitive-operator))

(defn behavior-env []
  (-> (default-env)
      (env/bind-at 'be:+ (behavior-operator :+ core/+) 0)
      (env/bind-at 'be:- (behavior-operator :- core/-) 0)
      (env/bind-at 'be:* (behavior-operator :* core/*) 0)
      (env/bind-at 'be:divide (behavior-operator :/ core//) 0)))

(defn behavior-tms-env []
  ((requiring-resolve
    'propagators.compiler-2.operators.behavior/behavior-tms-env)))

(defn legacy-central-tms-env []
  ((requiring-resolve
    'propagators.compiler-2.legacy/legacy-central-tms-env)))
