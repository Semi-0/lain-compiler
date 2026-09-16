(ns propagators.compiler.behavior-compiler-test
  (:require [clojure.core :as clj]
            [clojure.test :refer [deftest is testing]]
            [propagators.infra.cells.cell-protocol :as protocol]
            [propagators.infra.cells.value :as value]
            [propagators.infra.compile :as compile]
            [propagators.compiler.model.closure-value :as closure-value]
            [propagators.compiler.model.env :as env]
            [propagators.compiler.compiler.basis :as h]
            [propagators.compiler.language.parser :as parser]
            [propagators.compiler.behavior.main :as behavior-compiler]
            [propagators.infra.core :as core]
            [propagators.infra.datastructures.behavior :as behavior]
            [propagators.infra.datastructures.behavior-algebra :as hist]
            [propagators.infra.datastructures.compound-object :as obj]
            [propagators.infra.ids :as ids]
            [propagators.infra.message :refer [message]]
            [propagators.infra.network :as net]
            [propagators.infra.network-builder :as nb]))

(defn- run-compiled
  [compiled]
  (nb/run-propagators (:net compiled) (:props compiled)))

(def ^:private behavior-base-net
  (-> net/empty-net
      (compile/install-and-run (protocol/install-cell-protocol))
      (compile/install-and-run (protocol/install-behavior-protocol))))

(defn- behavior-net
  []
  behavior-base-net)

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

