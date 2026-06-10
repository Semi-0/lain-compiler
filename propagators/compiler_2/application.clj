(ns propagators.compiler-2.application
  "Application propagator for compiler-2 network closures.

  Closure cells are data. This namespace owns runtime application: materialize
  closure slots, bind arguments into an activation-local environment, compile the
  body into a transient activation network, run it, and emit only the declared
  output diff back to the outer network.
  "
  (:require [propagators.boundary :as boundary]
            [propagators.cells.diff :refer [diff-internal-output-cells]]
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
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def apply-closure-props-key :compiler-2/apply-closure-props)
(def apply-application-props-key :compiler-2/apply-application-props)

(defn- strongest-or-nothing
  [n id]
  (if (contains? (net/net-env n) id)
    (net/network-cell-strongest n id)
    value/nothing))

(defn- copy-outer-cell
  [n outer-net id]
  (cond
    (contains? (net/net-env n) id)
    n

    (contains? (net/net-env outer-net) id)
    (nb/install-cell n
                     id
                     (net/network-cell-content outer-net id)
                     (net/network-cell-strongest outer-net id))

    :else
    (nb/ensure-cell n id)))

(defn- install-declared-slot
  [n collection-id [slot-key parent->declaration]]
  (reduce
   (fn [[acc prop-ids] parent-id]
     (let [[prop-id acc'] ((obj/p:legacy-slot slot-key parent-id collection-id) acc)]
       [acc' (conj prop-ids prop-id)]))
   [n []]
   (sort-by pr-str (keys parent->declaration))))

(defn materialize-slot-object
  "Evaluate declared slot topology for one compound object in a local frame."
  [outer-net collection-id]
  (let [raw-value (strongest-or-nothing outer-net collection-id)
        source-value (if (obj/accessor-network? raw-value)
                       (obj/compound-object (obj/accessor-source-slots raw-value))
                       raw-value)
        decls (obj/slot-declarations-for outer-net collection-id)]
    (if (empty? decls)
      source-value
      (let [base-value (obj/compound-object source-value)
            parent-ids (->> decls vals (mapcat keys) (sort-by pr-str) vec)
            n0 (nb/install-cell net/empty-net
                                collection-id
                                base-value
                                base-value)
            n1 (reduce #(copy-outer-cell %1 outer-net %2) n0 parent-ids)
            [slot-net prop-ids]
            (reduce
             (fn [[acc prop-ids] declaration]
               (let [[acc' prop-ids'] (install-declared-slot acc
                                                             collection-id
                                                             declaration)]
                 [acc' (into prop-ids prop-ids')]))
             [n1 []]
             (sort-by (comp pr-str key) decls))
            materialized (nb/run-propagators slot-net prop-ids)]
        (strongest-or-nothing materialized collection-id)))))

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
  (if (closure-value/closure-info? v)
    (externalize-closure-value v network)
    v))

(defn- argument-cell-ids
  [arg-object]
  (let [arg-object (obj/compound-object arg-object)
        count-value (obj/slot-value arg-object :count)]
    (if (number? count-value)
      (mapv #(obj/slot-value arg-object %) (range count-value))
      (mapv #(obj/slot-value arg-object %)
            (sort-by pr-str (obj/public-slot-keys arg-object))))))

(defn- body-env
  [lexical-env inputs output input-ids out-id]
  (let [base-env (cond-> (env/sub-env lexical-env)
                   output (env/bind-local output (env/cell-binding out-id)))]
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

(defn- output-adapter
  [result-id out-inner]
  (prop/construct-propagator
   (prop/concrete-propagator
    (fn [_inputs _outputs current-net]
     (let [result-value (materialize-slot-object current-net result-id)]
       [(message out-inner
                 (externalize-output-value result-value current-net))])))
   [result-id]
   [out-inner]))

(defn- install-output-adapter
  [n result-id out-inner]
  (if (and result-id (not= result-id out-inner))
    (let [[prop-id n'] ((output-adapter result-id out-inner) n)]
      [n' [prop-id]])
    [n []]))

(defn- run-activation-network
  [n inner-inputs prop-ids]
  (let [input-prop-ids (pop-inputs inner-inputs (net/net-graph n))
        tasks (tq/enqueue-all tq/empty-queue
                              (concat prop-ids input-prop-ids))]
    (core/run-tasks tasks n)))

(defn- run-closure-body
  [network closure-info arg-ids out-id]
  (let [inputs (closure-value/closure-inputs closure-info)
        output (closure-value/closure-output closure-info)
        lexical-env (closure-value/closure-env closure-info)
        body (closure-value/closure-body closure-info)
        output-ids [out-id]]
    (if (or (value/unusable? lexical-env)
            (value/unusable? body)
            (not= (count inputs) (count arg-ids)))
      network
      (-> network
          (boundary/create-boundary-outputs output-ids)
          (boundary/create-boundary-inputs arg-ids)
          (#(let [inner-inputs (mapv (partial net/lookup-inner-in %) arg-ids)
                  [out-inner] (mapv (partial net/lookup-inner-out %) output-ids)
                  activation-env (body-env lexical-env
                                           inputs
                                           output
                                           inner-inputs
                                           out-inner)
                  [state' result] (compile-body
                                   body
                                   activation-env
                                   {:net %
                                    :env activation-env
                                    :seed [:compiler-2/apply-closure
                                           (closure-value/closure-scope closure-info)
                                           arg-ids
                                           out-id]
                                    :path []
                                    :props []})
                  result-id (env/binding-id result)
                  [activation-net adapter-props]
                  (install-output-adapter (:net state') result-id out-inner)]
              (run-activation-network activation-net
                                      inner-inputs
                                      (into (:props state') adapter-props))))))))

(defn- closure-application-messages
  [closure-id args-id _scheduled-arg-ids out-id network]
  (let [closure-cv (strongest-or-nothing network closure-id)
        arg-object (strongest-or-nothing network args-id)
        materialized-closure (when-not (value/unusable? closure-cv)
                               (materialize-slot-object network closure-id))
        materialized-args (when-not (value/unusable? arg-object)
                            (materialize-slot-object network args-id))
        arg-ids (when-not (value/unusable? arg-object)
                  (argument-cell-ids materialized-args))
        arg-values (mapv #(strongest-or-nothing network %) arg-ids)]
    (if (or (value/unusable? closure-cv)
            (value/unusable? materialized-closure)
            (not (closure-value/closure-info? materialized-closure))
            (value/unusable? arg-object)
            (value/any-unusable-values? arg-values))
      []
      (let [after-body (run-closure-body network
                                         materialized-closure
                                         (vec arg-ids)
                                         out-id)]
        (diff-internal-output-cells after-body network [out-id])))))

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
      (let [network* (reduce nb/ensure-cell network (conj inputs out-id))
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
  (let [application-info (strongest-or-nothing network application-id)
        operator (strongest-or-nothing network operator-id)]
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
      (let [network* (reduce nb/ensure-cell network (conj inputs out-id))
            [prop-id n] ((prop/construct-propagator activate inputs [out-id])
                         network*)]
        [prop-id
         (net/update-net-dict-entry n
                                    apply-application-props-key
                                    (fnil conj #{})
                                    prop-id)]))))
