(ns propagators.compiler.model.env
  "Live compound-object lexical environments for Compiler 2."
  (:require [propagators.compiler.model.env.access :as access]
            [propagators.compiler.model.env.binding :as binding]
            [propagators.compiler.model.env.index :as index]
            [propagators.compiler.model.env.topology :as topology]))

(def env-depth-key binding/env-depth-key)
(def env-scope-key binding/env-scope-key)
(def env-scope-chain-key binding/env-scope-chain-key)
(def env-parent-key binding/env-parent-key)
(def env-local-bindings-key binding/env-local-bindings-key)
(def binding-value-key binding/binding-value-key)
(def env-internal-keys binding/env-internal-keys)

(def cell-binding binding/cell-binding)
(def cell-binding? binding/cell-binding?)
(def compound-binding binding/compound-binding)
(def compound-binding? binding/compound-binding?)
(def binding-id binding/binding-id)

(def lexical-topology-key index/lexical-topology-key)
(def lexical-topology-scope index/lexical-topology-scope)
(def reserved-binding-id index/reserved-binding-id)
(def consume-reserved-binding index/consume-reserved-binding)
(def local-binding-id index/local-binding-id)
(def lexical-binding-status index/lexical-binding-status)
(def lexical-binding-id index/lexical-binding-id)
(def binding-names index/binding-names)
(def lexical-topology-effects index/lexical-topology-effects)

(def lexical-access-declaration access/lexical-access-declaration)
(def lexical-access-id access/lexical-access-id)
(def p:lexical-access-local-first access/p:lexical-access-local-first)
(def p:binding-value access/p:binding-value)
(def resolve-binding access/resolve-binding)
(def resolve-binding-id access/resolve-binding-id)

(def p:scope-frame topology/p:scope-frame)
(def p:root-frame topology/p:root-frame)
(def p:declare-canonical-local topology/p:declare-canonical-local)
(def p:reserve-canonical-local topology/p:reserve-canonical-local)
(def declare-bindings topology/declare-bindings)
(def declare-root topology/declare-root)
(def declare-child topology/declare-child)
