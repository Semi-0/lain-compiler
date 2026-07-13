(ns propagators.compiler-2-composition-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.compiler.core :as cps-core]
            [propagators.compiler-2.core :as compiler-core]
            [propagators.compiler-2.compiler.dispatch :as compiler-dispatch]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.main :as compiler]
            [propagators.compiler-2.predicate-core :as predicate-core]
            [propagators.compiler-behavior.core :as behavior-core]
            [propagators.compiler-behavior.main :as behavior-compiler]
            [propagators.compiler-common.core :as common]
            [propagators.datastructures.behavior :as behavior]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn- custom-expr [v]
  (obj/compound-object {ast/type-slot :composition-test
                        ast/value-slot v}))

(defn- compiler-with-custom [calls]
  (common/make-compiler
   (common/compose-rules
    (common/on (common/expression-kind? :composition-test)
               (fn [compile* state expr]
                 (swap! calls inc)
                 (compile* state (ast/lit (ast/value expr)))))
    compiler-core/compiler-dispatch)))

(defn- run-compiled [compiled]
  (nb/run-propagators (:net compiled) (:props compiled)))

(defn- strongest [network id]
  (net/network-cell-strongest network id))

(deftest public-multifn-identity-is-preserved
  (is (instance? clojure.lang.MultiFn compiler/g:compile))
  (is (instance? clojure.lang.MultiFn compiler/g:apply))
  (is (instance? clojure.lang.MultiFn compiler/g:advance))
  (is (identical? compiler/g:compile compiler-core/g:compile))
  (is (identical? compiler/g:apply compiler-core/g:apply))
  (is (identical? compiler/g:advance compiler-core/g:advance))
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
      (is (= 5 (strongest result-net (:cell compiled))))))
  (let [source "(let [x 1] (+ x 2))"
        seed :cps-core-parity
        via-main (compiler/compile-source source (h/default-env) {:seed seed})
        direct (predicate-core/compile-source source (h/default-env) {:seed seed})]
    (is (= (:cell direct) (:cell via-main)))
    (is (= (count (:props direct)) (count (:props via-main))))
    (is (= (count (:applications direct))
           (count (:applications via-main))))))

