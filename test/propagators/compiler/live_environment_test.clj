(ns propagators.compiler.live-environment-test
  (:require [clojure.test :refer [deftest is]]
            [propagators.infra.cells.value :as value]
            [propagators.compiler.model.env :as env]
            [propagators.infra.core :as core]
            [propagators.infra.ids :as ids]
            [propagators.infra.message :refer [message]]
            [propagators.infra.network :as net]
            [propagators.infra.network-builder :as nb]))

(defn- run [declared extra-props]
  (nb/run-propagators (:net declared) (into (:props declared) extra-props)))

(deftest root-and-child-frames-use-live-slot-topology
  (let [root (ids/new-node-id)
        child (ids/new-node-id)
        value-id (ids/new-node-id)
        root-declaration (env/declare-root net/empty-net root
                                           [['x (env/cell-binding value-id)]])
        child-declaration (env/declare-child (:net root-declaration)
                                             root child [])]
    (is (= root (:env root-declaration)))
    (is (= child (:env child-declaration)))
    (is (= value-id
           (env/lexical-binding-id (:net child-declaration) 'x child)))))

(deftest flat-lookup-waits-for-and-observes-late-parent-value
  (let [root (ids/new-node-id)
        child (ids/new-node-id)
        value-id (ids/new-node-id)
        binding-answer (ids/new-node-id)
        value-answer (ids/new-node-id)
        root-declaration (env/declare-root net/empty-net root
                                           [['x (env/cell-binding value-id)]])
        child-declaration (env/declare-child (:net root-declaration)
                                             root child [])
        [lookup-props with-lookup]
        ((env/p:lexical-access-local-first 'x child binding-answer)
         (:net child-declaration))
        [value-props installed]
        ((env/p:binding-value binding-answer value-answer) with-lookup)
        props (into (into (:props child-declaration) lookup-props) value-props)
        before (nb/run-propagators installed props)
        [tasks updated] (core/eval-cells [(message value-id 9)] before)
        after (core/run-tasks tasks updated)]
    (is (value/nothing? (net/network-cell-strongest before value-answer)))
    (is (= 9 (net/network-cell-strongest after value-answer)))))

(deftest declared-local-shadows-parent
  (let [root (ids/new-node-id)
        child (ids/new-node-id)
        parent-value (ids/new-node-id)
        child-value (ids/new-node-id)
        answer (ids/new-node-id)
        root-declaration (env/declare-root net/empty-net root
                                           [['x (env/cell-binding parent-value)]])
        child-declaration (env/declare-child (:net root-declaration)
                                             root child
                                             [['x (env/cell-binding child-value)]])
        [props installed]
        ((env/p:lexical-access-local-first 'x child answer)
         (:net child-declaration))
        settled (run (assoc child-declaration :net installed) props)]
    (is (= (env/cell-binding child-value)
           (net/network-cell-strongest settled answer)))))

(deftest undefined-symbol-remains-unavailable
  (let [root (ids/new-node-id)
        answer (ids/new-node-id)
        declared (env/declare-root net/empty-net root [])
        [props installed]
        ((env/p:lexical-access-local-first 'missing root answer) (:net declared))
        settled (run (assoc declared :net installed) props)]
    (is (value/nothing? (net/network-cell-strongest settled answer)))))

(deftest equivalent-redeclaration-is-stable
  (let [root (ids/new-node-id)
        value-id (ids/new-node-id)
        bindings [['x (env/cell-binding value-id)]]
        first-declaration (env/declare-root net/empty-net root bindings)
        second-declaration (env/declare-root (:net first-declaration)
                                             root bindings)]
    (is (= (count (net/net-env (:net first-declaration)))
           (count (net/net-env (:net second-declaration)))))
    (is (= (count (net/net-graph (:net first-declaration)))
           (count (net/net-graph (:net second-declaration)))))))

(deftest materialized-environment-is-rejected
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"live environment cell"
       (env/declare-bindings net/empty-net {} [['x 1]]))))
