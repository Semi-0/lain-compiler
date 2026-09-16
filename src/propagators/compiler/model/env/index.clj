(ns propagators.compiler.model.env.index
  (:require [propagators.infra.gur.flat :as fvm]
            [propagators.infra.ids :as ids]
            [propagators.infra.network :as net]))

(def lexical-topology-key ::lexical-topology)
(def lexical-topology-scope [:compiler-2 :lexical-topology])

(defn runtime-topology [network]
  (get (net/network-dict-entry network fvm/name-bindings-key)
       lexical-topology-scope {}))

(defn runtime-topology-value [network key]
  (get (runtime-topology network) key))

(defn declare-frame-addresses
  ([network env-id source-id chain-id]
   (declare-frame-addresses network env-id source-id chain-id nil))
  ([network env-id source-id chain-id parent-id]
   (net/update-net-dict-entry
    network lexical-topology-key
    #(cond-> (-> (or % {})
                 (assoc-in [:frames env-id :scope/source-id] source-id)
                 (assoc-in [:frames env-id :scope/chain-id] chain-id))
       parent-id (assoc-in [:frames env-id :parent-id] parent-id)))))

(defn declare-binding-address [network env-id sym binding-id]
  (net/update-net-dict-entry
   network lexical-topology-key
   #(-> (or % {})
        (update-in [:frames env-id :bindings sym] (fnil conj #{}) binding-id)
        (assoc-in [:frames env-id :current-bindings sym] binding-id))))

(defn reserve-binding-address [network env-id sym binding-id owner]
  (net/update-net-dict-entry
   network lexical-topology-key
   #(assoc-in (or % {}) [:reservations env-id sym]
              {:binding/id binding-id :reservation/owner owner})))

(defn- reservation-id [reservation]
  (if (ids/node-id? reservation) reservation (:binding/id reservation)))

(defn reserved-binding-id
  ([network env-id sym]
   (some-> (get-in (net/network-dict-entry network lexical-topology-key)
                   [:reservations env-id sym])
           reservation-id))
  ([network env-id sym owner]
   (let [reservation (get-in (net/network-dict-entry network lexical-topology-key)
                             [:reservations env-id sym])]
     (when (= owner (:reservation/owner reservation))
       (reservation-id reservation)))))

(defn consume-reserved-binding
  ([network env-id sym binding-id]
   (consume-reserved-binding network env-id sym binding-id nil))
  ([network env-id sym binding-id owner]
   (net/update-net-dict-entry
    network lexical-topology-key
    (fn [topology]
      (let [reservation (get-in topology [:reservations env-id sym])]
        (if (and (= binding-id (reservation-id reservation))
                 (or (nil? owner) (= owner (:reservation/owner reservation))))
          (update-in topology [:reservations env-id] dissoc sym)
          topology))))))

(defn- local-binding-ids [network env-id sym]
  (let [declared (get-in (net/network-dict-entry network lexical-topology-key)
                         [:frames env-id :bindings sym])
        runtime (->> (runtime-topology network)
                     (keep (fn [[key id]]
                             (when (and (= :binding (first key))
                                        (= env-id (second key))
                                        (= sym (nth key 2 nil)))
                               id)))
                     set)]
    (into (set declared) runtime)))

(defn local-binding-id [network env-id sym]
  (or (get-in (net/network-dict-entry network lexical-topology-key)
              [:frames env-id :current-bindings sym])
      (runtime-topology-value network [:current-binding env-id sym])
      (let [ids (local-binding-ids network env-id sym)]
        (when (= 1 (count ids)) (first ids)))))

(defn lexical-binding-status [network sym env-id]
  (let [frames (:frames (net/network-dict-entry network lexical-topology-key))]
    (loop [frame-id env-id seen #{}]
      (when-not (contains? seen frame-id)
        (let [{:keys [parent-id] :as frame} (get frames frame-id)
              known? (or frame
                         (runtime-topology-value network
                                                 [:frame frame-id :scope/source-id])
                         (runtime-topology-value network
                                                 [:frame frame-id :scope/chain-id]))
              parent-id (or parent-id
                            (runtime-topology-value network
                                                    [:frame frame-id :parent-id]))
              bound-id (local-binding-id network frame-id sym)
              bound-ids (local-binding-ids network frame-id sym)]
          (cond
            bound-id {:status :found :binding/id bound-id}
            (seq bound-ids) {:status :ambiguous}
            parent-id (recur parent-id (conj seen frame-id))
            known? {:status :missing}
            :else {:status :unknown}))))))

(defn lexical-binding-id [network sym env-id]
  (let [{:keys [status binding/id]} (lexical-binding-status network sym env-id)]
    (when (= :found status) id)))

(defn binding-names [network]
  (let [frames (:frames (net/network-dict-entry network lexical-topology-key))]
    (into {}
          (mapcat (fn [[_ {:keys [bindings]}]]
                    (for [[sym ids] bindings id ids] [id sym])))
          frames)))

(defn lexical-topology-effects [network]
  (let [{:keys [frames]} (net/network-dict-entry network lexical-topology-key)]
    (vec
     (mapcat
      (fn [[env-id {:keys [scope/source-id scope/chain-id parent-id bindings
                           current-bindings]}]]
        (concat
         (keep identity
               [(when source-id
                  (fvm/bind-name lexical-topology-scope
                                 [:frame env-id :scope/source-id] source-id))
                (when chain-id
                  (fvm/bind-name lexical-topology-scope
                                 [:frame env-id :scope/chain-id] chain-id))
                (when parent-id
                  (fvm/bind-name lexical-topology-scope
                                 [:frame env-id :parent-id] parent-id))])
         (for [[sym ids] bindings id ids]
           (fvm/bind-name lexical-topology-scope [:binding env-id sym id] id))
         (for [[sym id] current-bindings]
           (fvm/bind-name lexical-topology-scope
                          [:current-binding env-id sym] id))))
      frames))))
