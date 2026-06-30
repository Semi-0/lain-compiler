(ns propagators.compiler-2.application
  "Application propagator for compiler-2 network closures.

  Closure cells are data. This namespace owns runtime application: bind
  arguments into an activation-local environment, compile the
  body into a transient activation network, run it, and emit only the declared
  output diff back to the outer network.
  "
  (:require [propagators.boundary :as boundary]
            [propagators.cells.merge :as cell-merge]
            [propagators.cells.snapshot :refer [pop-inputs]]
            [propagators.cells.value :as value]
            [propagators.compiler-2.application-value :as application-value]
            [propagators.compiler-2.closure-value :as closure-value]
            [propagators.compiler-2.env :as env]
            [propagators.compiler-2.helpers :as h]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.helpers.task-queue :as tq]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.stdlib.prop :as stdlib-prop]))

(def apply-closure-props-key :compiler-2/apply-closure-props)
(def apply-application-props-key :compiler-2/apply-application-props)

(defn- inner->outer-boundary-map
  [network]
  (into {}
        (map (fn [[outer inner]] [inner outer]))
        (merge (get (net/net-dict-or-empty network) :avatars-in {})
               (get (net/net-dict-or-empty network) :avatars-out {}))))

(defn- externalize-closure-value
  [v network]
  (let [closure-env (closure-value/closure-env v)]
    (if (value/unusable? closure-env)
      v
      (obj/compound-object
       (assoc (closure-value/slot-map v)
              closure-value/closure-env-slot
              (env/externalize-env closure-env
                                   (inner->outer-boundary-map network)))))))

(defn- externalize-output-value
  [v network]
  (cond
    (closure-value/closure-info? v)
    (externalize-closure-value v network)

    :else
    v))

(defn- output-symbols
  [output]
  (cond
    (nil? output) []
    (symbol? output) [output]
    (vector? output) output
    :else []))

(defn- body-env
  [lexical-env inputs output-targets input-ids]
  (let [base-env (reduce (fn [scoped-env [sym id]]
                           (if sym
                             (env/bind-local scoped-env sym (env/cell-binding id))
                             scoped-env))
                         (env/sub-env lexical-env)
                         output-targets)]
    (reduce
     (fn [scoped-env [sym id]]
       (env/bind-local scoped-env sym (env/cell-binding id)))
     base-env
     (map vector inputs input-ids))))

