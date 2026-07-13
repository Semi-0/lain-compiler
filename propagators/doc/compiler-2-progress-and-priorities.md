# Compiler-2 Progress and Priorities

Status snapshot: 2026-07-13, after the CPS, live lexical environment, and
runtime boundary work on `main`.

This document is a checkpoint, not a claim that compiler-2 is green. It records
what is implemented, what has focused evidence, what remains uncertain, and the
order in which the remaining work should be done.

## Architectural invariants

The remaining work must preserve these boundaries:

- the CPS compiler declares topology; it does not install host values directly;
- the environment is a live compound object, not a materialized host map;
- lexical lookup uses `p:lexical-access` and returns scope-dependent values;
- scope provenance is interpreted by layered procedures, not erased by general
  unwrapping inside propagators;
- applications of closures use the declarative closure-application path;
- `->` and `<->` are primitive propagators, not compiler special forms;
- runtime commit/effect declarations remain separate from propagation and host
  IO;
- debugging and profiling extend the runtime without patching the scheduler.

## Implemented progress

### CPS compiler organization

- `propagators.compiler-2.cps-core` is the canonical stack-safe compiler.
- predicates, handlers, declarations, language values, runtime application, and
  operator families have responsibility-specific namespaces.
- the synchronous compiler and old cores remain compatibility shims under
  deprecated namespaces.
- public compiler entrypoints and multimethod identities remain available.

### Live lexical environment

- compiler lexical access is installed as topology against the compound
  environment;
- declarations record canonical binding addresses without replacing the
  reducer-backed lexical authority;
- a unique known address takes the direct read path while ambiguous or unknown
  bindings retain reducer selection;
- both paths return the same scope-dependent result shape and preserve lexical
  provenance;
- slot access can reuse an already-present cell instead of constructing another
  accessor network.

### Application and delayed compilation

- local CPS compilers are carried through delayed closure, lazy topology, list,
  retained application, and sub-environment compilation paths;
- known non-contextual operators can install concrete topology through their
  static installer;
- retained applications preserve operator and argument cells until the runtime
  operator becomes available;
- application and lexical propagators involved in the current hot paths have
  semantic names for profiling.

### Runtime boundary and diagnostics

- commit, propagation, and effect periods are documented separately in
  `boundary-effect-runtime.md`;
- topology effects can declare delayed internal network extensions;
- an additive activation profiler reports call count and inclusive/exclusive
  time without changing kernel evaluation;
- sub-environment execution now returns child topology changes instead of
  discarding them.

## Current evidence

The following observations were established with focused runs during this work:

- lexical direct access remains live after the binding cell receives a later
  value and retains a lexical dependency token;
- reducer fallback and direct lexical access return the same scoped value shape;
- a sub-environment TMS fact can be retracted and brought back through parent
  storage;
- two isolated behavior-producing closure applications produce `2` and `7`;
- isolated behavior arithmetic over those inputs produces `9`;
- the composed behavior network with sync can instead leave `7` at the observed
  output and can take minutes to settle;
- the earlier static-operator wiring checkpoint passed 436 compiler-2
  assertions, but that receipt predates the current lexical/runtime changes.

No current full-suite green receipt exists. A fresh focused event/TMS run was
started and deliberately stopped so the architecture could be discussed before
more long-running evaluation.

### 2026-07-13 event/TMS checkpoint

Behavior-history arithmetic integration is now reader-discarded in
`propagators.compile-2-test` and remains explicitly deferred. Its data
structures and isolated operator tests are not removed.

The supported event gate is green:

- seven event arithmetic, update, retraction, switch, DAG, and sync-chain tests;
- 15 assertions, no failures or errors;
- 3.49 seconds for the focused gate;
- the 50-stage update chain alone completes in about 1.43 seconds.

The first seven non-behavior TMS checks are also green with 27 assertions:

- default distributed premise operators;
- the behavior/TMS public compiler entrypoint;
- retained TMS operator declaration;
- distributed switch/forward sync;
- sub-environment TMS storage;
- premise epoch belief/retraction;
- compound-pair TMS insertion.

Their individual runtimes range from 0.03 to 23.1 seconds. The slower passing
tests are distributed switch at 23.1 seconds and sub-environment TMS at 17.8
seconds.

A final combined receipt for the seven event tests, three TMS smoke tests, and
the direct/fallback lexical shape checks is green: 12 tests, 32 assertions, no
failures or errors, in 14.26 seconds.

Two stale expectations were corrected rather than changing runtime semantics:

- compiler results are lexical scope values, so tests inspect their canonical
  binding address when they need reducer content or slots;
- an environment-bound TMS operator is a retained application, not a
  compile-time static operator, matching the rule that the compiler declares
  application topology and runtime resolves the operator cell.

The accepted fixed-parent traversal makes the dynamic premise-closure test
complete and exposes the application declaration tradeoff directly:

```text
compiler-2-redefined-premise-closure-keeps-declared-application-topology
12.9 seconds, passing

result: 6 -> nothing -> 6 -> 6
```

