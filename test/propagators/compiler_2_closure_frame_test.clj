(ns propagators.compiler-2-closure-frame-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell-protocol :as protocol]
            [propagators.cells.value :as value]
            [propagators.compile :as compile]
            [propagators.compiler-2.application :as application]
            [propagators.compiler-2.closure-frame :as frame]
            [propagators.compiler-2.closure-value :as closure-value]
            [propagators.compiler-2.env :as env]
            [propagators.compiler-2.helpers :as helpers]
            [propagators.compiler-2.main :as main]
            [propagators.compiler-2.tms :as compiler-tms]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.datastructures.tms.distributed :as tms]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(def map-chain-source
  "(def-net map-chain [node] [out]
     (let-cell [value rest mapped mapped-rest]
       (p:slot :value value node)
       (p:slot :rest rest node)
       (-> (+ value 1) mapped)
       (p:slot :value mapped out)
       (when rest
         (map-chain rest mapped-rest)
       (p:slot :rest mapped-rest out))))")

(def lexical-map-chain-source
  "(let-cell []
     (def-net parent-step [x] [out] (-> (+ x 1) out))
     (def-net local-step [x] [out] (-> (+ x x) out))
     (def-net lexical-map-chain [node] [out]
       (let-cell [value rest mapped mapped-rest]
         (p:slot :value value node)
         (p:slot :rest rest node)
         (step value mapped)
         (p:slot :value mapped out)
         (when rest
           (lexical-map-chain rest mapped-rest)
           (p:slot :rest mapped-rest out))))
     lexical-map-chain)")

(defn- scope-protocol-net []
  (-> net/empty-net
      (compile/install-and-run (protocol/install-cell-protocol))
      (compile/install-and-run (protocol/install-scope-source-protocol))))

(defn- tms-protocol-net []
  (-> net/empty-net
      (compile/install-and-run (protocol/install-cell-protocol))
      (compile/install-and-run (protocol/install-tms-distributed-protocol))))

(defn- chain-value [depth]
  (reduce (fn [rest n]
            (obj/as-accessor-network
             (cond-> {:value n} rest (assoc :rest rest))))
          nil
          (reverse (range 1 (inc depth)))))

(defn- chain-from [values]
  (reduce (fn [rest n]
            (obj/as-accessor-network
             (cond-> {:value n} rest (assoc :rest rest))))
          nil
          (reverse values)))

(defn- accessor-answer [network object slot]
  (or (some (fn [id]
              (let [v (net/network-cell-strongest network id)]
                (when-not (value/unusable? v) v)))
            (obj/accessor-parent-ids object slot))
      (obj/accessor-source-slot-value object slot)))

(defn- chain-values [network root]
  (loop [node (scope-source/unwrap root) values []]
    (if-not (obj/accessor-network? node)
      values
      (recur (scope-source/unwrap (accessor-answer network node :rest))
             (conj values
                   (scope-source/unwrap
                    (accessor-answer network node :value)))))))

(defn- prop-count [network]
  (count (filter (comp prop/prop? val)
                 (net/net-env network))))

(defn- distributed-current-value [network id]
  (let [v (net/network-cell-strongest network id)]
    (if (value/unusable? v)
      v
      (tms/distributed-base-value v))))

(defn- distributed-slot-keys [network id]
  (set (keys (tms/distributed-slots
              (net/network-cell-content network id)))))

(defn- run-props [network props]
  (core/run-tasks (tq/enqueue-all tq/empty-queue props) network))

