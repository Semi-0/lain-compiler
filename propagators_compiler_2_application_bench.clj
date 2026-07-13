(ns propagators-compiler-2-application-bench
  (:require [propagators.compiler-2.runtime.application :as compatibility]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-2.main :as main]
            [propagators.compiler-2.runtime.retained-application :as retained]
            [propagators.gur.flat :as fvm]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.cells.cell :as cell]))

(def warmup-count 10)
(def sample-count 30)

(defn- nested-body [depth expr]
  (if (zero? depth)
    expr
    (format "(let-cell [f]
               (<-> f (:: [y] (+ y 1)))
               (f %s))"
            (nested-body (dec depth) expr))))

(defn- nested-source [depth]
  (format "(let-cell [pipeline]
             (<-> pipeline (:: [x] %s))
             (pipeline 1))"
          (nested-body depth "x")))

(def cases
  [{:name :primitive
    :source "(+ 1 2)"}
   {:name :implicit-closure
    :source "((:: [x] (+ x 1)) 4)"}
   {:name :explicit-closure
    :source "(let-cell [out]
              ((network [x] [out] (+ x 1)) 4 out)
              out)"}
   {:name :nested-closures-1
    :source (nested-source 1)}
   {:name :nested-closures-3
    :source (nested-source 3)}
   {:name :nested-closures-5
    :source (nested-source 5)}])

(def strategies
  [{:name :compat-transient
    :opts {:application-installer compatibility/p:apply-application}}
   {:name :retained
    :opts {:application-installer retained/p:apply-application}}])

(defn- elapsed-nanos [f]
  (let [start (System/nanoTime)
        ret (f)]
    [(- (System/nanoTime) start) ret]))

(defn- median [xs]
  (let [sorted (vec (sort xs))
        n (count sorted)]
    (nth sorted (quot n 2))))

(defn- prop-count [network]
  (count (filter prop/prop? (vals (net/net-env network)))))

(defn- cell-count [network]
  (count (filter cell/cell? (vals (net/net-env network)))))

(defn- retained-application-count [network]
  (count (net/network-dict-entry
          network
          retained/retained-application-props-key)))

(defn- compatibility-application-count [network]
  (count (net/network-dict-entry
          network
          compatibility/apply-application-props-key)))

(defn- declared-effect-count [network]
  (+ (count (net/network-dict-entry network fvm/cell-index-key))
     (count (net/network-dict-entry network fvm/prop-index-key))))

(defn- run-case [{:keys [source]} {:keys [opts]}]
  (let [compiled (main/compile-source source (h/default-env) opts)
        final-network (nb/run-propagators (:net compiled) (:props compiled))]
    {:result (net/network-cell-strongest final-network (:cell compiled))
     :compiled-props (count (:props compiled))
     :cells (cell-count final-network)
     :props (prop-count final-network)
     :retained-applications (retained-application-count final-network)
     :compatibility-applications (compatibility-application-count final-network)
     :declared-effects (declared-effect-count final-network)}))

(defn- sample-case [case strategy]
  (dotimes [_ warmup-count]
    (run-case case strategy))
  (let [samples (repeatedly sample-count
                            #(elapsed-nanos
                              (fn [] (run-case case strategy))))
        nanos (mapv first samples)
        last-result (second (last samples))]
    (assoc last-result
           :warmups warmup-count
           :samples sample-count
           :median-ms (/ (double (median nanos)) 1000000.0))))

(defn- benchmark []
  (vec
   (for [case cases
         strategy strategies]
     (assoc (sample-case case strategy)
            :case (:name case)
            :strategy (:name strategy)))))

(defn -main [& _args]
  (binding [*print-namespace-maps* false]
    (prn {:benchmark :compiler-2-application
          :warmups warmup-count
          :samples sample-count
          :results (benchmark)})))
