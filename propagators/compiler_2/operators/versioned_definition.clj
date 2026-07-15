(ns propagators.compiler-2.operators.versioned-definition
  "Stable public definition routers over monotone premise-versioned candidates."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.compiler.declarations :as declarations]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.model.closure-value :as closure-value]
            [propagators.compiler-2.model.context :as context]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.compiler-2.operators.block-premise :as premise]
            [propagators.compiler-2.runtime.topology-effects :as topology-effects]
            [propagators.datastructures.tms.distributed :as tms]
            [propagators.gur.flat :as fvm]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def candidates-key :compiler-2/versioned-definition-candidates)
(def registries-key :compiler-2/versioned-definition-registries)
(def calls-key :compiler-2/versioned-definition-calls)
(def diagnostics-key :compiler-2/versioned-definition-diagnostics)
(def registry-proposition :compiler-2/definition-candidate)
(def call-metadata-scope :compiler-2/versioned-definition-call-metadata)
(def diagnostic-scope :compiler-2/versioned-definition-diagnostics)

(declare definition-router-operator record-candidate caller-contexts)

(defn definition-id [block-id name] [block-id name])

(defn public-cell-id [definition-id]
  (h/stable-node-id :compiler-2 :versioned-definition definition-id :public))

(defn registry-cell-id [definition-id]
  (h/stable-node-id :compiler-2 :versioned-definition definition-id :registry))

(defn candidate-context [definition-id version]
  {:premise/id [:compiler-2/definition definition-id version]
   :premise/epoch version
   :premise/state-cell
   (h/stable-node-id :compiler-2 :versioned-definition
                     definition-id version :state)})

(defn candidate-id [definition-id version]
  [definition-id version])

(defn- candidate-contexts [_block-context candidate-context explicit-context]
  (cond-> #{candidate-context}
    explicit-context (conj explicit-context)))

(defn- form-value [network lexical-env form]
  (let [v (case (ast/type form)
            :literal (ast/value form)
            :symbol (when-let [id (env/resolve-binding-id network lexical-env
                                                          (ast/name form))]
                      (h/strongest-or-nothing network id))
            nil)]
    (when-not (value/unusable? v)
      (tms/distributed-base-value v))))

(defn- explicit-context [definition-id version network lexical-env explicit]
  (when explicit
    (let [premise-id (form-value network lexical-env (:premise-form explicit))
          epoch (form-value network lexical-env (:epoch-form explicit))]
      (when (and premise-id (number? epoch))
        {:premise/id premise-id
         :premise/epoch epoch
         :premise/state-cell
         (h/stable-node-id :compiler-2 :versioned-definition definition-id
                           version :explicit-premise-state)}))))

(defn candidate-claim-update
  ([candidate] (candidate-claim-update candidate nil))
  ([{candidate-id :candidate/id context :candidate/context :as candidate}
    state-content]
   (let [claim-content
         (tms/indexed-distributed-content
          {(tms/claim-slot-key candidate-id)
           (tms/claim candidate-id
                      registry-proposition
                      candidate
                      [(tms/support
                        (:premise/id context)
                        [:compiler-2/versioned-definition candidate-id]
                        :definition-version)])})]
     (if (tms/distributed-value? state-content)
       (tms/merge-distributed-content claim-content state-content)
       claim-content))))

