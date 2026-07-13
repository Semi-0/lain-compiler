(ns propagators.compiler-2.cps-core
  "Stack-safe predicate-composed compiler-2 traversal."
  (:refer-clojure :exclude [symbol?])
  (:require [propagators.compiler-2.application :as application]
            [propagators.compiler-2.ast :as ast]
            [propagators.compiler-2.core :as core]
            [propagators.compiler-2.env :as env]
            [propagators.compiler-2.helpers :as h]
            [propagators.compiler-2.lazy-topology :as lazy-topology]
            [propagators.compiler-2.operator-value :as operator-value]
            [propagators.compiler-2.parser :as parser]
            [propagators.compiler-2.predicate-core :as predicate-core]
            [propagators.compiler-common.cps :as cps]
            [propagators.compiler-common.core :as common]
            [propagators.ids :as ids]))

(def compiler-result-key common/compiler-result-key)
(def compiler-props-key common/compiler-props-key)
(def compiler-applications-key common/compiler-applications-key)

(defn- kind? [kind expr]
  (= kind (common/expression-kind expr)))

(def literal? (partial kind? :literal))
(def symbol? (partial kind? :symbol))
(def sequence? (partial kind? :sequence))
(def let-cell? (partial kind? :let-cell))
(def let? (partial kind? :let))
(def when-topology? (partial kind? :when-topology))
(def network? (partial kind? :network))
(def compound? (partial kind? :compound))
(def def-net? (partial kind? :def-net))
(def def-constraint? (partial kind? :def-constraint))
(def definition? (partial kind? :def))
(def def-cell? (partial kind? :def-cell))

(defn- finish
  [k [state binding]]
  (cps/continue k state binding))

(defn compile-literal
  [_compile-k state expr k]
  (finish k (core/compile-literal nil state expr)))

(defn compile-symbol
  [_compile-k state expr k]
  (finish k (core/compile-symbol nil state expr)))

(defn compile-sequence
  [compile-k state expr k]
  (cps/compile-seq compile-k state (ast/body expr) k))

(defn compile-let-cell
  [compile-k state expr k]
  (let [[body-state _scoped-env]
        (common/declare-let-cell-scope state (ast/names expr))]
    (cps/call compile-k body-state (ast/body expr) k)))

(defn compile-let
  [compile-k state expr k]
  (cps/call compile-k state (core/lower-let expr) k))

