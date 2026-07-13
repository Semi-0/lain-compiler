(ns propagators.compiler-2-gur-linked-list-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.cell-protocol :as protocol]
            [propagators.cells.value :as value]
            [propagators.compile :as compile]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.compiler-2.model.env :as env]
            [propagators.gur :as gur]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]))

(defn- strongest [n id]
  (net/network-cell-strongest n id))

(defn- run-props [n prop-ids]
  (core/run-tasks (tq/enqueue-all tq/empty-queue prop-ids) n))

(defn- scope-source-protocol-net
  []
  (-> net/empty-net
      (compile/install-and-run (protocol/install-cell-protocol))
      (compile/install-and-run (protocol/install-scope-source-protocol))))

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
        n0 (reduce nb/install-cell
                   (scope-source-protocol-net)
                   (conj (into heads colls) empty-id))
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
        [n3 tasks] (nb/seed-cell! n2 tasks empty-id (obj/as-accessor-network {}))]
    {:net (core/run-tasks tasks n3)
     :root-id (first colls)}))

(defn- bias-access
  [env-id out-id]
  (env/p:lexical-access 'bias env-id out-id))

(defn- scoped-plus
  [a-id b-id out-id]
  ((prop/primitive-propagator
    (fn [a b]
      (let [values [(scope-source/unwrap a) (scope-source/unwrap b)]]
        (cond
          (some value/contradiction? values) value/contradiction
          (some value/nothing? values) value/nothing
          :else (try
                  (apply + values)
                  (catch Exception _
                    value/contradiction))))))
   a-id
   b-id
   out-id))

(defn- lexical-env
  [sym value]
  (-> (obj/empty-compound-object)
      (env/set-depth 0)
      (env/bind-local sym value)))

(defn- experiment-installers
  [runtime]
  (gur/contextual-installers
   (merge (compile/default-installers)
          {'obj/p:car obj/p:car
           'obj/p:cons obj/p:cons
           'prop/+ scoped-plus
           'p:bias-access bias-access})
   runtime))

(compile/def-recursive add-bias
  [x lexical-env out]
  {:name :compiler-2-gur-linked-list/add-bias
   :installers experiment-installers}
  (let-cell [bias]
    (p:bias-access lexical-env bias)
    (:+ x bias)))

(compile/def-recursive linked-list-compiler
  [decl out]
  {:name :compiler-2-gur-linked-list/compiler
   :installers experiment-installers
   :seed-values {compiled-add-bias add-bias}}
  (let-cell [tag name param op left right tail1 tail2 tail3 tail4 tail5]
    (obj/p:cons tag tail1 decl)
    (obj/p:cons name tail2 tail1)
    (obj/p:cons param tail3 tail2)
    (obj/p:cons op tail4 tail3)
    (obj/p:cons left tail5 tail4)
    (obj/p:car right tail5)
    compiled-add-bias))

(deftest linked-list-gur-compiler-declares-applies-and-accesses-lexically
  (testing "compiler-2/GUR prototype over accessor-linked AST, without source list materialization"
    (let [{:keys [net root-id]}
          (linked-list [:compound 'add-bias 'x '+ 'x 'bias])
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
                                  (lexical-env 'bias 10)
                                  (lexical-env 'bias 10))
                 (nb/install-cell x-id 5 5)
                 (nb/install-cell out-id))
          [compile-props n1] ((gur/p:apply-closure compiler-id
                                                   [root-id]
                                                   compiled-id)
                              n0)
          [apply-props n2] ((gur/p:apply-closure compiled-id
                                                 [x-id env-id]
                                                 out-id)
                            n1)
          n3 (run-props n2 (into compile-props apply-props))]
      (is (obj/accessor-network? (strongest n3 root-id)))
      (is (obj/accessor-network? (strongest n3 env-id)))
      (is (gur/recursive-closure? (strongest n3 compiled-id)))
      (is (= 15 (strongest n3 out-id))))))
