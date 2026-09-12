(ns propagators.compiler.call-graph-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.infra.cells.value :as value]
            [propagators.compiler.compiler.basis :as helpers]
            [propagators.compiler.main :as compiler]
            [propagators.compiler.model.closure-value :as closure-value]
            [propagators.compiler.model.env :as env]
            [propagators.compiler.model.operator-value :as operator-value]
            [propagators.compiler.operators.call-graph :as call-graph]
            [propagators.compiler.lowering.application :as application]
            [propagators.infra.datastructures.compound-object :as obj]
            [propagators.infra.ids :as ids]
            [propagators.infra.network :as net]
            [propagators.infra.network-builder :as nb]
            [propagators.infra.propagator :as prop]))

(defn- run-compiled
  [compiled]
  (nb/run-propagators (:net compiled) (:props compiled)))

(defn- strongest
  [network id]
  (net/network-cell-strongest network id))

(defn- binding-id
  [network sym]
  (let [ids (keep (fn [[id name]] (when (= sym name) id))
                  (env/binding-names network))]
    (when (= 1 (count ids)) (first ids))))

(defn- graph-statuses
  [graph]
  (set (keep :call/status (vals (:values graph)))))

(deftest call-graph-is-a-primitive-and-a-named-propagator
  (is (operator-value/operator-closure?
       (env/lookup (helpers/default-env) 'call-graph)))
  (is (operator-value/operator-closure?
       (env/lookup (helpers/default-env) 'p:call-graph)))
  (let [compiled (compiler/compile-source
                  "(call-graph (:: [x] (+ x 1)))")
        network (run-compiled compiled)
        graph (strongest network (:cell compiled))
        prop-names (set (keep (fn [[_ entry]]
                                (when (prop/prop? entry)
                                  (prop/prop-name entry)))
                              (net/net-env network)))]
    (is (= #{:potential} (graph-statuses graph)))
    (is (contains? (set (vals (:nodes graph))) "+"))
    (is (contains? prop-names 'call-graph))))

(deftest potential-call-graph-represents-recursion-as-a-cycle
  (let [compiled (compiler/compile-source
                  "(let-cell [self graph]
                     (def-net self [x] [out]
                       (self x out))
                     (call-graph self graph)
                     graph)")
        network (run-compiled compiled)
        graph (strongest network (:cell compiled))
        self-id (binding-id network 'self)
        recursive-call-id (some (fn [[id label]]
                                  (when (= "potential self" label) id))
                                (:nodes graph))]
    (is (some? self-id))
    (is (some? recursive-call-id))
    (is (contains? (set (:edges graph)) [self-id recursive-call-id]))
    (is (contains? (set (:edges graph)) [recursive-call-id self-id]))))

(deftest realized-recursion-also-remains-a-finite-cycle
  (let [closure-id (ids/new-node-id)
        application-id (ids/new-node-id)
        compiled (compiler/compile-source "(:: [x] x)")
        closure (strongest (:net compiled) (:cell compiled))
        graph (call-graph/realized-call-graph closure-id
                                              application-id
                                              closure-id
                                              closure)]
    (is (contains? (set (:edges graph)) [closure-id application-id]))
    (is (contains? (set (:edges graph)) [application-id closure-id]))))

(deftest connected-closure-applications-publish-realized-call-facts
  (let [compiled (compiler/compile-source
                  "(let-cell [f out graph]
                     (def-net f [x] [out]
                       (+ x 1))
                     (f 2 out)
                     (call-graph f graph)
                     graph)")
        network (run-compiled compiled)
        graph (strongest network (:cell compiled))]
    (is (contains? (graph-statuses graph) :potential))
    (is (contains? (graph-statuses graph) :realized))
    (is (some #{"call +"} (vals (:nodes graph))))
    (is (some #{:compiler-2/call-graph-application}
              (keep (fn [[_ entry]]
                      (when (prop/prop? entry) (prop/prop-name entry)))
                    (net/net-env network))))))

(deftest late-operator-arrival-refines-the-call-graph
  (let [compiled (compiler/compile-source
                  "(let-cell [g outer out graph]
                     (def-net outer [x] [out]
                       (g x out))
                     (outer 2 out)
                     (call-graph outer graph)
                     graph)")
        waiting (run-compiled compiled)
        graph-id (:cell compiled)
        waiting-graph (strongest waiting graph-id)
        g-id (binding-id waiting 'g)
        callee-compiled (compiler/compile-source
                         "(network [x] [out] (+ x 1))")
        callee (strongest (:net callee-compiled) (:cell callee-compiled))
        with-callee (nb/seed-cell waiting g-id callee)
        settled (nb/run-propagators
                 with-callee
                 (nb/neighbor-propagator-ids with-callee g-id))
        settled-graph (strongest settled graph-id)
        waiting-labels (set (vals (:nodes waiting-graph)))
        settled-labels (set (vals (:nodes settled-graph)))]
    (is (not (contains? waiting-labels "call g")))
    (is (not (value/unusable? callee)))
    (is (contains? settled-labels "call g"))))

(deftest pure-call-site-extraction-is-independent-of-runtime-state
  (let [compiled (compiler/compile-source
                  "(:: [x] (+ (* x 2) 1))")
        closure (strongest (:net compiled) (:cell compiled))
        labels (mapv :operator-label
                     (call-graph/call-sites
                      (closure-value/closure-body
                       (application/callable-declaration closure))))]
    (is (= ["->" "+" "*"] labels))))
