(ns propagators.compiler-2.helpers
  "Construction helpers and default operator environment for compile-2."
  (:refer-clojure :exclude [* + - /])
  (:require [clojure.core :as core]
            [propagators.cells.value :as value]
            [propagators.compiler-2.env :as env]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
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

(defn primitive-operator
  "Compile-2-local primitive wrapper that waits for partial inputs."
  [f]
  (fn [network arg-ids out-id]
    (let [[prop-id network']
          ((prop/construct-propagator
            (fn [_inputs _outputs current-net]
              (let [values (mapv #(scope-source/unwrap
                                    (net/network-cell-strongest current-net %))
                                 arg-ids)]
                (if (apply value/any-unusable-values? values)
                  []
                  [(message out-id (apply f values))])))
            arg-ids
            [out-id])
           network)]
      [network' [prop-id] out-id])))

(defn- bi-sync-operator []
  (fn [network arg-ids _out-id]
    (let [[a b] (vec arg-ids)]
      (when-not (and a b (= 2 (count arg-ids)))
        (throw (ex-info "<-> expects exactly two arguments" {:arg-ids arg-ids})))
      (let [[a->b network'] ((stdlib-prop/id a b) network)
            [b->a network''] ((stdlib-prop/id b a) network')]
        [network'' [a->b b->a] b]))))

(defn default-env []
  (-> (obj/empty-compound-object)
      (env/set-depth 0)
      (env/bind-at '+ (primitive-operator core/+) 0)
      (env/bind-at '- (primitive-operator core/-) 0)
      (env/bind-at '* (primitive-operator core/*) 0)
      (env/bind-at '/ (primitive-operator core//) 0)
      (env/bind-at 'switch
                   (primitive-operator
                    (fn [x enabled?]
                      (if enabled? x value/nothing)))
                   0)
      (env/bind-at '<-> (bi-sync-operator) 0)))
