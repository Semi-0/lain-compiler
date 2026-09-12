(ns propagators.compiler.closure-frame-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.compiler.compiler.basis :as basis]
            [propagators.compiler.main :as compiler]
            [propagators.compiler.model.env :as env]
            [propagators.infra.core :as core]
            [propagators.infra.gur :as gur]
            [propagators.infra.ids :as ids]
            [propagators.infra.message :refer [message]]
            [propagators.infra.network :as net]
            [propagators.infra.network-builder :as nb]
            [propagators.infra.propagator :as prop]))

(defn- run-compiled
  [compiled]
  (nb/run-propagators (:net compiled) (:props compiled)))

(defn- strongest
  [network id]
  (net/network-cell-strongest network id))

(defn- prop-count
  [network]
  (count (filter prop/prop? (vals (net/net-env network)))))

(defn- seed-and-run
  [network id candidate]
  (let [[tasks seeded]
        (core/eval-cell id (message id candidate) network)]
    (core/run-tasks tasks seeded)))

(deftest closure-builds-flat-frame-in-active-network
  (let [compiled (compiler/compile-source "((:: [x] (+ x 1)) 4)")
        result (run-compiled compiled)
        names (net/network-dict-entry result gur/name-bindings-key)
        frames (get names [:gur.flat :frames])]
    (is (= 5 (strongest result (:cell compiled))))
    (is (seq frames))))

(deftest captured-parent-binding-arrives-late
  (let [bias-id (ids/new-node-id)
        initial (nb/install-cell net/empty-net bias-id)
        compiler-env (env/bind (basis/default-env)
                               'bias
                               (env/cell-binding bias-id)
                               0)
        compiled (compiler/compile-source "((:: [x] (+ x bias)) 5)"
                                          compiler-env
                                          {:net initial})
        waiting (run-compiled compiled)
        complete (seed-and-run waiting bias-id 4)]
    (is (= :bool4/nothing (strongest waiting (:cell compiled))))
    (is (= 9 (strongest complete (:cell compiled))))))

(deftest declared-local-blocks-parent-and-wakes-late
  (let [outer-x-id (ids/new-node-id)
        input-id (ids/new-node-id)
        initial (-> net/empty-net
                    (nb/install-cell outer-x-id 100 100)
                    (nb/install-cell input-id))
        compiler-env (-> (basis/default-env)
                         (env/bind 'x (env/cell-binding outer-x-id) 0)
                         (env/bind 'input (env/cell-binding input-id) 0))
        compiled (compiler/compile-source "((:: [x] (+ x 1)) input)"
                                          compiler-env
                                          {:net initial})
        waiting (run-compiled compiled)
        complete (seed-and-run waiting input-id 5)]
    (is (= :bool4/nothing (strongest waiting (:cell compiled))))
    (is (= 6 (strongest complete (:cell compiled))))
    (is (= 100 (strongest complete outer-x-id)))))

(deftest returned-closure-composes-as-produced-operator
  (let [compiled
        (compiler/compile-source "(((:: [x] (:: [y] (+ x y))) 4) 5)")
        result
        (run-compiled compiled)]
    (is (= 9 (strongest result (:cell compiled))))))

(deftest recursive-frame-growth-is-stable
  (let [source
        "(let-cell [out]
           (def-net down [a] [out]
             (when (switch true (<= a 1)) (-> a out))
             (when (switch true (> a 1)) (down (- a 1) out)))
           (down 4 out)
           out)"
        compiled (compiler/compile-source source)
        once (run-compiled compiled)
        twice (nb/run-propagators once (:props compiled))]
    (is (= 1 (strongest once (:cell compiled))))
    (is (= (prop-count once) (prop-count twice)))))
