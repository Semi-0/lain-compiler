(ns propagators.compiler-2-gur-linked-list-test
  (:require [clojure.test :refer [deftest is testing]]
            [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.datastructures.compound-object :as obj]
            [propagators.compiler-2.env :as env]
            [propagators.gur.subenv :as subenv]
            [propagators.helpers.task-queue :as tq]
            [propagators.ids :as ids]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.stdlib.prop :as prop]))

(defn- strongest [n id]
  (net/network-cell-strongest n id))

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

(defn- compiled-add-bias-closure []
  (subenv/def-recursive
   :compiler-2-gur-linked-list/add-bias
   (fn [{:keys [network args out]}]
     (let [[x-id env-id] args
           bias-id (ids/new-node-id)
           n0 (nb/install-cell network bias-id)
           [lex-prop n1] ((env/p:lexical-access 'bias env-id bias-id) n0)
           [add-prop n2] ((prop/+ x-id bias-id out) n1)]
       {:net n2
        :prop-ids [lex-prop add-prop]}))))

(defn- tiny-linked-list-compiler []
  (subenv/def-recursive
   :compiler-2-gur-linked-list/compiler
   (fn [{:keys [network args out]}]
     (let [[decl-id] args
           [tag-id name-id param-id op-id left-id right-id
            tail1-id tail2-id tail3-id tail4-id tail5-id] (repeatedly ids/new-node-id)
           n0 (reduce nb/install-cell
                      network
                      [tag-id name-id param-id op-id left-id right-id
                       tail1-id tail2-id tail3-id tail4-id tail5-id])
           [[tag-prop cdr1-prop] n1] ((obj/p:cons tag-id tail1-id decl-id) n0)
           [[name-prop cdr2-prop] n2] ((obj/p:cons name-id tail2-id tail1-id) n1)
           [[param-prop cdr3-prop] n3] ((obj/p:cons param-id tail3-id tail2-id) n2)
           [[op-prop cdr4-prop] n4] ((obj/p:cons op-id tail4-id tail3-id) n3)
           [[left-prop cdr5-prop] n5] ((obj/p:cons left-id tail5-id tail4-id) n4)
           [right-prop n6] ((obj/p:car right-id tail5-id) n5)
           n7 (-> n6
                  (nb/seed-cell out (compiled-add-bias-closure))
                  (net/assoc-net-dict-entry ::tag tag-id)
                  (net/assoc-net-dict-entry ::name name-id)
                  (net/assoc-net-dict-entry ::param param-id)
                  (net/assoc-net-dict-entry ::op op-id)
                  (net/assoc-net-dict-entry ::left left-id)
                  (net/assoc-net-dict-entry ::right right-id))]
       {:net n7
        :prop-ids [tag-prop cdr1-prop
                   name-prop cdr2-prop
                   param-prop cdr3-prop
                   op-prop cdr4-prop
                   left-prop cdr5-prop
                   right-prop]}))))

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
                                  (tiny-linked-list-compiler)
                                  (tiny-linked-list-compiler))
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
      (is (= :compound (strongest compiler-frame
                                 (net/network-dict-entry compiler-frame ::tag))))
      (is (= 'add-bias (strongest compiler-frame
                                  (net/network-dict-entry compiler-frame ::name))))
      (is (= 'x (strongest compiler-frame
                           (net/network-dict-entry compiler-frame ::param))))
      (is (= '+ (strongest compiler-frame
                           (net/network-dict-entry compiler-frame ::op))))
      (is (= 'x (strongest compiler-frame
                           (net/network-dict-entry compiler-frame ::left))))
      (is (= 'bias (strongest compiler-frame
                              (net/network-dict-entry compiler-frame ::right)))))))
