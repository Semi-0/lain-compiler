(ns propagators.compiler-2.compiler.rewrite
  "Pure surface-to-canonical Compiler 2 term rewrites."
  (:require [propagators.compiler-2.language.ast :as ast]))

(defn let-cell->let
  [expr]
  (ast/let* (mapv (fn [name] [name nil]) (ast/names expr))
            (ast/body expr)))

(defn compound->network
  [expr]
  (ast/network (ast/inputs expr) (ast/output expr) (ast/body expr)))

(defn def-net->def
  [expr]
  (ast/def* (ast/name expr)
            (ast/network (ast/inputs expr)
                         (ast/output expr)
                         (ast/body expr))))

(defn def-constraint->def
  [expr]
  (let [applicants (ast/inputs expr)
        body (ast/body expr)
        result (if (some? (peek applicants))
                 (ast/sequence* body (ast/sym (peek applicants)))
                 body)]
    (ast/def* (ast/name expr)
              (ast/network applicants result))))
