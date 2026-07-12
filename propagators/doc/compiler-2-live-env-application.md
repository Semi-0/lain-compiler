# Compiler-2 Live Env And Application Boundary

## Current Status

Compiler-2 has working GUR coverage in the current tested scope:

- accumulating GUR lazy topology and HOP chains;
- compiler-2 cdr-gated list / map-chain examples;
- `when` as presence-gated topology;
- local-first lexical access as a primitive env accessor;
- a pure web multi-client coordinator proof slice.

The important limitation is not GUR itself. The remaining limitation is the
compiler-2 application boundary.

## What Works

The lower GUR substrate can build topology lazily and wake it when a delayed
tail or source becomes usable. Existing tests cover lazy `when` topology,
linked-list access, map chains, late cdr propagation, and bidirectional HOP
chain cases.

Compiler-2 also has a local-first lexical accessor:

```clojure
(p:lexical-access-local-first sym env-id out-id)
```

This accessor walks env frames structurally:

1. inspect the current frame local binding slots;
2. if the frame declares `sym`, wait for that binding value;
3. only if the frame definitely does not declare `sym`, continue to the parent;
4. emit the nearest raw binding value.

This is intentionally separate from provenance-aware `p:lexical-access`.
`p:lexical-access` keeps scoped candidates for strongest/provenance selection.
`p:lexical-access-local-first` is for compiler dispatch, where the compiler
needs the actual binding identity.

## What Application Still Blocks

Compiler-2 closure application still behaves like:

```text
operator cell becomes usable
argument cells become usable
build a transient activation env
compile the closure body in that transient network
run it to quiescence
copy declared output values back to the outer network
```

That blocks the cleaner architecture in five concrete ways.

### Live Lexical Env

Closure bodies are still compiled against a materialized host env value during
application. They are not yet compiled against a live env cell using
`p:sub-env`, `p:bind-local`, and structural lexical access.

This means later env facts do not naturally wake already-compiled closure
bodies. Workarounds still have to refresh or re-run application instead of
letting lexical access propagate through env topology.

### Persistent Applied Topology

The topology declared by a closure body is mostly inside a transient activation
network. It is not retained as a durable applied topology fragment in the outer
program network.

For GUR, the desired shape is:

```text
application = declared child topology
env/args/outputs = live cells
updates = wake existing topology
```

The current shape is closer to:

```text
application = evaluate body now
result = copy selected outputs out
```

### Delayed Binding Lookup

Correct lexical lookup is structural, not availability-based. If a local frame
declares `x`, lookup must wait for the local `x` binding rather than falling
back to parent `x`.

The local-first accessor provides this primitive, but general compiler symbol
compilation has not been migrated onto it. Directly replacing symbol compilation
would be unsafe because compiler-2 symbols denote cell/operator bindings, not
ordinary cell values.

### Boundary And Effect Escape

If a closure body installs bridge/effect topology, the current application path
only reliably externalizes declared output values. Topology declarations or
boundary effects produced inside a transient activation need special escape
handling.

This is a bad fit for declarative bridge models such as web-client routing,
where a GUR body should be able to declare durable bridge topology.

### Direct Recursive Closure Style

The goal is that ordinary self-application inside lazy topology is enough:

```clojure
(when rest
  (walk rest out))
```

without `def-recursive`, special `recur`, or recursion detection. Current tests
show important pieces working, but the general closure application path is still
mediated by transient compile/run/copy behavior rather than stable applied
network fragments.

## Multi-Client Proof Slice

The current web-client proof slice is pure runtime/coordinator code, not a full
browser server.

It proves:

- each web client gets an independent compiler-2 runtime session;
- a separate coordinator runtime loads `examples/lain/multi-client-messaging.lain`;
- `runtime:clients` publishes a linked client list into the coordinator;
- the `.lain` GUR model builds route rows from that list;
- a late client join extends routing;
- a message from A to B updates only B's latest view;
- a message from B to C updates only C's latest view.

This is enough to prove the declarative coordinator shape, but not enough to
claim the browser/WebSocket demo is complete.

## Next Migration Step

Do not replace symbol compilation with value lookup directly.

The next primitive should be a binding-driven application compiler:

```text
resolve operator/argument bindings structurally
wait while local bindings are declared but pending
when bindings are usable, declare an applied child topology
retain that topology by application identity
route outputs/effects through explicit outer cells
```

This lets compiler-2 move from "evaluate closure and copy output" toward
"declare applied topology with live env, args, and outputs."

## Verification Snapshot

Focused checks run against this status:

```bash
clojure -M:test propagators.compiler-2-gur-linked-list-test
clojure -M:test propagators.gur-accumulating-test
clojure -M:test propagators.compile-2-test
clojure -M:test graph.compiler-2-web-clients-test
```

At the time this note was written, all four focused checks passed.
