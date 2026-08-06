(ns propagators.compiler-2.compiler.predicates
  "Named predicates for compiler-2 expression selection."
  (:refer-clojure :exclude [symbol?])
  (:require [propagators.compiler-common.core :as common]))

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