(defn registry-candidates [network registry-id]
  (->> (net/network-cell-content network registry-id)
       tms/distributed-slots
       vals
       (filter tms/claim?)
       (filter #(= registry-proposition (tms/proposition %)))
       (mapv tms/claim-value)))

(defn- context-active? [network context]
  (let [content (net/network-cell-content network (:premise/state-cell context))]
    (and (tms/distributed-value? content)
         (contains? (-> content tms/distributed-slots tms/tms-view
                        tms/active-premises)
                    (:premise/id context)))))

(defn- candidate-active? [network candidate]
  (context-active? network (:candidate/context candidate)))

(defn p:definition-candidate
  [candidate-id callable-id candidate registry-id]
  (prop/construct-propagator
   (h/stable-node-id :compiler-2/versioned-definition-candidate candidate-id)
   :compiler-2/versioned-definition-candidate
   (fn [_inputs _outputs network]
     (if (or (value/unusable? (h/strongest-or-nothing network callable-id))
             (not (candidate-active? network candidate)))
       []
       [(message registry-id
                 (candidate-claim-update
                  candidate
                  (net/network-cell-content
                   network (:premise/state-cell (:candidate/context candidate)))))]))
   [callable-id (:premise/state-cell (:candidate/context candidate))]
   [registry-id]))

(defn- ensure-premise-context [network context]
  (h/ensure-cell network (:premise/state-cell context)))

(defn- public-definition-state
  [compile* state definition-id name public-id registry-id scalar?]
  (let [new? (not (contains? (net/net-env (:net state)) public-id))
        state (-> state
                  (update :net h/ensure-cell public-id)
                  (update :net h/ensure-cell registry-id))
        state (if new?
                (cond-> (declarations/declare-fixed-local state name public-id)
                  (not scalar?)
                  (update :net h/seed-cell public-id
                          (definition-router-operator compile*
                                                      definition-id
                                                      registry-id)))
                state)]
    state))

(defn- install-scalar-gate [state candidate source-id public-id]
  (let [[prop-id network]
        ((premise/p:block-premise
          [:versioned-scalar-definition (:candidate/id candidate)]
          source-id (:candidate/contexts candidate) public-id)
         (:net state))]
    (-> state
        (assoc :net network)
        (h/add-props [prop-id]))))

(defn declare-candidate
  "Publish one private definition candidate behind a stable public router.

  `binding` remains raw. Premise support is attached at the candidate output
  boundary, never by changing lexical lookup or wrapping the callable value."
  [compile* state name binding signature explicit]
  (let [block-context (:block/premise-context state)
        block-id (:block/id block-context)
        version (:premise/epoch block-context)
        definition-id (definition-id block-id name)
        public-id (public-cell-id definition-id)
        registry-id (registry-cell-id definition-id)
        version-context (candidate-context definition-id version)
        explicit-context (explicit-context definition-id version (:net state)
                                           (:env state) explicit)
        candidate-id (candidate-id definition-id version)
        scalar? (:scalar? signature)
        candidate {:candidate/id candidate-id
                   :candidate/definition-id definition-id
                   :candidate/public-cell public-id
                   :candidate/registry-cell registry-id
                   :candidate/callable-cell (env/binding-id binding)
                   :candidate/name name
                   :candidate/block-id block-id
                   :candidate/version version
                   :candidate/context version-context
                   :candidate/contexts
                   (candidate-contexts block-context version-context
                                       explicit-context)
                   :candidate/signature signature
                   :candidate/explicit (cond-> explicit
                                         explicit-context
                                         (assoc :premise-id
                                                (:premise/id explicit-context)
                                                :epoch
                                                (:premise/epoch explicit-context)))
                   :candidate/explicit-context explicit-context}
        prior (get (net/network-dict-entry (:net state) candidates-key)
                   candidate-id)
        _ (when prior
            (throw (ex-info "duplicate block-level definition"
                            {:definition-id definition-id
                             :version version
                             :name name})))
        state (-> (public-definition-state compile* state definition-id name
                                           public-id registry-id scalar?)
                  (update :net ensure-premise-context version-context)
                  (cond-> explicit-context
                    (update :net ensure-premise-context explicit-context)))
        [prop-id network]
        ((p:definition-candidate candidate-id (env/binding-id binding)
                                 candidate registry-id)
         (:net state))]
    [(cond-> (-> state
                 (assoc :net (record-candidate network candidate))
                 (h/add-props [prop-id]))
       scalar? (install-scalar-gate candidate (env/binding-id binding) public-id))
     (env/cell-binding public-id)]))

(defn- merge-results [left right]
  {:effects (into (vec (:effects left)) (:effects right))
   :messages (into (vec (:messages left)) (:messages right))})

(defn- private-output-id [call-id candidate-id index]
  (h/stable-node-id :compiler-2 :versioned-definition-call
                    call-id candidate-id :output index))

(defn- synthetic-application-id [call-id candidate-id]
  (h/stable-node-id :compiler-2 :versioned-definition-call
                    call-id candidate-id :application))

(defn placeholder-cell-id [call-id candidate-id kind index]
  (h/stable-node-id :compiler-2 :versioned-definition-call
                    call-id candidate-id :placeholder kind index))

(defn- normalized-call
  [{:candidate/keys [id signature]} caller-signature call-id arg-ids out-id]
  (let [{:keys [inputs outputs implicit?]} signature
        caller-signature (or caller-signature signature)
        caller-input-count (:inputs caller-signature)
        caller-output-count (if (:implicit? caller-signature)
                              0 (:outputs caller-signature))
        arg-ids (vec arg-ids)
        caller-required (+ caller-input-count caller-output-count)
        caller-inputs (subvec arg-ids 0
                              (min caller-input-count (count arg-ids)))
        caller-outputs* (if (:implicit? caller-signature)
                          [out-id]
                          (subvec arg-ids
                                  (min caller-input-count (count arg-ids))
                                  (min caller-required (count arg-ids))))
        supplied-inputs (subvec caller-inputs 0
                                (min inputs (count caller-inputs)))
        missing-inputs (range (count supplied-inputs) inputs)
        input-ids (into supplied-inputs
                        (map #(placeholder-cell-id call-id id :input %)
                             missing-inputs))
        supplied-outputs (if implicit?
                           []
                           (subvec (vec caller-outputs*) 0
                                   (min outputs (count caller-outputs*))))
        missing-outputs (range (count supplied-outputs) outputs)
        caller-outputs (if implicit?
                         [out-id]
                         (into supplied-outputs
                               (map #(placeholder-cell-id call-id id :output %)
                                    missing-outputs)))
        private-outputs (mapv #(private-output-id call-id id %)
                              (range (max 1 (count caller-outputs))))
        extra-ids (vec (concat
                        (drop inputs caller-inputs)
                        (when-not implicit? (drop outputs caller-outputs*))
                        (drop caller-required arg-ids)))
        diagnostics (cond-> []
                      (seq missing-inputs)
                      (conj {:warning :missing-inputs
                             :indexes (vec missing-inputs)})
                      (seq missing-outputs)
                      (conj {:warning :missing-outputs
                             :indexes (vec missing-outputs)})
                      (seq extra-ids)
                      (conj {:warning :extra-cells
                             :cell-ids (vec extra-ids)}))]
    {:application-args (into input-ids
                             (when-not implicit? private-outputs))
     :private-outputs private-outputs
     :caller-outputs caller-outputs
     :placeholder-ids (vec (concat (drop (count supplied-inputs) input-ids)
                                   (when-not implicit?
                                     (drop (count supplied-outputs)
                                           caller-outputs))))
     :diagnostics diagnostics}))

(defn- install-output-gates
  [state candidate private-outputs caller-outputs]
  (reduce
   (fn [state [index private-id public-id]]
     (let [contexts (:candidate/contexts candidate)
           [prop-id network]
           ((premise/p:block-premise
             [:definition-output (:candidate/id candidate) index]
             private-id contexts public-id)
            (:net state))]
       (-> state
           (assoc :net network)
           (h/add-props [prop-id]))))
   state
   (map vector (range) private-outputs caller-outputs)))

(defn- candidate-topology
  [compile* definition-id call-id arg-ids out-id candidate network]
  (let [caller-signature
        (or (get-in (net/network-dict-entry network fvm/name-bindings-key)
                    [call-metadata-scope call-id :caller-signature])
            (some (fn [[[known-call-id _] call]]
                    (when (= call-id known-call-id) (:caller-signature call)))
                  (net/network-dict-entry network calls-key)))
        {:keys [application-args private-outputs caller-outputs
                placeholder-ids diagnostics]}
        (normalized-call candidate caller-signature call-id arg-ids out-id)]
    (let [candidate-id (:candidate/id candidate)
          callable-id (:candidate/callable-cell candidate)
          application-id (synthetic-application-id call-id candidate-id)
          context-id (h/stable-node-id :compiler-2 :versioned-definition-call
                                       call-id candidate-id :context)
          callable (h/strongest-or-nothing network callable-id)
          lexical-env (when (closure-value/closure-info? callable)
                        (closure-value/closure-env callable))
          network (-> network
                      (nb/ensure-cell out-id)
                      (nb/ensure-cell context-id)
                      (h/seed-cell context-id
                                   (context/context-value lexical-env
                                                          application-id
                                                          (ast/sym
                                                           (:candidate/name
                                                            candidate)))))
          state {:net network
                 :env lexical-env
                 :seed [:compiler-2/versioned-definition-call
                        definition-id call-id candidate-id]
                 :path []
                 :props []
                 :applications []
                 :compiler compile*
                 :context-id context-id
                 :application/app-id application-id
                 :application/operator-ast
                 (ast/sym (:candidate/name candidate))}
          state (update state :net
                        #(reduce nb/ensure-cell %
                                 (concat private-outputs placeholder-ids)))
          [called _binding]
          (declarations/declare-runtime-cell-application-bindings
           compile*
           (env/cell-binding callable-id)
           (mapv env/cell-binding application-args)
           state
           (first private-outputs))
          gated (install-output-gates called candidate
                                      private-outputs caller-outputs)
          gated (if (and (seq private-outputs)
                         (not= out-id (peek caller-outputs)))
                  (install-output-gates gated candidate
                                        [(peek private-outputs)] [out-id])
                  gated)
          caller-contexts (->> (into [out-id] arg-ids)
                               (mapcat #(premise/binding-contexts (:net gated) %))
                               set)
          caller-versions (into {}
                                (keep (fn [context]
                                        (when-let [block-id (:block/id context)]
                                          [block-id
                                           (:premise/epoch context)])))
                                caller-contexts)
          caller-block-ids (set (keys caller-versions))
          diagnostics (mapv #(assoc %
                                    :definition-id definition-id
                                    :candidate-id candidate-id
                                    :definition-version (:candidate/version candidate)
                                    :caller-versions caller-versions
                                    :block-ids (conj caller-block-ids
                                                     (:candidate/block-id candidate)))
                            diagnostics)
          network (-> (:net gated)
                      (net/update-net-dict-entry
                       calls-key assoc [call-id candidate-id]
                       {:call-id call-id
                        :candidate-id candidate-id
                        :caller-signature (or caller-signature
                                              (:candidate/signature candidate))
                        :private-output-ids private-outputs
                        :placeholder-ids placeholder-ids})
                      (net/update-net-dict-entry
                       diagnostics-key assoc [call-id candidate-id]
                       diagnostics))]
      {:net network :props (:props gated)})))

