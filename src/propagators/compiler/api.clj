(ns propagators.compiler.api
  "Stable public compiler entrypoints."
  (:require [propagators.compiler.main :as compiler]))

(defn- compiler-result
  [compiled]
  {:network (:net compiled)
   :environment (:env compiled)
   :installed-propagator-ids (vec (:props compiled))
   :application-metadata (vec (:applications compiled))
   :result-cell (:cell compiled)
   :diagnostics []})

(defn- require-options
  [options]
  (if (map? options)
    options
    (throw (ex-info "compiler options must be a map"
                    {:options options}))))

(defn compile-form
  [form environment options]
  (if (nil? form)
    (throw (ex-info "compiler form must not be nil" {:form form}))
    (compiler-result
     (compiler/compile-expr form environment (require-options options)))))

(defn compile-source
  [source environment options]
  (if (string? source)
    (compiler-result
     (compiler/compile-source source environment (require-options options)))
    (throw (ex-info "compiler source must be a string"
                    {:source source}))))
