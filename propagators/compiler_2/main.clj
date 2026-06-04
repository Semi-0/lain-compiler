(ns propagators.compiler-2.main
  "Meander-based compiler core for compile-2."
  (:require [clojure.set :as set]
            [meander.epsilon :as m]
            [propagators.boundary :as boundary]
            [propagators.cells.diff :refer [diff-internal-output-cells]]
            [propagators.cells.value :as value]
            [propagators.closure :as closure]
            [propagators.compiler-2.ast :as ast]
            [propagators.compiler-2.env :as env]
            [propagators.compiler-2.helpers :as h]
            [propagators.ids :as ids]
            [propagators.message :refer [message]]
            [propagators.network :as net]
            [propagators.propagator :as prop]
            [propagators.stdlib.prop :as stdlib-prop]))

(def compiler-result-key :compiler/result)
(def compiler-props-key :compiler/props)

(declare compile-node)

(declare free-symbols)

(defn- free-symbol-set
  [& xs]
  (apply set/union #{} xs))

(defn- free-symbols-seq [forms]
  (apply free-symbol-set (map free-symbols forms)))

(defn- free-symbols
  [expr]
  (m/match (ast/ast-map expr)
    {:ast/type :literal}
    #{}

    {:ast/type :symbol :ast/name ?s}
    #{?s}

    {:ast/type :apply
     :ast/operator ?op
     :ast/args ?args
     :ast/output ?out}
    (free-symbol-set (free-symbols ?op)
                     (free-symbols-seq ?args)
                     (free-symbols ?out))

    {:ast/type :apply
     :ast/operator ?op
     :ast/args ?args}
    (free-symbol-set (free-symbols ?op)
                     (free-symbols-seq ?args))

    {:ast/type :do :ast/body ?body}
    (free-symbols-seq ?body)

    {:ast/type :let-cell :ast/names ?names :ast/body ?body}
    (set/difference (free-symbols ?body) (set ?names))

    {:ast/type :compound
     :ast/inputs ?inputs
     :ast/output ?output
     :ast/body ?body}
    (set/difference (free-symbols ?body)
                    (set (conj (vec ?inputs) ?output)))

    {:ast/type :let-compound
     :ast/name ?name
     :ast/value ?value
     :ast/body ?body}
    (free-symbol-set (free-symbols ?value)
                     (disj (free-symbols ?body) ?name))))

(defn- with-path [state path]
  (assoc state :path path))

(defn- compile-seq [state forms]
  (let [base-path (:path state)]
    (reduce
     (fn [[state _] [idx form]]
       (let [[state' result] (compile-node
                              (h/child (with-path state base-path) idx)
                              form)]
         [(with-path state' base-path) result]))
     [state nil]
     (map-indexed vector forms))))

(defn- compile-symbol [{:keys [env] :as state} sym]
  (if-let [value (env/lookup env sym)]
    [state value]
    (let [[state' binding] (h/new-cell state [:symbol sym])]
      [(assoc state' :env (env/bind-local env sym binding)) binding])))

(defn- output-binding [state maybe-output]
  (if (some? maybe-output)
    (let [[state' binding] (compile-node (h/child state :output) maybe-output)]
      (if (env/binding-id binding)
        [state' binding]
        (throw (ex-info "application output must compile to a cell"
                        {:output binding}))))
    (h/new-cell state :result)))

(defn- compile-args [state args]
  (let [base-path (:path state)]
    (reduce
     (fn [[state acc] [idx arg]]
       (let [[state' binding] (compile-node
                               (h/child (with-path state base-path) [:arg idx])
                               arg)]
         [(with-path state' base-path) (conj acc binding)]))
     [state []]
     (map-indexed vector args))))

(defn- ordered-closure-messages
  [closure-id ordered-input-ids output-ids network]
  (let [closure-cv (net/network-cell-strongest network closure-id)
        closure-payload (value/value-payload closure-cv)
        input-values (mapv #(net/network-cell-strongest network %)
                           ordered-input-ids)]
    (if (or (value/unusable? closure-cv)
            (value/any-unusable-values? input-values)
            (nil? closure-payload))
      []
      (-> network
          (closure/create-boundary-outputs output-ids)
          (closure/create-boundary-inputs ordered-input-ids)
          (#(let [inner-inputs (mapv (partial net/lookup-inner-in %)
                                     ordered-input-ids)
                  inner-outputs (mapv (partial net/lookup-inner-out %)
                                      output-ids)]
              ((closure/closure-f closure-payload)
               (closure/closure-net closure-payload)
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

(defn- apply-deferred-closure [network closure-id arg-ids out-id]
  (apply-closure-ordered network closure-id arg-ids out-id))

(defn- apply-compound-binding [network binding arg-ids out-id]
  (apply-closure-ordered network
                         (:binding/id binding)
                         (concat (mapcat env/boundary-ids
                                         (:binding/captures binding))
                                 arg-ids)
                         out-id))

(defn- compile-application [state op args maybe-output]
  (let [base-path (:path state)
        [state' op-value] (compile-node (h/child state :operator) op)
        [state'' arg-bindings] (compile-args (with-path state' base-path) args)
        arg-ids (mapv env/binding-id arg-bindings)
        [state''' out-binding] (output-binding (with-path state'' base-path)
                                               maybe-output)
        out-id (env/binding-id out-binding)]
    (when-not (every? some? arg-ids)
      (throw (ex-info "application arguments must compile to cells"
                      {:args arg-bindings})))
    (let [[network' prop-ids result-id]
          (cond
            (fn? op-value)
            (op-value (:net state''') arg-ids out-id)

            (env/compound-binding? op-value)
            (apply-compound-binding (:net state''') op-value arg-ids out-id)

            (env/binding-id op-value)
            (apply-deferred-closure (:net state''') (env/binding-id op-value) arg-ids out-id)

            :else
            (throw (ex-info "application operator is not callable"
                            {:operator op-value})))]
      [(-> state'''
           (assoc :net network')
           (h/add-props prop-ids))
       (env/cell-binding result-id)])))

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
    (compile-node (h/child (assoc state' :env scoped-env)
                           :body)
                  body)))

(defn- compile-compound [state inputs output body]
  (let [locals (conj (vec inputs) output)
        body-free-symbols (free-symbols body)
        captures (->> (env/captures (:env state) locals)
                      (filter #(contains? body-free-symbols (:symbol %)))
                      vec)
        capture-bindings (mapv :value captures)
        capture-ids (mapcat :ids captures)
        lexical-env (:env state)
        seed (:seed state)
        path (:path state)
        closure-value
        (closure/closure
         (fn [_closure-net input-ids output-ids network]
           (let [input-ids (vec input-ids)
                 capture-count (count capture-ids)
                 capture-inner (subvec input-ids 0 capture-count)
                 arg-inner (subvec input-ids capture-count)
                 [out-inner] output-ids
                 captured-env (env/rebind-captures
                               (env/sub-env lexical-env)
                               captures
                               capture-inner)
                 body-env
                 (reduce
                  (fn [scoped-env [sym id]]
                    (env/bind-local scoped-env sym (env/cell-binding id)))
                  (env/bind-local captured-env output (env/cell-binding out-inner))
                  (map vector inputs arg-inner))
                 [state' result] (compile-node {:net network
                                                :env body-env
                                                :seed [seed path :closure]
                                                :path []
                                                :props []}
                                               body)
                 result-id (env/binding-id result)]
             (if (and result-id (not= result-id out-inner))
               (second ((stdlib-prop/id result-id out-inner) (:net state')))
               (:net state'))))
         net/empty-net)
        [state' closure-binding] (h/new-cell state :closure closure-value)]
    [state' (env/compound-binding (:binding/id closure-binding) capture-bindings)]))

(defn- compile-node [state expr]
  (m/match (ast/ast-map expr)
    {:ast/type :literal :ast/value ?v}
    (h/new-cell state :literal ?v)

    {:ast/type :symbol :ast/name ?s}
    (compile-symbol state ?s)

    {:ast/type :apply
     :ast/operator ?op
     :ast/args ?args
     :ast/output ?out}
    (compile-application state ?op ?args ?out)

    {:ast/type :apply
     :ast/operator ?op
     :ast/args ?args}
    (compile-application state ?op ?args nil)

    {:ast/type :do :ast/body ?body}
    (compile-seq state ?body)

    {:ast/type :let-cell :ast/names ?names :ast/body ?body}
    (compile-let-cell state ?names ?body)

    {:ast/type :compound
     :ast/inputs ?inputs
     :ast/output ?output
     :ast/body ?body}
    (compile-compound state ?inputs ?output ?body)

    {:ast/type :let-compound
     :ast/name ?name
     :ast/value ?value
     :ast/body ?body}
    (let [[state' binding] (compile-node (h/child state [:let-compound ?name :value])
                                         ?value)
          env' (env/bind-local (:env state') ?name binding)]
      (compile-node (h/child (assoc state' :env env'
                                     :path (:path state))
                             [:let-compound ?name :body])
                    ?body))))

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
         [state result] (compile-node {:net net
                                       :env env
                                       :seed seed
                                       :path path
                                       :props []}
                                      expr)
         network (annotated-net (:net state) result (:props state) (:env state))]
     {:net network
      :cell (env/binding-id result)
      :binding result
      :env (:env state)
      :props (:props state)})))

(defn compiled-result [compiled-net]
  (net/network-dict-entry compiled-net compiler-result-key))

(defn compiled-props [compiled-net]
  (net/network-dict-entry compiled-net compiler-props-key))

(defn p:compile-expr
  "Compile AST/env cells into a compiled network cell."
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
