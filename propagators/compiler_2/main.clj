(ns propagators.compiler-2.main
  "Generic compiler core for compile-2."
  (:require [propagators.boundary :as boundary]
            [propagators.cells.diff :refer [diff-internal-output-cells]]
            [propagators.cells.value :as value]
            [propagators.closure :as closure]
            [propagators.compiler-2.ast :as ast]
            [propagators.compiler-2.env :as env]
            [propagators.compiler-2.helpers :as h]
            [propagators.compiler-2.parser :as parser]
            [propagators.datastructures.compound-object :as obj]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.network-builder :as nb]
            [propagators.propagator :as prop]
            [propagators.stdlib.prop :as stdlib-prop]))

(def compiler-result-key :compiler/result)
(def compiler-props-key :compiler/props)
(def closure-runtime-slot :closure/runtime)
(def closure-env-slot :closure/env)
(def closure-body-slot :closure/body)
(def closure-inputs-slot :closure/inputs)
(def closure-output-slot :closure/output)
(def closure-scope-slot :closure/scope)

(declare g:compile)

(defn- with-path [state path]
  (assoc state :path path))

(defn- compile-seq [state forms]
  (let [base-path (:path state)]
    (reduce
     (fn [[state _] [idx form]]
       (let [[state' result] (g:compile form
                                         (:env state)
                                         (h/child (with-path state base-path)
                                                  idx))]
         [(with-path state' base-path) result]))
     [state nil]
     (map-indexed vector forms))))

(defn- compile-symbol [{:keys [env] :as state} sym]
  (if-let [value (env/lookup env sym)]
    [state value]
    (let [[state' binding] (h/new-cell state [:symbol sym])]
      [(assoc state' :env (env/bind-local env sym binding)) binding])))

(defn- compile-args [state args]
  (let [base-path (:path state)]
    (reduce
     (fn [[state acc] [idx arg]]
       (let [[state' binding] (g:compile arg
                                         (:env state)
                                         (h/child (with-path state base-path)
                                                  [:arg idx]))]
         [(with-path state' base-path) (conj acc binding)]))
     [state []]
     (map-indexed vector args))))

(defn- copy-outer-cell
  [n outer-net id]
  (cond
    (contains? (net/net-env n) id)
    n

    (contains? (net/net-env outer-net) id)
    (nb/install-cell n
                     id
                     (net/network-cell-content outer-net id)
                     (net/network-cell-strongest outer-net id))

    :else
    (nb/ensure-cell n id)))

(defn- install-declared-slot
  [n collection-id [slot-key parent->declaration]]
  (reduce
   (fn [[acc prop-ids] parent-id]
     (let [[prop-id acc'] ((obj/p:slot slot-key parent-id collection-id) acc)]
       [acc' (conj prop-ids prop-id)]))
   [n []]
   (sort-by pr-str (keys parent->declaration))))

(defn- materialize-slot-object
  [outer-net collection-id]
  (let [declarations (obj/slot-declarations-for outer-net collection-id)
        parent-ids (->> declarations vals (mapcat keys) (sort-by pr-str) vec)
        n0 (copy-outer-cell net/empty-net outer-net collection-id)
        n1 (reduce #(copy-outer-cell %1 outer-net %2) n0 parent-ids)
        [slot-net prop-ids]
        (reduce
         (fn [[acc prop-ids] declaration]
           (let [[acc' prop-ids'] (install-declared-slot acc
                                                         collection-id
                                                         declaration)]
             [acc' (into prop-ids prop-ids')]))
         [n1 []]
         (sort-by (comp pr-str key) declarations))
        materialized (nb/run-propagators slot-net prop-ids)]
    (net/network-cell-strongest materialized collection-id)))

(defn- compiler-closure-runtime
  [closure-value]
  (if (closure/closure? closure-value)
    closure-value
    (obj/slot-value closure-value closure-runtime-slot)))

(defn- compiler-closure-env
  [closure-value]
  (obj/slot-value closure-value closure-env-slot))

(defn- compiler-closure-object
  [runtime lexical-env body inputs output scope]
  (obj/compound-object {closure-runtime-slot runtime
                        closure-env-slot lexical-env
                        closure-body-slot body
                        closure-inputs-slot (vec inputs)
                        closure-output-slot output
                        closure-scope-slot scope}))

(defn- object-slot-map [v]
  (into {}
        (map (fn [slot-key] [slot-key (obj/slot-value v slot-key)]))
        (obj/public-slot-keys v)))

(defn- inner->outer-boundary-map [network]
  (into {}
        (map (fn [[outer inner]] [inner outer]))
        (merge (get (net/net-dict-or-empty network) :avatars-in {})
               (get (net/net-dict-or-empty network) :avatars-out {}))))

(defn- externalize-closure-value [v network]
  (let [closure-env (compiler-closure-env v)]
    (if (value/unusable? closure-env)
      v
      (obj/compound-object
       (assoc (object-slot-map v)
              closure-env-slot
              (env/externalize-env closure-env
                                   (inner->outer-boundary-map network)))))))

(defn- externalize-output-value [v network]
  (if (and (not (value/unusable? v))
           (some? (compiler-closure-runtime v))
           (some? (compiler-closure-env v)))
    (externalize-closure-value v network)
    v))

(defn- ordered-closure-messages
  [closure-id ordered-input-ids output-ids network]
  (let [closure-cv (net/network-cell-strongest network closure-id)
        materialized-closure (when-not (value/unusable? closure-cv)
                               (materialize-slot-object network closure-id))
        closure-payload (compiler-closure-runtime materialized-closure)
        lexical-env (compiler-closure-env materialized-closure)
        input-values (mapv #(net/network-cell-strongest network %)
                           ordered-input-ids)]
    (if (or (value/unusable? closure-cv)
            (value/any-unusable-values? input-values)
            (nil? closure-payload)
            (value/unusable? lexical-env))
      []
      (-> network
          (closure/create-boundary-outputs output-ids)
          (closure/create-boundary-inputs ordered-input-ids)
          (#(let [inner-inputs (mapv (partial net/lookup-inner-in %)
                                     ordered-input-ids)
                  inner-outputs (mapv (partial net/lookup-inner-out %)
                                      output-ids)]
              ((closure/closure-f closure-payload)
               (net/assoc-net-dict-entry (closure/closure-net closure-payload)
                                         closure-env-slot
                                         lexical-env)
               inner-inputs
               inner-outputs
               %)))
          (#(boundary/run-internal-network ordered-input-ids %))
          (diff-internal-output-cells network output-ids)))))

(defn- apply-closure-ordered [network closure-id arg-ids out-id]
  (let [inputs (vec arg-ids)
        outputs [out-id]
        [prop-id network']
        ((prop/construct-propagator
          (fn [_inputs _outputs current-net]
            (ordered-closure-messages closure-id inputs outputs current-net))
          (into [closure-id] inputs)
          outputs)
         network)]
    [network' [prop-id] out-id]))

(defn- result-cell [state]
  (h/new-cell state :result))

(defmulti g:advance
  (fn [_binding _state] :default))

(defmethod g:advance :default
  [binding _state]
  binding)

(defmulti g:apply
  (fn [operator-binding _operand-forms _calling-env _state _out-id]
    (cond
      (fn? operator-binding) :primitive
      (env/binding-id operator-binding) :cell
      :else :unsupported)))

(defmethod g:apply :primitive
  [operator-binding operand-forms _calling-env state out-id]
  (let [[state' arg-bindings] (compile-args state operand-forms)
        arg-ids (mapv env/binding-id arg-bindings)]
    (when-not (every? some? arg-ids)
      (throw (ex-info "application arguments must compile to cells"
                      {:args arg-bindings})))
    (let [[network' prop-ids result-id]
          (operator-binding (:net state') arg-ids out-id)]
      [(-> state'
           (assoc :net network')
           (h/add-props prop-ids))
       (env/cell-binding result-id)])))

(defmethod g:apply :cell
  [operator-binding operand-forms _calling-env state out-id]
  (let [[state' arg-bindings] (compile-args state operand-forms)
        arg-ids (mapv env/binding-id arg-bindings)]
    (when-not (every? some? arg-ids)
      (throw (ex-info "application arguments must compile to cells"
                      {:args arg-bindings})))
    (let [[network' prop-ids result-id]
          (apply-closure-ordered (:net state')
                                 (env/binding-id operator-binding)
                                 arg-ids
                                 out-id)]
      [(-> state'
           (assoc :net network')
           (h/add-props prop-ids))
       (env/cell-binding result-id)])))

(defmethod g:apply :unsupported
  [operator-binding _operand-forms _calling-env _state _out-id]
  (throw (ex-info "application operator is not callable"
                  {:operator operator-binding})))

(defn- compile-application [state op args]
  (let [base-path (:path state)
        [state' op-binding] (g:compile op
                                       (:env state)
                                       (h/child (with-path state base-path)
                                                :operator))
        state' (with-path state' base-path)
        operator-binding (g:advance op-binding state')
        [state'' out-binding] (result-cell state')
        out-id (env/binding-id out-binding)]
    (g:apply operator-binding args (:env state'') state'' out-id)))

(defn- compile-let-cell [state names body]
  (let [base-path (:path state)
        child-env (env/sub-env (:env state))
        [state' scoped-env]
        (reduce
         (fn [[state scoped-env] [idx name]]
           (let [[state' binding] (h/new-cell (h/child
                                               (with-path state base-path)
                                               [:let-cell idx name])
                                              :cell)]
             [(with-path state' base-path)
              (env/bind-local scoped-env name binding)]))
         [state child-env]
         (map-indexed vector names))]
    (g:compile body
               scoped-env
               (h/child (assoc state' :env scoped-env) :body))))

(defn- body-env [lexical-env inputs output input-ids out-id]
  (let [base-env (cond-> (env/sub-env lexical-env)
                   output (env/bind-local output (env/cell-binding out-id)))]
    (reduce
     (fn [scoped-env [sym id]]
       (env/bind-local scoped-env sym (env/cell-binding id)))
     base-env
     (map vector inputs input-ids))))

(defn- compile-network [state inputs output body]
  (let [lexical-env (:env state)
        seed (:seed state)
        path (:path state)
        lexical-scope (env/scope-id lexical-env)
        runtime-closure
        (closure/closure
         (fn [closure-net input-ids output-ids network]
           (let [input-ids (vec input-ids)
                 [out-inner] output-ids
                 lexical-env (or (net/network-dict-entry closure-net closure-env-slot)
                                 lexical-env)
                 body-env (body-env lexical-env inputs output input-ids out-inner)
                 [state' result] (g:compile body
                                             body-env
                                             {:net network
                                              :env body-env
                                              :seed [seed path :closure]
                                              :path []
                                              :props []})
                 body-net (nb/run-propagators (:net state') (:props state'))
                 result-id (env/binding-id result)]
             (if (and result-id (not= result-id out-inner))
               (let [result-value (net/network-cell-strongest body-net result-id)
                     n0 (if (value/unusable? result-value)
                          body-net
                          (nb/seed-cell body-net
                                        result-id
                                        (externalize-output-value result-value network)))
                     [result-prop n'] ((stdlib-prop/id result-id out-inner)
                                       n0)]
                 (nb/run-propagators n' [result-prop]))
               body-net)))
         net/empty-net)
        closure-object (compiler-closure-object runtime-closure
                                                lexical-env
                                                body
                                                inputs
                                                output
                                                lexical-scope)
        [state-with-env lexical-env-binding] (h/new-cell state
                                                         :closure-env
                                                         lexical-env)
        lexical-env-id (env/binding-id lexical-env-binding)
        [state-with-closure closure-binding] (h/new-cell state-with-env
                                                         :closure
                                                         closure-object)
        closure-id (env/binding-id closure-binding)
        [env-slot-prop n']
        ((obj/p:slot closure-env-slot lexical-env-id closure-id)
         (:net state-with-closure))]
    [(-> state-with-closure
         (assoc :net n')
         (h/add-props [env-slot-prop]))
     (env/compound-binding closure-id)]))

(defn- expression-kind [expr]
  (let [type (:ast/type (ast/ast-map expr))]
    (case type
      :apply :application
      type)))

(defmulti g:compile
  (fn [expr _env _state]
    (expression-kind expr)))

(defmethod g:compile :literal
  [expr _env state]
  (h/new-cell state :literal (:ast/value (ast/ast-map expr))))

(defmethod g:compile :symbol
  [expr _env state]
  (compile-symbol state (:ast/name (ast/ast-map expr))))

(defmethod g:compile :sequence
  [expr _env state]
  (compile-seq state (:ast/body (ast/ast-map expr))))

(defmethod g:compile :let-cell
  [expr _env state]
  (let [m (ast/ast-map expr)]
    (compile-let-cell state (:ast/names m) (:ast/body m))))

(defmethod g:compile :network
  [expr _env state]
  (let [m (ast/ast-map expr)]
    (compile-network state (:ast/inputs m) nil (:ast/body m))))

(defmethod g:compile :compound
  [expr _env state]
  (let [m (ast/ast-map expr)]
    (compile-network state (:ast/inputs m) (:ast/output m) (:ast/body m))))

(defmethod g:compile :application
  [expr _env state]
  (let [m (ast/ast-map expr)]
    (compile-application state (:ast/operator m) (:ast/args m))))

(defn- annotated-net [network result props env]
  (-> network
      (net/assoc-net-dict-entry compiler-result-key (env/binding-id result))
      (net/assoc-net-dict-entry compiler-props-key (vec props))
      (net/assoc-net-dict-entry :compiler/env env)))

(defn compile-expr
  "Compile AST data into a network value and result cell."
  ([expr] (compile-expr expr (h/default-env)))
  ([expr env] (compile-expr expr env {}))
  ([expr env {:keys [net seed path]
              :or {net net/empty-net path []}}]
   (let [seed (or seed (ids/new-node-id))
         [state result] (g:compile expr
                                   env
                                   {:net net
                                    :env env
                                    :seed seed
                                    :path path
                                    :props []})
         network (annotated-net (:net state) result (:props state) (:env state))]
     {:net network
      :cell (env/binding-id result)
      :binding result
      :env (:env state)
      :props (:props state)})))

(defn compile-source
  "Parse and compile one compiler-2 source string."
  ([source]
   (compile-expr (parser/parse-string source)))
  ([source env]
   (compile-expr (parser/parse-string source) env))
  ([source env opts]
   (compile-expr (parser/parse-string source) env opts)))

(defn compiled-result [compiled-net]
  (net/network-dict-entry compiled-net compiler-result-key))

(defn compiled-props [compiled-net]
  (net/network-dict-entry compiled-net compiler-props-key))

(defn p:compile-expr
  "Compile source/env cells into a compiled network cell."
  [expr-id env-id out-id]
  (prop/construct-propagator
   (fn [_inputs _outputs network]
     (let [expr (net/network-cell-strongest network expr-id)
           env (net/network-cell-strongest network env-id)]
       (if (or (value/unusable? expr)
               (value/unusable? env))
         []
         (let [compiled (compile-expr expr
                                      env
                                      {:net network
                                       :seed [:compile-2 expr-id env-id]})]
           [(message out-id (:net compiled))]))))
   [expr-id env-id]
   [out-id]))