(def ^:private behavior-arithmetic-bindings
  (into (vec (h/default-bindings))
        [['be:+ (h/behavior-operator :+ clj/+)]
         ['be:- (h/behavior-operator :- clj/-)]
         ['be:* (h/behavior-operator :* clj/*)]
         ['be:divide (h/behavior-operator :/ clj//)]]))

(defn- behavior-arithmetic-env
  []
  behavior-arithmetic-bindings)

(def ^:private closure-history-bindings
  (filterv (fn [[symbol _operator]]
             (contains? '#{be:+ be:*} symbol))
           behavior-arithmetic-bindings))

(defn- closure-history-env
  [bindings]
  (into closure-history-bindings
        (map (fn [[symbol id]]
               [symbol (env/cell-binding id)]))
        bindings))

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
  (into (behavior-arithmetic-env)
        (map (fn [[sym id]] [sym (env/cell-binding id)]))
        bindings))

(defn- live-root
  [n bindings]
  (env/declare-root n (ids/new-node-id) bindings))

(defn- closure-info-fixture
  ([n source]
   (closure-info-fixture n source (behavior-arithmetic-env) {}))
  ([n source env]
   (closure-info-fixture n source env {}))
  ([n source env opts]
   (let [compiled (behavior-compiler/compile-source source env (assoc opts :net n))
         installed (run-compiled compiled)
         closure-summary (strongest installed (:cell compiled))]
     {:net installed
      :closure-info (behavior/base-value closure-summary)})))

(defn- closure-behavior-fixture
  [n environment-id source timestamp]
  (let [compiled (behavior-compiler/compile-source
                  source
                  environment-id
                  {:net n :timestamp timestamp})
        installed (run-compiled compiled)]
    {:net installed
     :closure-behavior (content installed (:cell compiled))}))

(defn- closure-view
  [tick source-keys closure-info]
  (behavior/retained-value :compiler-behavior/closure
                           tick
                           closure-info
                           source-keys))

(defn- closure-info
  [environment-id body-source]
  (closure-value/closure-object environment-id
                                (parser/parse-string body-source)
                                ['x]
                                nil
                                environment-id))

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
    (let [input (behavior-view [(hist/point-record 6 10)] #{[:a 6]})
          f-id (ids/new-node-id)
          [a-id n0] (behavior-cell (nb/install-cell (behavior-net) f-id) input)
          root (live-root n0 (env-with {'f f-id 'a a-id}))
          rooted (nb/run-propagators (:net root) (:props root))
          inc-info (closure-info (:env root) "(be:+ x 1)")
          double-info (closure-info (:env root) "(be:* x 2)")
          initial-closure (closure-view 0 #{0} inc-info)
          updated-closure (closure-view 1 #{1} double-info)
          [_tasks n3] (seed-behavior-message rooted f-id initial-closure)
          compiled (behavior-compiler/compile-source
                    "(f a)"
                    (:env root)
                    {:net n3})
          n4 (run-compiled compiled)
          [_tasks n5] (seed-behavior-message n4 f-id updated-closure)
          result-net (nb/run-propagators n5
                                         (nb/neighbor-propagator-ids n5 f-id))
          closure-content (content result-net f-id)]
      (is (= 11 (current-value n4 (:cell compiled))))
      (is (= [{:at 6 :value 11}]
             (records (content n4 (:cell compiled)))))
      (is (= 20 (current-value result-net (:cell compiled))))
      (is (= [{:at 6 :value 20}]
             (records (content result-net (:cell compiled)))))
      (is (= 2 (count (behavior/history-records closure-content))))
      (is (= 1 (behavior/summary-latest-time (strongest result-net f-id)))))))

(deftest behavior-compiler-same-closure-update-increases-closure-history
  (testing "the same latest closure can add retained history without changing the result"
    (let [input (behavior-view [(hist/point-record 6 10)] #{[:a 6]})
          f-id (ids/new-node-id)
          [a-id n0] (behavior-cell (nb/install-cell (behavior-net) f-id) input)
          root (live-root n0 (env-with {'f f-id 'a a-id}))
          rooted (nb/run-propagators (:net root) (:props root))
          {n1 :net inc-info :closure-info}
          (closure-info-fixture rooted "(:: [x] (be:+ x 1))" (:env root))
          initial-closure (closure-view 0 #{0} inc-info)
          repeated-closure (closure-view 1 #{1} inc-info)
          [_tasks n2] (seed-behavior-message n1 f-id initial-closure)
          compiled (behavior-compiler/compile-source
                    "(f a)"
                    (:env root)
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
          base (live-root n4 (behavior-arithmetic-env))
          base-net (nb/run-propagators (:net base) (:props base))
          small-env-id (ids/new-node-id)
          small-env (env/declare-child
                     base-net (:env base) small-env-id
                     [['y (env/cell-binding small-id)]])
          small-net (nb/run-propagators (:net small-env) (:props small-env))
          {n5 :net small-info :closure-info}
          (closure-info-fixture
           small-net
           "(:: [x] (be:+ x y))"
           small-env-id)
          large-env-id (ids/new-node-id)
          large-env (env/declare-child
                     n5 (:env base) large-env-id
                     [['y (env/cell-binding large-id)]])
          large-net (nb/run-propagators (:net large-env) (:props large-env))
          {n6 :net large-info :closure-info}
          (closure-info-fixture
           large-net
           "(:: [x] (be:+ x y))"
           large-env-id)
          initial-closure (closure-view 0 #{0} small-info)
          updated-closure (closure-view 1 #{1} large-info)
          f-id (ids/new-node-id)
          caller-env-id (ids/new-node-id)
          caller-env (env/declare-child
                      (nb/install-cell n6 f-id)
                      (:env base)
                      caller-env-id
                      [['f (env/cell-binding f-id)]
                       ['x (env/cell-binding x-id)]
                       ['y (env/cell-binding caller-y-id)]])
          caller-net (nb/run-propagators (:net caller-env) (:props caller-env))
          [_tasks n7] (seed-behavior-message caller-net f-id initial-closure)
          compiled (behavior-compiler/compile-source
                    "(f x)"
                    caller-env-id
                    {:net n7})
          n8 (run-compiled compiled)
          [_tasks n9] (seed-behavior-message n8 f-id updated-closure)
          result-net (nb/run-propagators n9
                                         (nb/neighbor-propagator-ids n9 f-id))
          closure-content (content result-net f-id)]
      (is (= 11 (current-value n8 (:cell compiled))))
      (is (= 110 (current-value result-net (:cell compiled))))
      (is (= 2 (count (behavior/history-records closure-content)))))))

(deftest behavior-compiler-compiled-application-reacts-to-timestamped-definition-updates
  (testing "compile the application once, then merge separately compiled closure versions"
    (let [input (behavior-view [(hist/point-record 6 10)] #{[:a 6]})
          f-id (ids/new-node-id)
          [a-id n1] (behavior-cell (nb/install-cell (behavior-net) f-id)
                                   input)
          root (live-root n1 (closure-history-env {'f f-id 'a a-id}))
          rooted (nb/run-propagators (:net root) (:props root))
          inc-info (closure-info (:env root) "(be:+ x 1)")
          double-info (closure-info (:env root) "(be:* x 2)")
          inc-closure (closure-view 0 #{0} inc-info)
          double-closure (closure-view 1 #{1} double-info)
          compiled (behavior-compiler/compile-source
                    "(f a)"
                    (:env root)
                    {:net rooted})
          empty-result (run-compiled compiled)
          [inc-tasks n4] (seed-behavior-message empty-result f-id inc-closure)
          inc-result (core/run-tasks inc-tasks n4)
          [double-tasks n5] (seed-behavior-message inc-result
                                                   f-id
                                                   double-closure)
          double-result (core/run-tasks double-tasks n5)
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
          root (live-root n1 (closure-history-env {'f f-id 'a a-id}))
          rooted (nb/run-propagators (:net root) (:props root))
          inc-info (closure-info (:env root) "(be:+ x 1)")
          double-info (closure-info (:env root) "(be:* x 2)")
          inc-closure (closure-view 0 #{0} inc-info)
          double-closure (closure-view 1 #{1} double-info)
          [_inc-tasks n2] (seed-behavior-message rooted f-id inc-closure)
          [_double-tasks n3] (seed-behavior-message n2 f-id double-closure)
          compiled (behavior-compiler/compile-source
                    "(f a)"
                    (:env root)
                    {:net n3})
          result-net (run-compiled compiled)]
      (is (= [{:at 0 :value 11}
              {:at 1 :value 40}]
             (records (content result-net (:cell compiled))))))))

(deftest behavior-compiler-same-input-sees-latest-closure-version
  (testing "the same already-declared application updates from closure v1 to v2"
    (let [input (behavior-view [(hist/point-record 6 :same-input)] #{[:a 6]})
          f-id (ids/new-node-id)
          [a-id n1] (behavior-cell (nb/install-cell (behavior-net) f-id)
                                   input)
          root (live-root n1 (env-with {'f f-id 'a a-id}))
          rooted (nb/run-propagators (:net root) (:props root))
          {n2 :net v1-closure :closure-behavior}
          (closure-behavior-fixture rooted (:env root) "(:: [x] 1)" 0)
          {n3 :net v2-closure :closure-behavior}
          (closure-behavior-fixture n2 (:env root) "(:: [x] 2)" 1)
          compiled (behavior-compiler/compile-source
                    "(f a)"
                    (:env root)
                    {:net n3})
          empty-result (run-compiled compiled)
          [v1-tasks n4] (seed-behavior-message empty-result f-id v1-closure)
          v1-result (core/run-tasks v1-tasks n4)
          [v2-tasks n5] (seed-behavior-message v1-result f-id v2-closure)
          v2-result (core/run-tasks v2-tasks n5)
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
