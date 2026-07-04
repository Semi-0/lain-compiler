(ns propagators.compiler-2.operator-value
  "Explicit compiler-2 operator closures.

  These values replace env-bound primitive functions that previously carried
  compiler hooks in Clojure metadata. User-authored network closures remain
  `propagators.compiler-2.closure-value` data and are evaluated by
  `propagators.compiler-2.application`.
  "
  (:require [propagators.cells.value :as value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def operator-kind :compiler-2/operator-closure)

(def kind-slot :operator/kind)
(def input-selector-slot :operator/input-selector)
(def install-slot :operator/install)
(def activate-slot :operator/activate)
(def output-selector-slot :operator/output-selector)
(def contextual?-slot :operator/contextual?)
(def name-slot :operator/name)

(defn operator-closure
  [{:keys [input-selector install activate output-selector contextual? name]}]
  (obj/compound-object
   (cond-> {kind-slot operator-kind
            activate-slot activate
            contextual?-slot (true? contextual?)}
     input-selector (assoc input-selector-slot input-selector)
     install (assoc install-slot install)
     output-selector (assoc output-selector-slot output-selector)
     name (assoc name-slot name))))

(defn operator-closure?
  [x]
  (and (not (value/unusable? x))
       (not (value/contradiction? x))
       (= operator-kind (obj/slot-value x kind-slot))))

(defn operator-install [operator]
  (obj/slot-value operator install-slot))

(defn operator-activate [operator]
  (obj/slot-value operator activate-slot))

(defn operator-input-selector [operator]
  (obj/slot-value operator input-selector-slot))

(defn operator-output-selector [operator]
  (obj/slot-value operator output-selector-slot))

(defn operator-contextual? [operator]
  (true? (obj/slot-value operator contextual?-slot)))

(defn- output-ids
  [selected]
  (cond
    (nil? selected) []
    (vector? selected) selected
    (sequential? selected) (vec selected)
    :else [selected]))

(defn operator-output-ids
  [operator arg-ids fallback-id]
  (output-ids
   (if-let [select-output (operator-output-selector operator)]
     (select-output arg-ids fallback-id)
     [fallback-id])))

(defn operator-output-id
  [operator arg-ids fallback-id]
  (first (operator-output-ids operator arg-ids fallback-id)))

(defn- default-input-selector
  [arg-ids _fallback-id _context-id]
  (vec arg-ids))

(defn- default-output-selector
  [_arg-ids fallback-id]
  [fallback-id])

(defn operator-call
  [operator arg-ids fallback-id context-id]
  (let [select-inputs (or (operator-input-selector operator)
                          default-input-selector)
        inputs (vec (select-inputs arg-ids fallback-id context-id))
        outputs (operator-output-ids operator arg-ids fallback-id)]
    {:arg-ids (vec arg-ids)
     :inputs inputs
     :outputs outputs
     :out-id (first outputs)
     :context-id context-id}))

(defn propagator-operator
  "Construct an operator closure backed by `prop/construct-propagator`.

  The preferred protocol mirrors compiler-2 network closures: selectors compute
  input and output cells, then `activate` receives those cells and returns
  messages. The older `plan`/`messages` pair is kept as a temporary migration
  bridge for existing operators.
  "
  [{:keys [name
           input-selector
           output-selector
           activate
           plan
           messages
           contextual?]}]
  (let [select-output (or output-selector default-output-selector)
        select-input (or input-selector default-input-selector)
        call (fn [arg-ids fallback-id context-id]
               (if plan
                 (plan arg-ids fallback-id)
                 (let [inputs (vec (select-input arg-ids fallback-id context-id))
                       outputs (output-ids (select-output arg-ids fallback-id))]
                   {:arg-ids (vec arg-ids)
                    :inputs inputs
                    :outputs outputs
                    :out-id (first outputs)
                    :context-id context-id})))
        activate-call (fn [network call-map]
                        (if activate
                          (activate network
                                    (:inputs call-map)
                                    (:outputs call-map)
                                    (:context-id call-map))
                          (messages network call-map)))
        install (fn [network arg-ids fallback-id]
                  (let [{:keys [inputs outputs out-id] :as call-map}
                        (call arg-ids fallback-id nil)
                        [prop-id network']
                        ((prop/construct-propagator
                          (fn [_inputs _outputs current-net]
                            (activate-call current-net call-map))
                          inputs
                          outputs)
                         (reduce nb/ensure-cell network (concat inputs outputs)))]
                    [network' [prop-id] out-id]))
        activate (fn [network _context-id arg-ids fallback-id]
                   (activate-call
                    network
                    (call arg-ids fallback-id _context-id)))]
    (operator-closure
     {:name name
      :input-selector input-selector
      :install install
      :activate activate
      :output-selector select-output
      :contextual? contextual?})))