The first `op` declaration owns the application topology. The later declaration
can add premise evidence, but does not rebuild the already-declared graph. This
is now an explicit compiler contract: fixed lexical addressing trades dynamic
partial repair of existing application topology for bounded declaration and
local reasoning. A future repair feature must be an explicit topology
redeclaration/effect, not an accidental consequence of reducer lookup.

### Lexical performance evidence

Imported legacy environments and compiler-created frames record their canonical
binding addresses. Compiler frames use local-first fixed parent traversal and
do not eagerly copy the complete accumulated reducer. Reads still produce scope
values with the declaring source, active child chain, binding address, and
dependencies; no value is unwrapped inside arithmetic propagators.

Delayed topology carries those addresses as declarative runtime name-binding
effects. Retained application and lazy guards consult the canonical address, so
they do not wake on a scope envelope whose base value is still `nothing`.
Compatibility `p:sub-env` remains the eager composition of `p:scope-frame` and
`p:inherit-bindings`; only compiler-created fixed frames take the new path.

The focused closure/composition/CPS/organization/list gate is green: 118
assertions, no failures or errors. A 40-frame declaration comparison measured
about 1.14 seconds and 603 propagators for fixed scope frames versus 8.02
seconds and 723 propagators with eager reducer inheritance, about a sevenfold
wall-clock improvement in that diagnostic. The recursive closure depth-one
case completes in about 438 ms after using the canonical condition address;
before that guard fix it exceeded 15 seconds and kept declaring frames.

A ten-second additive activation profile of the remaining P1 path reports:

| Propagator | Calls | Exclusive time |
| --- | ---: | ---: |
| `[:compiler-2/lexical-projection :grouped]` | 2,784 | 7.57 s |
| `[:compound-object/network-slot :env/bindings]` | 40 | 1.95 s |
| `:compiler-2/lexical-merge` | 17 | 0.06 s |

The fixed-frame change removes that eager lexical copying from the compiler
path, but does not eliminate all distributed-TMS costs. The ordinary
distributed closure-output test passes in about 42.9 seconds and the distributed
premise chain passes in about 81.9 seconds. A full `propagators.compile-2-test`
run was stopped after more than seven minutes without a failure report.

The representative chained-GUR benchmark has zero cell/propagator growth after
retract/bring-in at depths 1, 5, and 10. One-sample update times were:

| Depth | Retract | Bring in | Cells | Propagators |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 16.6 ms | 44.3 ms | 183 | 86 |
| 5 | 24.1 ms | 66.9 ms | 259 | 130 |
| 10 | 39.8 ms | 112.0 ms | 354 | 185 |

Depth 50 did not finish preparation within two minutes. Therefore the limited
scope is proven useful, but widening fixed addressing into ambiguous/dynamic
scopes is deferred until the remaining distributed-TMS construction hot path is
identified.

The CPS compatibility test compares semantic results with the deprecated
synchronous compiler. Internal result IDs, propagator counts, and application
counts are intentionally not parity contracts because the live lexical compiler
declares additional topology.

The fast structural checkpoint is green with 149 assertions across compound
slot access, reducer cells, additive profiling, compiler organization, and CPS
tests. This does not include the deferred behavior integration or the pending
event/TMS acceptance gate.

## Remaining problems

### P0: Establish a truthful supported arithmetic gate

**Problem:** behavior-history arithmetic currently obscures whether ordinary
event and TMS arithmetic are correct. The composed behavior path is both wrong
and expensive, while the requested near-term product boundary only requires
event-current and TMS arithmetic.

**Action:**

1. mark compiler-2 behavior-history arithmetic integration as deferred;
2. keep behavior construction/history data structures independently testable;
3. run the focused event arithmetic and retraction tests;
4. run ordinary TMS arithmetic, premise retraction/bring-in, and closure-output
   tests;
5. publish the exact passing and failing test names.

**Significance:** critical. Without this gate, every later result mixes the
supported path with a known deferred subsystem.

**Dependencies:** none. This is the first task.

### P1: Keep application topology repair explicit

**Decision:** a compiled application keeps the first lexical binding address it
resolved. Later same-name declarations do not implicitly rebuild that topology.

**Action:** preserve the passing fixed-topology test. If dynamic repair becomes
required, introduce a declarative topology-replacement effect with an explicit
identity and lifecycle. Do not restore eager reducer inheritance or add a
compiler special handler to obtain repair accidentally.

**Significance:** accepted limitation for the current compiler; high only if
dynamic same-name redefinition becomes a supported surface guarantee.

**Dependencies:** a future repair design depends on P4's explicit topology
realization contract.

### P2: Identify the first incorrect writer in composed sync

**Problem:** the composed behavior example can observe `7` where isolated
behavior arithmetic produces `9`. The currently proven fact is the bad write,
not its source.

**Action:** use the additive profiler/trace extension to record, for the target
cell, the first message containing `7`, its propagator name, application ID,
operator, argument IDs, and scope provenance. Then fix that producer or its
declaration.

**Significance:** high for correctness, but deferred from the immediate
event/TMS gate because the reproducer is behavior-history arithmetic.

**Dependencies:** P0. Investigation resumes only after event/TMS status is
known.

