(ns propagators.compiler.model.operator-value
  "Inspectable Compiler 2 primitive declarations exposed as GUR callables."
  (:require [propagators.infra.cells.value :as value]
            [propagators.compiler.lowering.application :as application]
            [propagators.infra.datastructures.compound-object :as obj]
            [propagators.infra.gur.flat :as fvm]
            [propagators.infra.network-builder :as nb]
            [propagators.infra.propagator :as prop]))

(def operator-kind :compiler-2/operator-closure)

(def kind-slot :operator/kind)
(def input-selector-slot :operator/input-selector)
(def install-slot :operator/install)
(def static-installer-slot :operator/static-installer)
(def compiler-activate-slot :operator/compiler-activate)
(def direct-installer-slot :operator/direct-installer)
(def direct-compiler-slot :operator/direct-compiler)
(def activate-slot :operator/activate)
(def output-selector-slot :operator/output-selector)
(def contextual?-slot :operator/contextual?)
(def name-slot :operator/name)

(defn operator-declaration
  [operator]
  (application/callable-declaration operator))

(defn- assoc-when
  [m key candidate]
  (cond
    (some? candidate)
    (assoc m key candidate)

    :else
    m))

(defn- declaration
  [{:keys [input-selector install static-installer direct-installer direct-compiler
           activate compiler-activate output-selector contextual? name]}]
  (-> {kind-slot operator-kind
       contextual?-slot (true? contextual?)}
      (assoc-when input-selector-slot input-selector)
      (assoc-when install-slot install)
      (assoc-when static-installer-slot static-installer)
      (assoc-when direct-installer-slot direct-installer)
      (assoc-when direct-compiler-slot direct-compiler)
      (assoc-when activate-slot activate)
      (assoc-when compiler-activate-slot compiler-activate)
      (assoc-when output-selector-slot output-selector)
      (assoc-when name-slot name)
      obj/compound-object))

(defn operator-install [operator]
  (obj/slot-value (operator-declaration operator) install-slot))

(defn operator-static-installer [operator]
  (obj/slot-value (operator-declaration operator) static-installer-slot))

(defn operator-compiler-activate [operator]
  (obj/slot-value (operator-declaration operator) compiler-activate-slot))

(defn operator-direct-installer [operator]
  (obj/slot-value (operator-declaration operator) direct-installer-slot))

(defn operator-direct-compiler [operator]
  (obj/slot-value (operator-declaration operator) direct-compiler-slot))

(defn operator-activate [operator]
  (obj/slot-value (operator-declaration operator) activate-slot))

(defn operator-input-selector [operator]
  (obj/slot-value (operator-declaration operator) input-selector-slot))

(defn operator-output-selector [operator]
  (obj/slot-value (operator-declaration operator) output-selector-slot))

(defn operator-contextual? [operator]
  (true? (obj/slot-value (operator-declaration operator) contextual?-slot)))

(defn operator-name [operator]
  (obj/slot-value (operator-declaration operator) name-slot))

(defn operator-closure?
  [candidate]
  (let [candidate (operator-declaration candidate)]
    (and (not (value/unusable? candidate))
         (not (value/contradiction? candidate))
         (= operator-kind (obj/slot-value candidate kind-slot)))))

(defn- output-ids
  [selected]
  (cond
    (nil? selected) []
    (vector? selected) selected
    (sequential? selected) (vec selected)
    :else [selected]))

