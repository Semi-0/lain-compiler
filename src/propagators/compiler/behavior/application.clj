(ns propagators.compiler.behavior.application
  "Behavior-valued application for the behavior compiler.

  Primitive behavior operators produce behavior messages directly. Closure
  behavior values may retain prior closure-info versions; applying one uses
  temporally overlapping closure/input history when available, with latest
  closure application as the compatibility fallback.
  "
  (:require [clojure.set :as set]
            [propagators.infra.cells.value :as value]
            [propagators.compiler.model.application-value :as application-value]
            [propagators.compiler.model.closure-value :as closure-value]
            [propagators.compiler.model.env :as env]
            [propagators.compiler.compiler.basis :as h]
            [propagators.compiler.lowering.topology-effects :as topology-effects]
            [propagators.infra.core :as core]
            [propagators.infra.datastructures.behavior :as behavior]
            [propagators.infra.datastructures.behavior-algebra :as hist]
            [propagators.infra.datastructures.compound-object :as obj]
            [propagators.infra.ids :as ids]
            [propagators.infra.message :refer [message]]
            [propagators.infra.network :as net]
            [propagators.infra.network-builder :as nb]
            [propagators.infra.propagator :as prop]))

(def apply-behavior-application-props-key
  :compiler-behavior/apply-application-props)

(def behavior-application-reducer-prefix
  :behavior.reducer/compiler-behavior-application)

(defn behavior-application-reducer-id
  [app-id]
  (behavior/dominant-source-reducer-id [behavior-application-reducer-prefix
                                        app-id]
                                       :operator))

(defn- strongest-or-nothing
  [n id]
  (if (contains? (net/net-env n) id)
    (net/network-cell-strongest n id)
    value/nothing))

(defn- behavior-view-or-nothing
  [n id]
  (if (contains? (net/net-env n) id)
    (behavior/strongest-history-view (net/network-cell-content n id))
    value/nothing))

(defn- behavior-base
  [view]
  (let [strongest (behavior/strongest-value view)]
    (if (value/unusable? strongest)
      strongest
      (behavior/base-value strongest))))

