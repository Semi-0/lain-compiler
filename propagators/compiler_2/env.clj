(ns propagators.compiler-2.env
  "Compound-object lexical environments for compile-2.

  A child environment is a new compound object that receives the parent's public
  slots one-way. Local bindings are written as scope-source candidates, so
  strongest-value selection handles shadowing without syncing child writes back
  into the parent scope.
  "
  (:require [propagators.cells.value :as value]
            [propagators.compiler-2.ast :as ast]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]))

(def env-depth-key :env/depth)
(def env-scope-key :env/scope)
(def env-scope-chain-key :env/scope-chain)
(def env-internal-keys #{env-depth-key env-scope-key env-scope-chain-key})

(defn cell-binding [id] {:binding/type :cell :binding/id id})
(defn cell-binding? [x] (= :cell (:binding/type x)))

(defn compound-binding
  ([closure-id]
   {:binding/type :compound
    :binding/id closure-id})
  ([closure-id _capture-bindings]
   (compound-binding closure-id)))

(defn compound-binding? [x] (= :compound (:binding/type x)))

(defn object->map [x]
  (ast/object->map x))

(defn slot-present? [env k]
  (contains? (obj/public-slot-keys env) k))

(defn depth [env]
  (let [depth (obj/slot-value env env-depth-key)]
    (if (number? depth) depth 0)))

(defn scope-id [env]
  (or (obj/slot-value env env-scope-key)
      ::root))

(defn scope-chain [env]
  (let [chain (obj/slot-value env env-scope-chain-key)]
    (if (vector? chain)
      chain
      [::root])))

(defn set-scope [env scope chain]
  (obj/compound-object
   (assoc (object->map env)
          env-scope-key scope
          env-scope-chain-key (vec chain)
          env-depth-key (max 0 (dec (count chain))))))

(defn set-depth [env depth]
  (let [root (scope-id env)
        chain (if (zero? depth)
                [root]
                (vec (concat [root]
                             (repeatedly depth ids/new-node-id))))]
    (set-scope env (last chain) chain)))

(defn sub-env
  "Create a child env by one-way receiving parent slots.

  Parent slot contents are copied into a fresh compound object; child/local
  writes happen only in that child object at the incremented depth.
  "
  [parent-env]
  (let [child-scope [:env/child (scope-id parent-env)]
        child-chain (conj (scope-chain parent-env) child-scope)
        parent-map (object->map parent-env)
        retargeted
        (reduce-kv
         (fn [acc k v]
           (if (and (not (contains? env-internal-keys k))
                    (scope-source/scope-content? v))
             (assoc acc
                    k
                    (let [strongest (scope-source/strongest-value v)]
                      (if (scope-source/scope-value? strongest)
                        (scope-source/retarget strongest child-scope child-chain)
                        v)))
             acc))
         parent-map
         parent-map)]
    (set-scope (obj/compound-object retargeted)
               child-scope
               child-chain)))

(def enter-scope sub-env)

(defn- source-for-depth [env binding-depth]
  (let [chain (scope-chain env)]
    (or (nth chain binding-depth nil)
        (scope-id env))))

(defn bind-at
  "Write one binding at an explicit lexical source depth."
  [env sym binding binding-depth]
  (let [source (source-for-depth env binding-depth)
        candidate (scope-source/scope-value source
                                            (scope-id env)
                                            (scope-chain env)
                                            binding)
        old (when (slot-present? env sym) (obj/slot-value env sym))
        merged (if old (scope-source/merge-content old candidate) candidate)]
    (obj/compound-object (assoc (object->map env) sym merged))))

(defn bind
  ([env sym binding]
   (bind-at env sym binding (depth env)))
  ([env sym binding binding-depth]
   (bind-at env sym binding binding-depth)))

(defn bind-local [env sym binding]
  (bind-at env sym binding (depth env)))

(defn bind-locals [env sym->binding]
  (reduce-kv bind-local env sym->binding))

(defn lookup-entry [env sym]
  (when (slot-present? env sym)
    (let [slot (obj/slot-value env sym)
          strongest (if (scope-source/scope-content? slot)
                      (scope-source/strongest-value slot)
                      slot)]
      (when-not (value/unusable? strongest)
        (if (scope-source/scope-value? strongest)
          {:value (scope-source/base-value strongest)
           :scope/source (scope-source/source-scope strongest)
           :scope/closure (scope-source/closure-scope strongest)
           :scope/chain (scope-source/context-chain strongest)}
          {:value strongest
           :scope/source nil})))))

(defn lookup [env sym]
  (:value (lookup-entry env sym)))

(defn- accessor-source-slot-content
  [env-value sym]
  (let [source-slots (when (obj/accessor-network? env-value)
                       (obj/accessor-source-slots env-value))]
    (cond
      (contains? source-slots sym)
      (get source-slots sym)

      (obj/accessor-network? env-value)
      (or (obj/slot-content env-value sym) value/nothing)

      :else
      value/nothing)))

(defn lexical-slot-content
  "Return one lexical env slot's content without resolving scoped candidates.

  This deliberately returns the slot content, not its strongest view, so a
  receiving cell can keep all scope-source candidates and choose strongest via
  the network-local cell protocol.
  "
  [env-value sym]
  (cond
    (value/nothing? env-value)
    value/nothing

    (value/contradiction? env-value)
    value/contradiction

    (obj/accessor-network? env-value)
    (accessor-source-slot-content env-value sym)

    :else
    (or (obj/slot-content env-value sym) value/nothing)))

(defn p:lexical-access
  "Copy one env slot's scoped candidate content into `out-id`.

  The propagator does not resolve lexical shadowing. The output cell receives
  scope-source content and relies on `install-scope-source-protocol` for merge
  and strongest selection.
  "
  [sym env-id out-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [env-value (net/network-cell-strongest network env-id)
           slot-content (lexical-slot-content env-value sym)]
       (if (value/nothing? slot-content)
         []
         [(message out-id slot-content)])))
   [env-id]
   [out-id]))

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
    (compound-binding? x) [(:binding/id x)]
    :else []))

