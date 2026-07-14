(ns propagators.compiler-2.model.env
  "Compound-object lexical environments for compile-2.

  A child environment is a lexical frame with a parent link. Local bindings are
  ordinary frame slots; lexical access builds scope-source candidates while
  walking parent frames.
  "
  (:require [propagators.cells.value :as value]
            [propagators.core :as core]
            [propagators.compiler-2.language.ast :as ast]
            [propagators.compiler-2.operators.lexical-reducer :as lexical-reducer]
            [propagators.datastructures.compound-object :as obj]
            [propagators.datastructures.reducer-cell :as reducer]
            [propagators.datastructures.scope-source :as scope-source]
            [propagators.gur.flat :as fvm]
            [propagators.ids :as ids]
            [propagators.install :as i]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.prop :as stdlib-prop])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def env-depth-key :env/depth)
(def env-scope-key :env/scope)
(def env-scope-chain-key :env/scope-chain)
(def env-parent-key :env/parent)
(def env-local-bindings-key :env/local-bindings)
(def env-bindings-key :env/bindings)
(def binding-value-key :value)
(def env-internal-keys #{env-depth-key
                         env-scope-key
                         env-scope-chain-key
                         env-parent-key
                         env-bindings-key
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
            env-bindings-key (or (get m env-bindings-key)
                                 (lexical-reducer/binding-reducer (first chain)))
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
      env-bindings-key (obj/slot-value parent-env env-bindings-key)
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
        chain (scope-chain env)
        source (peek chain)
        lineage (first chain)
        bindings (if (set? (get m env-local-bindings-key))
                   (get m env-local-bindings-key)
                   #{})
        reducer-value (or (get m env-bindings-key)
                          (lexical-reducer/binding-reducer lineage))
        reducer-value (reducer/reducer-cell
                       (reducer/reducer-id reducer-value)
                       (reducer/merge-net reducer-value)
                       (reducer/strongest-net reducer-value)
                       (assoc (reducer/reducer-slots reducer-value)
                              [sym source]
                              {:lexical/symbol sym
                               :scope/source source
                               :binding binding}))]
    (obj/compound-object
     (assoc m
            sym (binding-slot binding)
            env-bindings-key reducer-value
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

(def lexical-topology-key
  "Compiler-known stable lexical frame and binding addresses."
  ::lexical-topology)

(def lexical-topology-scope
  "Runtime name-binding scope for delayed lexical topology declarations."
  [:compiler-2 :lexical-topology])

(defn- runtime-topology
  [network]
  (get (net/network-dict-entry network fvm/name-bindings-key)
       lexical-topology-scope
       {}))

(defn- runtime-topology-value
  [network key]
  (get (runtime-topology network) key))

(defn- declare-frame-addresses
  ([network env-id source-id chain-id]
   (declare-frame-addresses network env-id source-id chain-id nil))
  ([network env-id source-id chain-id parent-id]
   (net/update-net-dict-entry
    network
    lexical-topology-key
    #(cond-> (-> (or % {})
                 (assoc-in [:frames env-id :scope/source-id] source-id)
                 (assoc-in [:frames env-id :scope/chain-id] chain-id))
       parent-id (assoc-in [:frames env-id :parent-id] parent-id)))))

(defn- declare-binding-address
  [network env-id sym binding-id]
  (net/update-net-dict-entry
   network
   lexical-topology-key
   #(-> (or % {})
        (update-in [:frames env-id :bindings sym]
                   (fnil conj #{})
                   binding-id)
        (assoc-in [:frames env-id :current-bindings sym] binding-id))))

(defn- reserve-binding-address
  [network env-id sym binding-id owner]
  (net/update-net-dict-entry
   network
   lexical-topology-key
   #(assoc-in (or % {}) [:reservations env-id sym]
              {:binding/id binding-id
               :reservation/owner owner})))

(defn- reservation-binding-id [reservation]
  (if (ids/node-id? reservation)
    reservation
    (:binding/id reservation)))

(defn reserved-binding-id
  "Return the unconsumed fixed address reserved for `sym` in this frame."
  ([network env-id sym]
   (some-> (get-in (net/network-dict-entry network lexical-topology-key)
                   [:reservations env-id sym])
           reservation-binding-id))
  ([network env-id sym owner]
   (let [reservation
         (get-in (net/network-dict-entry network lexical-topology-key)
                 [:reservations env-id sym])]
     (when (= owner (:reservation/owner reservation))
       (reservation-binding-id reservation)))))

