(ns ^:deprecated propagators.compiler-2.deprecated.core
  "Deprecated synchronous compiler compatibility façade.

  Production compilation is owned by `propagators.compiler-2.cps-core`.
  Declarations and handlers are re-exported here for source compatibility."
  (:require [propagators.compiler-2.compiler.declarations :as declarations]
            [propagators.compiler-2.deprecated.synchronous :as synchronous]
            [propagators.compiler-2.compiler.dispatch :as dispatch]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.model.operator-value :as operator-value]
            [propagators.compiler-2.language.parser :as parser]
            [propagators.compiler-common.core :as common]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def compiler-result-key common/compiler-result-key)
(def compiler-props-key common/compiler-props-key)
(def compiler-applications-key common/compiler-applications-key)

(def g:compile dispatch/g:compile)

(defmulti g:advance
  (fn [_binding _state] :default))

(defmethod g:advance :default
  [binding _state]
  binding)

(defmulti g:apply
  (fn [operator-binding _operand-forms _calling-env _state _out-id]
    (cond
      (operator-value/operator-closure? operator-binding) :primitive
      (fn? operator-binding) :primitive
      (env/binding-id operator-binding) :cell
      :else :unsupported)))

(declare default-compiler)

(defmethod g:apply :primitive
  [operator-binding operand-forms calling-env state out-id]
  (declarations/apply-operator default-compiler operator-binding operand-forms
                               calling-env state out-id))

(defmethod g:apply :cell
  [operator-binding operand-forms calling-env state out-id]
  (declarations/apply-operator default-compiler operator-binding operand-forms
                               calling-env state out-id))

(defmethod g:apply :unsupported
  [operator-binding _operand-forms _calling-env _state _out-id]
  (throw (ex-info "application operator is not callable"
                  {:operator operator-binding})))

;; Declaration compatibility exports.
(def declare-direct-operator-application
  declarations/declare-direct-operator-application)
(def declare-operator-application-bindings
  declarations/declare-operator-application-bindings)
(def declare-operator-application declarations/declare-operator-application)
(def normalize-closure-output declarations/normalize-closure-output)
(def declare-retained-cell-application-bindings
  declarations/declare-retained-cell-application-bindings)
(def declare-retained-cell-application
  declarations/declare-retained-cell-application)
(def declare-runtime-cell-application-bindings
  declarations/declare-runtime-cell-application-bindings)
(def declare-runtime-cell-application
  declarations/declare-runtime-cell-application)
(def resolve-cell-declarer declarations/resolve-cell-declarer)
(def apply-operator declarations/apply-operator)
(def closure-locals declarations/closure-locals)
(def seed-closure-declaration declarations/seed-closure-declaration)
(def attach-closure-environment declarations/attach-closure-environment)
(def declare-closure declarations/declare-closure)
(def define-binding declarations/define-binding)
(def lower-let declarations/lower-let)

;; Deprecated synchronous handler compatibility exports.
(def compile-literal synchronous/compile-literal)
(def compile-symbol synchronous/compile-symbol)
(def compile-sequence synchronous/compile-sequence)
(def compile-let-cell synchronous/compile-let-cell)
(def compile-let synchronous/compile-let)
(def compile-when-topology synchronous/compile-when-topology)
(def compile-network-form synchronous/compile-network-form)
(def compile-compound synchronous/compile-compound)
(def compile-def-net synchronous/compile-def-net)
(def compile-def-constraint synchronous/compile-def-constraint)
(def compile-def synchronous/compile-def)

(defn compile-application
  [compile* state expr]
  (synchronous/compile-application g:advance
                                   declarations/apply-operator
                                   compile* state expr))

(def ^:deprecated compiler-dispatch
  (:dispatch (synchronous/make-compiler g:advance
                                        declarations/apply-operator)))

(def ^:deprecated default-compiler
  (common/make-compiler compiler-dispatch))

(defmacro ^:private define-compile-adapter
  [kind handler]
  `(defmethod g:compile ~kind
     [expr# env# state#]
     (~handler default-compiler (assoc state# :env env#) expr#)))

(define-compile-adapter :literal compile-literal)
(define-compile-adapter :symbol compile-symbol)
(define-compile-adapter :sequence compile-sequence)
(define-compile-adapter :let-cell compile-let-cell)
(define-compile-adapter :let compile-let)
(define-compile-adapter :when-topology compile-when-topology)
(define-compile-adapter :network compile-network-form)
(define-compile-adapter :compound compile-compound)
(define-compile-adapter :def-net compile-def-net)
(define-compile-adapter :def-constraint compile-def-constraint)
(define-compile-adapter :def compile-def)
(define-compile-adapter :application compile-application)

(defn ^:deprecated compile-expr
  ([expr] (compile-expr expr (h/default-env)))
  ([expr compiler-env] (compile-expr expr compiler-env {}))
  ([expr compiler-env {:keys [net seed path compiler]
                       :or {net net/empty-net path []}
                       :as opts}]
   (let [seed (or seed (ids/new-node-id))
         compile* (or compiler default-compiler)
         [state result]
         (compile* {:net net
                    :env compiler-env
                    :seed seed
                    :path path
                    :props []
                    :applications []
                    :compiler compile*
                    :application-installer (:application-installer opts)
                    :application/cell-declarer
                    (:application/cell-declarer opts)
                    :reuse-existing-bindings?
                    (:reuse-existing-bindings? opts)}
                   expr)]
     (common/compiled-map state result))))

(defn ^:deprecated compile-source
  ([source] (compile-expr (parser/parse-string source)))
  ([source compiler-env]
   (compile-expr (parser/parse-string source) compiler-env))
  ([source compiler-env opts]
   (compile-expr (parser/parse-string source) compiler-env opts)))

(defn compiled-result [compiled-net]
  (net/network-dict-entry compiled-net compiler-result-key))

(defn compiled-props [compiled-net]
  (net/network-dict-entry compiled-net compiler-props-key))

(defn compiled-applications [compiled-net]
  (net/network-dict-entry compiled-net compiler-applications-key))

(defn ^:deprecated p:compile-expr
  [expr-id env-id out-id]
  (prop/construct-propagator
   (prop/concrete-propagator
    (fn [_inputs _outputs network]
      (let [expr (net/network-cell-strongest network expr-id)
            compiler-env (net/network-cell-strongest network env-id)
            compiled (compile-expr expr compiler-env
                                   {:net network
                                    :seed [:compile-2 expr-id env-id]})]
        [(message out-id (:net compiled))])))
   [expr-id env-id]
   [out-id]))