(deftest compiler-composition-is-local-and-recursive
  (let [left-calls (atom 0)
        right-calls (atom 0)
        left (compiler-with-custom left-calls)
        right (common/make-compiler
               (common/compose-rules
                (common/on (common/expression-kind? :composition-test)
                           (fn [compile* state _expr]
                             (swap! right-calls inc)
                             (compile* state (ast/lit 99))))
                compiler-core/compiler-dispatch))
        expr (ast/sequence* (custom-expr 1) (custom-expr 2))
        left-result (compiler/compile-expr expr (h/default-env)
                                           {:compiler left})
        right-result (compiler/compile-expr expr (h/default-env)
                                            {:compiler right})]
    (is (= 2 @left-calls))
    (is (= 2 @right-calls))
    (is (= 2 (strongest (:net left-result) (:cell left-result))))
    (is (= 99 (strongest (:net right-result) (:cell right-result)))))
  (let [calls (atom 0)
        compile* (compiler-with-custom calls)
        compiled (compiler/compile-expr
                  (ast/let* [['x (ast/lit 1)]] (custom-expr 8))
                  (h/default-env)
                  {:compiler compile*})]
    (is (= 1 @calls))
    (is (= 8 (strongest (:net compiled) (:cell compiled))))))

(deftest compatibility-adapter-makes-explicit-env-authoritative
  (let [left-id (ids/new-node-id)
        right-id (ids/new-node-id)
        left-env (env/bind-local (h/default-env) 'x (env/cell-binding left-id))
        right-env (env/bind-local (h/default-env) 'x (env/cell-binding right-id))
        [_ binding] (compiler/g:compile
                     (ast/sym 'x)
                     left-env
                     {:net net/empty-net
                      :env right-env
                      :seed :compatibility-env
                      :path []
                      :props []
                      :applications []})]
    (is (= left-id (env/binding-id binding)))))

(deftest selected-compiler-survives-delayed-compilation
  (testing "closure bodies"
    (let [calls (atom 0)
          compile* (compiler-with-custom calls)
          expr (ast/app (ast/network [] (custom-expr 41)))
          compiled (compiler/compile-expr expr (h/default-env)
                                          {:compiler compile*})
          result-net (run-compiled compiled)]
      (is (= 1 @calls))
      (is (= 41 (strongest result-net (:cell compiled))))))
  (testing "lazy topology"
    (let [calls (atom 0)
          compile* (compiler-with-custom calls)
          compiled (compiler/compile-expr
                    (ast/when-topology (ast/lit true) (custom-expr 7))
                    (h/default-env)
                    {:compiler compile*})]
      (is (zero? @calls))
      (run-compiled compiled)
      (is (= 1 @calls))))
  (testing "direct list operands"
    (let [calls (atom 0)
          compile* (compiler-with-custom calls)]
      (compiler/compile-expr (ast/app (ast/sym 'list) (custom-expr 3))
                             (h/default-env)
                             {:compiler compile*})
      (is (= 1 @calls))))
  (testing "sub-environment execution"
    (let [calls (atom 0)
          compile* (compiler-with-custom calls)
          parent-id (ids/new-node-id)
          expr-id (ids/new-node-id)
          child-id (ids/new-node-id)
          out-id (ids/new-node-id)
          network (-> net/empty-net
                      (nb/install-cell parent-id)
                      (nb/install-cell expr-id)
                      (nb/install-cell child-id)
                      (nb/install-cell out-id)
                      (nb/seed-cell parent-id (h/default-env))
                      (nb/seed-cell expr-id (custom-expr 17)))
          [prop-id installed]
          ((application/p:execute-sub-env-with compile* parent-id expr-id []
                                                child-id out-id)
           network)
          result-net (nb/run-propagators installed [prop-id])]
      (is (= 1 @calls))
      (is (= 17 (strongest result-net out-id))))))

(deftest lowering-and-closure-output-normalization-are-pure
  (let [lowered (compiler-core/lower-let
                 (ast/let* [['x (ast/lit 1)]] (ast/sym 'x)))]
    (is (= :let-cell (ast/type lowered)))
    (is (= '[x] (ast/names lowered)))
    (is (= :sequence (ast/type (ast/body lowered)))))
  (let [body (ast/sequence* (ast/lit 1) (ast/lit 2))
        implicit (compiler-core/normalize-closure-output 'return nil body)
        explicit (compiler-core/normalize-closure-output 'ignored 'out body)
        multiple (compiler-core/normalize-closure-output 'ignored '[a b] body)]
    (is (= '[return] (:output implicit)))
    (is (= 'out (:output explicit)))
    (is (= '[a b] (:output multiple)))
    (is (= body (:body multiple)))
    (is (= :sequence (ast/type (:body implicit))))))

(deftest cell-declaration-strategy-is-injectable
  (let [operator-id (ids/new-node-id)
        network (nb/install-cell net/empty-net operator-id)
        compiler-env (env/bind-local (h/default-env)
                                     'later
                                     (env/cell-binding operator-id))
        calls (atom 0)
        declarer (fn [_compile* _operator _operands state out-id]
                   (swap! calls inc)
                   [state (env/cell-binding out-id)])]
    (compiler/compile-expr (ast/app (ast/sym 'later))
                           compiler-env
                           {:net network
                            :application/cell-declarer declarer})
    (is (= 1 @calls))
    (is (= compiler-core/declare-runtime-cell-application
           (compiler-core/resolve-cell-declarer {})))
    (is (= compiler-core/declare-retained-cell-application
           (compiler-core/resolve-cell-declarer
            {:application/cell-declarer :retained-frame})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"unknown application cell declarer"
         (compiler-core/resolve-cell-declarer
          {:application/cell-declarer :unknown})))))

(deftest behavior-compiler-reuses-composed-traversal-with-own-values
  (let [behavior-result (behavior-compiler/compile-expr
                         (ast/sequence* (ast/lit 1))
                         (h/behavior-env)
                         {:timestamp 7
                          :compiler behavior-core/default-compiler})
        current-result (compiler/compile-expr (ast/sequence* (ast/lit 1)))
        behavior-value (strongest (:net behavior-result) (:cell behavior-result))]
    (is (= 1 (behavior/base-value behavior-value)))
    (is (= 1 (strongest (:net current-result) (:cell current-result))))))
