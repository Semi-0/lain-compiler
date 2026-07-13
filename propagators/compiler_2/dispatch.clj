(ns propagators.compiler-2.dispatch
  "Compiler-2's stable generic dispatch boundary.

  Runtime compilation code depends on this namespace rather than the concrete
  compiler implementation, so declaring and evaluating applications do not
  form a namespace cycle."
  (:require [propagators.compiler-2.ast :as ast]))

(defmulti g:compile
  (fn [expr _env _state]
    (let [kind (ast/type expr)]
      (if (= :apply kind) :application kind))))

(defn compile-expression
  "Invoke the compatibility MultiFn using the environment carried by state."
  [state expr]
  (g:compile expr (:env state) state))

(defn state-compiler
  "Return the locally selected compiler or the compatibility dispatcher."
  [state]
  (or (:compiler state) compile-expression))
