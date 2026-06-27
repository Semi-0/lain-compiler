(ns propagators.gur-accumulating-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.merge :as merge]
            [propagators.cells.value :as value]
            [propagators.compile :as compile]
            [propagators.compiler-2.env :as compiler-env]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.gur.accumulating :as acc]
            [propagators.gur.subenv :as subenv]
            [propagators.gur.subenv.env :as gur-env]
            [propagators.gur.subenv.queue :as queue]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.scoped-address :as scoped]))

(defn- strongest [n id]
  (net/network-cell-strongest n id))

(defn- run-props [n prop-ids]
  (core/run-tasks (tq/enqueue-all tq/empty-queue prop-ids) n))

(defn- value-predicate
  [pred]
  (prop/primitive-propagator
   (fn [v]
     (cond
       (value/contradiction? v) value/contradiction
       (value/nothing? v) value/nothing
       :else (pred v)))))

(def p:fib-base?
  (value-predicate
   (fn [v]
     (if (and (integer? v) (not (neg? v)))
       (<= v 1)
       value/contradiction))))

(def p:empty-list?
  (value-predicate subenv/empty-list?))

(def p:list-node-value?
  (value-predicate subenv/list-node-value?))

(def p:even?
  (value-predicate
   (fn [v]
     (if (integer? v) (even? v) value/contradiction))))

(defn- lazy-cons-cell-value
  [head tail]
  (obj/as-accessor-network {:car head :cdr tail}))

(defn- lazy-cons-list-value
  [values]
  (reduce (fn [tail head]
            (lazy-cons-cell-value head tail))
          value/nothing
          (reverse values)))

(def unused-acc-list ::unused-acc-list)

