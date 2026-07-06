(ns propagators.compiler-2.behavior.history
  "Compiler-2 behavior history and projection operators."
  (:refer-clojure :exclude [last])
  (:require [clojure.core :as core]
            [propagators.cells.value :as value]
            [propagators.compiler-2.operator-value :as operator-value]
            [propagators.datastructures.behavior.core :as behavior]
            [propagators.datastructures.behavior-algebra :as hist]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.dependency :as dependency]
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

(defn- record-time
  [record]
  (let [at (obj/slot-value record :at)]
    (if (some? at)
      at
      (obj/slot-value record :from))))

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
    :identities (behavior/identity-set source)
    :reducer (behavior/reducer-id source)}))

(defn- behavior-content-value
  [network behavior-id]
  (unwrap-compiler-value (net/network-cell-content network behavior-id)))

(defn- optional-output-selector
  [required-count]
  (fn [arg-ids fallback-id]
    [(or (nth (vec arg-ids) required-count nil) fallback-id)]))

(defn- input-selector
  [message required-count]
  (fn [arg-ids fallback-id _context-id]
    (let [arg-ids (vec arg-ids)
          out-id (or (nth arg-ids required-count nil) fallback-id)]
      (when-not (and out-id
                     (<= required-count (count arg-ids) (inc required-count))
                     (every? some? (subvec arg-ids 0 required-count)))
        (throw (ex-info message {:arg-ids arg-ids})))
      (subvec arg-ids 0 required-count))))

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

(defn- empty-latest-messages
  [out-id]
  [(message out-id
            (behavior/behavior-value
             {:history hist/empty-history
              :source-keys #{}
              :identities #{}
              :reducer behavior/latest-value-reducer-id}))])

(defn- latest-output-selector
  [arg-ids fallback-id]
  (let [arg-ids (vec arg-ids)]
    [(or (nth arg-ids 1 nil) fallback-id)]))

(defn- latest-input-selector
  [arg-ids _fallback-id _context-id]
  (let [arg-ids (vec arg-ids)]
    (when-not (<= 0 (count arg-ids) 2)
      (throw (ex-info "latest expects no args, behavior, or behavior plus output"
                      {:arg-ids arg-ids})))
    (if (empty? arg-ids) [] [(first arg-ids)])))

(defn latest-operator []
  (operator-value/propagator-operator
   {:name 'latest
    :output-selector latest-output-selector
    :input-selector latest-input-selector
    :activate (fn [network inputs outputs _context-id]
                (if (seq inputs)
                  (latest-messages network (first inputs) (first outputs))
                  (empty-latest-messages (first outputs))))}))

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
  (operator-value/propagator-operator
   {:name 'last
    :output-selector (optional-output-selector 2)
    :input-selector (input-selector "last expects behavior, index, and optional output" 2)
    :activate (fn [network inputs outputs _context-id]
                (let [[behavior-id index-id] inputs]
                  (last-messages network behavior-id index-id (first outputs))))}))

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
  (operator-value/propagator-operator
   {:name 'history
    :output-selector (optional-output-selector 3)
    :input-selector (input-selector "history expects behavior, start, end, and optional output" 3)
    :activate (fn [network inputs outputs _context-id]
                (let [[behavior-id start-id end-id] inputs]
                  (history-slice-messages slice-records
                                          network
                                          behavior-id
                                          [start-id end-id]
                                          (first outputs))))}))

(defn history-take-operator []
  (operator-value/propagator-operator
   {:name 'history-take
    :output-selector (optional-output-selector 2)
    :input-selector (input-selector "history-take expects behavior, count, and optional output" 2)
    :activate (fn [network inputs outputs _context-id]
                (let [[behavior-id count-id] inputs]
                  (history-slice-messages
                   (fn [records n] (take n records))
                   network behavior-id [count-id] (first outputs))))}))

(defn history-drop-operator []
  (operator-value/propagator-operator
   {:name 'history-drop
    :output-selector (optional-output-selector 2)
    :input-selector (input-selector "history-drop expects behavior, count, and optional output" 2)
    :activate (fn [network inputs outputs _context-id]
                (let [[behavior-id count-id] inputs]
                  (history-slice-messages
                   (fn [records n] (drop n records))
                   network behavior-id [count-id] (first outputs))))}))

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
  (operator-value/propagator-operator
   {:name 'history-split-at
    :output-selector (optional-output-selector 2)
    :input-selector (input-selector "history-split-at expects behavior, index, and optional output" 2)
    :activate (fn [network inputs outputs _context-id]
                (let [[behavior-id index-id] inputs]
                  (split-at-messages network behavior-id index-id (first outputs))))}))