### P3: Stop scoped sync/application activation explosion

**Problem:** a small composed behavior program has shown roughly 176,000
activations in each direction of a bi-sync edge and about 6,500 grouped lexical
projections. The lexical projection alone consumed about 30 seconds in one
profile.

The likely mechanism is repeated semantically equivalent scope-bearing
revisions circulating through sync and reducer projections. That remains a
hypothesis until P2 identifies the concrete message cycle.

**Action:** after correctness is restored, compare candidate identity using
canonical binding address, scope chain, base value, and dependency revision.
Suppress only revisions proven semantically identical. Preserve new
dependencies and premise epochs.

**Significance:** high. The current cost prevents reliable integration and full
suite runs.

**Dependencies:** P2. Optimizing before identifying the bad cycle risks hiding
the correctness defect.

### P4: Clarify retained application topology realization

**Problem:** known operators select concrete inputs/outputs and install an
ordinary propagator. Dynamic retained applications initially declare only a
fallback output, then may return messages addressed to an explicit applicant
after the operator becomes known.

This is not automatically a kernel correctness error: the scheduler routes a
message by its target ID and schedules that target's neighbors. It does mean the
declared graph can understate the cells written by the runtime application,
which matters for graph inspection, dependency analysis, and future garbage
collection.

**Action:** decide between two explicit strategies:

- keep retained application as an evaluator and record its dynamic write set;
  or
- let retained application resolve the operator once and declaratively install
  the operator's concrete topology, after which the concrete propagator owns
  evaluation.

Prefer the second strategy if it removes repeated interpretation without
duplicating closure-frame declaration.

**Significance:** medium for current semantics, high for truthful topology and
future garbage collection.

**Dependencies:** P1 and P2 provide evidence about whether this boundary causes
either current correctness defect.

### P5: Finish commit/effect runtime integration

**Problem:** the declarative commit/effect model and several runtime pieces
exist, but all external integrations do not yet uniformly execute as:

```text
commit -> propagate to equilibrium -> effect -> next-round commit
```

**Action:** finish one runtime driver and migrate trace/TUI/XR delivery to it
without adding IO callbacks to propagators or queues to the immutable network
value.

**Significance:** medium now, high before garbage collection or more external
ports. A quiescent round provides the safe observation boundary those features
need.

**Dependencies:** P0 for a stable acceptance gate; P4 before relying on graph
reachability for collection.

### P6: Restore full-suite and benchmark receipts

**Problem:** broad compiler, semantic REPL, runtime-server, and full-manifest
results have not been re-established after the live lexical changes. Older
failure counts must not be treated as a current baseline.

**Action:** after P0-P3:

1. run the focused compiler-2 suites;
2. run semantic REPL and runtime-server gates;
3. run the full manifest;
4. benchmark the representative event/TMS programs;
5. report runtime, activation counts, node/edge growth, and any explosion.

**Significance:** release gate.

**Dependencies:** P0-P3.

## Dependency order

| Order | Work | Depends on | Completion evidence |
| --- | --- | --- | --- |
| 1 | P0 event/TMS acceptance boundary | nothing | named focused tests with exact counts |
| 2 | P1 premise-closure correctness | P0 | redefine, retract, and bring-in assertions pass |
| 3 | P2 first incorrect writer | P0 | trace identifies one named producer |
| 4 | P3 activation explosion | P2 | equal results with bounded activation/node growth |
| 5 | P4 retained application realization | P1, P2 | graph and runtime write contract agree |
| 6 | P5 commit/effect integration | P0, P4 for GC | runtime reaches idle across all phases |
| 7 | P6 full verification | P0-P3 | focused, runtime, full-suite, and benchmark receipts |

## Lexical-model decision: accepted limited scope

The approved additive redesign splits the old eager operation into two
declarations:

1. `p:scope-frame` declares parent, scope, chain, depth, and local binding
   structure;
2. `p:inherit-bindings` retains the accumulated reducer copy as an explicit
   compatibility operation;
3. compatibility `p:sub-env` remains their eager composition;
4. compiler-declared fixed frames use `p:scope-frame` and canonical parent
   addresses;
5. unknown live frames use structural local-first access; legacy materialized
   frames retain their compatible lookup path.

This keeps lexical results scope-dependent and does not change the scheduler or
runtime value model. It deliberately gives up implicit partial repair for an
already-declared application. The evidence above is sufficient to retain this
limited compiler-frame path, but not sufficient to apply fixed addressing to
all ambiguous or runtime-created scopes.

## Deferred behavior-history arithmetic

Behavior-history arithmetic is not deleted. Its data structures, operators,
and isolated tests remain useful. Compiler-2 integration is deferred until the
event/TMS gate is green and the first-writer trace explains the composed sync
failure.

Re-enable it only when all of the following are true:

- same-timestamp point arithmetic produces the correct history;
- interval overlap remains correct;
- late shared timestamps react without rebuilding unbounded topology;
- closure and sub-environment applications preserve the result;
- sync does not circulate semantically identical scoped revisions;
- the representative benchmark shows no activation or node/edge explosion.