(defn- run-depth [depth]
  (let [compiled (main/compile-source map-chain-source)
        declared (core/run-tasks
                  (tq/enqueue-all tq/empty-queue (:props compiled))
                  (:net compiled))
        closure-id (env/binding-id (env/lookup (:env compiled) 'map-chain))
        closure (net/network-cell-strongest declared closure-id)
        node-id (ids/new-node-id)
        out-id (ids/new-node-id)
        env-id (ids/new-node-id)
        frame-env (application/closure-body-env
                   (closure-value/closure-env closure)
                   (closure-value/closure-inputs closure)
                   [['out out-id]]
                   [node-id])
        n0 (-> declared
               (nb/install-cell node-id (chain-value depth) (chain-value depth))
               (nb/install-cell out-id)
               (nb/install-cell env-id frame-env frame-env))
        [root-prop n1] ((frame/p:apply-closure closure-id env-id) n0)
        result (core/run-tasks (tq/enqueue-all tq/empty-queue [root-prop]) n1)]
    {:net result :out-id out-id :root-prop root-prop}))

(deftest two-cell-closure-frame-grows-chained-gur
  (doseq [depth [1 3 5]]
    (testing (str "depth " depth)
      (let [{:keys [net out-id root-prop]} (run-depth depth)
            cells (count (net/net-env net))
            props (prop-count net)
            rerun (core/run-tasks
                   (tq/enqueue-all tq/empty-queue [root-prop])
                   net)]
        (is (= (mapv inc (range 1 (inc depth)))
               (chain-values net (net/network-cell-strongest net out-id))))
        (is (= cells (count (net/net-env rerun))))
        (is (= props (prop-count rerun)))))))

(deftest retained-frame-applies-implicit-return-closure
  (let [compiled (main/compile-source "(:: [x] (+ x 1))")
        declared (run-props (:net compiled) (:props compiled))
        closure-id (:cell compiled)
        closure (net/network-cell-strongest declared closure-id)
        x-id (ids/new-node-id)
        out-id (ids/new-node-id)
        env-id (ids/new-node-id)
        {:keys [input-ids targets]}
        (application/closure-call-plan closure [x-id] out-id)
        frame-env (application/closure-body-env
                   (closure-value/closure-env closure)
                   (closure-value/closure-inputs closure)
                   targets
                   input-ids)
        n0 (-> declared
               (nb/install-cell x-id 4 4)
               (nb/install-cell out-id)
               (nb/install-cell env-id frame-env frame-env))
        [root-prop n1] ((frame/p:apply-closure closure-id env-id) n0)
        result (run-props n1 [root-prop])]
    (is (closure-value/implicit-return-symbol? (ffirst targets)))
    (is (= [x-id] input-ids))
    (is (= 5 (net/network-cell-strongest result out-id)))))

(deftest retained-frame-returns-escaped-implicit-closure
  (let [compiled (main/compile-source
                  "(:: [bias] (:: [x] (+ x bias)))")
        declared (run-props (:net compiled) (:props compiled))
        outer-id (:cell compiled)
        outer (net/network-cell-strongest declared outer-id)
        bias-id (ids/new-node-id)
        returned-id (ids/new-node-id)
        x-id (ids/new-node-id)
        out-id (ids/new-node-id)
        outer-env-id (ids/new-node-id)
        outer-plan (application/closure-call-plan outer [bias-id] returned-id)
        outer-frame-env (application/closure-body-env
                         (closure-value/closure-env outer)
                         (closure-value/closure-inputs outer)
                         (:targets outer-plan)
                         (:input-ids outer-plan))
        n0 (-> declared
               (nb/install-cell bias-id 10 10)
               (nb/install-cell returned-id)
               (nb/install-cell x-id 5 5)
               (nb/install-cell out-id)
               (nb/install-cell outer-env-id outer-frame-env outer-frame-env))
        [outer-prop n1] ((frame/p:apply-closure outer-id outer-env-id) n0)
        after-outer (run-props n1 [outer-prop])
        inner (net/network-cell-strongest after-outer returned-id)
        inner-env-id (ids/new-node-id)
        inner-plan (application/closure-call-plan inner [x-id] out-id)
        inner-frame-env (application/closure-body-env
                         (closure-value/closure-env inner)
                         (closure-value/closure-inputs inner)
                         (:targets inner-plan)
                         (:input-ids inner-plan))
        n2 (nb/install-cell after-outer inner-env-id inner-frame-env inner-frame-env)
        [inner-prop n3] ((frame/p:apply-closure returned-id inner-env-id) n2)
        result (run-props n3 [inner-prop])]
    (is (closure-value/closure-info? inner))
    (is (= 15 (net/network-cell-strongest result out-id)))))

(deftest retained-implicit-return-wakes-on-delayed-inputs
  (let [bias-id (ids/new-node-id)
        x-id (ids/new-node-id)
        out-id (ids/new-node-id)
        env-id (ids/new-node-id)
        base (-> net/empty-net
                 (nb/install-cell bias-id)
                 (nb/install-cell x-id)
                 (nb/install-cell out-id))
        compiler-env (env/bind (helpers/default-env)
                               'bias
                               (env/cell-binding bias-id))
        compiled (main/compile-source "(:: [x] (+ x bias))"
                                      compiler-env
                                      {:net base})
        declared (run-props (:net compiled) (:props compiled))
        closure-id (:cell compiled)
        closure (net/network-cell-strongest declared closure-id)
        {:keys [input-ids targets]}
        (application/closure-call-plan closure [x-id] out-id)
        frame-env (application/closure-body-env
                   (closure-value/closure-env closure)
                   (closure-value/closure-inputs closure)
                   targets
                   input-ids)
        n0 (nb/install-cell declared env-id frame-env frame-env)
        [root-prop n1] ((frame/p:apply-closure closure-id env-id) n0)
        topology (run-props n1 [root-prop])
        topology-cells (count (net/net-env topology))
        topology-props (prop-count topology)
        [bias-tasks with-bias] (core/eval-cell bias-id
                                               (message bias-id 10)
                                               topology)
        after-bias (core/run-tasks bias-tasks with-bias)
        [x-tasks with-x] (core/eval-cell x-id
                                         (message x-id 5)
                                         after-bias)
        after-x (core/run-tasks x-tasks with-x)]
    (is (= value/nothing (net/network-cell-strongest topology out-id)))
    (is (= value/nothing (net/network-cell-strongest after-bias out-id)))
    (is (= 15 (net/network-cell-strongest after-x out-id)))
    (is (= topology-cells (count (net/net-env after-x))))
    (is (= topology-props (prop-count after-x)))))

(deftest retained-frame-projects-tms-provenance-to-output
  (let [compiled (main/compile-source
                  "(network [x] [out] (-> (+ x 1) out))"
                  (helpers/default-env)
                  {:net (tms-protocol-net)})
        declared (run-props (:net compiled) (:props compiled))
        closure-id (:cell compiled)
        closure (net/network-cell-strongest declared closure-id)
        x-id (ids/new-node-id)
        private-out-id (ids/new-node-id)
        real-out-id (ids/new-node-id)
        env-id (ids/new-node-id)
        {:keys [input-ids targets]}
        (application/closure-call-plan closure [x-id private-out-id] real-out-id)
        frame-env (application/closure-body-env
                   (closure-value/closure-env closure)
                   (closure-value/closure-inputs closure)
                   targets
                   input-ids)
        n0 (-> declared
               (nb/install-cell x-id 5 5)
               (nb/install-cell private-out-id)
               (nb/install-cell real-out-id)
               (nb/install-cell env-id frame-env frame-env))
        [root-prop n1] ((frame/p:apply-closure closure-id env-id) n0)
        [project-prop n2]
        ((compiler-tms/p:distributed-premise-output
          [:test/retained-tms real-out-id]
          [:test/retained-tms real-out-id]
          [x-id]
          :premise/definition
          0
          private-out-id
          real-out-id)
         n1)
        result (run-props n2 [root-prop project-prop])]
    (is (= 6 (net/network-cell-strongest result private-out-id)))
    (is (= 6 (distributed-current-value result real-out-id)))
    (is (contains? (distributed-slot-keys result real-out-id)
                   (tms/premise-slot-key :premise/definition 0)))))

(deftest delayed-tail-grows-only-the-missing-closure-frame-suffix
  (let [compiled (main/compile-source map-chain-source)
        declared (core/run-tasks
                  (tq/enqueue-all tq/empty-queue (:props compiled))
                  (:net compiled))
        closure-id (env/binding-id (env/lookup (:env compiled) 'map-chain))
        closure (net/network-cell-strongest declared closure-id)
        node-id (ids/new-node-id)
        value-id (ids/new-node-id)
        rest-id (ids/new-node-id)
        out-id (ids/new-node-id)
        env-id (ids/new-node-id)
        frame-env (application/closure-body-env
                   (closure-value/closure-env closure)
                   (closure-value/closure-inputs closure)
                   [['out out-id]]
                   [node-id])
        n0 (-> declared
               (nb/install-cell node-id)
               (nb/install-cell value-id 1 1)
               (nb/install-cell rest-id)
               (nb/install-cell out-id)
               (nb/install-cell env-id frame-env frame-env))
        [value-prop n1] ((obj/p:slot :value value-id node-id) n0)
        [rest-prop n2] ((obj/p:slot :rest rest-id node-id) n1)
        [root-prop n3] ((frame/p:apply-closure closure-id env-id) n2)
        initial (core/run-tasks
                 (tq/enqueue-all tq/empty-queue
                                 [value-prop rest-prop root-prop])
                 n3)
        initial-count (count (net/net-env initial))
        [tail-tasks with-tail]
        (core/eval-cell rest-id
                        (message rest-id (chain-from [2 3]))
                        initial)
        grown (core/run-tasks tail-tasks with-tail)
        grown-count (count (net/net-env grown))
        rerun (core/run-tasks
               (tq/enqueue-all tq/empty-queue [root-prop])
               grown)]
    (is (= [2] (chain-values initial
                             (net/network-cell-strongest initial out-id))))
    (is (= [2 3 4] (chain-values grown
                                 (net/network-cell-strongest grown out-id))))
    (is (< initial-count grown-count))
    (is (= grown-count (count (net/net-env rerun))))))

(deftest generic-lexical-selection-feeds-retained-closure-frames
  (let [step-id (ids/new-node-id)
        node-id (ids/new-node-id)
        out-id (ids/new-node-id)
        root-env-id (ids/new-node-id)
        chain [:root :child]
        input (chain-value 3)
        base (-> (scope-protocol-net)
                 (nb/install-cell step-id)
                 (nb/install-cell node-id input input)
                 (nb/install-cell out-id))
        compiler-env (env/bind (helpers/default-env)
                               'step
                               (env/cell-binding step-id))
        compiled (main/compile-source lexical-map-chain-source
                                      compiler-env
                                      {:net base})
        declared (core/run-tasks
                  (tq/enqueue-all tq/empty-queue (:props compiled))
                  (:net compiled))
        map-id (env/binding-id (env/lookup (:env compiled) 'lexical-map-chain))
        parent-id (env/binding-id (env/lookup (:env compiled) 'parent-step))
        local-id (env/binding-id (env/lookup (:env compiled) 'local-step))
        map-closure (net/network-cell-strongest declared map-id)
        parent (net/network-cell-strongest declared parent-id)
        local (net/network-cell-strongest declared local-id)
        frame-env (application/closure-body-env
                   (closure-value/closure-env map-closure)
                   (closure-value/closure-inputs map-closure)
                   [['out out-id]]
                   [node-id])
        n0 (nb/install-cell declared root-env-id frame-env frame-env)
        [root-prop n1] ((frame/p:apply-closure map-id root-env-id) n0)
        topology (core/run-tasks
                  (tq/enqueue-all tq/empty-queue [root-prop]) n1)
        parent-answer (scope-source/scope-value
                       :root nil chain parent
                       #{(scope-source/lexical-token :parent-step :root chain)})
        local-answer (scope-source/scope-value
                      :child nil chain local
                      #{(scope-source/lexical-token :local-step :child chain)})
        [parent-tasks n2] (core/eval-cell step-id
                                          (message step-id parent-answer)
                                          topology)
        parent-net (core/run-tasks parent-tasks n2)
        parent-size (count (net/net-env parent-net))
        [local-tasks n3] (core/eval-cell step-id
                                         (message step-id local-answer)
                                         parent-net)
        local-net (core/run-tasks local-tasks n3)
        local-size (count (net/net-env local-net))
        rerun (core/run-tasks
               (tq/enqueue-all tq/empty-queue [root-prop])
               local-net)]
    (is (= [2 3 4]
           (chain-values parent-net
                         (net/network-cell-strongest parent-net out-id))))
    (is (obj/accessor-network? (net/network-cell-strongest parent-net out-id)))
    (is (= [2 4 6]
           (chain-values local-net
                         (net/network-cell-strongest local-net out-id))))
    (is (< parent-size local-size))
    (is (= local-size (count (net/net-env rerun))))))
