(ns propagators.compiler-2-gur-linked-list-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.compile :as compile]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.compiler-2.env :as env]
            [propagators.gur.subenv :as subenv]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]))

(defn- strongest [n id]
  (net/network-cell-strongest n id))

(defn- local-strongest [frame sym]
  (strongest frame (net/network-dict-entry frame sym)))

(defn- run-props [n prop-ids]
  (core/run-tasks (tq/enqueue-all tq/empty-queue prop-ids) n))

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
  (env/p:lexical-access 'bias env-id out-id))

(defn- experiment-installers
  [runtime]
  (subenv/source-contextual-installers
   (merge (compile/default-installers)
          {'obj/p:car obj/p:car
           'obj/p:cons obj/p:cons
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
  (testing "parallel compiler-2/GUR experiment over accessor-linked AST, without source list materialization"
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
                                  (obj/as-accessor-network {'bias 10})
                                  (obj/as-accessor-network {'bias 10}))
                 (nb/install-cell x-id 5 5)
                 (nb/install-cell out-id))
          [compile-props n1] ((subenv/p:apply-closure compiler-id
                                                    [root-id]
                                                    compiled-id)
                              n0)
          [apply-props n2] ((subenv/p:apply-closure compiled-id
                                                  [x-id env-id]
                                                  out-id)
                            n1)
          n3 (run-props n2 (into compile-props apply-props))
          compiler-frame-id (net/network-dict-entry
                             n3
                             (subenv/application-key compiler-id
                                                     [root-id]
                                                     compiled-id))
          compiler-frame (strongest n3 compiler-frame-id)]
      (is (obj/accessor-network? (strongest n3 root-id)))
      (is (obj/accessor-network? (strongest n3 env-id)))
      (is (subenv/recursive-closure? (strongest n3 compiled-id)))
      (is (= 15 (strongest n3 out-id)))
      (is (= :compound (local-strongest compiler-frame 'tag)))
      (is (= 'add-bias (local-strongest compiler-frame 'name)))
      (is (= 'x (local-strongest compiler-frame 'param)))
      (is (= '+ (local-strongest compiler-frame 'op)))
      (is (= 'x (local-strongest compiler-frame 'left)))
      (is (= 'bias (local-strongest compiler-frame 'right))))))
