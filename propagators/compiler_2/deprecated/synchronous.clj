(ns ^:deprecated propagators.compiler-2.deprecated.synchronous
  "Deprecated synchronous compiler handlers retained for compatibility."
  (:require [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.compiler.declarations :as declarations]
            [propagators.compiler-2.compiler.dispatch :as dispatch]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.runtime.lazy-topology :as lazy-topology]
            [propagators.compiler-common.core :as common]))

(defn compile-literal
  [_compile* state expr]
  (h/new-cell state :literal (ast/value expr)))

(defn compile-symbol
  [compile* state expr]
  (common/compile-symbol-expression compile* state expr))

(defn compile-sequence
  [compile* state expr]
  (common/compile-sequence-expression compile* state expr))

(defn compile-let-cell
  [compile* state expr]
  (common/compile-let-cell-expression compile* state expr))

(defn compile-let
  [compile* state expr]
  (compile* state (declarations/lower-let expr)))

(defn compile-when-topology
  [compile* state expr]
  (let [base-path (:path state)
        [state' condition-binding] (compile* (h/child state :condition)
                                             (ast/condition expr))
        condition-id (env/binding-id condition-binding)]
    (when-not condition-id
      (throw (ex-info "when condition must compile to a cell"
                      {:condition condition-binding})))
    (lazy-topology/install-when-topology-with
     compile*
     (assoc state' :path base-path)
     condition-id
     (ast/body expr))))

(defn compile-network-form
  [_compile* state expr]
  (declarations/declare-closure state (ast/inputs expr) nil (ast/body expr)))

(defn compile-compound
  [_compile* state expr]
  (declarations/declare-closure state (ast/inputs expr) (ast/output expr) (ast/body expr)))

(defn compile-def-net
  [_compile* state expr]
  (let [[state' closure-binding] (declarations/declare-closure state
                                                  (ast/name expr)
                                                  (ast/inputs expr)
                                                  (ast/output expr)
                                                  (ast/body expr))]
    (declarations/define-binding state' (ast/name expr) closure-binding)))

(defn compile-def-constraint
  [compile* state expr]
  (declarations/declare-constraint compile* state expr))

(defn compile-def
  [compile* state expr]
  (if-let [body (ast/body expr)]
    (let [[state' body-binding] (compile* (h/child state :body) body)]
      (declarations/define-binding state' (ast/name expr) body-binding))
    (let [[state' binding] (h/new-cell state [:def (ast/name expr)])]
      (declarations/define-binding state' (ast/name expr) binding))))

(defn compile-def-cell
  [_compile* state expr]
  (let [[state' closure-binding] (declarations/declare-closure state
                                                  (ast/name expr)
                                                  (ast/inputs expr)
                                                  nil
                                                  (ast/body expr))]
    (declarations/define-binding state' (ast/name expr) closure-binding)))

(defn compile-application
  [advance-f apply-f compile* state expr]
  (common/compile-application
   compile*
   advance-f
   (partial apply-f compile*)
   state
   (ast/operator expr)
   (ast/args expr)))

(defn- compile-via-multifn
  [_compile* state expr]
  (dispatch/compile-expression state expr))

(defn make-compiler
  [advance-f apply-f]
  (let [application-handler (partial compile-application advance-f apply-f)
        compiler-dispatch
        (common/compose-rules
         (common/on (common/expression-kind? :literal) compile-literal)
         (common/on (common/expression-kind? :symbol) compile-symbol)
         (common/on (common/expression-kind? :sequence) compile-sequence)
         (common/on (common/expression-kind? :let-cell) compile-let-cell)
         (common/on (common/expression-kind? :let) compile-let)
         (common/on (common/expression-kind? :when-topology) compile-when-topology)
         (common/on (common/expression-kind? :network) compile-network-form)
         (common/on (common/expression-kind? :compound) compile-compound)
         (common/on (common/expression-kind? :def-net) compile-def-net)
         (common/on (common/expression-kind? :def-constraint)
                    compile-def-constraint)
         (common/on (common/expression-kind? :def) compile-def)
         (common/on (common/expression-kind? :def-cell) compile-def-cell)
         (common/on (common/expression-kind? :application)
                    application-handler)
         compile-via-multifn)]
    {:dispatch compiler-dispatch
     :compile* (common/make-compiler compiler-dispatch)
     :application-handler application-handler}))
