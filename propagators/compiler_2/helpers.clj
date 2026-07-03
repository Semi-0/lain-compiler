(ns propagators.compiler-2.helpers
  "Construction helpers and default operator environment for compile-2."
  (:refer-clojure :exclude [* + - /])
  (:require [clojure.core :as core]
            [clojure.set :as set]
            [propagators.cells.value :as value]
            [propagators.compiler-2.context :as context]
            [propagators.compiler-2.env :as env]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.compound-object.network-slot :as network-slot]
            [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.datastructures.tms.distributed :as tms]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
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
  (with-meta
    (fn [network arg-ids out-id]
      (let [arg-ids (vec arg-ids)
            [slot-key-id value-id collection-id result-id]
            (case (count arg-ids)
              2 [(first arg-ids) out-id (second arg-ids) out-id]
              3 [(first arg-ids) (second arg-ids) (nth arg-ids 2) (nth arg-ids 2)]
              (throw (ex-info "p:slot expects key+collection or key+value+collection"
                              {:arg-ids arg-ids})))]
        (let [slot-key (net/network-cell-strongest network slot-key-id)]
          (if (value/unusable? slot-key)
            [network [] result-id]
            (let [[id network'] ((obj/p:slot slot-key value-id collection-id)
                                 network)]
              [network' [id] result-id])))))
    {output-selector-key
     (fn [arg-ids fallback-id]
       (if (= 3 (count arg-ids))
         (nth (vec arg-ids) 2)
         fallback-id))
     application-activate-key
     (fn [network _context-id arg-ids out-id]
       (let [arg-ids (vec arg-ids)
             [slot-key-id value-id collection-id]
             (case (count arg-ids)
               2 [(first arg-ids) out-id (second arg-ids)]
               3 [(first arg-ids) (second arg-ids) (nth arg-ids 2)]
               (throw (ex-info "p:slot expects key+collection or key+value+collection"
                               {:arg-ids arg-ids})))]
         (slot-key-messages network slot-key-id value-id collection-id)))}))

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

(defn execute-sub-env-operator []
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
  (let [a-update (sync-update network a)
        b-update (sync-update network b)]
    (cond-> []
      a-update (conj (message b a-update))
      b-update (conj (message a b-update)))))

(defn- forward-sync-messages
  [network a b]
  (if-let [a-update (sync-update network a)]
    [(message b a-update)]
    []))

(defn sync-operator []
  (with-meta
    (fn [network arg-ids _out-id]
      (let [[a b] (vec arg-ids)]
        (when-not (and a b (= 2 (count arg-ids)))
          (throw (ex-info "-> expects exactly two arguments" {:arg-ids arg-ids})))
        (let [[prop-id network']
              ((prop/construct-propagator
                (fn [_inputs _outputs current-net]
                  (forward-sync-messages current-net a b))
                [a]
                [b])
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
           (throw (ex-info "-> expects exactly two arguments"
                           {:arg-ids arg-ids})))
         (forward-sync-messages current-net a b)))}))

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
  (with-meta
    (fn [network arg-ids fallback-id]
      (let [[value-id condition-id out-id] (switch-ids arg-ids fallback-id)
            [prop-id network']
            ((prop/construct-propagator
              (fn [_inputs _outputs current-net]
                (switch-messages current-net value-id condition-id out-id))
              [value-id condition-id]
              [out-id])
             network)]
        [network' [prop-id] out-id]))
    {output-selector-key
     (fn [arg-ids fallback-id]
       (let [[_value-id _condition-id explicit-out-id] (vec arg-ids)]
         (or explicit-out-id fallback-id)))
     application-activate-key
     (fn [current-net _context-id arg-ids fallback-id]
       (let [[value-id condition-id out-id] (switch-ids arg-ids fallback-id)]
         (switch-messages current-net value-id condition-id out-id)))}))

(defn bi-sync-operator []
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

(defn- bind-default-tms-operators
  [compiler-env]
  ((requiring-resolve
    'propagators.compiler-2.tms/bind-distributed-tms-operators)
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
       (env/bind-at 'switch (switch-operator) 0)
       (env/bind-at 'p:cons (cons-operator) 0)
       (env/bind-at 'p:slot (slot-operator) 0)
       (env/bind-at 'p:car (accessor-operator :car obj/p:car "p:car") 0)
       (env/bind-at 'p:cdr (accessor-operator :cdr obj/p:cdr "p:cdr") 0)
       (env/bind-at 'cons (cons-operator) 0)
       (env/bind-at 'car (accessor-operator :car obj/p:car "car") 0)
       (env/bind-at 'cdr (accessor-operator :cdr obj/p:cdr "cdr") 0)
       (env/bind-at 'execute-sub-env (execute-sub-env-operator) 0)
       (env/bind-at '-> (sync-operator) 0)
       (env/bind-at '<-> (bi-sync-operator) 0))))

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
      (env/bind-at 'p:slot (slot-operator) 0)
      (env/bind-at 'execute-sub-env (execute-sub-env-operator) 0)
      (env/bind-at 'switch (switch-operator) 0)
      (env/bind-at '-> (sync-operator) 0)
      (env/bind-at '<-> (bi-sync-operator) 0)))

(defn behavior-tms-env []
  ((requiring-resolve
    'propagators.compiler-2.behavior/behavior-tms-env)))

(defn legacy-central-tms-env []
  ((requiring-resolve
    'propagators.compiler-2.legacy/legacy-central-tms-env)))
