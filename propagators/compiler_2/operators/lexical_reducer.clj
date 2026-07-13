(ns propagators.compiler-2.operators.lexical-reducer
  "Reducer policies for retaining lexical declarations without selecting them."
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.reducer-cell :as reducer]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn- projection-net [tag project]
  (let [slots-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell slots-id)
               (nb/install-cell out-id))
        [_ n1] ((prop/construct-propagator
                 (fn [_ _ network]
                   [(message out-id
                             (project (net/network-cell-strongest network slots-id)))])
                 [slots-id]
                 [out-id])
                n0)]
    (-> n1
        (net/assoc-net-dict-entry :slots slots-id)
        (net/assoc-net-dict-entry :out out-id)
        (net/assoc-net-dict-entry :lexical/tag tag))))

(defn- merge-net []
  (let [content-id (ids/new-node-id)
        update-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell content-id)
               (nb/install-cell update-id)
               (nb/install-cell out-id))
        [_ n1] ((prop/construct-propagator
                 (fn [_ _ network]
                   (let [content (net/network-cell-strongest network content-id)
                         update (net/network-cell-strongest network update-id)
                         content (if (map? content) content {})
                         update (if (map? update) update {})
                         merged (reduce-kv
                                 (fn [m k v]
                                   (if (and (contains? m k) (not= (get m k) v))
                                     (reduced value/contradiction)
                                     (assoc m k v)))
                                 content
                                 update)]
                     [(message out-id merged)]))
                 [content-id update-id]
                 [out-id])
                n0)]
    (-> n1
        (net/assoc-net-dict-entry :content content-id)
        (net/assoc-net-dict-entry :update update-id)
        (net/assoc-net-dict-entry :out out-id))))

(def lexical-merge-net (merge-net))

(def lexical-strongest-net
  (projection-net
   :grouped
   (fn [slots]
     (if-not (map? slots)
       {}
       (->> slots
            vals
            (group-by :lexical/symbol)
            (reduce-kv (fn [m sym declarations]
                         (assoc m sym (vec (sort-by (comp pr-str :scope/source)
                                                   declarations))))
                       {}))))))

(defn binding-reducer [lineage]
  (reducer/reducer-cell lineage lexical-merge-net lexical-strongest-net))

(defn binding-update [lineage sym source binding]
  (reducer/reducer-slot-update
   lineage
   lexical-merge-net
   lexical-strongest-net
   [sym source]
   {:lexical/symbol sym
    :scope/source source
    :binding binding}))


