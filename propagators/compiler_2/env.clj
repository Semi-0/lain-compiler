(ns propagators.compiler-2.env
  "Compound-object lexical environments for compile-2.

  A child environment is a lexical frame with a parent link. Local bindings are
  ordinary frame slots; lexical access builds scope-source candidates while
  walking parent frames.
  "
  (:require [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.compiler-2.ast :as ast]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.ids :as ids]
            [propagators.install :as i]
            [propagators.network :as net]
            [propagators.propagator :as prop])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def env-depth-key :env/depth)
(def env-scope-key :env/scope)
(def env-scope-chain-key :env/scope-chain)
(def env-parent-key :env/parent)
(def env-local-bindings-key :env/local-bindings)
(def binding-value-key :value)
(def env-internal-keys #{env-depth-key
                         env-scope-key
                         env-scope-chain-key
                         env-parent-key
                         env-local-bindings-key})

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

(defn- stable-node-id [& seed]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str (into [:compiler-2/env] seed))
               StandardCharsets/UTF_8))))

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

(defn local-bindings [env]
  (let [bindings (obj/slot-value env env-local-bindings-key)]
    (if (set? bindings) bindings #{})))

(defn set-scope [env scope chain]
  (let [m (object->map env)]
    (obj/compound-object
     (assoc m
            env-scope-key scope
            env-scope-chain-key (vec chain)
            env-depth-key (max 0 (dec (count chain)))
            env-local-bindings-key (if (set? (get m env-local-bindings-key))
                                     (get m env-local-bindings-key)
                                     #{})))))

(defn set-depth [env depth]
  (let [root (scope-id env)
        chain (if (zero? depth)
                [root]
                (vec (concat [root]
                             (repeatedly depth ids/new-node-id))))]
    (set-scope env (last chain) chain)))

(defn- child-scope-for-id [child-env-id]
  [:env/child child-env-id])

(defn extend-env
  "Create a child lexical frame with a parent link and extended chain."
  [parent-env child-scope]
  (let [child-chain (conj (scope-chain parent-env) child-scope)]
    (obj/compound-object
     {env-parent-key parent-env
      env-scope-key child-scope
      env-scope-chain-key child-chain
      env-depth-key (inc (depth parent-env))
      env-local-bindings-key #{}})))

(defn sub-env
  "Create a child lexical frame with a fresh scope."
  [parent-env]
  (extend-env parent-env (child-scope-for-id (ids/new-node-id))))

(def enter-scope sub-env)

(defn- binding-slot [binding]
  (obj/compound-object {binding-value-key binding}))

(defn- binding-slot-value [slot]
  (obj/slot-value slot binding-value-key))

(defn- current-frame-binding [env sym]
  (when (slot-present? env sym)
    (let [slot (obj/slot-value env sym)
          binding (binding-slot-value slot)]
      (when-not (value/unusable? binding)
        binding))))

(defn bind-at
  "Write one binding into the current lexical frame.

  `binding-depth` remains for the existing root setup call sites; frame storage
  is local, and lexical access owns parent traversal.
  "
  [env sym binding _binding-depth]
  (let [m (object->map env)
        bindings (if (set? (get m env-local-bindings-key))
                   (get m env-local-bindings-key)
                   #{})]
    (obj/compound-object
     (assoc m
            sym (binding-slot binding)
            env-local-bindings-key (conj bindings sym)))))

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
  (loop [frame env]
    (when (and (some? frame)
               (not (value/unusable? frame)))
      (if (contains? (local-bindings frame) sym)
        (when-let [binding (current-frame-binding frame sym)]
          {:value binding
           :scope/source (scope-id frame)
           :scope/closure (scope-id env)
           :scope/chain (scope-chain env)})
        (recur (obj/slot-value frame env-parent-key))))))

(defn lookup [env sym]
  (:value (lookup-entry env sym)))

(defn- declare-prop-id? [effect]
  (= :declare-prop
     (or (:network-vm/op effect)
         (:gur.flat/op effect)
         (:op effect))))

(defn- declared-prop-ids [effects]
  (->> effects
       (tree-seq sequential? seq)
       (filter declare-prop-id?)
       (mapv :id)))

(defn- apply-install-context
  [ctx]
  (let [{:keys [effects] :as ret} (i/result ctx)
        [_tasks n] (core/eval-activation-result ret (:net ctx))]
    [(declared-prop-ids effects) n]))

(defn- p:extend-chain
  [chain-id scope-id out-id]
  ((prop/primitive-propagator
    (fn [chain scope]
      (if (or (value/unusable? chain)
              (value/unusable? scope))
        value/nothing
        (conj (vec chain) scope))))
   chain-id
   scope-id
   out-id))

(defn- p:inc-depth
  [depth-id out-id]
  ((prop/primitive-propagator
    (fn [depth]
      (if (value/unusable? depth)
        value/nothing
        (inc depth))))
   depth-id
   out-id))

(defn- p:contains-binding?
  [sym]
  (fn [bindings-id out-id]
    ((prop/primitive-propagator
      (fn [bindings]
        (if (and (set? bindings)
                 (contains? bindings sym))
          true
          value/nothing)))
     bindings-id
     out-id)))

(defn- p:missing-binding?
  [sym]
  (fn [bindings-id out-id]
    ((prop/primitive-propagator
      (fn [bindings]
        (if (and (set? bindings)
                 (not (contains? bindings sym)))
          true
          value/nothing)))
     bindings-id
     out-id)))

(defn- share-slot
  [ctx slot-key slot-id from-env-id to-env-id]
  (-> ctx
      (i/slot slot-key slot-id from-env-id)
      (i/slot slot-key slot-id to-env-id)))

(declare lexical-route-effects)

(defn- lexical-local-effects
  [network sym active-chain-id frame-id out-id install-key]
  (let [slot-id (stable-node-id install-key :slot)
        value-id (stable-node-id install-key :value)
        source-id (stable-node-id install-key :source)]
    (-> (i/context network install-key)
        (i/slot sym slot-id frame-id)
        (i/slot binding-value-key value-id slot-id)
        (i/slot env-scope-key source-id frame-id)
        (i/install :scope-source
                   scope-source/p:scope-value
                   source-id
                   active-chain-id
                   value-id
                   out-id)
        i/effects)))

(defn- lexical-local-activation
  [sym active-chain-id frame-id out-id install-key]
  (fn [inputs _outputs network]
    (let [present? (net/network-cell-strongest network (first inputs))]
      (if (true? present?)
        {:effects (lexical-local-effects network
                                         sym
                                         active-chain-id
                                         frame-id
                                         out-id
                                         install-key)}
        []))))

(defn- lexical-parent-activation
  [sym active-chain-id parent-id out-id install-key]
  (fn [inputs _outputs network]
    (let [[missing-id parent-id*] inputs
          missing? (net/network-cell-strongest network missing-id)
          parent-env (net/network-cell-strongest network parent-id*)]
      (if (and (true? missing?)
               (not (value/unusable? parent-env)))
        {:effects (lexical-route-effects network
                                         sym
                                         active-chain-id
                                         parent-id
                                         out-id
                                         install-key)}
        []))))

(defn- lexical-route-effects
  [network sym active-chain-id frame-id out-id install-key]
  (let [bindings-id (stable-node-id install-key :local-bindings)
        present-id (stable-node-id install-key :binding-present?)
        missing-id (stable-node-id install-key :binding-missing?)
        parent-id (stable-node-id install-key :parent)]
    (-> (i/context network install-key)
        (i/slot env-local-bindings-key bindings-id frame-id)
        (i/install :contains-binding
                   (p:contains-binding? sym)
                   bindings-id
                   present-id)
        (i/install :missing-binding
                   (p:missing-binding? sym)
                   bindings-id
                   missing-id)
        (i/slot env-parent-key parent-id frame-id)
        (i/prop :local-binding
                [present-id]
                []
                (lexical-local-activation sym
                                          active-chain-id
                                          frame-id
                                          out-id
                                          [install-key :local-binding]))
        (i/prop :parent-frame
                [missing-id parent-id]
                []
                (lexical-parent-activation sym
                                           active-chain-id
                                           parent-id
                                           out-id
                                           [install-key :parent-frame]))
        i/effects)))

(defn p:lexical-access
  "Build the lexical candidate for `sym` by walking declared env frames.

  Local declaration metadata controls topology. If a frame declares `sym`,
  lexical access installs only the local read path. If metadata is known and
  the frame does not declare `sym`, lexical access recurses to the parent. If
  metadata is unknown, it emits nothing until the frame shape is known.
  "
  [sym env-id out-id]
  (let [active-chain-id (stable-node-id :lexical-access sym env-id out-id :chain)
        install-key [:compiler-2 :lexical-access sym env-id out-id]]
    (fn [network]
      (-> (i/context network install-key)
          (i/slot env-scope-chain-key active-chain-id env-id)
          (update :effects into
                  (lexical-route-effects network
                                         sym
                                         active-chain-id
                                         env-id
                                         out-id
                                         [install-key :root]))
          apply-install-context))))

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

(defn- externalize-binding-slot [slot inner->outer]
  (let [binding (binding-slot-value slot)]
    (if (value/unusable? binding)
      slot
      (binding-slot (rebind-boundary-map binding inner->outer)))))

(defn externalize-env [env inner->outer]
  (if (value/unusable? env)
    env
    (obj/compound-object
     (reduce-kv
      (fn [acc k v]
        (assoc acc
               k
               (cond
                 (= k env-parent-key)
                 (externalize-env v inner->outer)

                 (contains? env-internal-keys k)
                 v

                 :else
                 (externalize-binding-slot v inner->outer))))
      {}
      (object->map env)))))

(defn p:sub-env
  "Accessor-built parent -> child scope frame expansion."
  [parent-env-id child-env-id]
  (let [install-key [:compiler-2 :sub-env parent-env-id child-env-id]
        scope-id (stable-node-id install-key :scope)
        parent-chain-id (stable-node-id install-key :parent-chain)
        child-chain-id (stable-node-id install-key :child-chain)
        parent-depth-id (stable-node-id install-key :parent-depth)
        child-depth-id (stable-node-id install-key :child-depth)
        local-bindings-id (stable-node-id install-key :local-bindings)
        child-scope (child-scope-for-id child-env-id)]
    (fn [network]
      (-> (i/context network install-key)
          (i/slot env-parent-key parent-env-id child-env-id)
          (i/slot env-scope-key scope-id child-env-id)
          (i/slot env-local-bindings-key local-bindings-id child-env-id)
          (i/slot env-scope-chain-key parent-chain-id parent-env-id)
          (i/install :chain-extend
                     p:extend-chain
                     parent-chain-id
                     scope-id
                     child-chain-id)
          (i/slot env-scope-chain-key child-chain-id child-env-id)
          (i/slot env-depth-key parent-depth-id parent-env-id)
          (i/install :depth-inc p:inc-depth parent-depth-id child-depth-id)
          (i/slot env-depth-key child-depth-id child-env-id)
          (i/tell scope-id child-scope)
          (i/tell local-bindings-id #{})
          apply-install-context))))

(defn p:bind-local
  "Bind one fixed symbol into a fresh child env frame.

  Install this only for actual local variables. Scopes with no locals need only
  `p:sub-env`, so no local-bind propagator wakes.
  "
  [sym env-id binding-id out-env-id]
  (let [install-key [:compiler-2 :bind-local sym env-id binding-id out-env-id]
        slot-id (stable-node-id install-key :slot)
        scope-id (stable-node-id install-key :scope)
        chain-id (stable-node-id install-key :chain)
        depth-id (stable-node-id install-key :depth)
        local-bindings-id (stable-node-id install-key :local-bindings)]
    (fn [network]
      (-> (i/context network install-key)
          (i/slot env-parent-key env-id out-env-id)
          (share-slot env-scope-key scope-id env-id out-env-id)
          (share-slot env-scope-chain-key chain-id env-id out-env-id)
          (share-slot env-depth-key depth-id env-id out-env-id)
          (i/slot env-local-bindings-key local-bindings-id out-env-id)
          (i/slot sym slot-id out-env-id)
          (i/slot binding-value-key binding-id slot-id)
          (i/tell local-bindings-id #{sym})
          apply-install-context))))