(defn- candidate-call-effects
  [compile* definition-id call-id arg-ids out-id candidate network]
  (let [key [:versioned-definition-call call-id (:candidate/id candidate)]]
    (if-let [topology (candidate-topology compile* definition-id call-id
                                         arg-ids out-id candidate network)]
      (let [result (topology-effects/declare-once network key out-id
                                                  (constantly topology))
            metadata-key [call-id (:candidate/id candidate)]
            call (get (net/network-dict-entry (:net topology) calls-key)
                      metadata-key)
            diagnostics (get (net/network-dict-entry (:net topology)
                                                     diagnostics-key)
                             metadata-key)]
        (if (seq (:effects result))
          (update result :effects into
                  [(fvm/bind-name call-metadata-scope call-id call)
                   (fvm/bind-name diagnostic-scope
                                  [call-id (:candidate/id candidate)]
                                  diagnostics)])
          result))
      {:effects [] :messages []})))

(defn versioned-definition-call-effects
  [compile* definition-id registry-id call-id arg-ids out-id caller-contexts network]
  (reduce
   (fn [result candidate]
     (merge-results result
                    (candidate-call-effects compile* definition-id call-id
                                            arg-ids out-id candidate network)))
   {:effects [] :messages []}
   (filterv #(candidate-active? network %)
            (registry-candidates network registry-id))))

