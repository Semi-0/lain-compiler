(ns propagators.compiler-2.env
  "Compound-object lexical environments for compile-2.

  A child environment is a new compound object that receives the parent's public
  slots one-way. Local bindings are written at the child scope intensity, so
  strongest-value selection handles shadowing without syncing child writes back
  into the parent scope.
  "
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.ast :as ast]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.intensity :as intensity]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def env-depth-key :env/depth)

(defn cell-binding [id] {:binding/type :cell :binding/id id})
(defn cell-binding? [x] (= :cell (:binding/type x)))

(defn compound-binding [closure-id capture-bindings]
  {:binding/type :compound
   :binding/id closure-id
   :binding/captures (vec capture-bindings)})

(defn compound-binding? [x] (= :compound (:binding/type x)))

(defn object->map [x]
  (ast/object->map x))

(defn slot-present? [env k]
  (contains? (obj/public-slot-keys env) k))

(defn depth [env]
  (let [depth (obj/slot-value env env-depth-key)]
    (if (number? depth) depth 0)))

(defn set-depth [env depth]
  (obj/compound-object (assoc (object->map env) env-depth-key depth)))

(defn sub-env
  "Create a child env by one-way receiving parent slots.

  Parent slot contents are copied into a fresh compound object; child/local
  writes happen only in that child object at the incremented depth.
  "
  [parent-env]
  (-> parent-env
      object->map
      obj/compound-object
      (set-depth (inc (depth parent-env)))))

(def enter-scope sub-env)

(defn bind-at
  "Write one binding at an explicit intensity."
  [env sym binding binding-depth]
  (let [candidate (intensity/intensity-value binding-depth binding)
        old (when (slot-present? env sym) (obj/slot-value env sym))
        merged (if old (intensity/merge-content old candidate) candidate)]
    (obj/compound-object (assoc (object->map env) sym merged))))

(defn bind
  ([env sym binding]
   (bind-at env sym binding (depth env)))
  ([env sym binding binding-depth]
   (bind-at env sym binding binding-depth)))

(defn bind-local [env sym binding]
  (obj/compound-object
   (assoc (object->map env)
          sym
          (intensity/intensity-value (depth env) binding))))

(defn bind-locals [env sym->binding]
  (reduce-kv bind-local env sym->binding))

(defn lookup-entry [env sym]
  (when (slot-present? env sym)
    (let [slot (obj/slot-value env sym)
          strongest (if (intensity/intensity-content? slot)
                      (intensity/strongest-value slot)
                      slot)]
      (when-not (value/unusable? strongest)
        (if (intensity/intensity-value? strongest)
          {:value (intensity/base-value strongest)
           :intensity (intensity/intensity strongest)}
          {:value strongest
           :intensity nil})))))

(defn lookup [env sym]
  (:value (lookup-entry env sym)))

(defn binding-id [x]
  (cond
    (ids/node-id? x) x
    (cell-binding? x) (:binding/id x)
    (compound-binding? x) (:binding/id x)
    :else nil))

(defn boundary-ids [x]
  (cond
    (ids/node-id? x) [x]
    (cell-binding? x) [(:binding/id x)]
    (compound-binding? x) (into [(:binding/id x)]
                                (mapcat boundary-ids (:binding/captures x)))
    :else []))

(defn rebind-boundary [binding inner-ids]
  (cond
    (ids/node-id? binding) (cell-binding (first inner-ids))
    (cell-binding? binding) (cell-binding (first inner-ids))
    (compound-binding? binding)
    (compound-binding (first inner-ids)
                      (second
                       (reduce
                        (fn [[remaining acc] captured]
                          (let [n (count (boundary-ids captured))]
                            [(subvec remaining n)
                             (conj acc
                                   (rebind-boundary captured
                                                    (subvec remaining 0 n)))]))
                        [(vec (rest inner-ids)) []]
                        (:binding/captures binding))))
    :else binding))

(defn captures
  "Cellful parent bindings that must be passed as closure hidden inputs."
  [env local-symbols]
  (->> (obj/public-slot-keys env)
       (remove #{env-depth-key})
       (remove (set local-symbols))
       (keep (fn [sym]
               (when-let [{:keys [value intensity]} (lookup-entry env sym)]
                 (let [ids (boundary-ids value)]
                   (when (seq ids)
                     {:symbol sym
                      :value value
                      :intensity (or intensity 0)
                      :ids ids})))))
       vec))

(defn rebind-captures
  "Bind hidden input avatars into a child env at the child scope intensity."
  [child-env captures inner-ids]
  (first
   (reduce
    (fn [[env remaining] {:keys [symbol value ids]}]
      (let [n (count ids)
            current (subvec remaining 0 n)]
        [(bind-local env symbol (rebind-boundary value current))
         (subvec remaining n)]))
    [child-env (vec inner-ids)]
    captures)))

(defn p:sub-env
  "One-way parent -> child scope environment expansion."
  [parent-env-id child-env-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [parent-env (net/network-cell-strongest network parent-env-id)]
       (if (value/unusable? parent-env)
         []
         [(message child-env-id (sub-env parent-env))])))
   [parent-env-id]
   [child-env-id]))

(defn p:bind-local
  "Bind one fixed symbol into a fresh child env value.

  Install this only for actual local variables. Scopes with no locals need only
  `p:sub-env`, so no local-bind propagator wakes.
  "
  [sym env-id binding-id out-env-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [child-env (net/network-cell-strongest network env-id)
           binding (net/network-cell-strongest network binding-id)]
       (if (or (value/unusable? child-env)
               (value/unusable? binding))
         []
         [(message out-env-id (bind-local child-env sym binding))])))
   [env-id binding-id]
   [out-env-id]))
