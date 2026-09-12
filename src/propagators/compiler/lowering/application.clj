(ns propagators.compiler.lowering.application
  "Compiler 2 application as delayed flat-GUR topology in the active Net."
  (:require [propagators.infra.cells.value :as value]
            [propagators.compiler.model.closure-value :as closure-value]
            [propagators.compiler.model.env :as env]
            [propagators.compiler.lowering.topology-effects :as topology]
            [propagators.infra.core :as core]
            [propagators.infra.gur :as gur]
            [propagators.infra.ids :as ids]
            [propagators.infra.message :refer [message]]
            [propagators.infra.network :as net]
            [propagators.infra.network-builder :as nb]
            [propagators.infra.propagator :as prop]))

(def compiler-callable-key :compiler-2/callable?)
(def declaration-id-key :compiler-2/declaration-id)
(def captured-environment-key :compiler-2/captured-environment)
(def declaration-key :compiler-2/declaration)
(def execute-sub-env-props-key :compiler-2/execute-sub-env-props)
(def application-name-scope [:compiler-2 :applications])

(declare primitive-application-effects)
(declare closure-application-effects)
(declare constraint-application-effects)

(defprotocol ApplicationTopology
  (application-effects
    [application gur-context invocation-ids result-id]))

(deftype PrimitiveApplication [installer declaration]
  ApplicationTopology
  (application-effects
    [_ gur-context invocation-ids result-id]
    (primitive-application-effects
     installer declaration gur-context invocation-ids result-id)))

(deftype ClosureApplication
  [compile* declaration-id lexical-env-id closure-info]
  ApplicationTopology
  (application-effects
    [_ gur-context invocation-ids result-id]
    (closure-application-effects
     compile* declaration-id lexical-env-id closure-info
     gur-context invocation-ids result-id)))

(deftype ConstraintApplication
  [compile* declaration-id lexical-env-id closure-info]
  ApplicationTopology
  (application-effects
    [_ gur-context invocation-ids result-id]
    (constraint-application-effects
     compile* declaration-id lexical-env-id closure-info
     gur-context invocation-ids result-id)))

(defn compiler-callable
  [name declaration-id lexical-env-id application declaration]
  (assoc
   (gur/recursive-closure name (partial application-effects application))
   compiler-callable-key true
   declaration-id-key declaration-id
   captured-environment-key lexical-env-id
   declaration-key declaration))

(defn primitive-callable
  [name declaration-id installer declaration]
  (compiler-callable
   name declaration-id nil
   (PrimitiveApplication. installer declaration)
   declaration))

(defn closure-callable
  [compile* name declaration-id lexical-env-id closure-info]
  (compiler-callable
   name declaration-id lexical-env-id
   (ClosureApplication. compile* declaration-id lexical-env-id closure-info)
   closure-info))

(defn constraint-callable
  [compile* name declaration-id lexical-env-id closure-info]
  (compiler-callable
   name declaration-id lexical-env-id
   (ConstraintApplication. compile* declaration-id lexical-env-id closure-info)
   closure-info))

(defn compiler-callable?
  [candidate]
  (and (gur/recursive-closure? candidate)
       (true? (get candidate compiler-callable-key))
       (contains? candidate declaration-id-key)
       (contains? candidate captured-environment-key)))

(defn callable-declaration
  [candidate]
  (cond
    (compiler-callable? candidate)
    (get candidate declaration-key value/nothing)

    :else
    candidate))

(defn- application-relation-key?
  [key]
  (and (vector? key)
       (= 2 (count key))
       (vector? (first key))
       (= :gur.flat/application (first (first key)))))

(defn- ordered-argument-ids
  [relations]
  (->> relations
       (keep (fn [[role id]]
               (cond
                 (and (vector? role)
                      (= :argument (first role))
                      (number? (second role)))
                 [(second role) id]

                 :else
                 nil)))
       (sort-by first)
       (mapv second)))

(defn application-topologies
  "Read connected application declarations from flat GUR name bindings."
  [network]
  (let [bindings (get (net/network-dict-entry network gur/name-bindings-key)
                      application-name-scope
                      {})
        grouped
        (reduce-kv
         (fn [applications key id]
           (cond
             (application-relation-key? key)
             (let [[application-id role] key]
               (assoc-in applications [application-id role] id))

             :else
             applications))
         {}
         bindings)]
    (mapv (fn [[application-id relations]]
            {:application-id application-id
             :operator-id (:operator relations)
             :argument-ids (ordered-argument-ids relations)
             :context-id (:context relations)
             :captured-environment-id (:captured-environment relations)
             :frame-id (:frame relations)
             :result-id (:result relations)})
          grouped)))

