(ns propagators.compiler.application-runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.infra.cells.value :as value]
            [propagators.compiler.lowering.application :as application]
            [propagators.infra.core :as core]
            [propagators.infra.gur :as gur]
            [propagators.infra.ids :as ids]
            [propagators.infra.message :refer [message]]
            [propagators.infra.network :as net]
            [propagators.infra.network-builder :as nb]
            [propagators.infra.propagator :as prop]))

(defn- increment-installer
  [network argument-ids result-id _context-id]
  (let [[argument-id] argument-ids
        prepared (reduce nb/ensure-cell network [argument-id result-id])
        [prop-id installed]
        (((prop/concrete-primitive-propagator :test/increment inc)
          argument-id
          result-id)
         prepared)]
    [installed [prop-id] result-id]))

(defn- test-callable
  []
  (application/primitive-callable
   :test/increment
   (gur/stable-node-id [:test :increment-declaration])
   increment-installer
   {:name :test/increment}))

(defn- installed-application
  []
  (let [operator-id (ids/new-node-id)
        argument-id (ids/new-node-id)
        context-id (ids/new-node-id)
        result-id (ids/new-node-id)
        base (reduce nb/ensure-cell net/empty-net
                     [operator-id argument-id context-id result-id])
        [state _binding]
        (application/install-application
         {:net base
          :props []
          :context-id context-id
          :application/app-id [:test :application]}
         operator-id
         [argument-id]
         result-id)]
    {:state state
     :operator-id operator-id
     :argument-id argument-id
     :result-id result-id}))

(defn- seed-and-run
  [network id candidate]
  (let [[tasks seeded]
        (core/eval-cell id (message id candidate) network)]
    (core/run-tasks tasks seeded)))

(defn- prop-count
  [network]
  (count (filter prop/prop? (vals (net/net-env network)))))

(deftest application-protocol-declares-flat-topology
  (let [{:keys [state operator-id argument-id result-id]}
        (installed-application)
        initial
        (nb/run-propagators (:net state) (:props state))
        with-operator
        (seed-and-run initial operator-id (test-callable))
        complete
        (seed-and-run with-operator argument-id 4)
        name-bindings
        (get (net/network-dict-entry complete gur/name-bindings-key)
             application/application-name-scope)]
    (is (= 5 (net/network-cell-strongest complete result-id)))
    (is (= 1 (count (:props state))))
    (is (contains? name-bindings [:test :application]))))

(deftest application-waits-for-late-operator-and-argument
  (let [{:keys [state operator-id argument-id result-id]}
        (installed-application)
        initial
        (nb/run-propagators (:net state) (:props state))
        with-argument
        (seed-and-run initial argument-id 8)
        complete
        (seed-and-run with-argument operator-id (test-callable))]
    (is (= value/nothing
           (net/network-cell-strongest initial result-id)))
    (is (= value/nothing
           (net/network-cell-strongest with-argument result-id)))
    (is (= 9 (net/network-cell-strongest complete result-id)))))

(deftest contradictory-operator-waits-without-special-policy
  (let [{:keys [state operator-id result-id]}
        (installed-application)
        initial
        (nb/run-propagators (:net state) (:props state))
        contradicted
        (seed-and-run initial operator-id value/contradiction)]
    (is (= value/nothing
           (net/network-cell-strongest contradicted result-id)))))

(deftest equivalent-reactivation-does-not-grow-topology
  (let [{:keys [state operator-id argument-id]}
        (installed-application)
        initial
        (nb/run-propagators (:net state) (:props state))
        with-operator
        (seed-and-run initial operator-id (test-callable))
        complete
        (seed-and-run with-operator argument-id 3)
        reactivated
        (nb/run-propagators complete (:props state))]
    (is (= (prop-count complete) (prop-count reactivated)))))
