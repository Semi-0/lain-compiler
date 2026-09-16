(ns propagators.compiler.composition-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.compiler.lowering.application :as application]
            [propagators.compiler.compiler.declarations :as declarations]
            [propagators.compiler.language.ast :as ast]
            [propagators.compiler.language.parser :as parser]
            [propagators.compiler.cps-core :as cps-core]
            [propagators.compiler.compiler.dispatch :as compiler-dispatch]
            [propagators.compiler.model.env :as env]
            [propagators.compiler.compiler.basis :as h]
            [propagators.compiler.main :as compiler]
            [propagators.compiler.behavior.core :as behavior-core]
            [propagators.compiler.behavior.main :as behavior-compiler]
            [propagators.compiler.common.cps :as cps]
            [propagators.compiler.common.core :as common]
            [propagators.infra.datastructures.behavior :as behavior]
            [propagators.infra.datastructures.compound-object :as obj]
            [propagators.infra.datastructures.dependency :as dependency]
            [propagators.infra.datastructures.scope-source :as scope-source]
            [propagators.infra.gur :as gur]
            [propagators.infra.ids :as ids]
            [propagators.infra.network :as net]
            [propagators.infra.network-builder :as nb]))

(defn- custom-expr [v]
  (obj/compound-object {ast/type-slot :composition-test
                        ast/value-slot v}))

(defn- compiler-with-custom [calls]
  (cps/make-compiler
   (cps/compose-rules
    (cps/on (common/expression-kind? :composition-test)
               (fn [compile-k state expr k]
                 (swap! calls inc)
                 (cps/call compile-k state (ast/lit (ast/value expr)) k)))
    cps-core/compiler-dispatch)))

(defn- run-compiled [compiled]
  (nb/run-propagators (:net compiled) (:props compiled)))

(defn- strongest [network id]
  (net/network-cell-strongest network id))

(defn- semantic-value [network id]
  (loop [v (strongest network id)]
    (cond
      (scope-source/scope-value? v)
      (recur (scope-source/base-value v))

      (dependency/dependency-value? v)
      (recur (dependency/base-value v))

      :else v)))

(deftest active-compiler-entry-points-are-preserved
  (is (identical? compiler/default-compiler cps-core/default-compiler))
  (is (identical? cps-core/compile* cps-core/default-compiler))
  (is (identical? behavior-compiler/g:compile behavior-core/g:compile))
  (is (identical? behavior-compiler/g:apply behavior-core/g:apply))
  (is (identical? behavior-compiler/g:advance behavior-core/g:advance)))

(deftest main-compilation-migrates-to-cps-core
  (is (identical? compiler/default-compiler cps-core/default-compiler))
  (with-redefs [compiler-dispatch/compile-expression
                (fn [& _]
                  (throw (ex-info "generic compiler was called" {})))]
    (let [compiled (compiler/compile-source "((:: [x] (+ x 1)) 4)")
          result-net (run-compiled compiled)]
      (is (= 5 (semantic-value result-net (:cell compiled))))))
  (let [source "(let [x 1] (+ x 2))"
        seed :cps-core-parity
        via-main (compiler/compile-source source)
        direct (cps-core/compile-expr-with-bindings
                (parser/parse-string source) (h/default-bindings)
                {:seed seed})
        direct-net (run-compiled direct)
        main-net (run-compiled via-main)]
    (is (= (strongest direct-net (:cell direct))
           (semantic-value main-net (:cell via-main))))
    (is (= (count (:applications direct))
           (count (:applications via-main))))))

