(ns propagators-compiler-2-tms-chain-bench
  "Benchmark distributed-TMS retraction and bring-in through compiler-2 chains.

  Usage:
    clojure -M:compiler-2-tms-chain-bench
    clojure -M:compiler-2-tms-chain-bench 10,100,500 3 10
    clojure -M:compiler-2-tms-chain-bench updates 100 1 3
    clojure -M:compiler-2-tms-chain-bench behavior-updates 100 1 3
    clojure -M:compiler-2-tms-chain-bench event-updates 100 1 3
    clojure -M:compiler-2-tms-chain-bench conflicts 1000 3 10"
  (:require [clojure.string :as str]
            [propagators.cells.cell :as cell]
            [propagators.cells.cell-protocol :as protocol]
            [propagators.cells.value :as value]
            [propagators.compile :as compile]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.compiler.basis :as helpers]
            [propagators.compiler-2.main :as compiler]
            [propagators.core :as core]
            [propagators.datastructures.behavior.core :as behavior]
            [propagators.datastructures.event :as event]
            [propagators.datastructures.tms.distributed :as tms]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def default-depths [10 100 500])
(def default-warmups 3)
(def default-samples 10)

(defn chain-source [depth]
  (let [nodes (mapv #(symbol (str "n" %)) (range (inc depth)))
        links (map (fn [[a b]] (list '<-> (list '+ a 1) b))
                   (partition 2 1 nodes))]
    (pr-str
     (list* 'let-cell
            (vec nodes)
            (concat
             [(list 'def 'raw 1)
              (list 'def 'premise :benchmark/premise)
              (list 'def 'initial-epoch 0)
              (list 'def 'retract-epoch)
              (list 'def 'bring-epoch)
              (list 'premise-input 'raw 'premise 'initial-epoch (first nodes))
              (list 'premise-retract 'premise 'retract-epoch (first nodes))
              (list 'premise-believe 'premise 'bring-epoch (first nodes))]
             links
             [(last nodes)])))))

(defn protocol-net []
  (-> net/empty-net
      (compile/install-and-run (protocol/install-cell-protocol))
      (compile/install-and-run (protocol/install-tms-distributed-protocol))))

(defn behavior-protocol-net []
  (-> net/empty-net
      (compile/install-and-run (protocol/install-cell-protocol))
      (compile/install-and-run (protocol/install-behavior-protocol))))

(defn current-value [network id]
  (let [answer (net/network-cell-strongest network id)]
    (if (value/unusable? answer)
      answer
      (tms/distributed-base-value answer))))

(defn seed-and-run [network id update]
  (let [[tasks updated] (core/eval-cell id (message id update) network)]
    (core/run-tasks tasks updated)))

(defn prepare [depth]
  (let [compiled (compiler/compile-source-with-behavior-tms
                  (chain-source depth)
                  {:net (protocol-net)})
        network (nb/run-propagators (:net compiled) (:props compiled))
        id-of #(env/binding-id (env/lookup (:env compiled) %))
        prepared {:depth depth
                  :network network
                  :out-id (:cell compiled)
                  :retract-id (id-of 'retract-epoch)
                  :bring-id (id-of 'bring-epoch)}]
    (assert (= (inc depth) (current-value network (:out-id prepared))))
    prepared))

(defn transition [{:keys [network out-id retract-id bring-id]}]
  (let [retracted (seed-and-run network retract-id 1)
        brought (seed-and-run retracted bring-id 2)]
    (assert (value/nothing? (net/network-cell-strongest retracted out-id)))
    (assert (= (current-value network out-id)
               (current-value brought out-id)))
    {:retracted retracted :brought brought}))

(defn elapsed [f]
  (let [start (System/nanoTime)
        result (f)]
    [(- (System/nanoTime) start) result]))

(defn median-ms [samples]
  (/ (double (nth (vec (sort samples)) (quot (count samples) 2))) 1000000.0))

(defn p95-ms [samples]
  (/ (double (nth (vec (sort samples))
                  (dec (int (Math/ceil (* 0.95 (count samples)))))))
     1000000.0))

(defn count-type [pred network]
  (count (filter pred (vals (net/net-env network)))))

(defn topology-counts [network]
  {:cells (count-type cell/cell? network)
   :props (count-type prop/prop? network)})

(defn sample [{:keys [network retract-id bring-id] :as prepared}]
  (let [[retract-ns retracted] (elapsed #(seed-and-run network retract-id 1))
        [bring-ns brought] (elapsed #(seed-and-run retracted bring-id 2))]
    (assert (value/nothing?
             (net/network-cell-strongest retracted (:out-id prepared))))
    (assert (= (inc (:depth prepared))
               (current-value brought (:out-id prepared))))
    {:retract-ns retract-ns :bring-ns bring-ns}))

(defn benchmark-depth [depth warmups samples]
  (let [prepared (prepare depth)
        initial-topology (topology-counts (:network prepared))
        checked (transition prepared)
        final-topology (topology-counts (:brought checked))]
    (assert (= initial-topology final-topology))
    (dotimes [_ warmups] (transition prepared))
    (let [measurements (repeatedly samples #(sample prepared))]
      (merge {:depth depth
              :warmups warmups
              :samples samples
              :retract-median-ms (median-ms (map :retract-ns measurements))
              :bring-median-ms (median-ms (map :bring-ns measurements))
              :topology-delta {:cells 0 :props 0}}
             initial-topology))))

(defn parse-depths [arg]
  (if arg
    (mapv parse-long (str/split arg #","))
    default-depths))

(defn control-symbol [epoch]
  (symbol (str (if (odd? epoch) "retract-" "bring-") epoch)))

(defn retained-update-source [update-count]
  (let [controls (mapv control-symbol (range 1 (inc update-count)))]
    (pr-str
     (list* 'let-cell
            (into ['a 'd] controls)
            (concat
             [(list 'def 'raw-a 10)
              (list 'def 'b 3)
              (list 'def 'c 2)
              (list 'def 'premise :benchmark/premise)
              (list 'def 'initial-epoch 0)
              (list 'premise-input 'raw-a 'premise 'initial-epoch 'a)]
             (map-indexed
              (fn [index control]
                (list (if (even? index) 'premise-retract 'premise-believe)
                      'premise control 'a))
              controls)
             [(list '-> (list '+ (list '- 'a 'b) 'c) 'd)
              'd])))))

(defn prepare-retained-updates [update-count]
  (let [compiled (compiler/compile-source-with-behavior-tms
                  (retained-update-source update-count)
                  {:net (protocol-net)})
        network (nb/run-propagators (:net compiled) (:props compiled))
        id-of #(env/binding-id (env/lookup (:env compiled) %))]
    {:network network
     :out-id (:cell compiled)
     :control-ids (mapv (comp id-of control-symbol)
                        (range 1 (inc update-count)))}))

(defn run-retained-updates [{:keys [network out-id control-ids]}]
  (reduce (fn [{:keys [network rows]} [epoch control-id]]
            (let [[nanos updated] (elapsed #(seed-and-run network control-id epoch))
                  expected (if (odd? epoch) value/nothing 9)
                  actual (current-value updated out-id)]
              (assert (= expected actual))
              {:network updated
               :rows (conj rows nanos)}))
          {:network network :rows []}
          (map vector (range 1 (inc (count control-ids))) control-ids)))

(defn retained-update-benchmark [update-count warmups samples]
  (let [prepared (prepare-retained-updates update-count)
        initial-topology (topology-counts (:network prepared))]
    (dotimes [_ warmups] (run-retained-updates prepared))
    (let [runs (repeatedly samples #(elapsed (fn [] (run-retained-updates prepared))))
          total-nanos (mapv first runs)
          last-run (-> runs last second)
          final-topology (topology-counts (:network last-run))]
      (assert (= initial-topology final-topology))
      (merge {:updates update-count
              :warmups warmups
              :samples samples
              :total-median-ms (median-ms total-nanos)
              :per-update-median-ms (median-ms (:rows last-run))
              :per-update-p95-ms (p95-ms (:rows last-run))
              :last-update-ms (/ (double (peek (:rows last-run))) 1000000.0)
              :topology-delta {:cells 0 :props 0}}
             initial-topology))))

(defn retained-behavior [source tick value]
  (behavior/retained-value source tick value #{[source tick]}))

(defn behavior-current-value [network id]
  (let [answer (net/network-cell-strongest network id)]
    (if (value/unusable? answer)
      answer
      (behavior/base-value answer))))

(defn prepare-behavior-updates []
  (let [a-id (ids/new-node-id)
        b-id (ids/new-node-id)
        c-id (ids/new-node-id)
        initial-a (retained-behavior :benchmark/a 0 10)
        initial-b (behavior/constant-value 0 3 #{[:benchmark/b 0]})
        initial-c (behavior/constant-value 0 2 #{[:benchmark/c 0]})
        network (-> (behavior-protocol-net)
                    (nb/install-cell a-id initial-a (behavior/strongest-value initial-a))
                    (nb/install-cell b-id initial-b (behavior/strongest-value initial-b))
                    (nb/install-cell c-id initial-c (behavior/strongest-value initial-c)))
        compiler-env (-> (compiler/behavior-tms-env)
                         (env/bind 'a (env/cell-binding a-id) 0)
                         (env/bind 'b (env/cell-binding b-id) 0)
                         (env/bind 'c (env/cell-binding c-id) 0))
        compiled (compiler/compile-source
                  "(let-cell [d] (-> (be:+ (be:- a b) c) d) d)"
                  compiler-env
                  {:net network})
        ready (nb/run-propagators (:net compiled) (:props compiled))]
    (assert (= 9 (behavior-current-value ready (:cell compiled))))
    {:network ready :source-id a-id :out-id (:cell compiled)}))

(defn run-behavior-updates [{:keys [network source-id out-id]} update-count]
  (reduce (fn [{:keys [network rows]} tick]
            (let [update (retained-behavior :benchmark/a tick (+ 10 tick))
                  [nanos updated] (elapsed #(seed-and-run network source-id update))]
              (assert (= (+ 9 tick) (behavior-current-value updated out-id)))
              {:network updated :rows (conj rows nanos)}))
          {:network network :rows []}
          (range 1 (inc update-count))))

(defn behavior-update-benchmark [update-count warmups samples]
  (let [prepared (prepare-behavior-updates)
        initial-topology (topology-counts (:network prepared))]
    (dotimes [_ warmups] (run-behavior-updates prepared update-count))
    (let [runs (repeatedly samples
                           #(elapsed (fn []
                                       (run-behavior-updates prepared update-count))))
          total-nanos (mapv first runs)
          last-run (-> runs last second)
          final-topology (topology-counts (:network last-run))]
      (assert (= initial-topology final-topology))
      (merge {:updates update-count
              :warmups warmups
              :samples samples
              :total-median-ms (median-ms total-nanos)
              :per-update-median-ms (median-ms (:rows last-run))
              :per-update-p95-ms (p95-ms (:rows last-run))
              :last-update-ms (/ (double (peek (:rows last-run))) 1000000.0)
              :topology-delta {:cells 0 :props 0}}
             initial-topology))))

(defn event-content [input source tick value]
  (event/content-value [(event/active-event input source tick value)]))

(defn event-current-value [network id]
  (let [values (vals (event/active-values
                      (net/network-cell-content network id)))]
    (when (= 1 (count values)) (first values))))

(defn prepare-event-updates []
  (let [a-id (ids/new-node-id)
        b-id (ids/new-node-id)
        c-id (ids/new-node-id)
        initial-a (event-content :a :source/a 0 10)
        initial-b (event-content :b :source/b 0 3)
        initial-c (event-content :c :source/c 0 2)
        network (-> (compile/install-and-run net/empty-net
                                             (protocol/install-cell-protocol))
                    (nb/install-cell a-id initial-a (event/strongest-value initial-a))
                    (nb/install-cell b-id initial-b (event/strongest-value initial-b))
                    (nb/install-cell c-id initial-c (event/strongest-value initial-c)))
        compiler-env (-> (helpers/default-env)
                         (env/bind 'a (env/cell-binding a-id) 0)
                         (env/bind 'b (env/cell-binding b-id) 0)
                         (env/bind 'c (env/cell-binding c-id) 0))
        compiled (compiler/compile-source
                  "(let-cell [d] (-> (+ (- a b) c) d) d)"
                  compiler-env
                  {:net network})
        ready (nb/run-propagators (:net compiled) (:props compiled))]
    (assert (= 9 (event-current-value ready (:cell compiled))))
    {:network ready :source-id a-id :out-id (:cell compiled)}))

(defn run-event-updates [{:keys [network source-id out-id]} update-count]
  (reduce (fn [{:keys [network rows]} tick]
            (let [update (event/active-event :a :source/a tick (+ 10 tick))
                  [nanos updated] (elapsed #(seed-and-run network source-id update))]
              (assert (= (+ 9 tick) (event-current-value updated out-id)))
              {:network updated :rows (conj rows nanos)}))
          {:network network :rows []}
          (range 1 (inc update-count))))

(defn event-update-benchmark [update-count warmups samples]
  (let [prepared (prepare-event-updates)
        initial-topology (topology-counts (:network prepared))]
    (dotimes [_ warmups] (run-event-updates prepared update-count))
    (let [runs (repeatedly samples
                           #(elapsed (fn []
                                       (run-event-updates prepared update-count))))
          total-nanos (mapv first runs)
          last-run (-> runs last second)
          final-topology (topology-counts (:network last-run))]
      (assert (= initial-topology final-topology))
      (merge {:updates update-count
              :warmups warmups
              :samples samples
              :total-median-ms (median-ms total-nanos)
              :per-update-median-ms (median-ms (:rows last-run))
              :per-update-p95-ms (p95-ms (:rows last-run))
              :last-update-ms (/ (double (peek (:rows last-run))) 1000000.0)
              :topology-delta {:cells 0 :props 0}}
             initial-topology))))

(defn conflict-benchmark [iterations warmups samples]
  (let [event-conflict (event/content-value
                        [(event/active-event :value :left 1 10)
                         (event/active-event :value :right 1 20)])
        tms-conflict (tms/merge-distributed-content
                      (tms/distributed-input-update :left 10 :left 0 :left)
                      (tms/distributed-input-update :right 20 :right 0 :right))
        run-event #(dotimes [_ iterations] (event/strongest-value event-conflict))
        run-tms #(dotimes [_ iterations]
                   (tms/strongest-distributed-value tms-conflict))
        event-value (event/strongest-value event-conflict)
        tms-value (tms/strongest-distributed-value tms-conflict)]
    (assert (= 2 (count (value/contradiction-provenance event-value))))
    (assert (= 4 (count (value/contradiction-provenance tms-value))))
    (dotimes [_ warmups] (run-event) (run-tms))
    {:iterations iterations
     :warmups warmups
     :samples samples
     :event-median-ms (median-ms
                       (repeatedly samples #(first (elapsed run-event))))
     :event-provenance-count 2
     :tms-median-ms (median-ms
                     (repeatedly samples #(first (elapsed run-tms))))
     :tms-provenance-count 4}))

(defn -main [& [mode-or-depths count-or-warmups warmups-or-samples samples-arg]]
  (binding [*print-namespace-maps* false]
    (if (= "conflicts" mode-or-depths)
      (let [iterations (or (some-> count-or-warmups parse-long) 1000)
            warmups (or (some-> warmups-or-samples parse-long) 3)
            samples (or (some-> samples-arg parse-long) 10)]
        (prn {:benchmark :semantic-contradiction-provenance
              :result (conflict-benchmark iterations warmups samples)}))
      (if (#{"updates" "behavior-updates" "event-updates"} mode-or-depths)
      (let [updates (or (some-> count-or-warmups parse-long) 100)
            warmups (or (some-> warmups-or-samples parse-long) 1)
            samples (or (some-> samples-arg parse-long) 3)
            benchmark (case mode-or-depths
                        "behavior-updates" behavior-update-benchmark
                        "event-updates" event-update-benchmark
                        retained-update-benchmark)]
        (prn {:benchmark (keyword "compiler-2" mode-or-depths)
              :result (benchmark
                       updates warmups samples)}))
      (let [depths (parse-depths mode-or-depths)
            warmups (or (some-> count-or-warmups parse-long) default-warmups)
            samples (or (some-> warmups-or-samples parse-long) default-samples)]
        (prn {:benchmark :compiler-2-distributed-tms-chain
              :results (mapv #(benchmark-depth % warmups samples) depths)}))))))