(defn application-topology-for-result
  [network result-id]
  (let [matches (filterv #(= result-id (:result-id %))
                         (application-topologies network))]
    (cond
      (= 1 (count matches))
      (first matches)

      (empty? matches)
      nil

      :else
      (throw
       (ex-info "Application result has ambiguous topology"
                {:result-id result-id
                 :applications (mapv :application-id matches)})))))

(defn application-topology
  [network application-id]
  (let [matches (filterv #(= application-id (:application-id %))
                         (application-topologies network))]
    (cond
      (= 1 (count matches))
      (first matches)

      (empty? matches)
      nil

      :else
      (throw
       (ex-info "Application identity has ambiguous topology"
                {:application-id application-id
                 :applications (mapv :application-id matches)})))))

(defn boundary-name
  [application-id direction position]
  [:compiler-2/application application-id direction position])

(defn concrete-boundary
  [application-id direction position]
  (fn [source-id target-id]
    (prop/construct-propagator
     (boundary-name application-id direction position)
     (prop/concrete-propagator
      (fn [_inputs _outputs network]
        [(message target-id
                  (net/network-cell-content network source-id))]))
     [source-id]
     [target-id])))

(defn- boundary-installer
  [application-id direction position source-id target-id]
  (fn [network]
    (((concrete-boundary application-id direction position)
      source-id target-id)
     (reduce nb/ensure-cell network [source-id target-id]))))

(defn- installed-prop-ids
  [installed]
  (->> (tree-seq sequential? seq installed)
       (filter ids/node-id?)
       vec))

(defn- install-one
  [{:keys [net prop-ids]} installer]
  (let [[installed next-net] (installer net)]
    {:net next-net
     :prop-ids (into prop-ids (installed-prop-ids installed))}))

(defn- install-all
  [network installers]
  (reduce install-one {:net network :prop-ids []} installers))

(defn- invocation
  [invocation-ids]
  (let [ids (vec invocation-ids)]
    (cond
      (empty? ids)
      (throw
       (ex-info "Application requires a context cell"
                {:invocation-ids ids}))

      :else
      {:context-id (first ids)
       :argument-ids (subvec ids 1)})))

(defn- output-symbols
  [output]
  (cond
    (nil? output) []
    (symbol? output) [output]
    (vector? output) output
    :else
    (throw
     (ex-info "Unsupported closure output declaration"
              {:output output}))))

(defn- closure-call
  [closure-info argument-ids result-id]
  (let [parameters (closure-value/closure-inputs closure-info)
        output (closure-value/closure-output closure-info)
        outputs (output-symbols output)
        input-count (count parameters)
        output-count (count outputs)
        arguments (vec argument-ids)]
    (cond
      (and (closure-value/implicit-return-output? output)
           (= input-count (count arguments)))
      {:parameters parameters
       :input-ids arguments
       :outputs [{:name (first outputs)
                  :outer-id result-id
                  :implicit? true
                  :result? true}]}

      (and (pos? output-count)
           (= (+ input-count output-count) (count arguments)))
      (let [input-ids (subvec arguments 0 input-count)
            output-ids (subvec arguments input-count)]
        {:parameters parameters
         :input-ids input-ids
         :outputs
         (mapv (fn [name outer-id]
                 {:name name
                  :outer-id outer-id
                  :implicit? false
                  :result? (= outer-id (peek output-ids))})
               outputs
               output-ids)})

      (and (zero? output-count)
           (= input-count (count arguments)))
      {:parameters parameters
       :input-ids arguments
       :outputs []}

      :else
      (throw
       (ex-info "Closure application has invalid arity"
                {:parameters parameters
                 :outputs outputs
                 :argument-ids arguments})))))

(defn- local-input-id
  [application-id position]
  (gur/stable-node-id [application-id :local-input position]))

(defn- local-output-id
  [application-id position]
  (gur/stable-node-id [application-id :local-output position]))

(defn- input-installers
  [application-id frame-id parameters input-ids input-mode]
  (mapcat
   (fn [position parameter input-id]
     (let [local-id (local-input-id application-id position)]
       (let [inbound
             [(env/p:declare-canonical-local parameter frame-id local-id)
              (boundary-installer application-id :inbound position
                                  input-id local-id)]]
         (cond
           (= :bidirectional input-mode)
           (conj inbound
                 (boundary-installer application-id :outbound
                                     [:applicant position]
                                     local-id input-id))

           (= :inbound input-mode)
           inbound

           :else
           (throw
            (ex-info "Unsupported closure input boundary mode"
                     {:input-mode input-mode
                      :application-id application-id}))))))
   (range)
   parameters
   input-ids))