(defn- compile-body
  [body body-env state]
  ((requiring-resolve 'propagators.compiler-2.core/g:compile)
   body
   body-env
   state))

(defn- install-output-adapter
  [n result-id out-inner]
  (if (and result-id (not= result-id out-inner))
    (let [[prop-id n'] ((stdlib-prop/id result-id out-inner) n)]
      [n' [prop-id]])
    [n []]))

(defn- install-output-adapters
  [n result-id output-inners]
  (if (= 1 (count output-inners))
    (install-output-adapter n result-id (first output-inners))
    [n []]))

(defn- externalized-output-messages
  [network-from network-to external-outputs]
  (keep identity
        (map (fn [ext]
               (when-let [int-id (net/lookup-inner-out network-from ext)]
                 (let [inner-value (h/strongest-or-nothing network-from int-id)
                       outer-value (h/strongest-or-nothing network-to ext)
                       output-value (externalize-output-value inner-value
                                                              network-from)]
                   (when (and (not (value/unusable? output-value))
                              (cell-merge/cell-updated? output-value
                                                        outer-value
                                                        network-to))
                     (message ext output-value)))))
             (vec external-outputs))))

(defn- run-activation-network
  [n inner-inputs prop-ids]
  (let [input-prop-ids (pop-inputs inner-inputs (net/net-graph n))
        tasks (tq/enqueue-all tq/empty-queue
                              (concat prop-ids input-prop-ids))]
    (core/run-tasks tasks n)))

(defn- run-closure-body
  [network closure-info arg-ids output-targets]
  (let [inputs (closure-value/closure-inputs closure-info)
        output (closure-value/closure-output closure-info)
        lexical-env (closure-value/closure-env closure-info)
        body (closure-value/closure-body closure-info)
        output-ids (mapv second output-targets)]
    (if (or (value/unusable? lexical-env)
            (value/unusable? body)
            (not= (count inputs) (count arg-ids)))
      network
      (-> (reduce h/ensure-cell network output-ids)
          (boundary/create-boundary-outputs output-ids)
          (boundary/create-boundary-inputs arg-ids)
          (#(let [inner-inputs (mapv (partial net/lookup-inner-in %) arg-ids)
                  output-inners (mapv (partial net/lookup-inner-out %) output-ids)
                  activation-env (body-env lexical-env
                                           inputs
                                           (mapv (fn [[sym _id] inner-id]
                                                   [sym inner-id])
                                                 output-targets
                                                 output-inners)
                                           inner-inputs)
                  [state' result] (compile-body
                                   body
                                   activation-env
                                   {:net %
                                    :env activation-env
                                    :seed [:compiler-2/apply-closure
                                           (closure-value/closure-scope closure-info)
                                           arg-ids
                                           output-ids]
                                    :path []
                                    :props []})
                  result-id (env/binding-id result)
                  [activation-net adapter-props]
                  (install-output-adapters (:net state')
                                           result-id
                                           output-inners)]
              (run-activation-network activation-net
                                      inner-inputs
                                      (into (:props state') adapter-props))))))))

(defn- closure-call-plan
  [closure-info arg-ids out-id]
  (let [input-count (count (closure-value/closure-inputs closure-info))
        output-syms (output-symbols (closure-value/closure-output closure-info))
        output-count (count output-syms)
        arg-ids (vec arg-ids)]
    (if (pos? output-count)
      (when (= (count arg-ids) (+ input-count output-count))
        {:input-ids (subvec arg-ids 0 input-count)
         :targets (mapv vector
                        output-syms
                        (subvec arg-ids input-count))})
      (when (= (count arg-ids) input-count)
        {:input-ids arg-ids
         :targets [[nil out-id]]}))))

(defn- closure-application-messages
  [closure-id _args-id scheduled-arg-ids out-id network]
  (let [closure-cv (h/strongest-or-nothing network closure-id)
        closure-info closure-cv
        arg-ids (vec scheduled-arg-ids)]
    (if (or (value/unusable? closure-cv)
            (not (closure-value/closure-info? closure-info)))
      []
      (let [{:keys [input-ids targets]} (closure-call-plan closure-info
                                                           arg-ids
                                                           out-id)
            input-values (mapv #(h/strongest-or-nothing network %) input-ids)]
        (if (or (nil? targets)
                (value/any-unusable-values? input-values))
          []
          (let [output-ids (mapv second targets)
                network* (reduce h/ensure-cell network output-ids)
                after-body (run-closure-body network*
                                             closure-info
                                             (vec input-ids)
                                             targets)]
            {:messages (externalized-output-messages after-body
                                                     network*
                                                     output-ids)}))))))

(defn p:apply-closure
  "Apply a compiler-2 closure-info cell to argument cells and one output cell."
  [closure-id args-id arg-ids out-id]
  (let [arg-ids (vec arg-ids)
        activate (fn [_inputs _outputs network]
                   (closure-application-messages closure-id
                                                 args-id
                                                 arg-ids
                                                 out-id
                                                 network))
        inputs (into [closure-id args-id] arg-ids)]
    (fn [network]
      (let [network* (reduce h/ensure-cell network (conj inputs out-id))
            [prop-id n] ((prop/construct-propagator activate inputs [out-id])
                         network*)]
        [prop-id
         (net/update-net-dict-entry n
                                    apply-closure-props-key
                                    (fnil conj #{})
                                    prop-id)]))))

(defn- primitive-application-messages
  [operator context-id arg-ids out-id network]
  (if-let [activate (h/application-activate operator)]
    (activate network context-id arg-ids out-id)
    []))

(defn- application-messages
  [application-id operator-id args-id scheduled-arg-ids context-id out-id network]
  (let [application-info (h/strongest-or-nothing network application-id)
        operator (h/strongest-or-nothing network operator-id)]
    (cond
      (value/unusable? application-info)
      []

      (not (application-value/application-info? application-info))
      []

      (value/unusable? operator)
      []

      (h/application-activate operator)
      (primitive-application-messages operator
                                      context-id
                                      scheduled-arg-ids
                                      out-id
                                      network)

      :else
      (closure-application-messages operator-id
                                    args-id
                                    scheduled-arg-ids
                                    out-id
                                    network))))

(defn p:apply-application
  "Evaluate one retained compiler-2 application object.

  The application object is declaration data. This propagator owns executable
  lowering at evaluation time: primitive operators produce messages directly,
  and closure values delegate to the closure application path."
  [application-id operator-id args-id arg-ids context-id out-id]
  (let [arg-ids (vec arg-ids)
        activate (fn [_inputs _outputs network]
                   (application-messages application-id
                                         operator-id
                                         args-id
                                         arg-ids
                                         context-id
                                         out-id
                                         network))
        inputs (into [application-id operator-id args-id context-id] arg-ids)]
    (fn [network]
      (let [network* (reduce h/ensure-cell network (conj inputs out-id))
            [prop-id n] ((prop/construct-propagator activate inputs [out-id])
                         network*)]
        [prop-id
         (net/update-net-dict-entry n
                                    apply-application-props-key
                                    (fnil conj #{})
                                    prop-id)]))))