(defn operator-output-ids
  [operator arg-ids fallback-id]
  (let [select-output (operator-output-selector operator)]
    (cond
      (fn? select-output)
      (output-ids (select-output arg-ids fallback-id))

      :else
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

(defn- activation-installer
  [operator prepare-network activate]
  (fn [network arg-ids fallback-id context-id]
    (let [{:keys [inputs outputs out-id] :as call}
          (operator-call operator arg-ids fallback-id context-id)
          prepared (reduce nb/ensure-cell network (concat inputs outputs))
          prepared
          (cond
            (fn? prepare-network)
            (prepare-network prepared call)

            :else
            prepared)
          [prop-id installed]
          ((prop/construct-propagator
            (or (operator-name operator) :compiler-2/propagator-operator)
            (fn [_inputs _outputs current-net]
              (activate current-net call))
            inputs
            outputs)
           prepared)]
      [installed [prop-id] out-id])))

(defn- declaration-installer
  [operator application-installer]
  (let [install (operator-install operator)
        static-installer (operator-static-installer operator)
        direct-installer (operator-direct-installer operator)
        direct-compiler (operator-direct-compiler operator)
        activate (operator-activate operator)
        compiler-activate (operator-compiler-activate operator)]
    (cond
      (fn? application-installer)
      application-installer

      (fn? install)
      (fn [network arg-ids out-id _context-id]
        (install network arg-ids out-id))

      (fn? static-installer)
      (fn [network arg-ids out-id _context-id]
        (static-installer network arg-ids out-id))

      (fn? activate)
      (activation-installer
       operator
       nil
       (fn [network call]
         (activate network
                   (:context-id call)
                   (:arg-ids call)
                   (:out-id call))))

      (fn? compiler-activate)
      (activation-installer
       operator
       nil
       (fn [network call]
         (compiler-activate
          nil network (:context-id call) (:arg-ids call) (:out-id call))))

      (or (fn? direct-installer) (fn? direct-compiler))
      (fn [_network arg-ids out-id _context-id]
        (throw
         (ex-info "Direct compiler operator cannot be applied at runtime"
                  {:operator (operator-name operator)
                   :argument-ids (vec arg-ids)
                   :result-id out-id})))

      :else
      (throw
       (ex-info "Compiler 2 operator has no application behavior"
                {:operator operator})))))

(defn operator-closure
  [{:keys [name application-installer] :as options}]
  (let [operator (declaration options)
        declaration-id
        (fvm/stable-node-id [:compiler-2/operator (or name :anonymous)])
        installer (declaration-installer operator application-installer)]
    (application/primitive-callable
     [:compiler-2/operator (or name :anonymous)]
     declaration-id
     installer
     operator)))

(defn propagator-operator
  "Build an inspectable primitive declaration and a canonical GUR callable."
  [{:keys [name
           input-selector
           output-selector
           compiler-activate
           activate
           plan
           messages
           prepare-network
           contextual?]}]
  (let [select-output (or output-selector default-output-selector)
        select-input (or input-selector default-input-selector)
        call
        (fn [arg-ids fallback-id context-id]
          (cond
            (fn? plan)
            (assoc (plan arg-ids fallback-id) :context-id context-id)

            :else
            (let [inputs (vec (select-input arg-ids fallback-id context-id))
                  outputs (output-ids (select-output arg-ids fallback-id))]
              {:arg-ids (vec arg-ids)
               :inputs inputs
               :outputs outputs
               :out-id (first outputs)
               :context-id context-id})))
        activate-call
        (fn [network call-map]
          (cond
            (fn? activate)
            (activate network
                      (:inputs call-map)
                      (:outputs call-map)
                      (:context-id call-map))

            (fn? messages)
            (messages network call-map)

            :else
            (throw
             (ex-info "Propagator operator has no activation"
                      {:name name :call call-map}))))
        application-installer
        (fn [network arg-ids fallback-id context-id]
          (let [{:keys [inputs outputs out-id] :as call-map}
                (call arg-ids fallback-id context-id)
                prepared (reduce nb/ensure-cell network (concat inputs outputs))
                prepared
                (cond
                  (fn? prepare-network)
                  (prepare-network prepared call-map)

                  :else
                  prepared)
                [prop-id installed]
                ((prop/construct-propagator
                  (or name :compiler-2/propagator-operator)
                  (fn [_inputs _outputs current-net]
                    (activate-call current-net call-map))
                  inputs
                  outputs)
                 prepared)]
            [installed [prop-id] out-id]))
        compatible-install
        (fn [network arg-ids fallback-id]
          (application-installer network arg-ids fallback-id nil))]
    (operator-closure
     {:name name
      :input-selector input-selector
      :install compatible-install
      :static-installer compatible-install
      :compiler-activate compiler-activate
      :activate
      (fn [network context-id arg-ids fallback-id]
        (activate-call network (call arg-ids fallback-id context-id)))
      :output-selector select-output
      :contextual? contextual?
      :application-installer application-installer})))

(defn canonical-callable
  "Normalize one environment binding into Compiler 2's callable protocol."
  [name candidate]
  (let [metadata (meta candidate)
        activate
        (:propagators.compiler.compiler.basis/application-activate metadata)
        output-selector
        (:propagators.compiler.compiler.basis/output-selector metadata)
        contextual?
        (true?
         (:propagators.compiler.compiler.basis/contextual-operator metadata))]
    (cond
      (application/compiler-callable? candidate)
      candidate

      (and (fn? candidate) (fn? activate))
      (operator-closure
       {:name name
        :activate activate
        :output-selector output-selector
        :contextual? contextual?})

      (fn? candidate)
      (operator-closure
       {:name name
        :install candidate
        :output-selector output-selector
        :contextual? contextual?})

      :else
      candidate)))
