(ns propagators.compiler-2-cps-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.compiler-2.runtime.application :as application]
            [propagators.compiler-2.compiler.rewrite :as rewrite]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.cps-core :as compiler]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.compiler-2.predicate-core :as predicate-core]
            [propagators.compiler-common.cps :as cps]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.dependency :as dependency]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn- custom-expr [value]
  (obj/compound-object {ast/type-slot :cps-test
                        ast/value-slot value}))

(defn- direct-operator-expr [operator]
  (obj/compound-object {ast/type-slot :direct-operator-test
                        ast/value-slot operator}))

(defn- compiler-with-custom [calls]
  (cps/make-compiler
   (cps/compose-rules
    (cps/on #(= :cps-test (ast/type %))
            (fn [compile-k state expr k]
              (swap! calls inc)
              (cps/call compile-k state (ast/lit (ast/value expr)) k)))
    compiler/compiler-dispatch)))

(defn- run-compiled [compiled]
  (nb/run-propagators (:net compiled) (:props compiled)))

(defn- semantic-value [network id]
  (loop [v (net/network-cell-strongest network id)]
    (cond
      (scope-source/scope-value? v)
      (recur (scope-source/base-value v))

      (dependency/dependency-value? v)
      (recur (dependency/base-value v))

      :else v)))

