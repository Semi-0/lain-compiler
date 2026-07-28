(ns propagators.observer-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.compiler-2.observer :as compiler-observer]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.observer :as observer]))

(deftest observer-installs-without-changing-propagation-topology
  (let [n0 (nb/install-cell net/empty-net (ids/new-node-id))
        graph-before (net/net-graph n0)
        env-before (net/net-env n0)
        [observer-id n1]
        ((observer/construct-observer :test/observer (constantly :sampled)) n0)]
    (is (= graph-before (net/net-graph n1)))
    (is (= env-before (net/net-env n1)))
    (is (observer/observer? (observer/network-observer n1 observer-id)))
    (is (= :sampled (observer/sample-observer n1 observer-id)))))

(deftest observer-pulls-from-the-network-supplied-at-sampling-time
  (let [cell-id (ids/new-node-id)
        n0 (nb/install-cell net/empty-net cell-id 1 1)
        [observer-id n1]
        ((observer/construct-observer
          :test/current-value
          #(net/network-cell-strongest % cell-id))
         n0)
        n2 (nb/seed-cell n1 cell-id 2)]
    (is (= 1 (observer/sample-observer n1 observer-id)))
    (is (= 2 (observer/sample-observer n2 observer-id)))))

(deftest compiler-2-observer-shares-the-network-constructor-interface
  (let [observer-id (ids/new-node-id)
        installer (compiler-observer/p:observer
                   observer-id
                   :compiler-2/semantic-sample
                   (fn [network]
                     {:graph-nodes (count (net/net-graph network))}))
        [installed-id network] (installer net/empty-net)]
    (is (= observer-id installed-id))
    (is (empty? (net/net-graph network)))
    (is (empty? (net/net-env network)))
    (is (= {:graph-nodes 0}
           (observer/sample-observer network observer-id)))))

(deftest observer-rejects-invalid-construction-and-lookup
  (testing "sample must be executable"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"sample must be a function"
                          (observer/construct-observer :test/invalid nil))))
  (testing "sampling requires an installed observer"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"observer is not installed"
                          (observer/sample-observer net/empty-net
                                                    (ids/new-node-id))))))
