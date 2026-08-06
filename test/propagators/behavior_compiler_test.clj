(ns propagators.behavior-compiler-test
  (:require [clojure.core :as clj]
            [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell-protocol :as protocol]
            [propagators.cells.value :as value]
            [propagators.compile :as compile]
            [propagators.compiler-2.model.closure-value :as closure-value]
            [propagators.compiler-2.model.env :as env]
            [propagators.compiler-2.compiler.basis :as h]
            [propagators.compiler-behavior.main :as behavior-compiler]
            [propagators.core :as core]
            [propagators.datastructures.behavior :as behavior]
            [propagators.datastructures.behavior-algebra :as hist]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn- run-compiled
  [compiled]
  (nb/run-propagators (:net compiled) (:props compiled)))

(defn- behavior-net
  []
  (-> net/empty-net
      (compile/install-and-run (protocol/install-cell-protocol))
      (compile/install-and-run (protocol/install-behavior-protocol))))

(defn- strongest
  [n id]
  (net/network-cell-strongest n id))

(defn- content
  [n id]
  (net/network-cell-content n id))

(defn- behavior-view
  [records source-keys]
  (behavior/behavior-value
   {:history (hist/records->history records)
    :source-keys source-keys
    :reducer behavior/event-history-reducer-id}))

(defn- behavior-cell
  [n v]
  (let [id (ids/new-node-id)]
    [id (nb/install-cell n id v (behavior/strongest-value v))]))

(defn- behavior-arithmetic-env
  []
  (-> (h/default-env)
      (env/bind-at 'be:+ (h/behavior-operator :+ clj/+) 0)
      (env/bind-at 'be:- (h/behavior-operator :- clj/-) 0)
      (env/bind-at 'be:* (h/behavior-operator :* clj/*) 0)
      (env/bind-at 'be:divide (h/behavior-operator :/ clj//) 0)))

(defn- record-map
  [record]
  (cond
    (hist/point-record? record)
    {:at (obj/slot-value record :at)
     :value (obj/slot-value record :value)}

    (hist/interval-record? record)
    {:from (obj/slot-value record :from)
     :to (obj/slot-value record :to)
     :value (obj/slot-value record :value)}))

(defn- records
  [v]
  (mapv record-map (behavior/history-records v)))

(defn- current-value
  [n id]
  (let [v (strongest n id)]
    (if (value/unusable? v)
      v
      (behavior/base-value v))))

(defn- env-with
  [bindings]
  (reduce
   (fn [acc [sym id]]
     (env/bind acc sym (env/cell-binding id) 0))
   (behavior-arithmetic-env)
   bindings))

(defn- closure-info-from-source
  ([source]
   (closure-info-from-source source (behavior-arithmetic-env)))
  ([source env]
   (closure-info-from-source source env {}))
  ([source env opts]
   (let [compiled (behavior-compiler/compile-source source env opts)
         closure-summary (strongest (:net compiled) (:cell compiled))]
     (behavior/base-value closure-summary))))

(defn- closure-behavior-from-source
  ([source timestamp]
   (closure-behavior-from-source source (behavior-arithmetic-env) timestamp))
  ([source env timestamp]
   (let [compiled (behavior-compiler/compile-source source
                                                    env
                                                    {:timestamp timestamp})]
     (content (:net compiled) (:cell compiled)))))

(defn- closure-view
  [tick source-keys closure-info]
  (behavior/retained-value :compiler-behavior/closure
                           tick
                           closure-info
                           source-keys))

(defn- seed-behavior-message
  [n id v]
  (core/eval-cell id (message id v) n))

(deftest behavior-compiler-arithmetic-merges-same-timestamp-inputs
  (testing "compiled behavior + joins retained point histories"
    (let [left (behavior-view [(hist/point-record 6 2)] #{[:a 6]})
          right (behavior-view [(hist/point-record 6 7)] #{[:b 6]})
          [a-id n1] (behavior-cell (behavior-net) left)
          [b-id n2] (behavior-cell n1 right)
          compiled (behavior-compiler/compile-source
                    "(be:+ a b)"
                    (env-with {'a a-id 'b b-id})
                    {:net n2})
          result-net (run-compiled compiled)]
      (is (= 9 (current-value result-net (:cell compiled))))
      (is (= [{:at 6 :value 9}]
             (records (content result-net (:cell compiled))))))))

(deftest behavior-compiler-point-events-do-not-imply-continuation
  (testing "different point timestamps do not synchronize"
    (let [left (behavior-view [(hist/point-record 6 2)] #{[:a 6]})
          right (behavior-view [(hist/point-record 7 7)] #{[:b 7]})
          [a-id n1] (behavior-cell (behavior-net) left)
          [b-id n2] (behavior-cell n1 right)
          compiled (behavior-compiler/compile-source
                    "(be:+ a b)"
                    (env-with {'a a-id 'b b-id})
                    {:net n2})
          result-net (run-compiled compiled)]
      (is (= value/nothing (strongest result-net (:cell compiled)))))))

(deftest behavior-compiler-literals-are-constant-behaviors
  (testing "literal constants join with sparse behavior inputs"
    (let [input (behavior-view [(hist/point-record 6 2)] #{[:a 6]})
          [a-id n1] (behavior-cell (behavior-net) input)
          compiled (behavior-compiler/compile-source
                    "(be:+ a 1)"
                    (env-with {'a a-id})
                    {:net n1})
          result-net (run-compiled compiled)]
      (is (= 3 (current-value result-net (:cell compiled))))
      (is (= [{:at 6 :value 3}]
             (records (content result-net (:cell compiled))))))))

(deftest behavior-compiler-interval-arithmetic-uses-overlap
  (testing "compiled interval arithmetic emits only the common interval"
    (let [left (behavior-view [(hist/interval-record 0 10 2)] #{[:a 0]})
          right (behavior-view [(hist/interval-record 5 12 7)] #{[:b 5]})
          [a-id n1] (behavior-cell (behavior-net) left)
          [b-id n2] (behavior-cell n1 right)
          compiled (behavior-compiler/compile-source
                    "(be:+ a b)"
                    (env-with {'a a-id 'b b-id})
                    {:net n2})
          result-net (run-compiled compiled)]
      (is (= 9 (current-value result-net (:cell compiled))))
      (is (= [{:from 5 :to 10 :value 9}]
             (records (content result-net (:cell compiled))))))))

(deftest behavior-compiler-closure-declaration-is-latest-behavior
  (testing "a closure declaration emits behavior whose base is closure-info"
    (let [compiled (behavior-compiler/compile-source "(:: [x] (be:+ x 1))"
                                                     (behavior-arithmetic-env)
                                                     {:timestamp 4})
          closure-content (content (:net compiled) (:cell compiled))
          closure-summary (strongest (:net compiled) (:cell compiled))
          closure-info (behavior/base-value closure-summary)]
      (is (= behavior-compiler/closure-reducer-id
             (behavior/reducer-id closure-content)))
      (is (closure-value/closure-info? closure-info))
      (is (= '[x] (closure-value/closure-inputs closure-info)))
      (is (= 4 (behavior/summary-latest-time closure-summary)))
      (is (= 1 (count (behavior/history-records closure-content)))))))

(deftest behavior-compiler-applies-latest-retained-closure
  (testing "a behavior closure applies to behavior inputs"
    (let [input (behavior-view [(hist/point-record 6 2)] #{[:a 6]})
          [a-id n1] (behavior-cell (behavior-net) input)
          compiled (behavior-compiler/compile-source
                    "((:: [x] (be:+ x 1)) a)"
                    (env-with {'a a-id})
                    {:net n1})
          result-net (run-compiled compiled)]
      (is (= 3 (current-value result-net (:cell compiled))))
      (is (= [{:at 6 :value 3}]
             (records (content result-net (:cell compiled))))))))

(deftest behavior-compiler-closure-update-replaces-latest-output
  (testing "updating a closure cell changes later/refired application output"
    (let [inc-info (closure-info-from-source "(:: [x] (be:+ x 1))")
          double-info (closure-info-from-source "(:: [x] (be:* x 2))")
          initial-closure (closure-view 0 #{0} inc-info)
          updated-closure (closure-view 1 #{1} double-info)
          input (behavior-view [(hist/point-record 6 10)] #{[:a 6]})
          [f-id n1] (behavior-cell (behavior-net) initial-closure)
          [a-id n2] (behavior-cell n1 input)
          compiled (behavior-compiler/compile-source
                    "(f a)"
                    (env-with {'f f-id 'a a-id})
                    {:net n2})
          n3 (run-compiled compiled)
          [_tasks n4] (seed-behavior-message n3 f-id updated-closure)
          result-net (nb/run-propagators n4
                                         (nb/neighbor-propagator-ids n4 f-id))
          closure-content (content result-net f-id)]
      (is (= 11 (current-value n3 (:cell compiled))))
      (is (= [{:at 6 :value 11}]
             (records (content n3 (:cell compiled)))))
      (is (= 20 (current-value result-net (:cell compiled))))
      (is (= [{:at 6 :value 20}]
             (records (content result-net (:cell compiled)))))
      (is (= 2 (count (behavior/history-records closure-content))))
      (is (= 1 (behavior/summary-latest-time (strongest result-net f-id)))))))

(deftest behavior-compiler-same-closure-update-increases-closure-history
  (testing "the same latest closure can add retained history without changing the result"
    (let [inc-info (closure-info-from-source "(:: [x] (be:+ x 1))")
          initial-closure (closure-view 0 #{0} inc-info)
          repeated-closure (closure-view 1 #{1} inc-info)
          input (behavior-view [(hist/point-record 6 10)] #{[:a 6]})
          [f-id n1] (behavior-cell (behavior-net) initial-closure)
          [a-id n2] (behavior-cell n1 input)
          compiled (behavior-compiler/compile-source
                    "(f a)"
                    (env-with {'f f-id 'a a-id})
                    {:net n2})
          n3 (run-compiled compiled)
          [_tasks n4] (seed-behavior-message n3 f-id repeated-closure)
          result-net (nb/run-propagators n4
                                         (nb/neighbor-propagator-ids n4 f-id))
          closure-content (content result-net f-id)]
      (is (= 11 (current-value n3 (:cell compiled))))
      (is (= 11 (current-value result-net (:cell compiled))))
      (is (= 2 (count (behavior/history-records closure-content))))
      (is (= 2 (behavior/summary-retained-count (strongest result-net f-id))))
      (is (= 1 (behavior/summary-latest-time (strongest result-net f-id)))))))

(deftest behavior-compiler-closure-update-preserves-lexical-scope
  (testing "each closure version applies with its retained lexical env, not the caller env"
    (let [x-input (behavior-view [(hist/point-record 6 10)] #{[:x 6]})
          y-small (behavior-view [(hist/point-record 6 1)] #{[:y-small 6]})
          y-large (behavior-view [(hist/point-record 6 100)] #{[:y-large 6]})
          y-caller (behavior-view [(hist/point-record 6 1000)] #{[:y-caller 6]})
          [x-id n1] (behavior-cell (behavior-net) x-input)
          [small-id n2] (behavior-cell n1 y-small)
          [large-id n3] (behavior-cell n2 y-large)
          [caller-y-id n4] (behavior-cell n3 y-caller)
          small-info (closure-info-from-source
                      "(:: [x] (be:+ x y))"
                      (env-with {'y small-id}))
          large-info (closure-info-from-source
                      "(:: [x] (be:+ x y))"
                      (env-with {'y large-id}))
          initial-closure (closure-view 0 #{0} small-info)
          updated-closure (closure-view 1 #{1} large-info)
          [f-id n5] (behavior-cell n4 initial-closure)
          compiled (behavior-compiler/compile-source
                    "(f x)"
                    (env-with {'f f-id
                               'x x-id
                               'y caller-y-id})
                    {:net n5})
          n6 (run-compiled compiled)
          [_tasks n7] (seed-behavior-message n6 f-id updated-closure)
          result-net (nb/run-propagators n7
                                         (nb/neighbor-propagator-ids n7 f-id))
          closure-content (content result-net f-id)]
      (is (= 11 (current-value n6 (:cell compiled))))
      (is (= 110 (current-value result-net (:cell compiled))))
      (is (= 2 (count (behavior/history-records closure-content)))))))

(deftest behavior-compiler-compiled-application-reacts-to-timestamped-definition-updates
  (testing "compile the application once, then merge separately compiled closure versions"
    (let [input (behavior-view [(hist/point-record 6 10)] #{[:a 6]})
          f-id (ids/new-node-id)
          [a-id n1] (behavior-cell (nb/install-cell (behavior-net) f-id)
                                   input)
          compiled (behavior-compiler/compile-source
                    "(f a)"
                    (env-with {'f f-id 'a a-id})
                    {:net n1})
          empty-result (run-compiled compiled)
          inc-closure (closure-behavior-from-source "(:: [x] (be:+ x 1))" 0)
          double-closure (closure-behavior-from-source "(:: [x] (be:* x 2))" 1)
          [inc-tasks n2] (seed-behavior-message empty-result f-id inc-closure)
          inc-result (core/run-tasks inc-tasks n2)
          [double-tasks n3] (seed-behavior-message inc-result
                                                   f-id
                                                   double-closure)
          double-result (core/run-tasks double-tasks n3)
          closure-content (content double-result f-id)]
      (is (= value/nothing (strongest empty-result (:cell compiled))))
      (is (= 11 (current-value inc-result (:cell compiled))))
      (is (= [{:at 6 :value 11}]
             (records (content inc-result (:cell compiled)))))
      (is (= 20 (current-value double-result (:cell compiled))))
      (is (= [{:at 6 :value 20}]
             (records (content double-result (:cell compiled)))))
      (is (= 2 (count (behavior/history-records closure-content))))
      (is (= 1 (behavior/summary-latest-time (strongest double-result f-id)))))))

(deftest behavior-compiler-applies-closure-history-over-input-history
  (testing "each retained closure version applies only where it overlaps inputs"
    (let [input (behavior-view [(hist/point-record 0 10)
                                (hist/point-record 1 20)]
                               #{[:a 0] [:a 1]})
          f-id (ids/new-node-id)
          [a-id n1] (behavior-cell (nb/install-cell (behavior-net) f-id)
                                   input)
          compiled (behavior-compiler/compile-source
                    "(f a)"
                    (env-with {'f f-id 'a a-id})
                    {:net n1})
          empty-result (run-compiled compiled)
          inc-closure (closure-behavior-from-source "(:: [x] (be:+ x 1))" 0)
          double-closure (closure-behavior-from-source "(:: [x] (be:* x 2))" 1)
          [inc-tasks n2] (seed-behavior-message empty-result f-id inc-closure)
          inc-result (core/run-tasks inc-tasks n2)
          [double-tasks n3] (seed-behavior-message inc-result
                                                   f-id
                                                   double-closure)
          result-net (core/run-tasks double-tasks n3)]
      (is (= [{:at 0 :value 11}
              {:at 1 :value 40}]
             (records (content result-net (:cell compiled))))))))

(deftest behavior-compiler-same-input-sees-latest-closure-version
  (testing "the same already-declared application updates from closure v1 to v2"
    (let [input (behavior-view [(hist/point-record 6 :same-input)] #{[:a 6]})
          f-id (ids/new-node-id)
          [a-id n1] (behavior-cell (nb/install-cell (behavior-net) f-id)
                                   input)
          compiled (behavior-compiler/compile-source
                    "(f a)"
                    (env-with {'f f-id 'a a-id})
                    {:net n1})
          empty-result (run-compiled compiled)
          v1-closure (closure-behavior-from-source "(:: [x] 1)" 0)
          v2-closure (closure-behavior-from-source "(:: [x] 2)" 1)
          [v1-tasks n2] (seed-behavior-message empty-result f-id v1-closure)
          v1-result (core/run-tasks v1-tasks n2)
          [v2-tasks n3] (seed-behavior-message v1-result f-id v2-closure)
          v2-result (core/run-tasks v2-tasks n3)
          closure-content (content v2-result f-id)]
      (is (= value/nothing (strongest empty-result (:cell compiled))))
      (is (= 1 (current-value v1-result (:cell compiled))))
      (is (= [{:from 0 :to :infinity :value 1}]
             (records (content v1-result (:cell compiled)))))
      (is (= 2 (current-value v2-result (:cell compiled))))
      (is (= [{:from 0 :to :infinity :value 2}]
             (records (content v2-result (:cell compiled)))))
      (is (= 2 (count (behavior/history-records closure-content))))
      (is (= 1 (behavior/summary-latest-time (strongest v2-result f-id)))))))

(deftest behavior-compiler-closure-argument-does-not-write-outer-binding
  (testing "closure argument binding shadows an outer behavior cell"
    (let [outer (behavior-view [(hist/point-record 6 100)] #{[:outer 6]})
          input (behavior-view [(hist/point-record 6 5)] #{[:input 6]})
          [outer-x-id n1] (behavior-cell (behavior-net) outer)
          [arg-id n2] (behavior-cell n1 input)
          compiled (behavior-compiler/compile-source
                    "((:: [x] (be:+ x 1)) a)"
                    (env-with {'x outer-x-id 'a arg-id})
                    {:net n2})
          result-net (run-compiled compiled)]
      (is (= 6 (current-value result-net (:cell compiled))))
      (is (= 100 (current-value result-net outer-x-id))))))