(defn- example-installers
  [runtime]
  (acc/contextual-installers
   (merge (compile/default-installers)
          {'p:fib-base? p:fib-base?
           'p:empty-list? p:empty-list?
           'p:list-node-value? p:list-node-value?
           'p:even? p:even?
           'obj/p:car obj/p:car
           'obj/p:cdr obj/p:cdr
           'obj/p:cons obj/p:cons
           'cons obj/p:cons})
   runtime))

(acc/def-recursive fib
  [n out]
  {:installers example-installers}
  (let [one 1
        two 2
        base? (::fib-base? n)
        recur? (::not base?)]
    (cond
      base? n
      recur? (::+ (::recur (switch recur? (::- n one)))
                  (::recur (switch recur? (::- n two)))))))

(acc/def-recursive factorial
  [n out]
  {:installers example-installers}
  (let [one 1
        base? (::fib-base? n)
        recur? (::not base?)]
    (cond
      base? one
      recur? (::* n (::recur (switch recur? (::- n one)))))))

(acc/def-recursive int-sqrt-search
  [n lo hi out]
  {:installers example-installers}
  (let [one 1
        two 2
        done? (::<= hi lo)
        search? (::not done?)
        mid (::quot (::+ lo hi one) two)
        square (::* mid mid)
        fits? (::<= square n)
        too-big? (::not fits?)
        search-fits? (::and search? fits?)
        search-too-big? (::and search? too-big?)
        hi* (::- mid one)]
    (cond
      done? lo
      search-fits? (::recur (switch search-fits? n)
                            (switch search-fits? mid)
                            (switch search-fits? hi))
      search-too-big? (::recur (switch search-too-big? n)
                               (switch search-too-big? lo)
                               (switch search-too-big? hi*)))))

(acc/def-recursive map-list
  [list mapper acc-list out]
  {:installers example-installers}
  (let-cell [head rest mapped-rest]
    (obj/p:car head list)
    (obj/p:cdr rest list)
      (let [mapped (::apply mapper head)
            mapped-node (::cons mapped mapped-rest)]
      (when rest
        (p:id (::recur rest mapper acc-list) mapped-rest))
      mapped-node)))

(acc/def-recursive map-list-fib
  [list out]
  {:installers example-installers
   :seed-values {map-list map-list
                 fib fib
                 acc-list unused-acc-list}}
  (::apply map-list list fib acc-list))

(acc/def-recursive relational-id-map-list
  [list out]
  {:installers example-installers}
  (let-cell [head rest mapped-head mapped-rest]
    (obj/p:car head list)
    (obj/p:cdr rest list)
    (obj/p:car mapped-head out)
    (obj/p:cdr mapped-rest out)
    (p:id head mapped-head)
    (p:id mapped-head head)
    (when rest
      (p:id (::recur rest) mapped-rest))
    out))

(acc/def-recursive sum-step
  [acc-list value out]
  {:installers example-installers}
  (::+ acc-list value))

(acc/def-recursive reduce-list
  [list step acc-list out]
  {:installers example-installers}
  (let-cell [head rest]
    (let [list-empty? (::empty-list? list)
          list-more? (::not list-empty?)
          node (switch list-more? list)]
      (obj/p:car head node)
      (obj/p:cdr rest node)
      (let [next-acc (::apply step acc-list head)
            rest-empty? (::empty-list? rest)
            rest-more? (::not rest-empty?)]
        (cond
          list-empty? acc-list
          rest-empty? next-acc
          rest-more? (::recur (switch rest-more? rest) step next-acc))))))

(acc/def-recursive even-predicate
  [value out]
  {:installers example-installers}
  (::even? value))

(acc/def-recursive double-value
  [value out]
  {:installers example-installers}
  (::* value 2))

(acc/def-recursive bidirectional-double-value
  [value out]
  {:installers example-installers}
  (let [two 2]
    (p:id (:/ out two) value)
    (::* value two)))

(acc/def-recursive bidirectional-id
  [value out]
  {:installers example-installers}
  (do
    (p:id out value)
    value))

(defn- scope-object-installers
  [runtime]
  (acc/contextual-installers
   (merge (compile/default-installers)
          {'p:scope-x (fn [& ids]
                        (let [out-id (or (last ids) (ids/new-node-id))]
                          (fn [n]
                            (let [n0 (gur-env/bind-in-scope-object n
                                                                    (:scope runtime)
                                                                    'x
                                                                    (first (:args runtime)))
                                  bind-props (gur-env/scope-object-prop-ids n0)
                                  [access-prop n1]
                                  ((gur-env/p:scope-object-access (:scope runtime)
                                                                  'x
                                                                  out-id)
                                   (nb/ensure-cell n0 out-id))]
                              [(vec (conj bind-props access-prop)) n1]))))})
   runtime))

(acc/def-recursive scope-object-echo-x
  [x out]
  {:installers scope-object-installers}
  (let-cell [scope-x]
    (p:scope-x scope-x)
    scope-x))

(def counted-double-runs (atom 0))

(def p:counted-double
  (prop/primitive-propagator
   (fn [v]
     (swap! counted-double-runs inc)
     (* v 2))))

(defn- counted-installers
  [runtime]
  (acc/contextual-installers
   (merge (compile/default-installers)
          {'p:counted-double p:counted-double})
   runtime))

(acc/def-recursive counted-double-value
  [value out]
  {:installers counted-installers}
  (::counted-double value))

(acc/def-recursive filter-list
  [list predicate acc-list out]
  {:installers example-installers}
  (let-cell [head rest]
    (let [list-empty? (::empty-list? list)
          list-more? (::not list-empty?)
          node (switch list-more? list)]
      (obj/p:car head node)
      (obj/p:cdr rest node)
      (let [keep? (::apply predicate head)
            rest-empty? (::empty-list? rest)
            rest-more? (::not rest-empty?)
            filtered-rest (cond
                            rest-empty? acc-list
                            rest-more? (::recur (switch rest-more? rest)
                                                predicate
                                                acc-list))
            kept-node (::cons head filtered-rest)
            drop? (::not keep?)
            branch (cond
                     keep? kept-node
                     drop? filtered-rest)]
        (cond
          list-empty? acc-list
          list-more? branch)))))

(defn- run-closure
  [closure arg-values]
  (let [closure-id (ids/new-node-id)
        arg-ids (vec (repeatedly (count arg-values) ids/new-node-id))
        out-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell closure-id closure closure)
               (nb/install-cell out-id))
        n1 (reduce (fn [n [id v]]
                     (nb/install-cell n id v v))
                   n0
                   (map vector arg-ids arg-values))
        [props n2] ((acc/p:apply-closure closure-id arg-ids out-id) n1)
        n3 (run-props n2 props)
        applied-net-id (net/network-dict-entry
                        n3
                        (acc/application-key closure-id arg-ids out-id))]
    {:net n3
     :props props
     :closure-id closure-id
     :arg-ids arg-ids
     :out-id out-id
     :applied-net-id applied-net-id
     :acc-net (strongest n3 applied-net-id)
     :value (strongest n3 out-id)}))

(defn- list-slot
  [v slot-key]
  (cond
    (value/unusable? v) v
    (and (net/net? v) (obj/accessor-source-slot-present? v slot-key))
    (obj/accessor-source-slot-value v slot-key)
    (net/net? v) (or (obj/slot-value v slot-key) value/nothing)
    :else value/nothing))

(defn- list->vec
  ([v] (list->vec v 32))
  ([v limit]
   (loop [current v
          remaining limit
          acc []]
     (cond
       (zero? remaining) acc
       (subenv/empty-list? current) acc
       (value/unusable? current) acc
       :else (recur (list-slot current :cdr)
                    (dec remaining)
                    (conj acc (list-slot current :car)))))))

(defn- list->data
  [v]
  (if (subenv/list-node-value? v)
    (mapv list->data (list->vec v))
    v))

(defn- route-owner-ids
  [n]
  (->> (net/net-dict-or-empty n)
       vals
       (keep (fn [route]
               (when (and (vector? route)
                          (= :dispatch/subenv (first route)))
                 (second route))))
       set))

(defn- route-count
  [n owner-id]
  (count (filter (fn [[_ route]]
                   (and (vector? route)
                        (= :dispatch/subenv (first route))
                        (= owner-id (second route))))
                 (net/net-dict-or-empty n))))

(defn- prop-count
  [n]
  (count (filter prop/prop? (vals (net/net-env n)))))

(defn- frame-count
  [acc-net]
  (count (net/network-dict-entry acc-net acc/frame-index-key)))

(defn- nested-frame-net-count
  [acc-net]
  (count
   (keep (fn [[_ entry]]
           (let [v (when (map? entry) (:strongest entry))]
             (when (and (net/net? v)
                        (net/network-dict-entry v subenv/scope-key))
               v)))
         (net/net-env acc-net))))

(defn- topology-stats
  [{:keys [net applied-net-id acc-net]}]
  {:frames (frame-count acc-net)
   :owners (count (route-owner-ids net))
   :routes (route-count net applied-net-id)
   :props (prop-count acc-net)
   :nested-frame-nets (nested-frame-net-count acc-net)})

(deftest accumulating-gur-computes-scalar-recursion
  (testing "scalar parity cases"
    (is (= 0 (:value (run-closure fib [0]))))
    (is (= 1 (:value (run-closure fib [1]))))
    (is (= 5 (:value (run-closure fib [5]))))
    (is (= 8 (:value (run-closure fib [6]))))
    (is (= 120 (:value (run-closure factorial [5]))))
    (is (= 9 (:value (run-closure int-sqrt-search [81 0 81]))))))

(deftest accumulating-gur-computes-list-map-reduce-filter
  (testing "compound list cases use accessor values and bounded assertion readers"
    (is (= [0 1 1 2 3 5]
           (list->vec (:value (run-closure map-list-fib
                                           [(lazy-cons-list-value
                                             [0 1 2 3 4 5])])))))
    (is (= 15 (:value (run-closure reduce-list
                                    [(subenv/cons-list-value [1 2 3 4 5])
                                     sum-step
                                     0]))))
    (is (= [2 4 6]
           (list->vec (:value (run-closure filter-list
                                           [(subenv/cons-list-value
                                             [1 2 3 4 5 6])
                                            even-predicate
                                            subenv/empty-list])))))))

(deftest accumulating-gur-computes-nested-map-list
  (let [source (lazy-cons-list-value [(lazy-cons-list-value [0 1])
                                      (lazy-cons-list-value [2 3])])
        result (run-closure map-list [source map-list-fib unused-acc-list])]
    (is (= [[0 1] [1 2]] (list->data (:value result))))))

(deftest accumulating-gur-can-read-frame-binding-through-compound-scope
  (let [result (run-closure scope-object-echo-x [42])
        scope-objects (net/network-dict-entry (:acc-net result)
                                              gur-env/scope-object-key)]
    (is (= 42 (:value result)))
    (is (seq scope-objects))
    (is (every? #(obj/accessor-network? (strongest (:acc-net result) %))
                (vals scope-objects)))))

(defn- pcons-list-source
  [n values terminal-value]
  (let [values (vec values)
        len (count values)
        heads (vec (repeatedly len ids/new-node-id))
        colls (vec (repeatedly len ids/new-node-id))
        terminal-id (ids/new-node-id)
        n0 (reduce nb/install-cell n (conj (into heads colls) terminal-id))]
    (reduce
     (fn [{:keys [net prop-ids] :as acc} i]
       (let [[ids n'] ((obj/p:cons (heads i)
                                   (if (= i (dec len))
                                     terminal-id
                                     (colls (inc i)))
                                   (colls i))
                       net)]
         (assoc acc :net n' :prop-ids (into prop-ids ids))))
     {:net n0
      :prop-ids []
      :root-id (first colls)
      :seeds (conj (mapv vector heads values)
                   [terminal-id terminal-value])}
     (range len))))

(defn- run-list-hop-chain
  ([operator mapper values depth]
   (run-list-hop-chain operator mapper values depth value/nothing))
  ([operator mapper values depth terminal-value]
   (run-list-hop-chain operator mapper values depth terminal-value unused-acc-list))
  ([operator mapper values depth terminal-value acc-value]
   (let [op-id (ids/new-node-id)
        mapper-id (ids/new-node-id)
        acc-id (ids/new-node-id)
        out-ids (vec (repeatedly depth ids/new-node-id))
        n0 (-> net/empty-net
               (nb/install-cell op-id operator operator)
               (nb/install-cell mapper-id mapper mapper)
               (nb/install-cell acc-id acc-value acc-value))
        {:keys [net prop-ids root-id seeds]} (pcons-list-source n0 values terminal-value)
        n1 (reduce nb/install-cell net out-ids)
        [props n2]
        (reduce (fn [[props n] i]
                  (let [in-id (if (zero? i) root-id (out-ids (dec i)))
                        out-id (out-ids i)
                        [ids n'] ((acc/p:apply-closure op-id
                                                       [in-id mapper-id acc-id]
                                                       out-id)
                                  n)]
                    [(into props ids) n']))
                [[] n1]
                (range depth))
        [n3 tasks] (reduce (fn [[n tasks] [id v]]
                             (nb/seed-cell! n tasks id v))
                           [n2 (tq/enqueue-all tq/empty-queue
                                               (into prop-ids props))]
                           seeds)
        n3 (core/run-tasks tasks n3)]
    {:net n3
     :out-id (peek out-ids)
     :value (strongest n3 (peek out-ids))})))

(deftest accumulating-gur-one-map-hop-works
  (is (= [2 2 2 2 2]
         (list->vec (:value (run-list-hop-chain map-list
                                                double-value
                                                [1 1 1 1 1]
                                                1))))))

(deftest accumulating-gur-hop-output-stores-scoped-slot-participants
  (let [result (run-list-hop-chain map-list double-value [1 1 1 1 1] 1)
        output (:value result)
        parents (mapcat #(obj/accessor-parent-ids output %)
                        (obj/accessor-slot-keys output))
        scoped-parents (filter scoped/address? parents)]
    (is (obj/accessor-network? output))
    (is (seq scoped-parents))
    (is (not-any? #(contains? (net/net-env (:net result)) %) scoped-parents))))

(deftest accumulating-gur-hop-output-cdr-update-is-bidirectional
  (let [result (run-list-hop-chain map-list double-value [1] 1)
        tail-id (ids/new-node-id)
        n0 (nb/install-cell (:net result) tail-id)
        [slot-prop n1] ((obj/p:network-cdr tail-id (:out-id result)) n0)
        n2 (run-props n1 [slot-prop])
        [tasks n3] (core/eval-cell tail-id
                                    (message tail-id
                                             (subenv/cons-list-value [9]))
                                    n2)
        n4 (core/run-tasks tasks n3)
        n5 (run-props n4 [slot-prop])]
    (is (= [2 9] (list->vec (strongest n5 (:out-id result)))))))

(defn- one-node-unseeded-hop
  [closure arg-cells]
  (let [closure-id (ids/new-node-id)
        head-id (ids/new-node-id)
        terminal-id (ids/new-node-id)
        source-id (ids/new-node-id)
        out-id (ids/new-node-id)
        out-head-id (ids/new-node-id)
        arg-ids (mapv first arg-cells)
        n0 (-> net/empty-net
               (nb/install-cell closure-id closure closure)
               (#(reduce nb/install-cell
                          %
                          [head-id terminal-id source-id out-id out-head-id]))
               (#(reduce (fn [n [id v]]
                            (nb/install-cell n id v v))
                          %
                          arg-cells)))
        [cons-props n1] ((obj/p:cons head-id terminal-id source-id) n0)
        [apply-props n2] ((acc/p:apply-closure closure-id
                                               (into [source-id] arg-ids)
                                               out-id)
                          n1)
        n3 (core/run-tasks (tq/enqueue-all tq/empty-queue
                                           (into cons-props apply-props))
                           n2)
        [out-car-prop n4] ((obj/p:car out-head-id out-id) n3)
        n5 (run-props n4 [out-car-prop])
        [tasks n6] (core/eval-cell out-head-id
                                    (message out-head-id 9)
                                    n5)
        n7 (core/run-tasks tasks n6)
        n8 (run-props n7 (into apply-props [out-car-prop]))]
    {:net n8
     :head-id head-id
     :out-id out-id}))

(deftest accumulating-gur-closure-mapper-output-car-flows-to-input
  (let [mapper-id (ids/new-node-id)
        acc-id (ids/new-node-id)
        {:keys [net head-id out-id]}
        (one-node-unseeded-hop map-list
                               [[mapper-id bidirectional-id]
                                [acc-id unused-acc-list]])]
    (is (= 9 (strongest net head-id)))
    (is (= [9] (take 1 (list->vec (strongest net out-id)))))))

(deftest accumulating-gur-relational-map-list-output-car-flows-to-input
  (let [{:keys [net head-id out-id]} (one-node-unseeded-hop relational-id-map-list [])]
    (is (= 9 (strongest net head-id)))
    (is (= [9] (take 1 (list->vec (strongest net out-id)))))))

(defn- unseeded-hop-chain-output-write
  ([depth]
   (unseeded-hop-chain-output-write depth bidirectional-id 9))
  ([depth mapper output-value]
  (let [op-id (ids/new-node-id)
        mapper-id (ids/new-node-id)
        acc-id (ids/new-node-id)
        head-id (ids/new-node-id)
        terminal-id (ids/new-node-id)
        source-id (ids/new-node-id)
        out-ids (vec (repeatedly depth ids/new-node-id))
        out-head-id (ids/new-node-id)
        n0 (-> net/empty-net
               (nb/install-cell op-id map-list map-list)
               (nb/install-cell mapper-id mapper mapper)
               (nb/install-cell acc-id unused-acc-list unused-acc-list)
               (#(reduce nb/install-cell
                          %
                          (concat [head-id terminal-id source-id out-head-id]
                                  out-ids))))
        [cons-props n1] ((obj/p:cons head-id terminal-id source-id) n0)
        [apply-props n2]
        (reduce (fn [[props n] i]
                  (let [in-id (if (zero? i) source-id (out-ids (dec i)))
                        out-id (out-ids i)
                        [ids n'] ((acc/p:apply-closure op-id
                                                       [in-id mapper-id acc-id]
                                                       out-id)
                                  n)]
                    [(into props ids) n']))
                [[] n1]
                (range depth))
        n3 (core/run-tasks (tq/enqueue-all tq/empty-queue
                                           (into cons-props apply-props))
                           n2)
        [out-car-prop n4] ((obj/p:car out-head-id (peek out-ids)) n3)
        n5 (run-props n4 [out-car-prop])
        [tasks n6] (core/eval-cell out-head-id
                                    (message out-head-id output-value)
                                    n5)
        n7 (core/run-tasks tasks n6)
        n8 (run-props n7 (into apply-props [out-car-prop]))]
    {:net n8
     :head-id head-id
     :out-id (peek out-ids)})))

(deftest accumulating-gur-bidirectional-mapper-chain-output-car-flows-to-source
  (doseq [depth [2 5]]
    (let [{:keys [net head-id out-id]} (unseeded-hop-chain-output-write depth)]
      (is (= 9 (strongest net head-id)) (str "depth " depth))
      (is (= [9] (take 1 (list->vec (strongest net out-id))))
          (str "depth " depth)))))

(deftest accumulating-gur-bidirectional-arithmetic-mapper-chain-output-car-flows-to-source
  (let [{:keys [net head-id out-id]}
        (unseeded-hop-chain-output-write 5 bidirectional-double-value 32)]
    (is (= 1 (strongest net head-id)))
    (is (= [32] (take 1 (list->vec (strongest net out-id)))))))

(deftest accumulating-gur-hop-chain-parity
  (testing "same-parent mapper chains traverse lazy linked lists"
    (doseq [depth [5 10 15]]
      (is (= (repeat 5 (long (Math/pow 2 depth)))
             (list->vec (:value (run-list-hop-chain map-list
                                                    double-value
                                                    [1 1 1 1 1]
                                                    depth))))
          (str "mapper chain depth " depth))))
  (testing "filter chains keep the existing empty-list accumulator"
    (doseq [depth [5 10]]
      (is (= [2 4 6]
             (list->vec (:value (run-list-hop-chain filter-list
                                                    even-predicate
                                                    [1 2 3 4 5 6]
                                                    depth
                                                    subenv/empty-list
                                                    subenv/empty-list))))
          (str "filter chain depth " depth)))))

(deftest accumulating-gur-topology-only-tail-remains-legacy-list-shape-gap
  (let [parent (scoped/name-ref [:debug/frame] :tail)
        topology-only-empty (-> subenv/empty-list
                                (merge/cell-merge
                                 (obj/accessor-declaration :car parent)
                                 net/empty-net)
                                (merge/cell-merge
                                 (obj/accessor-declaration :cdr parent)
                                 net/empty-net))]
    (is (empty? (obj/accessor-source-slots topology-only-empty)))
    (is (= #{:car :cdr} (obj/accessor-slot-keys topology-only-empty)))
    (is (false? (subenv/empty-list? topology-only-empty))
        "Known gap: topology-only slot declarations currently count as list shape.")))

(deftest accumulating-gur-uses-one-owner-and-is-idempotent
  (let [result (run-closure fib [5])
        stats (topology-stats result)
        rerun-net (run-props (:net result) (:props result))
        rerun-result (assoc result
                            :net rerun-net
                            :acc-net (strongest rerun-net (:applied-net-id result)))
        stats* (topology-stats rerun-result)]
    (is (= 5 (:value result)))
    (is (= #{(:applied-net-id result)} (route-owner-ids (:net result))))
    (is (pos? (:frames stats)))
    (is (pos? (:props stats)))
    (is (= 0 (:nested-frame-nets stats)))
    (is (= stats stats*))))

(deftest accumulating-gur-ran-task-state-is-primitive-local
  (reset! counted-double-runs 0)
  (let [result (run-closure counted-double-value [5])
        rerun-net (run-props (:net result) (:props result))
        count-after-rerun @counted-double-runs
        stable-net (run-props rerun-net (:props result))
        count-after-stable @counted-double-runs
        rerun-acc-net (strongest stable-net (:applied-net-id result))]
    (is (= 10 (:value result)))
    (is (= 10 (strongest rerun-net (:out-id result))))
    (is (= 1 count-after-rerun))
    (is (= 10 (strongest stable-net (:out-id result))))
    (is (= count-after-rerun count-after-stable))
    (is (nil? (net/network-dict-entry (:acc-net result) queue/child-queue-key)))
    (is (nil? (net/network-dict-entry rerun-acc-net queue/child-queue-key)))
    (is (seq (net/network-dict-entry rerun-acc-net acc/task-index-key)))
    (is (not (re-find #":(cursor|pending|ran|scheduled)"
                      (pr-str (net/net-dict-or-empty rerun-acc-net)))))))

(defn- dispatch-route-value
  [network route]
  (case (first route)
    :dispatch/subenv
    (let [[_ owner-id local-id] route
          child-net (strongest network owner-id)]
      (strongest child-net local-id))
    :dispatch/local
    (strongest network (second route))))

(defn- rest-target-with-nothing
  [network owner-id]
  (some (fn [[target route]]
          (when (and (vector? target)
                     (= :env/ref (first target))
                     (= :rest (nth target 2 nil))
                     (vector? route)
                     (= :dispatch/subenv (first route))
                     (= owner-id (second route))
                     (value/nothing? (dispatch-route-value network route)))
            target))
        (net/net-dict-or-empty network)))

(deftest accumulating-gur-routes-late-cdr-through-one-owner
  (testing "late cdr update enters the accumulated net through scoped dispatch"
    (let [initial-list (lazy-cons-cell-value 0 value/nothing)
          result (run-closure map-list
                              [initial-list fib unused-acc-list])
          target (rest-target-with-nothing (:net result)
                                           (:applied-net-id result))]
      (is (= [0] (list->vec (:value result))))
      (is (some? target))
      (let [[tasks n1] (core/eval-cell* (net/net-dict-or-empty (:net result))
                                        (message target
                                                 (lazy-cons-list-value [1 2]))
                                        (:net result))
            n2 (core/run-tasks tasks n1)]
        (is (= [0 1 1] (list->vec (strongest n2 (:out-id result)))))
        (is (= #{(:applied-net-id result)} (route-owner-ids n2)))))))

(deftest accumulating-gur-strict-pcons-late-cdr-stops-at-nothing
  (testing "public p:cons late extension uses nothing as the lazy terminal, not empty-list"
    (let [map-id (ids/new-node-id)
          fib-id (ids/new-node-id)
          acc-id (ids/new-node-id)
          source-id (ids/new-node-id)
          head0-id (ids/new-node-id)
          tail0-id (ids/new-node-id)
          out-id (ids/new-node-id)
          n0 (-> net/empty-net
                 (nb/install-cell map-id map-list map-list)
                 (nb/install-cell fib-id fib fib)
                 (nb/install-cell acc-id unused-acc-list unused-acc-list)
                 (nb/install-cell source-id)
                 (nb/install-cell head0-id 0 0)
                 (nb/install-cell tail0-id)
                 (nb/install-cell out-id))
          [cons0-props n1] ((obj/p:cons head0-id tail0-id source-id) n0)
          [apply-props n2] ((acc/p:apply-closure map-id
                                                 [source-id fib-id acc-id]
                                                 out-id)
                            n1)
          n3 (run-props n2 (concat cons0-props apply-props))
          head1-id (ids/new-node-id)
          tail1-id (ids/new-node-id)
          n4 (-> n3
                 (nb/install-cell head1-id 1 1)
                 (nb/install-cell tail1-id value/nothing value/nothing))
          [cons1-props n5] ((obj/p:cons head1-id tail1-id tail0-id) n4)
          n6 (run-props n5 cons1-props)
          n7 (run-props n6 apply-props)]
      (is (= [0] (list->vec (strongest n3 out-id))))
      (is (= [0 1] (list->vec (strongest n6 out-id))))
      (is (= [0 1] (list->vec (strongest n7 out-id)))))))

(defn- install-cons! [n tasks head-id tail-id coll-id]
  (let [[prop-ids n'] ((obj/p:cons head-id tail-id coll-id) n)]
    [n' (tq/enqueue-all tasks prop-ids)]))

(defn- linked-list
  [values]
  (let [values (vec values)
        len (count values)
        heads (vec (repeatedly len ids/new-node-id))
        colls (vec (repeatedly len ids/new-node-id))
        empty-id (ids/new-node-id)
        n0 (nb/install-cells (conj (into heads colls) empty-id))
        [n1 tasks] (reduce
                    (fn [[n tasks] i]
                      (install-cons! n
                                     tasks
                                     (heads i)
                                     (if (= i (dec len))
                                       empty-id
                                       (colls (inc i)))
                                     (colls i)))
                    [n0 tq/empty-queue]
                    (range len))
        [n2 tasks] (reduce-kv (fn [[n tasks] i v]
                                (nb/seed-cell! n tasks (heads i) v))
                              [n1 tasks]
                              values)
        [n3 tasks] (nb/seed-cell! n2 tasks empty-id subenv/empty-list)]
    {:net (core/run-tasks tasks n3)
     :root-id (first colls)}))

(defn- bias-access
  [env-id out-id]
  (compiler-env/p:lexical-access 'bias env-id out-id))

(defn- compiler-installers
  [runtime]
  (acc/contextual-installers
   (merge (compile/default-installers)
          {'obj/p:car obj/p:car
           'obj/p:cons obj/p:cons
           'p:bias-access bias-access})
   runtime))

(acc/def-recursive add-bias
  [x lexical-env out]
  {:name :accumulating-compiler/add-bias
   :installers compiler-installers}
  (let-cell [bias]
    (p:bias-access lexical-env bias)
    (:+ x bias)))

(acc/def-recursive linked-list-compiler
  [decl out]
  {:name :accumulating-compiler/compiler
   :installers compiler-installers
   :seed-values {compiled-add-bias add-bias}}
  (let-cell [tag name param op left right tail1 tail2 tail3 tail4 tail5]
    (obj/p:cons tag tail1 decl)
    (obj/p:cons name tail2 tail1)
    (obj/p:cons param tail3 tail2)
    (obj/p:cons op tail4 tail3)
    (obj/p:cons left tail5 tail4)
    (obj/p:car right tail5)
    compiled-add-bias))

(deftest accumulating-gur-compiler-2-linked-list-lexical-access
  (let [{:keys [net root-id]} (linked-list [:compound 'add-bias 'x '+ 'x 'bias])
        compiler-id (ids/new-node-id)
        compiled-id (ids/new-node-id)
        env-id (ids/new-node-id)
        x-id (ids/new-node-id)
        out-id (ids/new-node-id)
        n0 (-> net
               (nb/install-cell compiler-id
                                linked-list-compiler
                                linked-list-compiler)
               (nb/install-cell compiled-id)
               (nb/install-cell env-id
                                (obj/as-accessor-network {'bias 10})
                                (obj/as-accessor-network {'bias 10}))
               (nb/install-cell x-id 5 5)
               (nb/install-cell out-id))
        [compile-props n1] ((acc/p:apply-closure compiler-id
                                                 [root-id]
                                                 compiled-id)
                            n0)
        [apply-props n2] ((acc/p:apply-closure compiled-id
                                               [x-id env-id]
                                               out-id)
                          n1)
        n3 (run-props n2 (into compile-props apply-props))]
    (is (obj/accessor-network? (strongest n3 root-id)))
    (is (obj/accessor-network? (strongest n3 env-id)))
    (is (acc/recursive-closure? (strongest n3 compiled-id)))
    (is (= 15 (strongest n3 out-id)))))
