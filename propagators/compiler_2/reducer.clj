(ns propagators.compiler-2.reducer
  "Compiler-2 closures adapted to reducer-subnet merge networks."
  (:require [propagators.compiler-2.closure-value :as closure-value]
            [propagators.compiler-2.helpers :as h]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn- output-symbols
  [output]
  (cond
    (nil? output) []
    (symbol? output) [output]
    (vector? output) output
    :else []))

(defn- reducer-arg-ids
  [closure-info acc-id update-id out-id]
  (let [inputs (closure-value/closure-inputs closure-info)
        outputs (output-symbols (closure-value/closure-output closure-info))]
    (cond
      (and (= 2 (count inputs)) (= 1 (count outputs)))
      [acc-id update-id out-id]

      (and (= 2 (count inputs)) (empty? outputs))
      [acc-id update-id])))

(defn closure-merge-net
  "Return a reducer merge-net for a compiler-2 closure.

  The closure receives `[acc next]`; internally reducer-subnet still uses
  `:update` for the second input cell."
  [closure-id closure-info {:keys [seed reducer-id-key reducer-id]}]
  (let [seed (or seed [:compiler-2/reducer closure-id])
        acc-id (h/stable-node-id seed :acc)
        update-id (h/stable-node-id seed :update)
        out-id (h/stable-node-id seed :out)
        closure-cell-id (h/stable-node-id seed :closure)
        args-id (h/stable-node-id seed :args)
        arg-ids (reducer-arg-ids closure-info acc-id update-id out-id)]
    (when arg-ids
      (let [n0 (-> net/empty-net
                   (nb/install-cell acc-id)
                   (nb/install-cell update-id)
                   (nb/install-cell out-id)
                   (nb/install-cell args-id)
                   (nb/install-cell closure-cell-id closure-info closure-info))
            [_prop-id n1] (((requiring-resolve
                             'propagators.compiler-2.application/p:apply-closure)
                            closure-cell-id
                            args-id
                            arg-ids
                            out-id)
                           n0)]
        (cond-> (-> n1
                    (net/assoc-net-dict-entry :acc acc-id)
                    (net/assoc-net-dict-entry :update update-id)
                    (net/assoc-net-dict-entry :out out-id))
          reducer-id-key
          (net/assoc-net-dict-entry reducer-id-key reducer-id))))))
