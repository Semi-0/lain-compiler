# Propagators As A Coordination Language

Status: analysis note, June 2026.

This experiment should be framed as a minimal kernel for a coordination
language, not as a process language with propagators as an implementation
technique.

The language coordinates partial information, declared networks, and multiple
views over the same evolving facts. A program does not primarily say "run this
process next." It says which cells, propagators, slots, closures, applications,
and projections should exist, then lets monotone message flow and fixpoint
evaluation settle the declared structure.

## Thesis

The kernel is small because the main abstraction is coordination:

- cells hold partial information
- propagators emit messages
- merge absorbs contributions
- strongest/projection views expose readable facts
- network values can be declared, stored, inspected, merged, and evaluated

That makes the propagator language self-reflective: network structure is not
only runtime machinery. It can also be ordinary data in cells, named networks,
closure values, application IR, recursive frame fragments, and expanded
declaration networks.

It also makes the language multi-projectional: the same underlying information
can support several declared views, such as base values, provenance layers,
compound slots, behavior summaries, accessor topology, dependency facts, graph
visualizations, or compiler-retained IR. A projection should be an explicit
relation over shared partial information, not a hidden copy maintained by
process-local state.

## Minimal Kernel Boundary

The minimal kernel should remain close to the current runtime model:

- immutable `Net` values made of `graph`, `env`, and `dict`
- cell entries with `content` and `strongest`
- propagator activation functions that return messages
- merge and strongest policy at cell absorption time
- explicit task queues and fixpoint evaluation
- installers as pure network-value transformations
- activation-local subnet execution through boundaries, avatars, diffs, and
  ordinary outbound messages

The kernel should not grow into a process runtime. It should not own domain
control flow, dependency semantics, projection policy, or recursive expansion
policy. Those belong in declared topology, merge policies, named-network facts,
compiler layers, or explicit higher-order propagators.

The important boundary is:

```text
declaration: build or accumulate network data
evaluation: run declared topology to quiescence
projection: expose selected information through messages or strongest views
```

Crossing that boundary should be explicit. For example, a recursive declaration
can accumulate network facts into a cell. A recursive evaluation can run an
activation-local network and diff declared output cells. A dynamic subnet can
use activation-local taps to discover changed cells, but the taps themselves are
not durable declaration data.

## Scope

In scope:

- treating networks, closures, applications, procedures, recursive frames, and
  compound objects as inspectable declaration data
- keeping declaration separate from evaluation
- using named-network fragments and stable ids for idempotent accumulation
- adding projections as explicit topology, reducer policies, slot accessors,
  strongest policies, or compiler-retained IR
- using activation-local effects only to bridge an executed subnet back into
  ordinary messages
- testing that results depend on declared facts and merge policy, not incidental
  construction order or scheduler order

Out of scope for the minimal kernel:

- a general process language with program counters, threads, coroutines, or
  scheduler-visible domain steps
- propagators that mutate the live outer graph during activation
- global mutable registries for procedures, methods, layers, projections, or
  recursive frames
- durable effectful taps, mutable frontier atoms, or task queues stored inside
  declaration values
- dependence tracking inside `eval-propagator` or `eval-cell` rather than at
  merge time
- projection logic hidden in ad hoc readers that bypass declared topology
- unbounded recursive expansion as a scheduler feature

## What To Do

Prefer declaration over procedural control. If a feature needs more structure,
first ask what network facts, slots, named ids, or projection topology should be
declared.

Keep output movement message-shaped. Inner networks may run, but their effects
leave through declared output cells, explicit diffs, accumulator messages, or an
activation-local changed-cell frontier that is converted back into messages.

Make reflection ordinary. Compiler IR, closure data, application objects,
recursive frame facts, and expanded networks should remain inspectable cell
content or named-network data. Avoid opaque evaluator state when a declared fact
would do.

Make projections explicit. A new view should normally be represented by a slot,
layer, reducer policy, strongest policy, accessor topology, or retained IR
relation. The projection can be lazy or summarized, but its existence should be
visible in the network model.

Use stable identities for accumulated declarations. Recursive and iterative
network accumulation only remains coordination-friendly when repeated expansion
redeclares the same semantic topology instead of generating fresh unrelated
topology.

## What Not To Do

Do not make the scheduler the semantic center. It should drain tasks and wake
neighbors; it should not know about layers, recursion, behavior windows,
projection invalidation, or dependency truth maintenance.

Do not turn recursive declaration into direct outer-graph mutation. If recursion
discovers topology, emit network data or named-network fragments. If recursion
computes values, run an activation-local network and project selected results
out as messages.

Do not persist activation-local effects. Taps, changed-cell atoms, and task
frontiers are execution machinery. Persisting them inside compound or recursive
declaration values leaks one activation into future activations.

Do not collapse multi-projectional data into one native value too early. Native
maps, vectors, and scalars are useful strongest views, but the durable
coordination object should remain slot-backed or network-backed when later
projection or update is expected.

Do not treat provenance as the whole dependence story. Provenance layers are
domain projections. Generic dependence tracking belongs at merge time as a
future subsystem, not in propagator activation or scheduler logic.

## Open Design Work

- stable-id discipline for recursive and iterative network accumulation
- a common projection algebra for slots, layers, reducer policies, behavior
  summaries, and compiler-retained IR
- merge-time dependence tracking that can explain and retract cell content
- bounded iteration as declared fixed topology rather than recursive runtime
  expansion
- clearer APIs for converting activation-local changed-cell frontiers into
  outbound messages

These are kernel-adjacent design problems. They should be solved by tightening
the declaration, merge, projection, and boundary model, not by turning the
propagator language into a process language.
