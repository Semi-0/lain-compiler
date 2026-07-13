(ns propagators.compiler-common.cps
  "Small continuation primitives for stack-safe compiler traversal."
  (:require [propagators.compiler-2.helpers :as h]
            [propagators.compiler-common.core :as common]))

(defn call
  "Schedule compilation without growing the caller's stack."
  [compile-k state expr k]
  #(compile-k state expr k))

(defn continue
  "Schedule a compiler continuation."
  [k state binding]
  #(k state binding))

(defn on
  "Make a CPS compiler rule that delegates non-matching expressions."
  [predicate handler]
  (fn [next]
    (fn [compile-k state expr k]
      (if (predicate expr)
        (handler compile-k state expr k)
        (next compile-k state expr k)))))

(defn compose-rules
  "Compose CPS rule functions around one final compiler handler."
  [& rules]
  (when-not (seq rules)
    (throw (ex-info "compose-rules requires a final handler" {})))
  (reduce (fn [next rule] (rule next))
          (last rules)
          (reverse (butlast rules))))

(defn make-compiler
  "Return the synchronous `[state expr]` facade over a CPS dispatcher."
  [dispatch]
  (letfn [(compile-k [state expr k]
            (dispatch compile-k state expr k))
          (compile* [state expr]
            (trampoline compile-k
                        (assoc state :compiler compile*)
                        expr
                        (fn [state binding] [state binding])))]
    compile*))

(defn compile-seq
  "Compile forms left-to-right and continue with the last binding."
  [compile-k state forms k]
  (let [base-path (:path state)
        forms (vec forms)]
    (letfn [(step [state idx result]
              (if (= idx (count forms))
                (continue k state result)
                (call compile-k
                      (h/child (common/with-path state base-path) idx)
                      (nth forms idx)
                      (fn [state' binding]
                        #(step (common/with-path state' base-path)
                               (inc idx)
                               binding)))))]
      (step state 0 nil))))

(defn compile-args
  "Compile application arguments left-to-right and continue with bindings."
  [compile-k state args k]
  (let [base-path (:path state)
        args (vec args)]
    (letfn [(step [state idx bindings]
              (if (= idx (count args))
                (continue k state bindings)
                (call compile-k
                      (h/child (common/with-path state base-path) [:arg idx])
                      (nth args idx)
                      (fn [state' binding]
                        #(step (common/with-path state' base-path)
                               (inc idx)
                               (conj bindings binding))))))]
      (step state 0 []))))