(defn compile-when-topology
  [compile-k state expr k]
  (let [base-path (:path state)]
    (cps/call
     compile-k (h/child state :condition) (ast/condition expr)
     (fn [state' condition-binding]
       (let [condition-id (env/binding-id condition-binding)]
         (when-not condition-id
           (throw (ex-info "when condition must compile to a cell"
                           {:condition condition-binding})))
         (finish k
                 (lazy-topology/install-when-topology-with
                  (:compiler state')
                  (assoc state' :path base-path)
                  condition-id
                  (ast/body expr))))))))

(defn compile-network-form
  [_compile-k state expr k]
  (finish k (core/compile-network-form nil state expr)))

(defn compile-compound
  [_compile-k state expr k]
  (finish k (core/compile-compound nil state expr)))

(defn compile-def-net
  [_compile-k state expr k]
  (finish k (core/compile-def-net nil state expr)))

(defn compile-def-constraint
  [_compile-k state expr k]
  (finish k (core/compile-def-constraint (:compiler state) state expr)))

(defn compile-def
  [compile-k state expr k]
  (if-let [body (ast/body expr)]
    (cps/call
     compile-k (h/child state :body) body
     (fn [state' body-binding]
       (finish k (core/define-binding state' (ast/name expr) body-binding))))
    (finish k (core/compile-def (:compiler state) state expr))))

(defn compile-def-cell
  [_compile-k state expr k]
  (finish k (core/compile-def-cell nil state expr)))

(defn- built-in-cell-declaration
  [compile* operator-binding arg-bindings state out-id]
  (let [declarer (:application/cell-declarer state)]
    (cond
      (or (nil? declarer) (= :runtime declarer))
      (core/declare-runtime-cell-application-bindings
       compile* operator-binding arg-bindings state out-id)

      (= :retained-frame declarer)
      (core/declare-retained-cell-application-bindings
       compile* operator-binding arg-bindings state out-id)

      (fn? declarer)
      nil

      :else
      (core/resolve-cell-declarer state))))

(defn- declare-compiled-application
  [compile* operator-binding arg-bindings state out-id]
  (cond
    (or (operator-value/operator-closure? operator-binding)
        (fn? operator-binding))
    (core/declare-operator-application-bindings
     compile* operator-binding arg-bindings state out-id)

    (env/binding-id operator-binding)
    (or (built-in-cell-declaration compile* operator-binding
                                   arg-bindings state out-id)
        (throw (ex-info "custom cell declarer requires operand forms"
                        {:application/cell-declarer
                         (:application/cell-declarer state)})))

    :else
    (throw (ex-info "application operator is not callable"
                    {:operator operator-binding}))))

(defn- custom-cell-declarer?
  [operator-binding state]
  (and (env/binding-id operator-binding)
       (fn? (:application/cell-declarer state))))

(defn compile-application
  [compile-k state expr k]
  (let [base-path (:path state)
        op (ast/operator expr)
        operand-forms (ast/args expr)]
    (cps/call
     compile-k (h/child (common/with-path state base-path) :operator) op
     (fn [state' op-binding]
       (let [state' (common/with-path state' base-path)
             [state'' operator-binding out-id]
             (common/prepare-application (fn [binding _state] binding)
                                         state' op op-binding)
             compile* (:compiler state'')
             direct-compiler
             (and (operator-value/operator-closure? operator-binding)
                  (operator-value/operator-direct-compiler operator-binding))]
         (cond
           direct-compiler
           (direct-compiler compile-k state'' operand-forms out-id k)

           (and (operator-value/operator-closure? operator-binding)
                (operator-value/operator-direct-installer operator-binding))
           (finish k (core/declare-direct-operator-application
                      compile* operator-binding operand-forms state'' out-id))

           (custom-cell-declarer? operator-binding state'')
           (finish k ((:application/cell-declarer state'')
                      compile* operator-binding operand-forms state'' out-id))

           :else
           (cps/compile-args
            compile-k state'' operand-forms
            (fn [state''' arg-bindings]
              (finish k (declare-compiled-application
                         compile* operator-binding arg-bindings
                         (merge state'''
                                (select-keys state''
                                             [:context-id
                                              :application/app-id
                                              :application/operator-ast]))
                         out-id))))))))))

(def compiler-dispatch
  (cps/compose-rules
   (cps/on literal? compile-literal)
   (cps/on symbol? compile-symbol)
   (cps/on sequence? compile-sequence)
   (cps/on let-cell? compile-let-cell)
   (cps/on let? compile-let)
   (cps/on when-topology? compile-when-topology)
   (cps/on network? compile-network-form)
   (cps/on compound? compile-compound)
   (cps/on def-net? compile-def-net)
   (cps/on def-constraint? compile-def-constraint)
   (cps/on definition? compile-def)
   (cps/on def-cell? compile-def-cell)
   compile-application))

(def compile* (cps/make-compiler compiler-dispatch))
(def default-compiler compile*)

(defn compile-expr
  ([expr] (compile-expr expr (h/default-env)))
  ([expr compiler-env] (compile-expr expr compiler-env {}))
  ([expr compiler-env opts]
   (predicate-core/compile-expr
    expr compiler-env
    (assoc opts :compiler (or (:compiler opts) default-compiler)))))

(defn compile-source
  ([source] (compile-expr (parser/parse-string source)))
  ([source compiler-env]
   (compile-expr (parser/parse-string source) compiler-env))
  ([source compiler-env opts]
   (compile-expr (parser/parse-string source) compiler-env opts)))

(def compiled-result predicate-core/compiled-result)
(def compiled-props predicate-core/compiled-props)
(def compiled-applications predicate-core/compiled-applications)

(defn p:compile-expr
  [expr-id env-id out-id]
  (predicate-core/p:compile-expr-with default-compiler expr-id env-id out-id))

(defn p:execute-sub-env
  ([parent-env-id expr-id out-id]
   (p:execute-sub-env parent-env-id expr-id [] (ids/new-node-id) out-id))
  ([parent-env-id expr-id child-env-id out-id]
   (p:execute-sub-env parent-env-id expr-id [] child-env-id out-id))
  ([parent-env-id expr-id watch-ids child-env-id out-id]
   (application/p:execute-sub-env-with default-compiler
                                       parent-env-id expr-id watch-ids
                                       child-env-id out-id)))
