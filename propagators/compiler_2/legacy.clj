(ns propagators.compiler-2.legacy
  "Legacy compiler-2 operators kept for compatibility."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.model.closure-value :as closure-value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.datastructures.tms.legacy :as tms]
            [propagators.message :refer [message message-id message-value]]))

(defn- activation-messages
  [ret]
  (vec (if (map? ret) (:messages ret) ret)))

(defn- closure-output-symbols
  [output]
  (cond
    (nil? output) []
    (symbol? output) [output]
    (vector? output) output
    :else []))

(defn- single-output-id
  [closure-info arg-ids out-id]
  (let [outputs (closure-output-symbols
                 (closure-value/closure-output closure-info))]
    (if (= 1 (count outputs))
      (peek (vec arg-ids))
      out-id)))

(defn- legacy-premise-closure-call-messages
  [closure-id closure-info premise storage-id network arg-ids out-id]
  (let [apply-messages (activation-messages
                        ((requiring-resolve
                          'propagators.compiler-2.runtime.application/closure-application-messages)
                         closure-id
                         nil
                         arg-ids
                         out-id
                         network))
        premise-out-id (single-output-id closure-info arg-ids out-id)
        output-value (or (some (fn [m]
                                 (when (= premise-out-id (message-id m))
                                   (message-value m)))
                               apply-messages)
                         (h/strongest-or-nothing network premise-out-id))]
    (cond-> apply-messages
      (and (not (value/unusable? output-value))
           (not (value/unusable? premise)))
      (conj (message storage-id
                     (tms/premise-update tms/reducer-id premise 0 true))
            (message storage-id
                     (tms/claim-update
                      (tms/claim [:premise-closure premise premise-out-id]
                                 :answer
                                 output-value
                                 [(tms/support premise
                                               [:compiler-2/premise-closure
                                                premise-out-id]
                                               :premise-closure)])))))))

(defn- legacy-premise-closure-value
  [closure-id closure-value premise storage-id]
  (with-meta
    {:compiler-2/operator :premise-closure
     :closure-id closure-id
     :closure closure-value
     :premise premise
     :storage-id storage-id}
    {h/application-activate-key
     (fn [network _context-id arg-ids out-id]
       (legacy-premise-closure-call-messages closure-id
                                            closure-value
                                            premise
                                            storage-id
                                            network
                                            arg-ids
                                            out-id))}))

(defn legacy-premise-closure-operator []
  (with-meta
    (fn [network _arg-ids out-id]
      [network [] out-id])
    {h/application-activate-key
     (fn [network _context-id arg-ids out-id]
       (let [[closure-id premise-id storage-id] (vec arg-ids)]
         (when-not (and closure-id premise-id storage-id (= 3 (count arg-ids)))
           (throw (ex-info "premise-closure expects closure, premise, and tms storage"
                           {:arg-ids arg-ids})))
         (let [closure-value (h/strongest-or-nothing network closure-id)
               premise (h/strongest-or-nothing network premise-id)]
           (if (or (value/unusable? closure-value)
                   (value/unusable? premise))
             []
             [(message out-id
                       (legacy-premise-closure-value closure-id
                                                    closure-value
                                                    premise
                                                    storage-id))]))))}))

(defn bind-legacy-central-tms-operators
  [compiler-env]
  (env/bind-at compiler-env 'premise-closure (legacy-premise-closure-operator) 0))

(defn legacy-central-tms-env []
  (bind-legacy-central-tms-operators (h/default-env)))
