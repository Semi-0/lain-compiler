(ns ^:deprecated propagators.compiler-2.deprecated.compiler-core
  "Archived compiler namespace shim; use `propagators.compiler-2.cps-core`."
  (:require [propagators.compiler-2.cps-core :as cps]))

(def compiler-result-key cps/compiler-result-key)
(def compiler-props-key cps/compiler-props-key)
(def compiler-applications-key cps/compiler-applications-key)
(def ^:deprecated compiler-dispatch cps/compiler-dispatch)
(def ^:deprecated compile* cps/compile*)
(def ^:deprecated default-compiler cps/default-compiler)
(def ^:deprecated compile-expr cps/compile-expr)
(def ^:deprecated compile-source cps/compile-source)
(def compiled-result cps/compiled-result)
(def compiled-props cps/compiled-props)
(def compiled-applications cps/compiled-applications)
(def ^:deprecated p:compile-expr cps/p:compile-expr)
(def ^:deprecated p:execute-sub-env cps/p:execute-sub-env)