(deftest compiler-composition-is-local-and-recursive
  (let [left-calls (atom 0)
        right-calls (atom 0)
        left (compiler-with-custom left-calls)
        right (cps/make-compiler
               (cps/compose-rules
                (cps/on (common/expression-kind? :composition-test)
                           (fn [compile-k state _expr k]
                             (swap! right-calls inc)
                             (cps/call compile-k state (ast/lit 99) k)))
                cps-core/compiler-dispatch))
        expr (ast/sequence* (custom-expr 1) (custom-expr 2))
        left-result (cps-core/compile-expr-with-bindings
                     expr (h/default-bindings) {:compiler left})
        right-result (cps-core/compile-expr-with-bindings
                      expr (h/default-bindings) {:compiler right})]
    (is (= 2 @left-calls))
    (is (= 2 @right-calls))
    (is (= 2 (strongest (:net left-result) (:cell left-result))))
    (is (= 99 (strongest (:net right-result) (:cell right-result)))))
  (let [calls (atom 0)
        compile* (compiler-with-custom calls)
        compiled (cps-core/compile-expr-with-bindings
                  (ast/let* [['x (ast/lit 1)]] (custom-expr 8))
                  (h/default-bindings)
                  {:compiler compile*})]
    (is (= 1 @calls))
    (is (= 8 (strongest (:net compiled) (:cell compiled))))))

(deftest explicit-live-env-is-authoritative
  (let [left-id (ids/new-node-id)
        right-id (ids/new-node-id)
        left-env (ids/new-node-id)
        right-env (ids/new-node-id)
        left (env/declare-root net/empty-net left-env
                               [['x (env/cell-binding left-id)]])
        right (env/declare-root (:net left) right-env
                                [['x (env/cell-binding right-id)]])
        [_ binding] (cps-core/default-compiler
                     {:net (:net right)
                      :env left-env
                      :seed :explicit-live-env
                      :path []
                      :props []
                      :applications []
                      :compiler cps-core/default-compiler}
                     (ast/sym 'x))]
    (is (= left-id (env/binding-id binding)))))

(deftest selected-compiler-survives-delayed-compilation
  (testing "closure bodies"
    (let [calls (atom 0)
          compile* (compiler-with-custom calls)
          expr (ast/app (ast/network [] (custom-expr 41)))
          compiled (cps-core/compile-expr-with-bindings
                    expr (h/default-bindings) {:compiler compile*})
          result-net (run-compiled compiled)]
      (is (= 1 @calls))
      (is (= 41 (semantic-value result-net (:cell compiled))))))
  (testing "lazy topology"
    (let [calls (atom 0)
          compile* (compiler-with-custom calls)
          compiled (cps-core/compile-expr-with-bindings
                    (ast/when-topology (ast/lit true) (custom-expr 7))
                    (h/default-bindings)
                    {:compiler compile*})]
      (is (zero? @calls))
      (run-compiled compiled)
      (is (= 1 @calls))))
  (testing "direct list operands"
    (let [calls (atom 0)
          compile* (compiler-with-custom calls)]
      (cps-core/compile-expr-with-bindings
       (ast/app (ast/sym 'list) (custom-expr 3))
       (h/default-bindings) {:compiler compile*})
      (is (= 1 @calls))))
  (testing "sub-environment execution"
    (let [calls (atom 0)
          compile* (compiler-with-custom calls)
          parent-id (ids/new-node-id)
          expr-id (ids/new-node-id)
          child-id (ids/new-node-id)
          out-id (ids/new-node-id)
          declared (env/declare-root net/empty-net parent-id
                                     (h/default-bindings))
          network (-> (:net declared)
                      (nb/install-cell expr-id)
                      (nb/install-cell child-id)
                      (nb/install-cell out-id)
                      (nb/seed-cell expr-id (custom-expr 17)))
          rooted (nb/run-propagators network (:props declared))
          [prop-id installed]
          ((application/p:execute-sub-env-with compile* parent-id expr-id []
                                                child-id out-id)
           rooted)
          result-net (nb/run-propagators installed [prop-id])]
      (is (= 1 @calls))
      (is (= 17 (strongest result-net out-id))))))

(deftest lowering-and-closure-output-normalization-are-pure
  (let [lowered (declarations/lower-let
                 (ast/let* [['x (ast/lit 1)]] (ast/sym 'x)))]
    (is (= :let-cell (ast/type lowered)))
    (is (= '[x] (ast/names lowered)))
    (is (= :sequence (ast/type (ast/body lowered)))))
  (let [body (ast/sequence* (ast/lit 1) (ast/lit 2))
        implicit (declarations/normalize-closure-output 'return nil body)
        explicit (declarations/normalize-closure-output 'ignored 'out body)
        multiple (declarations/normalize-closure-output 'ignored '[a b] body)]
    (is (= '[return] (:output implicit)))
    (is (= 'out (:output explicit)))
    (is (= '[a b] (:output multiple)))
    (is (= body (:body multiple)))
    (is (= :sequence (ast/type (:body implicit))))))

(deftest runtime-cell-application-is-canonical-flat-gur
  (let [operator-id (ids/new-node-id)
        network (nb/install-cell net/empty-net operator-id)]
    (let [compiled
          (cps-core/compile-expr-with-bindings
           (ast/app (ast/sym 'later))
           (conj (vec (h/default-bindings))
                 ['later (env/cell-binding operator-id)])
           {:net network})
          applications (application/application-topologies (:net compiled))
          application-id (:application-id (first applications))
          apply-prop-id (gur/stable-node-id [application-id :apply-prop])]
      (is (= 1 (count applications)))
      (is (= [application-id] (:applications compiled)))
      (is (contains? (set (:props compiled)) apply-prop-id)))))

(deftest behavior-compiler-reuses-composed-traversal-with-own-values
  (let [behavior-result (behavior-compiler/compile-expr
                         (ast/sequence* (ast/lit 1))
                         (h/behavior-bindings)
                         {:timestamp 7
                          :compiler behavior-core/default-compiler})
        current-result (compiler/compile-expr (ast/sequence* (ast/lit 1)))
        behavior-value (strongest (:net behavior-result) (:cell behavior-result))]
    (is (= 1 (behavior/base-value behavior-value)))
    (is (= 1 (strongest (:net current-result) (:cell current-result))))))