(defn- output-installers
  [application-id frame-id outputs result-id]
  (mapcat
   (fn [position {:keys [name outer-id result?]}]
     (let [local-id (local-output-id application-id position)
           external
           [(env/p:declare-canonical-local name frame-id local-id)
            (boundary-installer application-id :outbound position
                                local-id outer-id)]
           result-route
           (cond
             (and result? (not= outer-id result-id))
             [(boundary-installer application-id :outbound
                                  [:result position]
                                  local-id result-id)]

             :else
             [])]
       (into external result-route)))
   (range)
   outputs))

(defn- frame-topology
  [network application-id lexical-env-id frame-id parameters input-ids
   outputs result-id input-mode]
  (install-all
   network
   (concat
    [(env/p:scope-frame
      lexical-env-id
      frame-id
      (into (vec parameters) (mapv :name outputs)))]
    (input-installers application-id frame-id parameters input-ids input-mode)
    (output-installers application-id frame-id outputs result-id))))

(defn- compile-body
  [topology compile* caller-id frame-id context-id declaration-id closure-info]
  (let [[compiled-state body-binding]
        (compile*
         {:net (:net topology)
          :env frame-id
          :context-id context-id
          :seed [:compiler-2/application declaration-id frame-id]
          :path []
          :props (:prop-ids topology)
          :applications []
          :application/caller caller-id
          :compiler compile*}
         (closure-value/closure-body closure-info))]
    {:net (:net compiled-state)
     :prop-ids (:props compiled-state)
     :result-id (env/binding-id body-binding)}))

(defn- route-body-result
  [compiled application-id outputs result-id]
  (let [implicit-output
        (first (filter :implicit? outputs))
        selected-output
        (first (filter :result? outputs))
        body-result-id
        (:result-id compiled)]
    (cond
      (and implicit-output (ids/node-id? body-result-id))
      (let [position (.indexOf outputs implicit-output)
            local-id (local-output-id application-id position)
            routed
            (install-one
             {:net (:net compiled) :prop-ids (:prop-ids compiled)}
             (boundary-installer application-id :outbound :body-result
                                 body-result-id local-id))]
        (assoc routed :result-id result-id))

      implicit-output
      (throw
       (ex-info "Implicit-return closure body declared no result"
                {:application-id application-id
                 :result body-result-id}))

      (and selected-output (ids/node-id? body-result-id))
      (let [position (.indexOf outputs selected-output)
            local-id (local-output-id application-id position)]
        (cond
          (= body-result-id local-id)
          (assoc compiled :result-id result-id)

          :else
          (let [routed
                (install-one
                 {:net (:net compiled) :prop-ids (:prop-ids compiled)}
                 (boundary-installer application-id :outbound :body-result
                                     body-result-id local-id))]
            (assoc routed :result-id result-id))))

      selected-output
      compiled

      (ids/node-id? body-result-id)
      (let [routed
            (install-one
             {:net (:net compiled) :prop-ids (:prop-ids compiled)}
             (boundary-installer application-id :outbound :body-result
                                 body-result-id result-id))]
        (assoc routed :result-id result-id))

      :else
      (throw
       (ex-info "Closure body declared no result"
                {:application-id application-id
                 :result body-result-id})))))

(defn- application-name-effects
  [application-id relations]
  (mapv (fn [[role id]]
          (gur/bind-name application-name-scope
                         [application-id role]
                         id))
        relations))

(defn- declaration-result
  [base application-id compiled relations]
  (let [diff (topology/network-diff base (:net compiled) (:prop-ids compiled))]
    {:effects (into (application-name-effects application-id relations)
                    (:effects diff))
     :messages (:messages diff)}))

