(ns propagators.compiler-2.operators.call-graph
  "Reactive call graphs derived from closure syntax and retained application IR."
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.model.application-value :as application-value]
            [propagators.compiler-2.model.closure-value :as closure-value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.semantic-trace :as semantic-trace])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(defn- stable-node-id
  [& seed]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str (into [:compiler-2/call-graph] seed))
               StandardCharsets/UTF_8))))

(defn graph-cell-id
  "Stable cell receiving realized call facts for one closure cell."
  [closure-id]
  (stable-node-id :closure closure-id :graph))

(defn- operator-label
  [operator-expr]
  (if (= :symbol (ast/type operator-expr))
    (str (ast/name operator-expr))
    (str (ast/type operator-expr))))

(defn- expression-children
  [expr]
  (let [expr (ast/ast expr)]
    (case (ast/type expr)
      :apply (into [(ast/operator expr)] (ast/args expr))
      :sequence (ast/body expr)
      :let-cell [(ast/body expr)]
      :let (concat (map second (ast/bindings expr)) [(ast/body expr)])
      :when-topology [(ast/condition expr) (ast/body expr)]
      :network [(ast/body expr)]
      :compound [(ast/body expr)]
      :def-net [(ast/body expr)]
      :def-constraint [(ast/body expr)]
      :def (keep identity [(ast/body expr)])
      [])))

(defn call-sites
  "Return syntax-level application descriptors without consulting a network."
  [expr]
  (letfn [(walk [expr path]
            (let [expr (ast/ast expr)
                  site (when (= :apply (ast/type expr))
                         [{:path path
                           :operator (ast/operator expr)
                           :operator-label (operator-label (ast/operator expr))}])]
              (into (vec site)
                    (mapcat (fn [[index child]]
                              (walk child (conj path index))))
                    (map-indexed vector (expression-children expr)))))]
    (walk expr [])))

(defn- empty-graph []
  (semantic-trace/graph-union
   {:nodes {} :node-aliases {} :values {} :node-ui {} :expansions {} :edges []}))

(defn- closure-label
  [closure-info]
  (str "closure " (pr-str (closure-value/closure-inputs closure-info))))

(defn- potential-callee-id
  [network closure-info {:keys [operator operator-label]}]
  (if (= :symbol (ast/type operator))
    (or (env/lexical-binding-id network
                                (ast/name operator)
                                (closure-value/closure-env closure-info))
        (stable-node-id :operator operator-label))
    (stable-node-id :operator operator-label)))

(defn potential-call-graph
  "Build the potential call graph stored in one closure body."
  [network closure-id closure-info]
  (reduce
   (fn [graph {:keys [path operator-label] :as site}]
     (let [call-id (stable-node-id :potential closure-id path)
           callee-id (potential-callee-id network closure-info site)
           callee-label (if (= closure-id callee-id)
                          (closure-label closure-info)
                          operator-label)]
       (semantic-trace/graph-union
        graph
        {:nodes (assoc {closure-id (closure-label closure-info)
                        call-id (str "potential " operator-label)}
                       callee-id callee-label)
         :values {call-id {:call/status :potential
                           :call/path path
                           :call/operator operator-label}}
         :edges [[closure-id call-id] [call-id callee-id]]})))
   (empty-graph)
   (call-sites (closure-value/closure-body closure-info))))

(defn- realized-callee-label
  [operator operator-expr]
  (cond
    (closure-value/closure-info? operator) (closure-label operator)
    (operator-value/operator-closure? operator)
    (str (or (obj/slot-value operator operator-value/name-slot)
             (operator-label operator-expr)))
    :else (operator-label operator-expr)))

(defn realized-call-graph
  "Build one realized call fact from retained application IR."
  [caller-id application-id application-info operator-id operator]
  (let [operator-expr (obj/slot-value
                       application-info
                       application-value/application-operator-ast-slot)
        call-label (operator-label operator-expr)
        callee-label (if (= caller-id operator-id)
                       "closure"
                       (realized-callee-label operator operator-expr))]
    (semantic-trace/graph-union
     {:nodes (assoc {caller-id "closure"
                     application-id (str "call " call-label)}
                    operator-id callee-label)
      :values {application-id {:call/status :realized
                               :call/application application-id
                               :call/operator operator-id}}
      :edges [[caller-id application-id]
              [application-id operator-id]]})))

(defn- application-call-messages
  [caller-id application-id operator-id graph-id network]
  (let [application-info (net/network-cell-strongest network application-id)
        operator (net/network-cell-strongest network operator-id)]
    (if (or (value/unusable? application-info)
            (not (application-value/application-info? application-info))
            (value/unusable? operator))
      []
      [(message graph-id
                (realized-call-graph caller-id application-id application-info
                                     operator-id operator))])))

(defn p:application-call
  "Publish one retained application into its caller's stable graph cell."
  [caller-id application-id operator-id]
  (let [graph-id (graph-cell-id caller-id)]
    (fn [network]
      (let [network (reduce nb/ensure-cell network
                            [caller-id application-id operator-id graph-id])
            [prop-id network]
            ((prop/construct-propagator
              (stable-node-id :application application-id :prop)
              :compiler-2/call-graph-application
              (fn [_inputs _outputs current-net]
                (application-call-messages caller-id application-id operator-id
                                           graph-id current-net))
              [application-id operator-id]
              [graph-id])
             network)]
        [prop-id network]))))

(defn- call-graph-messages
  [closure-id graph-id out-id network]
  (let [closure-info (net/network-cell-strongest network closure-id)
        realized (net/network-cell-strongest network graph-id)]
    (if-not (closure-value/closure-info? closure-info)
      []
      [(message out-id
                (semantic-trace/graph-union
                 (potential-call-graph network closure-id closure-info)
                 realized))])))

(defn p:call-graph
  "Construct a named propagator exposing potential and realized closure calls."
  [closure-id out-id]
  (let [graph-id (graph-cell-id closure-id)]
    (fn [network]
      (let [network (reduce nb/ensure-cell network [closure-id graph-id out-id])
            [prop-id network]
            ((prop/construct-propagator
              (stable-node-id :closure closure-id :prop)
              :compiler-2/call-graph
              (fn [_inputs _outputs current-net]
                (call-graph-messages closure-id graph-id out-id current-net))
              [closure-id graph-id]
              [out-id])
             network)]
        [prop-id network]))))

(defn call-graph-operator
  []
  (operator-value/propagator-operator
   {:name 'call-graph
    :output-selector
    (fn [arg-ids fallback-id]
      (let [[_closure-id explicit-out-id] (vec arg-ids)]
        [(or explicit-out-id fallback-id)]))
    :input-selector
    (fn [arg-ids _fallback-id _context-id]
      (let [[closure-id _explicit-out-id] (vec arg-ids)]
        (when-not (and closure-id (<= 1 (count arg-ids) 2))
          (throw (ex-info "call-graph expects a closure and optional output"
                          {:arg-ids arg-ids})))
        [closure-id (graph-cell-id closure-id)]))
    :activate
    (fn [network inputs outputs _context-id]
      (let [[closure-id graph-id] inputs]
        (call-graph-messages closure-id graph-id (first outputs) network)))}))