(defn- argument-cell-ids
  [arg-object]
  (let [arg-object (obj/compound-object arg-object)
        count-value (obj/slot-value arg-object :count)]
    (if (number? count-value)
      (mapv #(obj/slot-value arg-object %) (range count-value))
      (mapv #(obj/slot-value arg-object %)
            (sort-by pr-str (obj/public-slot-keys arg-object))))))

(defn- compile-body
  [body body-env state]
  ((requiring-resolve 'propagators.compiler.behavior.core/g:compile)
   body
   body-env
   state))

(defn- closure-topology-key
  [closure-info]
  [(closure-value/closure-env closure-info)
   (closure-value/closure-inputs closure-info)
   (closure-value/closure-output closure-info)
   (closure-value/closure-body closure-info)])

(defn- behavior-output-adapter
  [result-id out-inner]
  (prop/construct-propagator
   (prop/concrete-propagator
    (fn [_inputs _outputs current-net]
     (let [result-view (behavior-view-or-nothing current-net result-id)]
       [(message out-inner result-view)])))
   [result-id]
   [out-inner]))

(defn- install-output-adapter
  [n result-id out-inner]
  (if (and result-id (not= result-id out-inner))
    (let [prepared (-> n
                       (nb/ensure-cell result-id)
                       (nb/ensure-cell out-inner))
          [prop-id n'] ((behavior-output-adapter result-id out-inner) prepared)]
      [n' [prop-id]])
    [n []]))

(defn- run-closure-body
  [network closure-info arg-ids app-id]
  (let [inputs (closure-value/closure-inputs closure-info)
        output (closure-value/closure-output closure-info)
        lexical-env (closure-value/closure-env closure-info)
        body (closure-value/closure-body closure-info)]
    (if (or (value/unusable? lexical-env)
            (not (ids/node-id? lexical-env))
            (value/unusable? body)
            (not= (count inputs) (count arg-ids)))
      nil
      (let [closure-key (closure-topology-key closure-info)
            frame-id (h/stable-node-id :compiler-behavior :application
                                       app-id closure-key arg-ids :frame)
            out-inner (h/stable-node-id :compiler-behavior :application
                                        app-id closure-key arg-ids :result)
            bindings (cond-> (mapv (fn [sym id]
                                     [sym (env/cell-binding id)])
                                   inputs arg-ids)
                       output (conj [output (env/cell-binding out-inner)]))
            declared (env/declare-child network lexical-env frame-id bindings)
            [state' result] (compile-body
                             body
                             frame-id
                             {:net (:net declared)
                              :env frame-id
                              :seed [:compiler-behavior/apply-closure
                                     app-id closure-key]
                              :path []
                              :props (:props declared)
                              :applications []})
            result-id (env/binding-id result)
            [activation-net adapter-props]
            (install-output-adapter (:net state') result-id out-inner)
            props (into (:props state') adapter-props)
            after-body (nb/run-propagators activation-net props)]
        {:net after-body
         :out-inner out-inner
         :props props}))))

(defn- tagged-source-keys
  [tag view]
  (set (map (fn [source-key] [tag source-key])
            (behavior/source-keys view))))

(defn- application-output-value
  [app-id operator-view body-view]
  (behavior/behavior-value
   {:history (behavior/history body-view)
    :source-keys (into (tagged-source-keys :operator operator-view)
                       (tagged-source-keys :body body-view))
    :identities (set/union (behavior/identity-set operator-view)
                           (behavior/identity-set body-view))
    :reducer (behavior-application-reducer-id app-id)}))

(defn- record-history
  [record]
  (hist/records->history [record]))

(defn- empty-history?
  [history]
  (empty? (hist/history-records history)))

(defn- arg-slice-view
  [closure-record arg-view]
  (let [slice-history (hist/history-join
                       (fn [_closure arg] arg)
                       (record-history closure-record)
                       (behavior/history arg-view))]
    (when-not (or (value/contradiction? slice-history)
                  (empty-history? slice-history))
      (behavior/behavior-value
       {:history slice-history
        :source-keys (behavior/source-keys arg-view)
        :identities (behavior/identity-set arg-view)
        :reducer (behavior/reducer-id arg-view)}))))

(defn- closure-slice-arg-views
  [closure-record arg-views]
  (let [slices (mapv #(arg-slice-view closure-record %) arg-views)]
    (when-not (some nil? slices)
      slices)))

(defn- install-arg-view-cells
  [network app-id record-key arg-views]
  (reduce
   (fn [{:keys [net arg-ids]} [position arg-view]]
     (let [arg-id (h/stable-node-id :compiler-behavior :application
                                    app-id record-key :argument position)]
       {:net (nb/install-cell net
                              arg-id
                              arg-view
                              (behavior/strongest-value arg-view))
        :arg-ids (conj arg-ids arg-id)}))
   {:net network :arg-ids []}
   (map-indexed vector arg-views)))

(defn- run-closure-body-view
  [network closure-info arg-views app-id record-key]
  (let [{:keys [net arg-ids]}
        (install-arg-view-cells network app-id record-key arg-views)
        applied (run-closure-body net closure-info arg-ids [app-id record-key])]
    (when applied
      (assoc applied :view (behavior-view-or-nothing (:net applied)
                                                     (:out-inner applied))))))

(defn- combined-body-view
  [body-views]
  (behavior/behavior-value
   {:history (apply hist/history-union (map behavior/history body-views))
    :source-keys (apply set/union (map behavior/source-keys body-views))
    :identities (apply set/union (map behavior/identity-set body-views))
    :reducer behavior/event-history-reducer-id}))

(defn- closure-history-body-view
  [network operator-view arg-views app-id]
  (let [{:keys [net views props]}
        (reduce
         (fn [{:keys [net views props]} [position closure-record]]
           (let [closure-info (hist/record-value closure-record)
                 arg-slices (closure-slice-arg-views closure-record arg-views)
                 applied (when (and (closure-value/closure-info? closure-info)
                                    arg-slices)
                           (run-closure-body-view net closure-info arg-slices
                                                  app-id position))]
             (cond
               (and applied (not (value/unusable? (:view applied))))
               {:net (:net applied)
                :views (conj views (:view applied))
                :props (into props (:props applied))}

               :else
               {:net net :views views :props props})))
         {:net network :views [] :props []}
         (map-indexed vector (behavior/history-records operator-view)))]
    (when (seq views)
      {:net net
       :props props
       :view (combined-body-view views)})))

(defn- closure-behavior-messages
  [app-id operator-id args-id out-id network]
  (let [operator-view (behavior-view-or-nothing network operator-id)
        closure-info (when-not (value/unusable? operator-view)
                       (behavior-base operator-view))
        arg-object (strongest-or-nothing network args-id)
        arg-ids (when-not (value/unusable? arg-object)
                  (argument-cell-ids arg-object))
        arg-views (mapv #(behavior-view-or-nothing network %) arg-ids)]
    (if (or (value/unusable? operator-view)
            (value/unusable? closure-info)
            (not (closure-value/closure-info? closure-info))
            (value/unusable? arg-object)
            (some value/unusable? arg-views))
      []
      (let [history (closure-history-body-view network
                                               operator-view arg-views app-id)
            history-view (:view history)
            base (when (or (nil? history-view)
                           (value/unusable? history-view))
                   (run-closure-body network closure-info (vec arg-ids) app-id))
            body-view (or history-view
                          (when (:out-inner base)
                            (behavior-view-or-nothing (:net base)
                                                      (:out-inner base))))
            final-net (or (:net history) (:net base) network)
            props (into (vec (:props history)) (:props base))]
        (if (value/unusable? body-view)
          []
          (update (topology-effects/network-diff network final-net props)
                  :messages
                  conj
                  (message out-id
                           (application-output-value app-id
                                                     operator-view
                                                     body-view))))))))

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
      (closure-behavior-messages application-id
                                 operator-id
                                 args-id
                                 out-id
                                 network))))

(defn p:apply-behavior-application
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
      (let [[prop-id n] ((prop/construct-propagator activate inputs [out-id])
                         network)]
        [prop-id
         (net/update-net-dict-entry n
                                    apply-behavior-application-props-key
                                    (fnil conj #{})
                                    prop-id)]))))