(defn p:versioned-definition-call-with
  [compile* definition-id registry-id call-id arg-ids out-id initial-contexts]
  (let [arg-ids (vec arg-ids)
        initial-contexts (vec initial-contexts)]
    (prop/construct-propagator
     (h/stable-node-id :compiler-2/versioned-definition-call call-id)
     :compiler-2/versioned-definition-call
     (fn [_inputs _outputs network]
       (let [contexts (into (set initial-contexts)
                            (caller-contexts network arg-ids out-id))]
         (if (or (empty? contexts)
                 (every? #(context-active? network %) contexts))
         (versioned-definition-call-effects compile* definition-id registry-id
                                            call-id arg-ids out-id
                                            contexts network)
         {:effects [] :messages []})))
     (into [registry-id]
           (concat arg-ids (map :premise/state-cell initial-contexts)))
     [out-id])))

(defn- caller-contexts [network arg-ids out-id]
  (->> (into [out-id] arg-ids)
       (mapcat #(premise/binding-contexts network %))
       set))

(defn- install-versioned-call
  [compile* definition-id registry-id network arg-ids out-id]
  (let [call-id [definition-id (vec arg-ids) out-id]
        contexts (caller-contexts network arg-ids out-id)
        [prop-id installed]
        ((p:versioned-definition-call-with compile* definition-id registry-id
                                           call-id arg-ids out-id contexts)
         network)]
    [installed [prop-id] out-id]))

(defn definition-router-operator
  [compile* definition-id registry-id]
  (operator-value/operator-closure
   {:name [:compiler-2/versioned-definition definition-id]
    :static-installer
    (partial install-versioned-call compile* definition-id registry-id)
    :compiler-activate
    (fn [_compile* network context-id arg-ids out-id]
      (let [call-id [definition-id context-id (vec arg-ids) out-id]
            key [:versioned-definition-router call-id]
            contexts (caller-contexts network arg-ids out-id)]
        (topology-effects/declare-once
         network key out-id
         (fn [network]
           (let [[prop-id installed]
                 ((p:versioned-definition-call-with
                   compile* definition-id registry-id call-id arg-ids out-id
                   contexts)
                  network)]
             {:net installed :props [prop-id]})))))}))

(defn record-candidate [network candidate]
  (-> network
      (net/update-net-dict-entry candidates-key
                                 assoc (:candidate/id candidate) candidate)
      (net/update-net-dict-entry registries-key
                                 assoc (:candidate/definition-id candidate)
                                 (:candidate/registry-cell candidate))))

(defn candidates-for-block-version [network block-id version]
  (->> (vals (net/network-dict-entry network candidates-key))
       (filter #(and (= block-id (:candidate/block-id %))
                     (= version (:candidate/version %))))
       vec))

(defn public-definition-cell? [network cell-id]
  (boolean
   (some #(= cell-id (:candidate/public-cell %))
         (vals (net/network-dict-entry network candidates-key)))))