(defn rebind-boundary [binding inner-ids]
  (cond
    (ids/node-id? binding) (cell-binding (first inner-ids))
    (cell-binding? binding) (cell-binding (first inner-ids))
    (compound-binding? binding) (compound-binding (first inner-ids))
    :else binding))

(defn rebind-boundary-map [binding inner->outer]
  (cond
    (ids/node-id? binding) (or (get inner->outer binding) binding)
    (cell-binding? binding) (cell-binding (or (get inner->outer (:binding/id binding))
                                              (:binding/id binding)))
    (compound-binding? binding) (compound-binding (or (get inner->outer (:binding/id binding))
                                                      (:binding/id binding)))
    :else binding))

(defn- externalize-scope-content [content inner->outer]
  (cond
    (scope-source/scope-value? content)
    (scope-source/map-base content #(rebind-boundary-map % inner->outer))

    (scope-source/scope-content? content)
    (mapv #(scope-source/map-base % (fn [base]
                                      (rebind-boundary-map base inner->outer)))
          (scope-source/content-candidates content))

    :else
    (rebind-boundary-map content inner->outer)))

(defn externalize-env [env inner->outer]
  (obj/compound-object
   (reduce-kv
    (fn [acc k v]
      (assoc acc
             k
             (if (contains? env-internal-keys k)
               v
               (externalize-scope-content v inner->outer))))
    {}
    (object->map env))))

(defn p:sub-env
  "One-way parent -> child scope environment expansion."
  [parent-env-id child-env-id]
  (prop/construct-propagator
   (prop/concrete-propagator
    (fn [_inputs _outputs network]
     (let [parent-env (net/network-cell-strongest network parent-env-id)]
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
   (prop/concrete-propagator
    (fn [_inputs _outputs network]
     (let [child-env (net/network-cell-strongest network env-id)
           binding (net/network-cell-strongest network binding-id)]
       [(message out-env-id (bind-local child-env sym binding))])))
   [env-id binding-id]
   [out-env-id]))
