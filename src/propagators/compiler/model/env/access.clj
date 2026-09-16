(ns propagators.compiler.model.env.access
  (:require [propagators.infra.cells.value :as value]
            [propagators.compiler.model.env.binding :as binding]
            [propagators.compiler.model.env.index :as index]
            [propagators.infra.core :as core]
            [propagators.infra.gur :as gur]
            [propagators.infra.ids :as ids]
            [propagators.infra.install :as i]
            [propagators.infra.network :as net]
            [propagators.infra.network-builder :as nb]
            [propagators.infra.propagator :as prop]
            [propagators.infra.stdlib.prop :as stdlib-prop]))

(defn- p:contains? [sym]
  (fn [names-id out-id]
    ((prop/primitive-propagator
      [:lexical-access/contains-binding sym]
      (fn [names]
        (if (and (set? names) (contains? names sym)) true value/nothing)))
     names-id out-id)))

(defn- p:missing? [sym]
  (fn [names-id out-id]
    ((prop/primitive-propagator
      [:lexical-access/missing-binding sym]
      (fn [names]
        (if (and (set? names) (not (contains? names sym))) true value/nothing)))
     names-id out-id)))

(defn- local-binding [sym frame out]
  (fn [ctx]
    (-> ctx
        (i/slot sym :binding-slot frame)
        (i/slot binding/binding-value-key :binding :binding-slot)
        (i/copy :binding out))))

(defn- parent-binding [frame out]
  (fn [ctx]
    (-> ctx
        (i/slot binding/env-parent-key :parent frame)
        (i/recur [:parent] out))))

(defn lexical-access-declaration [sym]
  (gur/recursive-declaration
   (symbol (str "compiler-2-lexical-access-" sym))
   (fn [ctx [frame-id] out-id]
     (-> ctx
         (i/$ {:frame frame-id :out out-id})
         (i/slot binding/env-local-bindings-key :local-names :frame)
         (i/install :contains-local (p:contains? sym)
                    :local-names :local-present)
         (i/install :missing-local (p:missing? sym)
                    :local-names :local-missing)
         (i/when-named :local-binding :local-present
                       (local-binding sym :frame :out))
         (i/when-named :parent-frame :local-missing
                       (parent-binding :frame :out))))))

(defn lexical-access-id [sym]
  (gur/stable-node-id [:compiler-2 :lexical-access sym]))

(defn- ensure-closure [network sym closure-id]
  (let [candidate (net/network-cell-strongest network closure-id)]
    (cond
      (value/nothing? candidate)
      (nb/seed-cell network closure-id (lexical-access-declaration sym))
      (gur/recursive-closure? candidate) network
      (value/contradiction? candidate) network
      :else
      (throw (ex-info "Lexical access closure cell contains an unsupported value"
                      {:symbol sym :closure-id closure-id :value candidate})))))

(defn p:lexical-access-local-first [sym env-id out-id]
  (fn [network]
    (let [closure-id (lexical-access-id sym)
          prepared (reduce nb/ensure-cell network [closure-id env-id out-id])
          seeded (ensure-closure prepared sym closure-id)
          effect (gur/apply-closure-effect closure-id [env-id] out-id)
          [_ installed] (core/eval-activation-result effect seeded)]
      [[(:id effect)] installed])))

(defn p:binding-value [binding-answer-id value-answer-id]
  (let [key [:compiler-2 :binding-value binding-answer-id value-answer-id]
        activate
        (fn [_ _ network]
          (if-let [bound-id (some-> (net/network-cell-strongest
                                     network binding-answer-id)
                                    binding/binding-id)]
            {:effects (-> (i/context network [key bound-id])
                          (i/install :binding-value stdlib-prop/id
                                     bound-id value-answer-id)
                          i/effects)}
            []))]
    (fn [network]
      (let [network (reduce nb/ensure-cell network
                            [binding-answer-id value-answer-id])
            [prop-id installed]
            ((prop/construct-propagator
              (binding/stable-node-id key :prop)
              :lexical-access/binding-value
              activate [binding-answer-id] [])
             network)]
        [[prop-id] installed]))))

(defn resolve-binding [network environment-id sym]
  (when-not (ids/node-id? environment-id)
    (throw (ex-info "Compiler environment must be a live environment cell"
                    {:environment environment-id})))
  (if-let [id (index/lexical-binding-id network sym environment-id)]
    (binding/cell-binding id)
    (let [answer-id (binding/stable-node-id :resolve-binding environment-id sym)
          [props installed]
          ((p:lexical-access-local-first sym environment-id answer-id)
           (nb/ensure-cell network answer-id))
          settled (nb/run-propagators installed props)
          answer (net/network-cell-strongest settled answer-id)]
      (when-not (value/unusable? answer) answer))))

(defn resolve-binding-id [network environment-id sym]
  (or (index/lexical-binding-id network sym environment-id)
      (some-> (resolve-binding network environment-id sym)
              binding/binding-id)))
