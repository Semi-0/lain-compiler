(ns propagators.compiler-2.behavior
  "Compiler-2 behavior operators."
  (:refer-clojure :exclude [* + - /])
  (:require [clojure.core :as core]
            [propagators.cells.value :as value]
            [propagators.compiler-2.behavior.arithmetic :as compiler-behavior-arithmetic]
            [propagators.compiler-2.closure-value :as closure-value]
            [propagators.compiler-2.behavior.history :as behavior-history]
            [propagators.compiler-2.env :as env]
            [propagators.compiler-2.helpers :as h]
            [propagators.compiler-2.operator-value :as operator-value]
            [propagators.compiler-2.reducer :as compiler-reducer]
            [propagators.compiler-2.tms :as compiler-tms]
            [propagators.datastructures.behavior.core :as behavior]
            [propagators.datastructures.behavior-algebra :as hist]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.reducer-subnet :as reducer]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.datastructures.tms.distributed :as tms]
            [propagators.message :refer [message]]
            [propagators.network :as net]))

(defn- unwrap-compiler-value
  [v]
  (-> v
      scope-source/unwrap
      tms/distributed-base-value
      dependency/unwrap))

(def distributed-behavior-operator
  compiler-behavior-arithmetic/distributed-behavior-operator)

(def stable-distributed-behavior-operator
  compiler-behavior-arithmetic/stable-distributed-behavior-operator)

(defn- optional-output-selector
  [required-count]
  (fn [arg-ids fallback-id]
    (or (nth (vec arg-ids) required-count nil) fallback-id)))

(defn- optional-output-plan
  [name arg-ids fallback-id required-count]
  (let [arg-ids (vec arg-ids)
        out-id (or (nth arg-ids required-count nil) fallback-id)]
    (when-not (and out-id
                   (<= required-count (count arg-ids) (inc required-count))
                   (every? some? (subvec arg-ids 0 required-count)))
      (throw (ex-info name {:arg-ids arg-ids})))
    {:arg-ids arg-ids
     :inputs (subvec arg-ids 0 required-count)
     :outputs [out-id]
     :out-id out-id}))

(defn- behavior-propagator-operator
  [{:keys [name required-count messages]}]
  (operator-value/propagator-operator
   {:name name
    :output-selector (optional-output-selector required-count)
    :input-selector (fn [arg-ids fallback-id _context-id]
                      (:inputs (optional-output-plan
                                name
                                arg-ids
                                fallback-id
                                required-count)))
    :activate (fn [network inputs outputs _context-id]
                (messages network inputs (first outputs)))}))

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
  (behavior-propagator-operator
   {:name "behavior-point expects time, value, and optional output"
    :required-count 2
    :messages (fn [network [at-id value-id] out-id]
                (behavior-point-messages network at-id value-id out-id))}))

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
  (behavior-propagator-operator
   {:name "behavior-event expects time, value, and optional source"
    :required-count 2
    :messages (fn [network [at-id value-id] out-id]
                (event-source-messages network at-id value-id out-id))}))

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
  (behavior-propagator-operator
   {:name "behavior-add-event expects acc, update, and optional output"
    :required-count 2
    :messages (fn [network [acc-id update-id] out-id]
                (add-event-state-messages network acc-id update-id out-id))}))

(defn- state-events-messages
  [network state-id out-id]
  (let [state (unwrap-compiler-value (net/network-cell-strongest network state-id))]
    (cond
      (value/nothing? state) []
      (value/contradiction? state) [(message out-id value/contradiction)]
      :else [(message out-id (behavior/state-events state))])))

(defn behavior-state-events-operator []
  (behavior-propagator-operator
   {:name "behavior-state-events expects state and optional output"
    :required-count 1
    :messages (fn [network [state-id] out-id]
                (state-events-messages network state-id out-id))}))

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
  (behavior-propagator-operator
   {:name (str name " expects update and optional output")
    :required-count 1
    :messages (fn [network [update-id] out-id]
                (update-field-messages field network update-id out-id))}))

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
  (behavior-propagator-operator
   {:name "behavior-assoc-event expects events, tick, value, and optional output"
    :required-count 3
    :messages (fn [network [events-id tick-id value-id] out-id]
                (assoc-event-messages network events-id tick-id value-id out-id))}))

(defn- state-from-events-messages
  [network events-id out-id]
  (let [events (unwrap-compiler-value (net/network-cell-strongest network events-id))]
    (cond
      (value/nothing? events) []
      (or (value/contradiction? events)
          (not (map? events))) [(message out-id value/contradiction)]
      :else [(message out-id (event-history-state events))])))

(defn behavior-state-from-events-operator []
  (behavior-propagator-operator
   {:name "behavior-state-from-events expects events and optional output"
    :required-count 1
    :messages (fn [network [events-id] out-id]
                (state-from-events-messages network events-id out-id))}))

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
  (behavior-propagator-operator
   {:name "behavior-retain-last expects state, n, and optional output"
    :required-count 2
    :messages (fn [network [state-id n-id] out-id]
                (retain-last-messages network state-id n-id out-id))}))

(defn behavior-empty-state-operator []
  (operator-value/propagator-operator
   {:name "behavior-empty-state expects no arguments"
    :input-selector (fn [arg-ids _fallback-id _context-id]
                      (when-not (empty? arg-ids)
                        (throw (ex-info "behavior-empty-state expects no arguments"
                                        {:arg-ids arg-ids})))
                      [])
    :activate (fn [_network _inputs outputs _context-id]
                [(message (first outputs) (behavior/empty-history-state))])}))

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
  (operator-value/operator-closure
   {:name "behavior"
    :output-selector (optional-output-selector 3)
    :install (fn [network arg-ids fallback-id]
               (let [{:keys [out-id]}
                     (optional-output-plan
                      "behavior expects source, reducer closure, init, and optional output"
                      arg-ids
                      fallback-id
                      3)]
                 [network [] out-id]))
    :activate (fn [network _context-id arg-ids fallback-id]
                (let [{[source-id closure-id init-id] :inputs
                       :keys [out-id]}
                      (optional-output-plan
                       "behavior expects source, reducer closure, init, and optional output"
                       arg-ids
                       fallback-id
                       3)]
                  (behavior-closure-messages network source-id closure-id init-id out-id)))}))

(defn behavior-cell-operator []
  (operator-value/operator-closure
   {:name "behavior-cell"
    :output-selector (optional-output-selector 3)
    :install (fn [network arg-ids fallback-id]
               (let [{:keys [out-id]}
                     (optional-output-plan
                      "behavior-cell expects source, init, reducer closure, and optional output"
                      arg-ids
                      fallback-id
                      3)]
                 [network [] out-id]))
    :activate (fn [network _context-id arg-ids fallback-id]
                (let [{[source-id init-id closure-id] :inputs
                       :keys [out-id]}
                      (optional-output-plan
                       "behavior-cell expects source, init, reducer closure, and optional output"
                       arg-ids
                       fallback-id
                       3)]
                  (behavior-closure-messages network source-id closure-id init-id out-id)))}))

(def latest-operator behavior-history/latest-operator)
(def last-operator behavior-history/last-operator)
(def history-operator behavior-history/history-operator)
(def history-take-operator behavior-history/history-take-operator)
(def history-drop-operator behavior-history/history-drop-operator)
(def history-split-at-operator behavior-history/history-split-at-operator)

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
