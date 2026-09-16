(ns propagators.compiler.model.env.binding
  (:require [propagators.infra.ids :as ids])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def env-depth-key :env/depth)
(def env-scope-key :env/scope)
(def env-scope-chain-key :env/scope-chain)
(def env-parent-key :env/parent)
(def env-local-bindings-key :env/local-bindings)
(def binding-value-key :value)
(def env-internal-keys #{env-depth-key env-scope-key env-scope-chain-key
                         env-parent-key env-local-bindings-key})

(defn cell-binding [id] {:binding/type :cell :binding/id id})
(defn cell-binding? [x] (= :cell (:binding/type x)))

(defn compound-binding
  ([id] {:binding/type :compound :binding/id id})
  ([id _captures] (compound-binding id)))

(defn compound-binding? [x] (= :compound (:binding/type x)))

(defn binding-id [x]
  (cond
    (ids/node-id? x) x
    (cell-binding? x) (:binding/id x)
    (compound-binding? x) (:binding/id x)
    :else nil))

(defn stable-node-id [& seed]
  (ids/->NodeId
   (UUID/nameUUIDFromBytes
    (.getBytes (pr-str (into [:compiler-2/env] seed))
               StandardCharsets/UTF_8))))
