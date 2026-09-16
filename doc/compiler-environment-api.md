# Compiler 2 live environment API

Compiler 2 has one environment representation: a live compound-object cell
identified by a `NodeId`. Environment maps and materialized compound values are
rejected by compiler, session, closure, extension, and sub-environment entry
points.

## Modules

`propagators.compiler.model.env` is a thin facade over four modules:

| Module | Responsibility |
| --- | --- |
| `env.binding` | binding descriptors, slot keys, stable node identities |
| `env.index` | frame and binding addresses for compilation and inspection |
| `env.access` | flat-GUR local-first lookup and binding-value projection |
| `env.topology` | root/child frames and canonical local slot declarations |

Generic flat GUR, compound objects, cells, TMS, and scheduling remain outside
this boundary.

## Declaration

Environment construction declares topology and returns every propagator that
must be scheduled:

```clojure
(env/declare-root network environment-id bindings)
;; => {:net Net :env NodeId :props [NodeId]}

(env/declare-child network parent-id child-id bindings)
;; => {:net Net :env NodeId :props [NodeId]}
```

Bindings are declaration data:

```clojure
[['+ primitive-plus]
 ['x (env/cell-binding x-id)]]
```

Compiler bootstrap uses `default-bindings`, `dependency-bindings`,
`behavior-bindings`, or `behavior-tms-bindings`, declares one deterministic
root, then compiles with its environment ID.

## Slot topology

A canonical local is represented only by compound-object slots:

```text
environment cell
  -- symbol slot --> binding descriptor cell
  -- :binding/value --> value cell
```

A child frame adds live parent, scope, chain, depth, and declared-local slots.
The declared local-name set is complete when the frame is created. A local
declaration blocks parent traversal even while its value is unavailable.

## Lexical access

Symbol compilation composes two propagators:

```clojure
(env/p:lexical-access-local-first
 symbol environment-id binding-answer-id)

(env/p:binding-value
 binding-answer-id value-answer-id)
```

`p:lexical-access-local-first` is a flat-GUR recursive declaration. It reads
the local-name slot, selects the symbol slot when local, and follows the parent
slot when missing. Nothing and contradiction wait according to propagator
readiness. Late local values and late parent values wake the existing topology.

The operator path uses the same slot access. There is no strongest-value
environment lookup before application.

An operator that already knows a compound key and does not need lexical parent
selection installs the slot propagator directly:

```clojure
(obj/p:slot 'bias value-id environment-id)
```

This direct access still waits for missing compound information and wakes when
the slot arrives. Compiler symbol lookup uses the recursive accessor because it
must distinguish a declared local from a missing local before following
`:env/parent`; direct slot access alone cannot preserve that shadowing rule.

## Runtime ownership

Sessions store:

```clojure
{:program/net Net
 :program/env NodeId
 :program/props [NodeId]}
```

Session extensions and sub-environments declare deterministic child frames.
Closures capture an environment ID and compile their body below that live
frame. Behavior closures use same-network topology and include their closure
declaration tuple in stable application identities.

## Removed representations

The following materialized or compatibility APIs were removed after production
caller searches reached zero:

```clojure
#{extend-env
  sub-env
  enter-scope
  bind-at
  bind
  bind-local
  bind-locals
  lookup
  lookup-entry
  scope-id
  scope-chain
  externalize-env
  import-environment
  import-environment-topology
  p:sub-env
  p:lexical-access
  p:access-binding}
```

Deprecated synchronous, predicate, and legacy Compiler 2 adapters that existed
only to accept those values were removed with them. Historical documents may
describe those paths; this page is the current environment contract.
