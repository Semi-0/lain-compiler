# Flat GUR and Compiler 2 Application

Status: current architecture, 2026-09-12.

`propagators.infra.gur` is the public flat-GUR facade. Compiler 2 application and
local-first lexical access declare additive topology in the active `Net`.
`propagators.infra.gur.accumulating` remains an explicit alternative for programs
that need an accumulated child-network value.

## Boundary

```clojure
{:compiler
 {:input '[compiler-state application-ast continuation]
  :output '[compiler-state result-cell-binding]
  :effect :declare-topology}

 :application
 {:input '[operator-cell context-cell argument-cells result-cell]
  :output '[flat-declaration-effects]
  :effect :extend-active-net}

 :runtime
 {:input '[Net scheduled-propagators]
  :output '[Net messages]
  :effect :propagate}}
```

The dependency direction is:

```clojure
'[[compiler.handlers -> runtime.application]
  [compiler.declarations -> runtime.application]
  [runtime.application -> model.env]
  [runtime.application -> propagators.infra.gur]
  [model.env -> compound-object]
  [propagators.infra.gur.flat -/> compiler-2]
  [scheduler -/> compiler-2]
  [tms -/> compiler-2]]
```

## Application protocol

Compiler callables are flat recursive closures whose body dispatches through a
Compiler 2 protocol. The protocol keeps primitive, closure, and constraint
topology extension explicit.

```clojure
(defprotocol ApplicationTopology
  (application-effects
    [application gur-context invocation-ids result-id]))

(deftype PrimitiveApplication [installer declaration]
  ApplicationTopology
  (application-effects [_ context invocation result-id]
    (primitive-application-effects
     installer declaration context invocation result-id)))

(deftype ClosureApplication
  [compile* declaration-id lexical-env-id closure-info]
  ApplicationTopology
  (application-effects [_ context invocation result-id]
    (closure-application-effects
     compile* declaration-id lexical-env-id closure-info
     context invocation result-id)))
```

Application installation is one same-network declaration:

```clojure
(gur/apply-closure-effect
 operator-id
 (into [context-id] argument-ids)
 result-id)
```

The effect has a stable semantic identity and named relations for operator,
arguments, context, frame, and result. Inspection reads those relations with
`application-topologies`; it does not require a parallel retained-application
object.

```text
operator cell
  -> flat GUR application
  -> concrete inbound arguments
  -> live scope frame
  -> compiled body topology
  -> concrete outbound values
  -> result cell
```

Unavailable and contradictory operator information waits in flat GUR. No
Compiler 2 operator classifier, unwrap layer, pending reader, child applied
network, or scheduler branch participates in readiness.

## Live compound environments

Every compiler environment is a compound object stored in a normal cell.
Canonical locals are declared as live slot topology:

```clojure
(env/p:declare-canonical-local 'x frame-id value-id)
```

The graph shape is:

```clojure
{:frame-env
 {:env/parent captured-env
  :env/local-bindings '#{x}
  'x {:value value-cell}}}
```

Imported host environments use `import-environment-topology`. Each imported
slot points to a binding descriptor, and the descriptor points to the bound
value cell:

```text
environment --slot(x)--> binding-slot
binding-slot --slot(:value)--> binding-descriptor
binding-descriptor --binding-id--> value-cell
```

Callers schedule every returned `:prop-id`, so later values refine the same
environment topology.

## Recursive lexical access

Local-first lookup is itself a flat recursive declaration authored with
`propagators.infra.install`:

```clojure
(-> ctx
    (i/slot :env/local-bindings :local-names :frame)
    (i/install :contains-local
               (p:contains-binding? sym)
               :local-names
               :local-present)
    (i/install :missing-local
               (p:missing-binding? sym)
               :local-names
               :local-missing)
    (i/when-named :local-binding
                  :local-present
                  (local-binding sym :frame :out))
    (i/when-named :parent-frame
                  :local-missing
                  (parent-binding :frame :out)))
```

A declared local blocks parent traversal while its descriptor or value is
unavailable. A definitely missing local recursively applies the same closure
to the parent frame. Named availability gives each branch a stable identity,
waits for `nothing` or contradiction, and declares its body once when usable.

Compiler symbol evaluation remains two visible steps:

```clojure
(-> network
    ((env/p:lexical-access-local-first sym environment-id binding-id))
    ((env/p:binding-value binding-id value-id)))
```

## Inspection and compatibility

Connected named topology is the application IR. Call graph, session repair,
retraction inspection, and TUI publishing traverse the same relations that
execute the call.

`gur/p:apply-closure` remains as a deprecated installer-shaped adapter over
`gur/apply-closure-effect`. Accumulating GUR is imported explicitly from
`propagators.infra.gur.accumulating`.

Historical retained-application, closure-frame, lexical-application, and
application-layer modules have no production callers and were deleted.

## Port provenance

This standalone implementation was ported from
`Semi-0/datalog-research@3eac0243745cf1a6a21495e4f6e77a5bbef5900d`.
The extraction renames `propagators.compiler-2.*` to
`propagators.compiler.*` and maps runtime application lowering to
`propagators.compiler.lowering.*`. Shared flat-GUR and installer behavior comes
from the pinned `lain-infrastructure` dependency. Runtime sessions and TUI
integration remain owned by their respective repositories.