(defn- declare-closure-effects
  [compile* declaration-id lexical-env-id closure-info
   gur-context invocation-ids result-id input-mode]
  (let [{:keys [context-id argument-ids]} (invocation invocation-ids)
        application-id (:app-key gur-context)
        {:keys [parameters input-ids outputs]}
        (closure-call closure-info argument-ids result-id)
        frame-id (gur/stable-node-id [application-id :lexical-frame])
        base (:network gur-context)
        compiled
        (-> (frame-topology base application-id lexical-env-id frame-id
                            parameters input-ids outputs result-id input-mode)
            (compile-body compile* (:closure-id gur-context)
                          frame-id context-id
                          declaration-id closure-info)
            (route-body-result application-id outputs result-id))]
    (declaration-result
     base application-id compiled
     (concat [[:operator (:closure-id gur-context)]
              [:context context-id]
              [:captured-environment lexical-env-id]
              [:frame frame-id]
              [:result result-id]]
             (map-indexed (fn [position id]
                            [[:argument position] id])
                          argument-ids)))))

(defn closure-application-effects
  [compile* declaration-id lexical-env-id closure-info
   gur-context invocation-ids result-id]
  (declare-closure-effects
   compile* declaration-id lexical-env-id closure-info
   gur-context invocation-ids result-id :inbound))

(defn constraint-application-effects
  [compile* declaration-id lexical-env-id closure-info
   gur-context invocation-ids result-id]
  (declare-closure-effects
   compile* declaration-id lexical-env-id closure-info
   gur-context invocation-ids result-id :bidirectional))

(defn- primitive-input-topology
  [base application-id argument-ids]
  (let [local-ids (mapv (partial local-input-id application-id)
                        (range (count argument-ids)))
        installers
        (mapcat (fn [position outer-id local-id]
                  [(boundary-installer application-id :inbound position
                                       outer-id local-id)
                   (boundary-installer application-id :outbound
                                       [:argument position]
                                       local-id outer-id)])
                (range)
                argument-ids
                local-ids)]
    (assoc (install-all base installers) :input-ids local-ids)))

(defn primitive-application-effects
  [installer _declaration gur-context invocation-ids result-id]
  (let [{:keys [context-id argument-ids]} (invocation invocation-ids)
        application-id (:app-key gur-context)
        base (:network gur-context)
        inbound (primitive-input-topology base application-id argument-ids)
        [installed primitive-props selected-output]
        (installer (:net inbound) argument-ids result-id context-id)
        primitive
        {:net installed
         :prop-ids (into (:prop-ids inbound)
                         (installed-prop-ids primitive-props))}
        routed
        (cond
          (= selected-output result-id)
          primitive

          (ids/node-id? selected-output)
          (install-one
           primitive
           (boundary-installer application-id :outbound :primitive-result
                               selected-output result-id))

          :else
          (throw
           (ex-info "Primitive application declared no output"
                    {:application-id application-id
                     :selected-output selected-output})))]
    (declaration-result
     base application-id routed
     (concat [[:operator (:closure-id gur-context)]
              [:context context-id]
              [:result result-id]]
             (map-indexed (fn [position id]
                            [[:argument position] id])
                          argument-ids)))))

(defn install-application
  [state operator-id argument-ids result-id]
  (let [application-id (:application/app-id state)
        invocation-ids (into [(:context-id state)] argument-ids)
        topology-id (gur/application-key operator-id invocation-ids result-id)
        apply-effect (gur/apply-closure-effect
                      operator-id invocation-ids result-id)
        name-effect (gur/bind-name application-name-scope
                                   application-id
                                   (:id apply-effect))
        relation-effects
        (application-name-effects
         topology-id
         (concat [[:operator operator-id]
                  [:context (:context-id state)]
                  [:result result-id]]
                 (map-indexed (fn [position id]
                                [[:argument position] id])
                              argument-ids)))
        [_ installed]
        (core/eval-activation-result
         (into [apply-effect name-effect] relation-effects)
         (:net state))]
    [(-> state
         (assoc :net installed)
         (update :props (fnil conj []) (:id apply-effect))
         (update :applications (fnil conj []) topology-id))
     (env/cell-binding result-id)]))

;; Compatibility entry points delegated to the compiler-owned lowering module.
(defn execute-sub-env-messages-with
  [& args]
  (apply
   (requiring-resolve
    'propagators.compiler.lowering.sub-environment/execute-sub-env-messages-with)
   args))

(defn execute-sub-env-messages
  [& args]
  (apply
   (requiring-resolve
    'propagators.compiler.lowering.sub-environment/execute-sub-env-messages)
   args))

(defn p:execute-sub-env-with
  [& args]
  (apply
   (requiring-resolve
    'propagators.compiler.lowering.sub-environment/p:execute-sub-env-with)
   args))

(defn p:execute-sub-env
  [& args]
  (apply
   (requiring-resolve
    'propagators.compiler.lowering.sub-environment/p:execute-sub-env)
   args))