(deftest cps-primitives-schedule-work-and-fall-through
  (let [events (atom [])
        dispatch
        (cps/compose-rules
         (cps/on #(= :handled (:kind %))
                 (fn [_compile-k state expr k]
                   (swap! events conj :handled)
                   (cps/continue k state (:value expr))))
         (fn [_compile-k state expr k]
           (swap! events conj :fallback)
           (cps/continue k state (:value expr))))
        compile* (cps/make-compiler dispatch)]
    (is (= [{:compiler compile*} 1]
           (compile* {} {:kind :handled :value 1})))
    (is (= [{:compiler compile*} 2]
           (compile* {} {:kind :other :value 2})))
    (is (= [:handled :fallback] @events)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"continuation failure"
       ((cps/make-compiler
         (fn [_compile-k _state _expr _k]
           #(throw (ex-info "continuation failure" {}))))
        {} :expr))))

(deftest transform-expr-composes-surface-terms-with-canonical-handlers
  (let [seen (atom nil)
        handler (cps/transform-expr
                 #(update % :value inc)
                 (fn [_compile-k state expr k]
                   (reset! seen [state expr])
                   (cps/continue k state (:value expr))))
        compile* (cps/make-compiler handler)]
    (is (= [{:compiler compile*} 2]
           (compile* {} {:value 1})))
    (is (= [{:compiler compile*} {:value 2}] @seen)))
  (let [body (ast/lit 1)
        let-expr (rewrite/let-cell->let (ast/let-cell ['x] body))
        network-expr (rewrite/compound->network
                      (ast/compound {:inputs ['x] :output ['out]} body))
        def-expr (rewrite/def-net->def
                  (ast/def-net 'f ['x] ['out] body))
        constraint-expr (rewrite/def-constraint->def
                         (ast/def-constraint 'same ['x 'y] body))
        constraint-network (ast/body constraint-expr)]
    (is (= :let (ast/type let-expr)))
    (is (= [['x nil]] (ast/bindings let-expr)))
    (is (= :network (ast/type network-expr)))
    (is (= ['out] (ast/output network-expr)))
    (is (= :def (ast/type def-expr)))
    (is (= 'f (ast/name def-expr)))
    (is (= ['x] (ast/inputs (ast/body def-expr))))
    (is (= ['out] (ast/output (ast/body def-expr))))
    (is (= :def (ast/type constraint-expr)))
    (is (= 'same (ast/name constraint-expr)))
    (is (= ['x 'y] (ast/inputs constraint-network)))
    (is (nil? (ast/output constraint-network)))
    (is (= 'y (ast/name (last (ast/body (ast/body constraint-network))))))))

(deftest cps-default-preserves-synchronous-result-semantics
  (let [compiler-env (h/default-env)]
    (doseq [source ["1"
                    "(+ 1 2)"
                    "(let [x 1] (+ x 2))"]]
      (testing source
        (let [seed [:cps-parity source]
              expected (predicate-core/compile-source source compiler-env
                                                      {:seed seed})
              actual (compiler/compile-source source compiler-env {:seed seed})
              expected-net (run-compiled expected)
              actual-net (run-compiled actual)]
          (is (= (semantic-value expected-net (:cell expected))
                 (semantic-value actual-net (:cell actual)))))))))

(deftest lexical-scope-construction-restores-and-reuses-frames
  (let [root-id (ids/new-node-id)
        [network _] (env/import-environment net/empty-net root-id
                                            (h/default-env))
        compiled (compiler/compile-expr
                  (ast/let-cell ['x] (ast/sym 'x))
                  root-id
                  {:net network :seed :restore-let-env})]
    (is (= root-id (:env compiled))))
  (let [env-id (ids/new-node-id)
        network (nb/ensure-cell net/empty-net env-id)
        [props installed] ((env/p:sub-env env-id env-id) network)]
    (is (empty? props))
    (is (= network installed))))

(deftest cps-compilation-is-stack-safe
  (testing "five thousand nested lexical scopes"
    (let [expr (reduce (fn [body idx]
                         (ast/let-cell [(symbol (str "x" idx))] body))
                       (ast/lit 1)
                       (range 5000))
          compiled (compiler/compile-expr expr (h/default-env)
                                          {:seed :deep-let})]
      (is (= 1 (net/network-cell-strongest (:net compiled) (:cell compiled))))))
  (testing "five thousand nested sequences"
    (let [expr (reduce (fn [body _] (ast/sequence* body))
                       (ast/lit 2)
                       (range 5000))
          compiled (compiler/compile-expr expr (h/default-env)
                                          {:seed :deep-sequence})]
      (is (= 2 (net/network-cell-strongest (:net compiled)
                                           (:cell compiled))))))
  (testing "nested ordinary and built-in direct applications"
    (let [ordinary (reduce (fn [value _]
                             (ast/app (ast/sym '+) value (ast/lit 1)))
                           (ast/lit 0)
                           (range 2000))
          direct (reduce (fn [value _]
                           (ast/app (ast/sym 'list) value))
                         (ast/lit 0)
                         (range 2000))]
      (is (= 2000 (count (:applications
                          (compiler/compile-expr ordinary (h/default-env)
                                                 {:seed :deep-application})))))
      (is (some? (:cell (compiler/compile-expr direct (h/default-env)
                                               {:seed :deep-list})))))))

(deftest local-cps-compiler-survives-delayed-and-direct-work
  (let [calls (atom 0)
        compile* (compiler-with-custom calls)]
    (testing "closure activation"
      (let [compiled (compiler/compile-expr
                      (ast/app (ast/network [] (custom-expr 41)))
                      (h/default-env)
                      {:compiler compile*})
            result (run-compiled compiled)]
        (is (= 41 (net/network-cell-strongest result (:cell compiled))))))
    (testing "lazy activation"
      (let [before @calls
            compiled (compiler/compile-expr
                      (ast/when-topology (ast/lit true) (custom-expr 7))
                      (h/default-env)
                      {:compiler compile*})]
        (is (= before @calls))
        (run-compiled compiled)
        (is (= (inc before) @calls))))
    (testing "list direct compiler"
      (let [before @calls]
        (compiler/compile-expr
         (ast/app (ast/sym 'list) (custom-expr 3))
         (h/default-env)
         {:compiler compile*})
        (is (= (inc before) @calls))))
    (testing "sub-environment execution"
      (let [before @calls
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
            ((application/p:execute-sub-env-with
              compile* parent-id expr-id [] child-id out-id)
             network)
            result (nb/run-propagators installed [prop-id])]
        (is (= (inc before) @calls))
        (is (= 17 (net/network-cell-strongest result out-id)))))))

(deftest direct-compiler-is-additive-over-legacy-installer
  (let [legacy-calls (atom 0)
        cps-calls (atom 0)
        legacy (operator-value/operator-closure
                {:name 'legacy
                 :direct-installer
                 (fn [state _forms out-id]
                   (swap! legacy-calls inc)
                   [state (env/cell-binding out-id)])})
        preferred (operator-value/operator-closure
                   {:name 'preferred
                    :direct-installer
                    (fn [state _forms out-id]
                      (swap! legacy-calls inc)
                      [state (env/cell-binding out-id)])
                    :direct-compiler
                    (fn [_compile-k state _forms out-id k]
                      (swap! cps-calls inc)
                      (cps/continue k state (env/cell-binding out-id)))})
        compile* (cps/make-compiler
                  (cps/compose-rules
                   (cps/on #(= :direct-operator-test (ast/type %))
                           (fn [_compile-k state expr k]
                             (cps/continue k state (ast/value expr))))
                   compiler/compiler-dispatch))]
    (compiler/compile-expr (ast/app (direct-operator-expr legacy))
                           (h/default-env)
                           {:compiler compile*})
    (compiler/compile-expr (ast/app (direct-operator-expr preferred))
                           (h/default-env)
                           {:compiler compile*})
    (is (= 1 @legacy-calls))
    (is (= 1 @cps-calls))))
