(ns propagators.compiler-2.behavior
  "Compiler-2 behavior operators."
  (:refer-clojure :exclude [* + - /])
  (:require [clojure.core :as core]
            [propagators.cells.value :as value]
            [propagators.compiler-2.closure-value :as closure-value]
            [propagators.compiler-2.env :as env]
            [propagators.compiler-2.helpers :as h]
            [propagators.compiler-2.reducer :as compiler-reducer]
            [propagators.compiler-2.tms :as compiler-tms]
            [propagators.datastructures.behavior.core :as behavior]
            [propagators.datastructures.behavior.arithmetic :as behavior-arithmetic]
            [propagators.datastructures.behavior-algebra :as hist]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.reducer-subnet :as reducer]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.datastructures.tms.distributed :as tms]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(defn- unwrap-compiler-value
  [v]
  (-> v
      scope-source/unwrap
      tms/distributed-base-value
      dependency/unwrap))

(defn distributed-behavior-operator
  "TMS-composed behavior wrapper; plain behavior arithmetic remains in helpers."
  [op f]
  (with-meta
    (fn [network arg-ids out-id]
      (let [[prop-id network']
            ((apply (behavior-arithmetic/distributed-behavior-propagator op f)
                    (conj (vec arg-ids) out-id))
             network)]
        [network' [prop-id] out-id]))
    {h/application-activate-key
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
    {h/application-activate-key
     (fn [current-net _context-id arg-ids out-id]
       (behavior-arithmetic/distributed-behavior-stable-messages
        op
        f
        arg-ids
        out-id
        current-net))}))

(defn- behavior-point-messages
  [network at-id value-id out-id]
  (let [at (unwrap-compiler-value (net/network-cell-strongest network at-id))
        v (unwrap-compiler-value (net/network-cell-strongest network value-id))]
    (cond
      (or (value/contradiction? at)
          (value/contradiction? v))
      [(message out-id value/contradiction)]

      (or (value/nothing? at)
          (value/nothing? v))
      []

      :else
      [(message out-id
                (behavior/behavior-value
                 {:history (hist/records->history [(hist/point-record at v)])
                  :source-keys #{[:compiler-2/behavior-point out-id at]}
                  :reducer behavior/event-history-reducer-id}))])))

(defn behavior-point-operator []
  (with-meta
    (fn [network arg-ids fallback-id]
      (let [[at-id value-id explicit-out-id] (vec arg-ids)
            out-id (or explicit-out-id fallback-id)]
        (when-not (and at-id value-id out-id (<= 2 (count arg-ids) 3))
          (throw (ex-info "behavior-point expects time, value, and optional output"
                          {:arg-ids arg-ids})))
        (let [[prop-id network']
              ((prop/construct-propagator
                (fn [_inputs _outputs current-net]
                  (behavior-point-messages current-net at-id value-id out-id))
                [at-id value-id]
                [out-id])
               network)]
          [network' [prop-id] out-id])))
    {h/output-selector-key
     (fn [arg-ids fallback-id]
       (or (nth (vec arg-ids) 2 nil) fallback-id))
     h/application-activate-key
     (fn [network _context-id arg-ids fallback-id]
       (let [[at-id value-id explicit-out-id] (vec arg-ids)
             out-id (or explicit-out-id fallback-id)]
         (when-not (and at-id value-id out-id (<= 2 (count arg-ids) 3))
           (throw (ex-info "behavior-point expects time, value, and optional output"
                           {:arg-ids arg-ids})))
         (behavior-point-messages network at-id value-id out-id)))}))

(defn- event-source-messages
  [network at-id value-id out-id]
  (let [at (unwrap-compiler-value (net/network-cell-strongest network at-id))
        v (unwrap-compiler-value (net/network-cell-strongest network value-id))]
    (cond
      (or (value/contradiction? at)
          (value/contradiction? v))
      [(message out-id value/contradiction)]

      (or (value/nothing? at)
          (value/nothing? v))
      []

      (not (integer? at))
      [(message out-id value/contradiction)]

      :else
      [(message out-id (obj/compound-object {at v}))])))

(defn behavior-event-operator []
  (with-meta
    (fn [network arg-ids fallback-id]
      (let [[at-id value-id explicit-out-id] (vec arg-ids)
            out-id (or explicit-out-id fallback-id)]
        (when-not (and at-id value-id out-id (<= 2 (count arg-ids) 3))
          (throw (ex-info "behavior-event expects time, value, and optional source"
                          {:arg-ids arg-ids})))
        (let [[prop-id network']
              ((prop/construct-propagator
                (fn [_inputs _outputs current-net]
                  (event-source-messages current-net at-id value-id out-id))
                [at-id value-id]
                [out-id])
               network)]
          [network' [prop-id] out-id])))
    {h/output-selector-key
     (fn [arg-ids fallback-id]
       (or (nth (vec arg-ids) 2 nil) fallback-id))
     h/application-activate-key
     (fn [network _context-id arg-ids fallback-id]
       (let [[at-id value-id explicit-out-id] (vec arg-ids)
             out-id (or explicit-out-id fallback-id)]
         (when-not (and at-id value-id out-id (<= 2 (count arg-ids) 3))
           (throw (ex-info "behavior-event expects time, value, and optional source"
                           {:arg-ids arg-ids})))
         (event-source-messages network at-id value-id out-id)))}))

(defn- event-update-tick
  [slot]
  (cond
    (integer? slot) slot
    (and (vector? slot)
         (= :behavior/event (first slot))
         (integer? (second slot))) (second slot)))

(defn- state-from-events
  [events retained-events]
  (behavior/history-state
   events
   (into {}
         (map (fn [[tick v]] [tick (behavior/point-event tick v)]))
         retained-events)))

(defn- event-history-state
  [events]
  (state-from-events events (sort-by key events)))

(defn- add-event-state
  [acc update]
  (let [tick (some-> update :slot event-update-tick)
        v (:value update)
        events (behavior/state-events acc)]
    (cond
      (or (nil? tick)
          (not (map? events)))
      value/contradiction

      (and (contains? events tick)
           (not= (get events tick) v))
      value/contradiction

      :else
      (event-history-state (assoc events tick v)))))

(defn- add-event-state-messages
  [network acc-id update-id out-id]
  (let [acc (unwrap-compiler-value (net/network-cell-strongest network acc-id))
        update (unwrap-compiler-value (net/network-cell-strongest network update-id))]
    (cond
      (or (value/contradiction? acc)
          (value/contradiction? update))
      [(message out-id value/contradiction)]

      (or (value/nothing? acc)
          (value/nothing? update))
      []

      :else
      [(message out-id (add-event-state acc update))])))

(defn behavior-add-event-operator []
  (with-meta
    (fn [network arg-ids out-id]
      (let [[acc-id update-id explicit-out-id] (vec arg-ids)
            out-id (or explicit-out-id out-id)]
        (when-not (and acc-id update-id out-id (<= 2 (count arg-ids) 3))
          (throw (ex-info "behavior-add-event expects acc, update, and optional output"
                          {:arg-ids arg-ids})))
        (let [[prop-id network']
              ((prop/construct-propagator
                (fn [_inputs _outputs current-net]
                  (add-event-state-messages current-net acc-id update-id out-id))
                [acc-id update-id]
                [out-id])
               network)]
          [network' [prop-id] out-id])))
    {h/output-selector-key
     (fn [arg-ids fallback-id]
       (or (nth (vec arg-ids) 2 nil) fallback-id))
     h/application-activate-key
     (fn [network _context-id arg-ids fallback-id]
       (let [[acc-id update-id explicit-out-id] (vec arg-ids)
             out-id (or explicit-out-id fallback-id)]
         (when-not (and acc-id update-id out-id (<= 2 (count arg-ids) 3))
           (throw (ex-info "behavior-add-event expects acc, update, and optional output"
                           {:arg-ids arg-ids})))
         (add-event-state-messages network acc-id update-id out-id)))}))

(defn- state-events-messages
  [network state-id out-id]
  (let [state (unwrap-compiler-value (net/network-cell-strongest network state-id))]
    (cond
      (value/nothing? state) []
      (value/contradiction? state) [(message out-id value/contradiction)]
      :else [(message out-id (behavior/state-events state))])))

(defn behavior-state-events-operator []
  (with-meta
    (fn [network arg-ids out-id]
      (let [[state-id explicit-out-id] (vec arg-ids)
            out-id (or explicit-out-id out-id)]
        (when-not (and state-id out-id (<= 1 (count arg-ids) 2))
          (throw (ex-info "behavior-state-events expects state and optional output"
                          {:arg-ids arg-ids})))
        (let [[prop-id network']
              ((prop/construct-propagator
                (fn [_inputs _outputs current-net]
                  (state-events-messages current-net state-id out-id))
                [state-id]
                [out-id])
               network)]
          [network' [prop-id] out-id])))
    {h/output-selector-key
     (fn [arg-ids fallback-id]
       (or (nth (vec arg-ids) 1 nil) fallback-id))
     h/application-activate-key
     (fn [network _context-id arg-ids fallback-id]
       (let [[state-id explicit-out-id] (vec arg-ids)
             out-id (or explicit-out-id fallback-id)]
         (when-not (and state-id out-id (<= 1 (count arg-ids) 2))
           (throw (ex-info "behavior-state-events expects state and optional output"
                           {:arg-ids arg-ids})))
         (state-events-messages network state-id out-id)))}))

(defn- update-field-messages
  [field network update-id out-id]
  (let [update (unwrap-compiler-value (net/network-cell-strongest network update-id))]
    (cond
      (value/nothing? update) []
      (value/contradiction? update) [(message out-id value/contradiction)]
      :else
      (case field
        :tick [(message out-id (event-update-tick (:slot update)))]
        :value [(message out-id (:value update))]))))

(defn behavior-update-field-operator [field name]
  (with-meta
    (fn [network arg-ids out-id]
      (let [[update-id explicit-out-id] (vec arg-ids)
            out-id (or explicit-out-id out-id)]
        (when-not (and update-id out-id (<= 1 (count arg-ids) 2))
          (throw (ex-info (str name " expects update and optional output")
                          {:arg-ids arg-ids})))
        (let [[prop-id network']
              ((prop/construct-propagator
                (fn [_inputs _outputs current-net]
                  (update-field-messages field current-net update-id out-id))
                [update-id]
                [out-id])
               network)]
          [network' [prop-id] out-id])))
    {h/output-selector-key
     (fn [arg-ids fallback-id]
       (or (nth (vec arg-ids) 1 nil) fallback-id))
     h/application-activate-key
     (fn [network _context-id arg-ids fallback-id]
       (let [[update-id explicit-out-id] (vec arg-ids)
             out-id (or explicit-out-id fallback-id)]
         (when-not (and update-id out-id (<= 1 (count arg-ids) 2))
           (throw (ex-info (str name " expects update and optional output")
                           {:arg-ids arg-ids})))
         (update-field-messages field network update-id out-id)))}))

(defn- assoc-event-messages
  [network events-id tick-id value-id out-id]
  (let [events (unwrap-compiler-value (net/network-cell-strongest network events-id))
        tick (unwrap-compiler-value (net/network-cell-strongest network tick-id))
        v (unwrap-compiler-value (net/network-cell-strongest network value-id))]
    (cond
      (or (value/nothing? events)
          (value/nothing? tick)
          (value/nothing? v)) []

      (or (value/contradiction? events)
          (value/contradiction? tick)
          (value/contradiction? v)
          (not (integer? tick))
          (not (map? events))) [(message out-id value/contradiction)]

      (and (contains? events tick)
           (not= (get events tick) v)) [(message out-id value/contradiction)]

      :else [(message out-id (assoc events tick v))])))

(defn behavior-assoc-event-operator []
  (with-meta
    (fn [network arg-ids out-id]
      (let [[events-id tick-id value-id explicit-out-id] (vec arg-ids)
            out-id (or explicit-out-id out-id)]
        (when-not (and events-id tick-id value-id out-id
                       (<= 3 (count arg-ids) 4))
          (throw (ex-info "behavior-assoc-event expects events, tick, value, and optional output"
                          {:arg-ids arg-ids})))
        (let [[prop-id network']
              ((prop/construct-propagator
                (fn [_inputs _outputs current-net]
                  (assoc-event-messages current-net
                                        events-id
                                        tick-id
                                        value-id
                                        out-id))
                [events-id tick-id value-id]
                [out-id])
               network)]
          [network' [prop-id] out-id])))
    {h/output-selector-key
     (fn [arg-ids fallback-id]
       (or (nth (vec arg-ids) 3 nil) fallback-id))
     h/application-activate-key
     (fn [network _context-id arg-ids fallback-id]
       (let [[events-id tick-id value-id explicit-out-id] (vec arg-ids)
             out-id (or explicit-out-id fallback-id)]
         (when-not (and events-id tick-id value-id out-id
                        (<= 3 (count arg-ids) 4))
           (throw (ex-info "behavior-assoc-event expects events, tick, value, and optional output"
                           {:arg-ids arg-ids})))
         (assoc-event-messages network events-id tick-id value-id out-id)))}))

(defn- state-from-events-messages
  [network events-id out-id]
  (let [events (unwrap-compiler-value (net/network-cell-strongest network events-id))]
    (cond
      (value/nothing? events) []
      (or (value/contradiction? events)
          (not (map? events))) [(message out-id value/contradiction)]
      :else [(message out-id (event-history-state events))])))

(defn behavior-state-from-events-operator []
  (with-meta
    (fn [network arg-ids out-id]
      (let [[events-id explicit-out-id] (vec arg-ids)
            out-id (or explicit-out-id out-id)]
        (when-not (and events-id out-id (<= 1 (count arg-ids) 2))
          (throw (ex-info "behavior-state-from-events expects events and optional output"
                          {:arg-ids arg-ids})))
        (let [[prop-id network']
              ((prop/construct-propagator
                (fn [_inputs _outputs current-net]
                  (state-from-events-messages current-net events-id out-id))
                [events-id]
                [out-id])
               network)]
          [network' [prop-id] out-id])))
    {h/output-selector-key
     (fn [arg-ids fallback-id]
       (or (nth (vec arg-ids) 1 nil) fallback-id))
     h/application-activate-key
     (fn [network _context-id arg-ids fallback-id]
       (let [[events-id explicit-out-id] (vec arg-ids)
             out-id (or explicit-out-id fallback-id)]
         (when-not (and events-id out-id (<= 1 (count arg-ids) 2))
           (throw (ex-info "behavior-state-from-events expects events and optional output"
                           {:arg-ids arg-ids})))
         (state-from-events-messages network events-id out-id)))}))

(defn- retain-last-messages
  [network state-id n-id out-id]
  (let [state (unwrap-compiler-value (net/network-cell-strongest network state-id))
        n (unwrap-compiler-value (net/network-cell-strongest network n-id))]
    (cond
      (or (value/nothing? state)
          (value/nothing? n)) []
      (or (value/contradiction? state)
          (value/contradiction? n)
          (not (pos-int? n))) [(message out-id value/contradiction)]
      :else
      [(message out-id
                (state-from-events
                 (behavior/state-events state)
                 (take-last n (sort-by key (behavior/state-events state)))))])))

(defn behavior-retain-last-operator []
  (with-meta
    (fn [network arg-ids out-id]
      (let [[state-id n-id explicit-out-id] (vec arg-ids)
            out-id (or explicit-out-id out-id)]
        (when-not (and state-id n-id out-id (<= 2 (count arg-ids) 3))
          (throw (ex-info "behavior-retain-last expects state, n, and optional output"
                          {:arg-ids arg-ids})))
        (let [[prop-id network']
              ((prop/construct-propagator
                (fn [_inputs _outputs current-net]
                  (retain-last-messages current-net state-id n-id out-id))
                [state-id n-id]
                [out-id])
               network)]
          [network' [prop-id] out-id])))
    {h/output-selector-key
     (fn [arg-ids fallback-id]
       (or (nth (vec arg-ids) 2 nil) fallback-id))
     h/application-activate-key
     (fn [network _context-id arg-ids fallback-id]
       (let [[state-id n-id explicit-out-id] (vec arg-ids)
             out-id (or explicit-out-id fallback-id)]
         (when-not (and state-id n-id out-id (<= 2 (count arg-ids) 3))
           (throw (ex-info "behavior-retain-last expects state, n, and optional output"
                           {:arg-ids arg-ids})))
         (retain-last-messages network state-id n-id out-id)))}))

(defn behavior-empty-state-operator []
  (with-meta
    (fn [network arg-ids out-id]
      (when-not (empty? arg-ids)
        (throw (ex-info "behavior-empty-state expects no arguments"
                        {:arg-ids arg-ids})))
      (let [[prop-id network']
            ((prop/construct-propagator
              (fn [_inputs _outputs _current-net]
                [(message out-id (behavior/empty-history-state))])
              []
              [out-id])
             network)]
        [network' [prop-id] out-id]))
    {h/application-activate-key
     (fn [_network _context-id arg-ids out-id]
       (when-not (empty? arg-ids)
         (throw (ex-info "behavior-empty-state expects no arguments"
                         {:arg-ids arg-ids})))
       [(message out-id (behavior/empty-history-state))])}))

(defn- behavior-source-event-keys
  [source]
  (let [source* (obj/as-accessor-network source)
        slot-keys (if (obj/accessor-network? source*)
                    (obj/accessor-slot-keys source*)
                    (obj/public-slot-keys (obj/compound-object source)))]
    (set (keep event-update-tick slot-keys))))

(defn- valid-history-state?
  [state]
  (and (not (value/contradiction? state))
       (map? (behavior/state-events state))
       (not (value/contradiction? (behavior/state-history state)))))

(defn- behavior-closure-messages
  [network source-id closure-id init-id out-id]
  (let [source (h/strongest-or-nothing network source-id)
        closure-info (h/strongest-or-nothing network closure-id)
        init (h/strongest-or-nothing network init-id)]
    (cond
      (or (value/nothing? source)
          (value/nothing? closure-info)
          (value/nothing? init))
      []

      (or (value/contradiction? source)
          (value/contradiction? closure-info)
          (value/contradiction? init)
          (not (closure-value/closure-info? closure-info)))
      [(message out-id value/contradiction)]

      :else
      (if-let [merge-net (compiler-reducer/closure-merge-net
                          closure-id
                          closure-info
                          {:seed [:compiler-2/behavior-merge closure-id]
                           :reducer-id-key behavior/reducer-id-key
                           :reducer-id [:behavior.reducer/compiler-2-closure
                                        closure-id]})]
        (let [state (reducer/strongest
                     (reducer/reducer-subnet source merge-net init))
              source-keys* (behavior-source-event-keys source)
              folded-keys (set (keys (behavior/state-events state)))
              reducer-id (net/network-dict-entry merge-net behavior/reducer-id-key)]
          (cond
            (nil? reducer-id) [(message out-id value/contradiction)]
            (not (valid-history-state? state)) [(message out-id value/contradiction)]
            :else
            [(message out-id
                      (behavior/behavior-value
                       {:history (behavior/state-history state)
                        :source-keys (if (seq source-keys*)
                                       folded-keys
                                       #{})
                        :reducer reducer-id}))]))
        [(message out-id value/contradiction)]))))

(defn behavior-operator []
  (with-meta
    (fn [network arg-ids fallback-id]
      (let [[source-id closure-id init-id explicit-out-id] (vec arg-ids)
            out-id (or explicit-out-id fallback-id)]
        (when-not (and source-id closure-id init-id out-id
                       (<= 3 (count arg-ids) 4))
          (throw (ex-info "behavior expects source, reducer closure, init, and optional output"
                          {:arg-ids arg-ids})))
        [network [] out-id]))
    {h/output-selector-key
     (fn [arg-ids fallback-id]
       (or (nth (vec arg-ids) 3 nil) fallback-id))
     h/application-activate-key
     (fn [network _context-id arg-ids fallback-id]
       (let [[source-id closure-id init-id explicit-out-id] (vec arg-ids)
             out-id (or explicit-out-id fallback-id)]
         (when-not (and source-id closure-id init-id out-id
                        (<= 3 (count arg-ids) 4))
           (throw (ex-info "behavior expects source, reducer closure, init, and optional output"
                           {:arg-ids arg-ids})))
         (behavior-closure-messages network source-id closure-id init-id out-id)))}))

(defn behavior-cell-operator []
  (with-meta
    (fn [network arg-ids fallback-id]
      (let [[source-id init-id closure-id explicit-out-id] (vec arg-ids)
            out-id (or explicit-out-id fallback-id)]
        (when-not (and source-id init-id closure-id out-id
                       (<= 3 (count arg-ids) 4))
          (throw (ex-info "behavior-cell expects source, init, reducer closure, and optional output"
                          {:arg-ids arg-ids})))
        [network [] out-id]))
    {h/output-selector-key
     (fn [arg-ids fallback-id]
       (or (nth (vec arg-ids) 3 nil) fallback-id))
     h/application-activate-key
     (fn [network _context-id arg-ids fallback-id]
       (let [[source-id init-id closure-id explicit-out-id] (vec arg-ids)
             out-id (or explicit-out-id fallback-id)]
         (when-not (and source-id init-id closure-id out-id
                        (<= 3 (count arg-ids) 4))
           (throw (ex-info "behavior-cell expects source, init, reducer closure, and optional output"
                           {:arg-ids arg-ids})))
         (behavior-closure-messages network source-id closure-id init-id out-id)))}))

(defn- record-time
  [record]
  (let [at (obj/slot-value record :at)]
    (if (some? at)
      at
      (obj/slot-value record :from))))

(defn- record-value
  [record]
  (obj/slot-value record :value))

(defn- time-rank
  [t]
  (if (= :infinity t)
    Long/MAX_VALUE
    t))

(defn- sorted-history-records
  [behavior-value]
  (sort-by (comp time-rank record-time)
           (behavior/history-records behavior-value)))

(defn- history-behavior-value
  [source records]
  (behavior/behavior-value
   {:history (hist/records->history records)
    :source-keys (behavior/source-keys source)
    :reducer (behavior/reducer-id source)}))

(defn- behavior-content-value
  [network behavior-id]
  (unwrap-compiler-value (net/network-cell-content network behavior-id)))

(defn- latest-messages
  [network behavior-id out-id]
  (let [v (behavior-content-value network behavior-id)]
    (cond
      (value/nothing? v) []
      (value/contradiction? v) [(message out-id value/contradiction)]
      :else
      (let [records (vec (sorted-history-records v))
            selected (peek records)]
        (cond
          (nil? selected) []
          :else [(message out-id (history-behavior-value v [selected]))])))))

(defn latest-operator []
  (with-meta
    (fn [network arg-ids fallback-id]
      (let [[behavior-id explicit-out-id] (vec arg-ids)
            out-id (or explicit-out-id fallback-id)]
        (when-not (and behavior-id out-id (<= 1 (count arg-ids) 2))
          (throw (ex-info "latest expects behavior and optional output"
                          {:arg-ids arg-ids})))
        (let [[prop-id network']
              ((prop/construct-propagator
                (fn [_inputs _outputs current-net]
                  (latest-messages current-net behavior-id out-id))
                [behavior-id]
                [out-id])
               network)]
          [network' [prop-id] out-id])))
    {h/output-selector-key
     (fn [arg-ids fallback-id]
       (or (nth (vec arg-ids) 1 nil) fallback-id))
     h/application-activate-key
     (fn [network _context-id arg-ids fallback-id]
       (let [[behavior-id explicit-out-id] (vec arg-ids)
             out-id (or explicit-out-id fallback-id)]
         (when-not (and behavior-id out-id (<= 1 (count arg-ids) 2))
           (throw (ex-info "latest expects behavior and optional output"
                           {:arg-ids arg-ids})))
         (latest-messages network behavior-id out-id)))}))

(defn- last-messages
  [network behavior-id index-id out-id]
  (let [v (behavior-content-value network behavior-id)
        index (unwrap-compiler-value (net/network-cell-strongest network index-id))]
    (cond
      (or (value/nothing? v)
          (value/nothing? index)) []
      (or (value/contradiction? v)
          (value/contradiction? index)
          (not (nat-int? index))) [(message out-id value/contradiction)]
      :else
      (let [records (vec (sorted-history-records v))
            selected (nth records (core/- (count records) index 1) nil)]
        (cond
          (nil? selected) []
          :else [(message out-id (history-behavior-value v [selected]))])))))

(defn last-operator []
  (with-meta
    (fn [network arg-ids fallback-id]
      (let [[behavior-id index-id explicit-out-id] (vec arg-ids)
            out-id (or explicit-out-id fallback-id)]
        (when-not (and behavior-id index-id out-id (<= 2 (count arg-ids) 3))
          (throw (ex-info "last expects behavior, index, and optional output"
                          {:arg-ids arg-ids})))
        (let [[prop-id network']
              ((prop/construct-propagator
                (fn [_inputs _outputs current-net]
                  (last-messages current-net behavior-id index-id out-id))
                [behavior-id index-id]
                [out-id])
               network)]
          [network' [prop-id] out-id])))
    {h/output-selector-key
     (fn [arg-ids fallback-id]
       (or (nth (vec arg-ids) 2 nil) fallback-id))
     h/application-activate-key
     (fn [network _context-id arg-ids fallback-id]
       (let [[behavior-id index-id explicit-out-id] (vec arg-ids)
             out-id (or explicit-out-id fallback-id)]
         (when-not (and behavior-id index-id out-id (<= 2 (count arg-ids) 3))
           (throw (ex-info "last expects behavior, index, and optional output"
                           {:arg-ids arg-ids})))
         (last-messages network behavior-id index-id out-id)))}))

(defn- slice-records
  [records start end]
  (->> records
       (drop start)
       (take (max 0 (core/- end start)))))

(defn- history-slice-messages
  [slice-f network behavior-id arg-ids out-id]
  (let [v (behavior-content-value network behavior-id)
        args (mapv #(unwrap-compiler-value (net/network-cell-strongest network %))
                   arg-ids)]
    (cond
      (or (value/nothing? v)
          (some value/nothing? args)) []
      (or (value/contradiction? v)
          (some value/contradiction? args)
          (not-every? nat-int? args)) [(message out-id value/contradiction)]
      :else
      (let [records (vec (sorted-history-records v))]
        [(message out-id
                  (history-behavior-value v (apply slice-f records args)))]))))

(defn history-operator []
  (with-meta
    (fn [network arg-ids fallback-id]
      (let [[behavior-id start-id end-id explicit-out-id] (vec arg-ids)
            out-id (or explicit-out-id fallback-id)]
        (when-not (and behavior-id start-id end-id out-id
                       (<= 3 (count arg-ids) 4))
          (throw (ex-info "history expects behavior, start, end, and optional output"
                          {:arg-ids arg-ids})))
        (let [[prop-id network']
              ((prop/construct-propagator
                (fn [_inputs _outputs current-net]
                  (history-slice-messages slice-records
                                          current-net
                                          behavior-id
                                          [start-id end-id]
                                          out-id))
                [behavior-id start-id end-id]
                [out-id])
               network)]
          [network' [prop-id] out-id])))
    {h/output-selector-key
     (fn [arg-ids fallback-id]
       (or (nth (vec arg-ids) 3 nil) fallback-id))
     h/application-activate-key
     (fn [network _context-id arg-ids fallback-id]
       (let [[behavior-id start-id end-id explicit-out-id] (vec arg-ids)
             out-id (or explicit-out-id fallback-id)]
         (when-not (and behavior-id start-id end-id out-id
                        (<= 3 (count arg-ids) 4))
           (throw (ex-info "history expects behavior, start, end, and optional output"
                           {:arg-ids arg-ids})))
         (history-slice-messages slice-records
                                 network
                                 behavior-id
                                 [start-id end-id]
                                 out-id)))}))

(defn history-take-operator []
  (with-meta
    (fn [network arg-ids fallback-id]
      (let [[behavior-id count-id explicit-out-id] (vec arg-ids)
            out-id (or explicit-out-id fallback-id)]
        (when-not (and behavior-id count-id out-id (<= 2 (count arg-ids) 3))
          (throw (ex-info "history-take expects behavior, count, and optional output"
                          {:arg-ids arg-ids})))
        (let [[prop-id network']
              ((prop/construct-propagator
                (fn [_inputs _outputs current-net]
                  (history-slice-messages
                   (fn [records n] (take n records))
                   current-net behavior-id [count-id] out-id))
                [behavior-id count-id]
                [out-id])
               network)]
          [network' [prop-id] out-id])))
    {h/output-selector-key
     (fn [arg-ids fallback-id]
       (or (nth (vec arg-ids) 2 nil) fallback-id))
     h/application-activate-key
     (fn [network _context-id arg-ids fallback-id]
       (let [[behavior-id count-id explicit-out-id] (vec arg-ids)
             out-id (or explicit-out-id fallback-id)]
         (when-not (and behavior-id count-id out-id (<= 2 (count arg-ids) 3))
           (throw (ex-info "history-take expects behavior, count, and optional output"
                           {:arg-ids arg-ids})))
         (history-slice-messages
          (fn [records n] (take n records))
          network behavior-id [count-id] out-id)))}))

(defn history-drop-operator []
  (with-meta
    (fn [network arg-ids fallback-id]
      (let [[behavior-id count-id explicit-out-id] (vec arg-ids)
            out-id (or explicit-out-id fallback-id)]
        (when-not (and behavior-id count-id out-id (<= 2 (count arg-ids) 3))
          (throw (ex-info "history-drop expects behavior, count, and optional output"
                          {:arg-ids arg-ids})))
        (let [[prop-id network']
              ((prop/construct-propagator
                (fn [_inputs _outputs current-net]
                  (history-slice-messages
                   (fn [records n] (drop n records))
                   current-net behavior-id [count-id] out-id))
                [behavior-id count-id]
                [out-id])
               network)]
          [network' [prop-id] out-id])))
    {h/output-selector-key
     (fn [arg-ids fallback-id]
       (or (nth (vec arg-ids) 2 nil) fallback-id))
     h/application-activate-key
     (fn [network _context-id arg-ids fallback-id]
       (let [[behavior-id count-id explicit-out-id] (vec arg-ids)
             out-id (or explicit-out-id fallback-id)]
         (when-not (and behavior-id count-id out-id (<= 2 (count arg-ids) 3))
           (throw (ex-info "history-drop expects behavior, count, and optional output"
                           {:arg-ids arg-ids})))
         (history-slice-messages
          (fn [records n] (drop n records))
          network behavior-id [count-id] out-id)))}))

(defn- split-at-messages
  [network behavior-id index-id out-id]
  (let [v (behavior-content-value network behavior-id)
        index (unwrap-compiler-value (net/network-cell-strongest network index-id))]
    (cond
      (or (value/nothing? v)
          (value/nothing? index)) []
      (or (value/contradiction? v)
          (value/contradiction? index)
          (not (nat-int? index))) [(message out-id value/contradiction)]
      :else
      (let [records (vec (sorted-history-records v))
            [left right] (split-at index records)]
        [(message out-id
                  (obj/compound-object
                   {:left (history-behavior-value v left)
                    :right (history-behavior-value v right)}))]))))

(defn history-split-at-operator []
  (with-meta
    (fn [network arg-ids fallback-id]
      (let [[behavior-id index-id explicit-out-id] (vec arg-ids)
            out-id (or explicit-out-id fallback-id)]
        (when-not (and behavior-id index-id out-id (<= 2 (count arg-ids) 3))
          (throw (ex-info "history-split-at expects behavior, index, and optional output"
                          {:arg-ids arg-ids})))
        (let [[prop-id network']
              ((prop/construct-propagator
                (fn [_inputs _outputs current-net]
                  (split-at-messages current-net behavior-id index-id out-id))
                [behavior-id index-id]
                [out-id])
               network)]
          [network' [prop-id] out-id])))
    {h/output-selector-key
     (fn [arg-ids fallback-id]
       (or (nth (vec arg-ids) 2 nil) fallback-id))
     h/application-activate-key
     (fn [network _context-id arg-ids fallback-id]
       (let [[behavior-id index-id explicit-out-id] (vec arg-ids)
             out-id (or explicit-out-id fallback-id)]
         (when-not (and behavior-id index-id out-id (<= 2 (count arg-ids) 3))
           (throw (ex-info "history-split-at expects behavior, index, and optional output"
                           {:arg-ids arg-ids})))
         (split-at-messages network behavior-id index-id out-id)))}))

(defn bind-behavior-construction-operators
  [compiler-env]
  (-> compiler-env
      (env/bind-at 'behavior-point (behavior-point-operator) 0)
      (env/bind-at 'behavior-event (behavior-event-operator) 0)
      (env/bind-at 'behavior-empty-state (behavior-empty-state-operator) 0)
      (env/bind-at 'behavior-add-event (behavior-add-event-operator) 0)
      (env/bind-at 'behavior-state-events (behavior-state-events-operator) 0)
      (env/bind-at 'behavior-update-tick
                   (behavior-update-field-operator :tick "behavior-update-tick")
                   0)
      (env/bind-at 'behavior-update-value
                   (behavior-update-field-operator :value "behavior-update-value")
                   0)
      (env/bind-at 'behavior-assoc-event (behavior-assoc-event-operator) 0)
      (env/bind-at 'behavior-state-from-events
                   (behavior-state-from-events-operator)
                   0)
      (env/bind-at 'behavior-retain-last (behavior-retain-last-operator) 0)
      (env/bind-at 'behavior (behavior-operator) 0)
      (env/bind-at 'behavior-cell (behavior-cell-operator) 0)
      (env/bind-at 'latest (latest-operator) 0)
      (env/bind-at 'last (last-operator) 0)
      (env/bind-at 'history (history-operator) 0)
      (env/bind-at 'history-take (history-take-operator) 0)
      (env/bind-at 'history-drop (history-drop-operator) 0)
      (env/bind-at 'history-split-at (history-split-at-operator) 0)))

(defn bind-behavior-operators
  [compiler-env]
  (-> compiler-env
      (env/bind-at '+ (stable-distributed-behavior-operator :+ core/+) 0)
      (env/bind-at '- (stable-distributed-behavior-operator :- core/-) 0)
      (env/bind-at '* (stable-distributed-behavior-operator :* core/*) 0)
      (env/bind-at '/ (stable-distributed-behavior-operator :/ core//) 0)
      bind-behavior-construction-operators))

(defn behavior-tms-env []
  (-> (obj/empty-compound-object)
      (env/set-depth 0)
      bind-behavior-operators
      (env/bind-at 'p:slot (h/slot-operator) 0)
      (env/bind-at 'execute-sub-env (h/execute-sub-env-operator) 0)
      (env/bind-at 'switch (h/switch-operator) 0)
      (env/bind-at '-> (h/sync-operator) 0)
      compiler-tms/bind-distributed-tms-operators
      (env/bind-at '<-> (h/bi-sync-operator) 0)))