(defn consume-reserved-binding
  "Consume the current-frame reservation for `sym` when it names `binding-id`."
  ([network env-id sym binding-id]
   (consume-reserved-binding network env-id sym binding-id nil))
  ([network env-id sym binding-id owner]
   (net/update-net-dict-entry
    network
    lexical-topology-key
    (fn [topology]
      (let [reservation (get-in topology [:reservations env-id sym])]
        (if (and (= binding-id (reservation-binding-id reservation))
                 (or (nil? owner)
                     (= owner (:reservation/owner reservation))))
          (update-in topology [:reservations env-id] dissoc sym)
          topology))))))

(defn- p:extend-chain
  [chain-id scope-id out-id]
  ((prop/primitive-propagator
    :lexical-access/extend-chain
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
    :lexical-access/inc-depth
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
      [:lexical-access/contains-binding sym]
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
      [:lexical-access/missing-binding sym]
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
        (i/prop [:lexical-access/local sym]
                [present-id]
                []
                (lexical-local-activation sym
                                          active-chain-id
                                          frame-id
                                          out-id
                                          [install-key :local-binding]))
        (i/prop [:lexical-access/parent sym]
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

(def p:structural-lexical-access
  "Compatibility name for the original frame-walking accessor."
  p:lexical-access)

(defn p:binding-declaration
  "Retain one binding declaration using lineage/source inferred from the chain."
  [sym chain-id binding-id bindings-id]
  ((prop/primitive-propagator
    [:lexical-access/binding-declaration sym]
    (fn [chain binding]
      (if (or (not (vector? chain))
              (empty? chain)
              (value/unusable? binding))
        value/nothing
        (lexical-reducer/binding-update (first chain)
                                        sym
                                        (peek chain)
                                        binding))))
   chain-id binding-id bindings-id))

(defn p:copy-bindings
  "Copy reducer content—not its strongest projection—into a child scope."
  [parent-bindings-id child-bindings-id]
  (prop/construct-propagator
   :lexical-access/copy-bindings
   (fn [_inputs _outputs network]
     (let [content (net/network-cell-content network parent-bindings-id)]
       (if (value/unusable? content)
         []
         [(message child-bindings-id content)])))
   [parent-bindings-id]
   [child-bindings-id]))

(defn- p:binding-candidates
  "Project the declarations for `sym` from the existing lexical reducer."
  [sym bindings-id candidates-id]
  ((prop/primitive-propagator
    :lexical-access/binding-candidates
    (fn [reduced]
      (if (reducer/reduced-value? reduced)
        (let [candidates (get (reducer/reduced-result reduced) sym)]
          (if (seq candidates) candidates value/nothing))
        value/nothing)))
   bindings-id candidates-id))

(defn p:reducer-lexical-access
  [lookup-key sym env-id out-id]
  (let [bindings-id (stable-node-id :reducer-lexical lookup-key env-id :bindings)
        candidates-id (stable-node-id :reducer-lexical lookup-key env-id :candidates)
        chain-id (stable-node-id :reducer-lexical lookup-key env-id :chain)
        install-key [:compiler-2 :reducer-lexical lookup-key sym env-id out-id]]
    (fn [network]
      (-> (i/context network install-key)
          (i/slot env-bindings-key bindings-id env-id)
          (i/slot env-scope-chain-key chain-id env-id)
          (i/install :binding-candidates
                     (partial p:binding-candidates sym)
                     bindings-id candidates-id)
          (i/install :adapt-candidates
                     (partial scope-source/p:adapt-candidates
                              :lexical-access
                              lookup-key)
                     candidates-id chain-id out-id)
          apply-install-context))))

(defn p:lexical-access
  "Install scoped local-first access through lexical frame topology.

  Reducer-backed selection remains available explicitly as
  `p:reducer-lexical-access`; compiler-created frames do not inherit it."
  ([sym env-id out-id]
   (p:structural-lexical-access sym env-id out-id))
  ([_lookup-key sym env-id out-id]
   (p:structural-lexical-access sym env-id out-id)))

(declare local-first-route-effects)

(defn- local-first-local-effects
  [network sym frame-id out-id install-key]
  (let [slot-id (stable-node-id install-key :slot)
        value-id (stable-node-id install-key :value)]
    (-> (i/context network install-key)
        (i/slot sym slot-id frame-id)
        (i/slot binding-value-key value-id slot-id)
        (i/install :binding-copy stdlib-prop/id value-id out-id)
        i/effects)))

(defn- local-first-local-activation
  [sym frame-id out-id install-key]
  (fn [inputs _outputs network]
    (let [present? (net/network-cell-strongest network (first inputs))]
      (if (true? present?)
        {:effects (local-first-local-effects network
                                             sym
                                             frame-id
                                             out-id
                                             install-key)}
        []))))

(defn- local-first-parent-activation
  [sym parent-id out-id install-key]
  (fn [inputs _outputs network]
    (let [[missing-id parent-id*] inputs
          missing? (net/network-cell-strongest network missing-id)
          parent-env (net/network-cell-strongest network parent-id*)]
      (if (and (true? missing?)
               (not (value/unusable? parent-env)))
        {:effects (local-first-route-effects network
                                             sym
                                             parent-id
                                             out-id
                                             install-key)}
        []))))

(defn- local-first-route-effects
  [network sym frame-id out-id install-key]
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
                (local-first-local-activation sym
                                              frame-id
                                              out-id
                                              [install-key :local-binding]))
        (i/prop :parent-frame
                [missing-id parent-id]
                []
                (local-first-parent-activation sym
                                               parent-id
                                               out-id
                                               [install-key :parent-frame]))
        i/effects)))

(defn p:lexical-access-local-first
  "Resolve `sym` to the nearest lexical binding and copy that raw binding to
  `out-id`.

  This is intentionally separate from `p:lexical-access`, which emits
  scope-source candidates for provenance-aware paths. Compiler live-env symbol
  lookup should use this operator when it needs an actual binding value for
  application dispatch.
  "
  [sym env-id out-id]
  (let [install-key [:compiler-2 :lexical-access-local-first sym env-id out-id]]
    (fn [network]
      (-> (i/context network install-key)
          (update :effects into
                  (local-first-route-effects network
                                             sym
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

(defn p:binding-value
  "Dereference a raw lexical binding descriptor into `value-answer-id`.

  Selection and dereference are deliberately separate: lexical access chooses
  a binding address, while this installer only declares the identity edge from
  that address to the ordinary value cell."
  [binding-answer-id value-answer-id]
  (let [install-key [:compiler-2 :binding-value
                     binding-answer-id value-answer-id]
        activate
        (fn [_inputs _outputs network]
          (if-let [bound-id
                   (some-> (net/network-cell-strongest network binding-answer-id)
                           binding-id)]
            {:effects
             (-> (i/context network [install-key bound-id])
                 (i/install :binding-value
                            stdlib-prop/id
                            bound-id
                            value-answer-id)
                 i/effects)}
            []))]
    (fn [network]
      (let [network* (reduce nb/ensure-cell network
                             [binding-answer-id value-answer-id])
            [prop-id installed]
            ((prop/construct-propagator
              (stable-node-id install-key :prop)
              :lexical-access/binding-value
              activate
              [binding-answer-id]
              [])
             network*)]
        [[prop-id] installed]))))

(defn- p:scoped-bound-value
  [candidate bound-id out-id]
  ((prop/primitive-propagator
    :lexical-access/read
    (fn [bound-value]
      (scope-source/scope-value
       (scope-source/source-scope candidate)
       bound-id
       (scope-source/context-chain candidate)
       bound-value
       (scope-source/dependencies candidate))))
   bound-id out-id))

(defn p:bound-value-layer
  "Connect a lexical answer's base layer to its addressed binding cell."
  [value-answer-id bound-id]
  (obj/p:slot scope-source/base-layer bound-id value-answer-id))

(defn p:scope-dependent-read
  "Read one known binding cell as a lexical value with provenance."
  [lookup-key source-id chain-id bound-id value-answer-id]
  ((prop/primitive-propagator
    [:lexical-access/scoped-read lookup-key]
    (fn [source chain bound-value]
      (if (or (value/unusable? source)
              (value/unusable? chain))
        value/nothing
        (scope-source/scope-value
         source
         bound-id
         chain
         bound-value
         #{(scope-source/lexical-token lookup-key source chain)}))))
   source-id chain-id bound-id value-answer-id))

(defn p:access-binding
  "Dereference the selected binding while retaining its lexical envelope.

  Each binding address declares its own stable copy path, so a later nearer
  binding adds topology without removing the earlier path."
  [binding-answer-id value-answer-id]
  (let [install-key [:compiler-2 :access-binding
                     binding-answer-id value-answer-id]
        activate
        (fn [_inputs _outputs network]
          (let [candidate (net/network-cell-strongest network binding-answer-id)
                bound-id (when (scope-source/scope-value? candidate)
                           (binding-id (scope-source/base-value candidate)))]
            (cond
              bound-id
              {:effects
               (-> (i/context network
                              [install-key
                               (scope-source/source-scope candidate)
                               bound-id])
                   (i/install :read-bound-value
                              (partial p:scoped-bound-value candidate)
                              bound-id
                              value-answer-id)
                   i/effects)}

              (scope-source/scope-value? candidate)
              {:effects
               (-> (i/context network [install-key :immediate])
                   (i/install :copy-immediate
                              stdlib-prop/id
                              binding-answer-id
                              value-answer-id)
                   i/effects)}

              :else
              [])))]
    (fn [network]
      (let [network* (reduce (fn [n id]
                               (if (contains? (net/net-env n) id)
                                 n
                                 (net/seed-net-cell n id)))
                             network
                             [binding-answer-id value-answer-id])
            [prop-id n] ((prop/construct-propagator
                          :lexical-access/access-binding
                          activate
                          [binding-answer-id]
                          [])
                         network*)]
        [[prop-id] n]))))

(defn- local-binding-ids
  [network env-id sym]
  (let [bindings (get-in (net/network-dict-entry network lexical-topology-key)
                         [:frames env-id :bindings sym])
        runtime-ids
        (->> (runtime-topology network)
             (keep (fn [[key id]]
                     (when (and (= :binding (first key))
                                (= env-id (second key))
                                (= sym (nth key 2 nil)))
                       id)))
             set)
        bound-ids (into (set bindings) runtime-ids)]
    bound-ids))

(defn local-binding-id
  "Return one unambiguous fixed binding address in exactly `env-id`."
  [network env-id sym]
  (or (get-in (net/network-dict-entry network lexical-topology-key)
              [:frames env-id :current-bindings sym])
      (runtime-topology-value network [:current-binding env-id sym])
      (let [bound-ids (local-binding-ids network env-id sym)]
        (when (= 1 (count bound-ids))
          (first bound-ids)))))

(defn lexical-binding-status
  "Describe fixed-topology resolution as `:found`, `:missing`, or `:ambiguous`.

  `:unknown` means the environment itself is not compiler-owned topology and
  should retain structural accessor lookup."
  [network sym env-id]
  (let [frames (:frames (net/network-dict-entry network lexical-topology-key))]
    (loop [frame-id env-id
           seen #{}]
      (when-not (contains? seen frame-id)
        (let [{:keys [parent-id] :as frame}
              (get frames frame-id)
              runtime-frame? (or (runtime-topology-value
                                  network [:frame frame-id :scope/source-id])
                                 (runtime-topology-value
                                  network [:frame frame-id :scope/chain-id]))
              known-frame? (or frame runtime-frame?)
              parent-id (or parent-id
                            (runtime-topology-value
                             network [:frame frame-id :parent-id]))
              bound-id (local-binding-id network frame-id sym)
              bound-ids (local-binding-ids network frame-id sym)]
          (cond
            bound-id
            {:status :found :binding/id bound-id}

            (seq bound-ids)
            {:status :ambiguous}

            parent-id
            (recur parent-id (conj seen frame-id))

            known-frame?
            {:status :missing}

            :else
            {:status :unknown}))))))

(defn lexical-binding-id
  "Return the nearest unambiguous fixed binding address for `sym`."
  [network sym env-id]
  (let [{:keys [status binding/id]}
        (lexical-binding-status network sym env-id)]
    (when (= :found status) id)))

(defn binding-names
  "Return the declared lexical name for every canonical binding address."
  [network]
  (let [frames (:frames (net/network-dict-entry network lexical-topology-key))]
    (into {}
          (mapcat (fn [[_env-id {:keys [bindings]}]]
                    (for [[sym ids] bindings
                          id ids]
                      [id sym])))
          frames)))

(defn- fixed-lexical-binding-address
  [network sym env-id]
  (let [frames (:frames (net/network-dict-entry network lexical-topology-key))
        active-chain-id (or (get-in frames [env-id :scope/chain-id])
                            (runtime-topology-value
                             network [:frame env-id :scope/chain-id]))]
    (loop [frame-id env-id
           seen #{}]
      (when-not (contains? seen frame-id)
        (let [source-id (or (get-in frames [frame-id :scope/source-id])
                            (runtime-topology-value
                             network [:frame frame-id :scope/source-id]))
              parent-id (or (get-in frames [frame-id :parent-id])
                            (runtime-topology-value
                             network [:frame frame-id :parent-id]))
              bound-id (local-binding-id network frame-id sym)
              bound-ids (local-binding-ids network frame-id sym)]
          (cond
            bound-id
            (when (every? ids/node-id?
                          [bound-id source-id active-chain-id])
              {:binding/id bound-id
               :scope/source-id source-id
               :scope/chain-id active-chain-id})

            (seq bound-ids)
            nil

            parent-id
            (recur parent-id (conj seen frame-id))

            :else
            nil))))))

(defn lexical-topology-effects
  "Lower compile-time lexical addresses into delayed runtime declarations."
  [network]
  (let [{:keys [frames]}
        (net/network-dict-entry network lexical-topology-key)]
    (vec
     (mapcat
      (fn [[env-id {:keys [scope/source-id scope/chain-id parent-id bindings
                           current-bindings]}]]
        (concat
         (keep identity
               [(when source-id
                  (fvm/bind-name lexical-topology-scope
                                 [:frame env-id :scope/source-id]
                                 source-id))
                (when chain-id
                  (fvm/bind-name lexical-topology-scope
                                 [:frame env-id :scope/chain-id]
                                 chain-id))
                (when parent-id
                  (fvm/bind-name lexical-topology-scope
                                 [:frame env-id :parent-id]
                                 parent-id))])
         (for [[sym ids] bindings
               id ids]
           (fvm/bind-name lexical-topology-scope
                          [:binding env-id sym id]
                          id))
         (for [[sym id] current-bindings]
           (fvm/bind-name lexical-topology-scope
                          [:current-binding env-id sym]
                          id))))
      frames))))

(defn p:direct-lexical-value
  "Read a known canonical binding cell without constructing reducer access."
  [lookup-key source-id chain-id bound-id value-answer-id]
  (fn [network]
    (let [[prop-id installed]
          ((p:scope-dependent-read lookup-key
                                   source-id chain-id bound-id value-answer-id)
           network)]
      [[prop-id] installed])))

(defn p:reducer-lexical-value
  "Resolve a binding through the lexical reducer, then read its live value."
  [lookup-key sym env-id value-answer-id]
  (let [binding-answer-id
        (stable-node-id :reducer-lexical-value
                        lookup-key sym env-id value-answer-id :binding)]
    (fn [network]
      (let [network (-> network
                        (nb/ensure-cell binding-answer-id)
                        (nb/ensure-cell value-answer-id))
            [access-props with-access]
            ((p:lexical-access lookup-key sym env-id binding-answer-id) network)
            [read-props with-read]
            ((p:access-binding binding-answer-id value-answer-id) with-access)]
        [(into (vec access-props) read-props) with-read]))))

(defn p:structural-lexical-value
  "Walk local-first frame topology, then dereference the selected binding.

  This retains the scope candidate emitted by `p:structural-lexical-access`;
  no reducer projection or environment materialization is involved."
  [lookup-key sym env-id value-answer-id]
  (let [binding-answer-id
        (stable-node-id :structural-lexical-value
                        lookup-key sym env-id value-answer-id :binding)]
    (fn [network]
      (let [network (-> network
                        (nb/ensure-cell binding-answer-id)
                        (nb/ensure-cell value-answer-id))
            [access-props with-access]
            ((p:structural-lexical-access sym env-id binding-answer-id) network)
            [read-props with-read]
            ((p:access-binding binding-answer-id value-answer-id) with-access)]
        [(into (vec access-props) read-props) with-read]))))

(defn p:legacy-lexical-value
  "Adapt one already-materialized legacy frame lookup to scoped-read topology."
  [lookup-key sym legacy-env value-answer-id]
  (fn [network]
    (if-let [{:keys [value scope/source scope/chain]}
             (lookup-entry legacy-env sym)]
      (let [source-id (stable-node-id :legacy-lexical lookup-key :source)
            chain-id (stable-node-id :legacy-lexical lookup-key :chain)
            bound-id (or (binding-id value)
                         (stable-node-id :legacy-lexical lookup-key :value))
            network (cond-> (-> network
                                (nb/ensure-cell source-id)
                                (nb/seed-cell source-id source)
                                (nb/ensure-cell chain-id)
                                (nb/seed-cell chain-id chain)
                                (nb/ensure-cell value-answer-id))
                      (nil? (binding-id value))
                      (nb/seed-cell bound-id value))]
        ((p:direct-lexical-value lookup-key
                                 source-id chain-id bound-id value-answer-id)
         network))
      [[] network])))

(defn p:local-first-lexical-value
  "Compiler lexical read: fixed address, then the cheapest compatible fallback.

  Legacy materialized frames retain reducer lookup. Live accessor frames use
  the structural local-first walk and do not require accumulated inheritance."
  [lookup-key sym env-id value-answer-id]
  (fn [network]
    (if-let [{:keys [binding/id scope/source-id scope/chain-id]}
             (fixed-lexical-binding-address network sym env-id)]
      ((p:direct-lexical-value lookup-key
                               source-id chain-id id value-answer-id)
       network)
      (let [frame (net/network-cell-strongest network env-id)]
        (if (and (net/network? frame)
                 (not (obj/accessor-network? frame)))
          ((p:legacy-lexical-value lookup-key sym frame value-answer-id)
           network)
          ((p:structural-lexical-value lookup-key sym env-id value-answer-id)
           network))))))

(defn p:lexical-value
  "Read `sym` as one scope-dependent live value.

  A declared canonical binding is selected local-first through fixed frame
  addresses and read directly. Its declaring scope and the active child chain
  remain in the result provenance. Unknown or ambiguous topology falls back to
  reducer selection without changing the result shape."
  ([sym env-id value-answer-id]
   (p:lexical-value [:lexical sym env-id value-answer-id]
                    sym env-id value-answer-id))
  ([lookup-key sym env-id value-answer-id]
   (fn [network]
     (if-let [{:keys [binding/id scope/source-id scope/chain-id]}
              (fixed-lexical-binding-address network sym env-id)]
       ((p:direct-lexical-value lookup-key
                                source-id chain-id id value-answer-id)
        network)
       ((p:reducer-lexical-value lookup-key sym env-id value-answer-id)
        network)))))

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

(defn p:scope-frame
  "Declare parent, scope, chain and depth for one child lexical frame.

  This intentionally does not copy the parent's accumulated binding reducer.
  Compiler frames can use fixed-address local-first access without paying that
  eager inheritance cost. `p:sub-env` retains the old eager composition."
  ([parent-env-id child-env-id]
   (p:scope-frame parent-env-id child-env-id #{}))
  ([parent-env-id child-env-id local-names]
   (let [install-key [:compiler-2 :sub-env parent-env-id child-env-id]
        scope-id (stable-node-id install-key :scope)
        parent-chain-id (stable-node-id install-key :parent-chain)
        child-chain-id (stable-node-id install-key :child-chain)
        parent-depth-id (stable-node-id install-key :parent-depth)
        child-depth-id (stable-node-id install-key :child-depth)
        local-bindings-id (stable-node-id install-key :local-bindings)
        child-scope (child-scope-for-id child-env-id)]
     (fn [network]
       (let [[props installed]
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
                 (i/tell local-bindings-id (set local-names))
                 apply-install-context)]
         [props (declare-frame-addresses installed
                                         child-env-id
                                         scope-id
                                         child-chain-id
                                         parent-env-id)])))))

(defn p:inherit-bindings
  "Copy the parent's accumulated lexical reducer into an existing child frame."
  [parent-env-id child-env-id]
  (let [install-key [:compiler-2 :sub-env parent-env-id child-env-id]
        parent-bindings-id (stable-node-id install-key :parent-bindings)
        child-bindings-id (stable-node-id install-key :child-bindings)]
    (fn [network]
      (-> (i/context network install-key)
          (i/slot env-bindings-key parent-bindings-id parent-env-id)
          (i/slot env-bindings-key child-bindings-id child-env-id)
          (i/install :copy-bindings
                     p:copy-bindings
                     parent-bindings-id
                     child-bindings-id)
          apply-install-context))))

(defn p:sub-env
  "Accessor-built parent -> child scope frame with eager reducer inheritance."
  [parent-env-id child-env-id]
  (fn [network]
    (if (= parent-env-id child-env-id)
      [[] network]
      (let [[frame-props framed] ((p:scope-frame parent-env-id child-env-id)
                                  network)
            [binding-props inherited]
            ((p:inherit-bindings parent-env-id child-env-id) framed)]
        [(into (vec frame-props) binding-props) inherited]))))

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
        local-bindings-id (stable-node-id install-key :local-bindings)
        bindings-id (stable-node-id install-key :bindings)
        binding-descriptor-id (stable-node-id install-key :binding-descriptor)]
    (fn [network]
      (let [[props installed]
            (-> (i/context network install-key)
                (i/slot env-parent-key env-id out-env-id)
                (share-slot env-scope-key scope-id env-id out-env-id)
                (share-slot env-scope-chain-key chain-id env-id out-env-id)
                (share-slot env-depth-key depth-id env-id out-env-id)
                (share-slot env-bindings-key
                            bindings-id
                            env-id
                            out-env-id)
                (i/tell binding-descriptor-id (cell-binding binding-id))
                (i/install :binding-declaration
                           (partial p:binding-declaration sym)
                           chain-id binding-descriptor-id bindings-id)
                (i/slot env-local-bindings-key local-bindings-id out-env-id)
                (i/slot sym slot-id out-env-id)
                (i/slot binding-value-key binding-id slot-id)
                (i/tell local-bindings-id #{sym})
                apply-install-context)]
        [props (-> installed
                   (declare-frame-addresses out-env-id
                                            scope-id
                                            chain-id
                                            env-id)
                   (declare-binding-address out-env-id sym binding-id))]))))

(defn p:declare-local
  "Declare `sym` in the existing lexical scope represented by `env-id`.

  Unlike `p:bind-local`, this refines the scope's reducer-backed binding
  collection in place. Entering a scope is `p:sub-env`; adding names is
  `p:declare-local`.
  "
  [sym env-id binding-id]
  (let [install-key [:compiler-2 :declare-local sym env-id binding-id]
        chain-id (stable-node-id install-key :chain)
        bindings-id (stable-node-id install-key :bindings)
        binding-descriptor-id (stable-node-id install-key :binding-descriptor)]
    (fn [network]
      (let [[props installed]
            (-> (i/context network install-key)
                (i/slot env-scope-chain-key chain-id env-id)
                (i/slot env-bindings-key bindings-id env-id)
                (i/tell binding-descriptor-id (cell-binding binding-id))
                (i/install :binding-declaration
                           (partial p:binding-declaration sym)
                           chain-id binding-descriptor-id bindings-id)
                apply-install-context)]
        [props installed]))))

(defn p:declare-fixed-local
  "Declare a lexical binding whose address cannot be replaced in this frame."
  [sym env-id binding-id]
  (fn [network]
    (let [[declaration-props declared]
          ((p:declare-local sym env-id binding-id) network)
          slot-id (stable-node-id :fixed-local env-id sym binding-id :slot)
          binding-descriptor-id
          (stable-node-id :fixed-local env-id sym binding-id :descriptor)
          [slot-props installed]
          (-> (i/context declared [:compiler-2 :fixed-local env-id sym binding-id])
              (i/slot sym slot-id env-id)
              (i/tell binding-descriptor-id (cell-binding binding-id))
              (i/slot binding-value-key binding-descriptor-id slot-id)
              apply-install-context)]
      [(into (vec declaration-props) slot-props)
       (declare-binding-address installed env-id sym binding-id)])))

(defn p:declare-canonical-local
  "Record one compiler-owned fixed address without materializing a slot path."
  [sym env-id binding-id]
  (fn [network]
    [[] (-> network
            (nb/ensure-cell binding-id)
            (declare-binding-address env-id sym binding-id))]))

(defn p:reserve-fixed-local
  "Declare a fixed local and mark its address for the next same-frame `def`."
  ([sym env-id binding-id]
   (p:reserve-fixed-local sym env-id binding-id nil))
  ([sym env-id binding-id owner]
   (fn [network]
     (let [[props declared] ((p:declare-fixed-local sym env-id binding-id)
                             network)]
       [props (reserve-binding-address declared env-id sym binding-id owner)]))))

(defn p:reserve-canonical-local
  "Record and reserve a compiler-owned fixed address without slot topology."
  ([sym env-id binding-id]
   (p:reserve-canonical-local sym env-id binding-id nil))
  ([sym env-id binding-id owner]
   (fn [network]
     (let [[props declared] ((p:declare-canonical-local sym env-id binding-id)
                             network)]
       [props (reserve-binding-address declared env-id sym binding-id owner)]))))

(defn- imported-binding-id
  [env-id sym source]
  (stable-node-id :imported-binding env-id sym source))

(defn- declare-imported-addresses
  [network env-id environment bindings]
  (let [source-id (stable-node-id :imported-frame env-id :scope)
        chain-id (stable-node-id :imported-frame env-id :chain)
        slots (if (reducer/reducer-cell? bindings)
                (reducer/reducer-slots bindings)
                bindings)
        network (-> network
                    (nb/ensure-cell source-id)
                    (nb/seed-cell source-id (scope-id environment))
                    (nb/ensure-cell chain-id)
                    (nb/seed-cell chain-id (scope-chain environment))
                    (declare-frame-addresses env-id source-id chain-id))]
    (if-not (map? slots)
      network
      (reduce-kv
       (fn [n [sym _source] declaration]
         (if-let [id (binding-id (:binding declaration))]
           (declare-binding-address n env-id sym id)
           n))
       network
       slots))))

(defn import-environment
  "Install a legacy compound environment as one live environment cell.

  Direct reducer bindings are moved into stable value cells; the environment
  reducer retains only binding descriptors. Already-addressed bindings are
  preserved. Returns `[network env-id]`.
  "
  [network env-id environment]
  (let [bindings (obj/slot-value environment env-bindings-key)
        [network bindings']
        (if-not (reducer/reducer-cell? bindings)
          [network bindings]
          (reduce-kv
           (fn [[n slots] [sym source :as slot-key] declaration]
             (let [binding (:binding declaration)]
               (if (binding-id binding)
                 [n (assoc slots slot-key declaration)]
                 (let [value-id (imported-binding-id env-id sym source)]
                   [(nb/seed-cell (nb/ensure-cell n value-id) value-id binding)
                    (assoc slots slot-key
                           (assoc declaration :binding (cell-binding value-id)))]))))
           [network {}]
           (reducer/reducer-slots bindings)))
        descriptors
        (if (reducer/reducer-cell? bindings')
          {}
          (reduce-kv
           (fn [m [_sym _source] {:keys [lexical/symbol binding]}]
             (assoc m symbol binding))
           {}
           bindings'))
        environment'
        (if (reducer/reducer-cell? bindings)
          (obj/compound-object
           (reduce-kv
            (fn [m sym binding]
              (if (contains? m sym)
                (assoc m sym (binding-slot binding))
                m))
            (assoc (object->map environment)
                   env-bindings-key
                   (reducer/reducer-cell (reducer/reducer-id bindings)
                                         (reducer/merge-net bindings)
                                         (reducer/strongest-net bindings)
                                         bindings'))
            descriptors))
          environment)]
    [(-> network
         (nb/ensure-cell env-id)
         (nb/seed-cell env-id environment')
         (declare-imported-addresses env-id environment' bindings'))
     env-id]))

(defn- declared-binding-id
  [env-id sym binding]
  (or (binding-id binding)
      (stable-node-id :declared-binding env-id sym)))

(defn declare-bindings
  "Declare `bindings` in one live environment without evaluating them.

  `environment` may be a live environment id or a legacy compound value.
  Returns declaration topology as `{:net :env :props}`; callers decide when
  to run the returned propagators."
  [network environment env-id bindings]
  (let [[network env-id] (if (ids/node-id? environment)
                           [(nb/ensure-cell network environment) environment]
                           (import-environment network env-id environment))]
    (reduce
     (fn [{:keys [net props] :as declared} [sym binding]]
       (let [id (declared-binding-id env-id sym binding)
             net (if (binding-id binding)
                   (nb/ensure-cell net id)
                   (nb/seed-cell (nb/ensure-cell net id) id binding))
             [new-props net] ((p:declare-local sym env-id id) net)]
         (assoc declared :net net :props (into props new-props))))
     {:net network :env env-id :props []}
     bindings)))

(defn resolve-binding
  "Resolve `sym` through the live lexical reducer on a local network copy."
  [network environment sym]
  (if-not (ids/node-id? environment)
    (lookup environment sym)
    (let [answer-id (stable-node-id :resolve-binding environment sym)
          [props installed] ((p:lexical-access [:resolve-binding environment sym]
                                               sym environment answer-id)
                             (nb/ensure-cell network answer-id))
          settled (nb/run-propagators installed props)
          answer (net/network-cell-strongest settled answer-id)]
      (when (scope-source/scope-value? answer)
        (scope-source/base-value answer)))))

(defn resolve-binding-id
  [network environment sym]
  (if (ids/node-id? environment)
    (let [declared (fixed-lexical-binding-address network sym environment)]
      (if-let [id (:binding/id declared)]
        id
        (some-> (resolve-binding network environment sym) binding-id)))
    (some-> (resolve-binding network environment sym) binding-id)))
