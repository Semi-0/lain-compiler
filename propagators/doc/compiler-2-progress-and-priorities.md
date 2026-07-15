# Compiler-2 Progress and Priorities

Status snapshot: 2026-07-15, on
`codex/compiler-2-closure-correctness`.

This document records the supported boundary and the evidence still required.
It is not a correctness claim until every gate below is green.

## Nightly handoff

This branch now has a green correctness receipt for the supported compiler-2
boundary. It preserves the fixed-frame lexical work and the runtime
investigations while keeping behavior-history integration deferred.

### Progress preserved here

- Compiler-created lexical frames no longer inherit the reducer.
- Ordinary CPS symbol compilation returns a canonical binding cell when the
  address is unambiguous. Structural lookup and raw binding dereference remain
  separate declarations for ambiguous topology.
- Local reservations distinguish same-compilation filling from later
  shadowing. Named closures reserve their name before compiling their body.
- Delayed closure, retained application, and lazy topology paths consume raw or
  canonical cells rather than scope envelopes.
- CPS `let-cell` compilation restores the outer environment after compiling
  its body.
- A same-ID `p:sub-env` request is idempotent. This prevents a runtime rebuild
  from declaring an environment as its own parent and falling into unbounded
  structural lookup.
- Runtime and graph inspection resolve names from environment topology rather
  than assuming the environment is still a host map.
- Canonical declarations removed the earlier accessor-network explosion in the
  focused recursive list and 100-hop sync diagnostics.

The latest gates on this working tree are:

- focused compiler/demo/cell protocol: 532 assertions, zero failures/errors;
- runtime server: 315 assertions, zero failures/errors;
- semantic REPL: 35 assertions, zero failures/errors;
- full manifest: 2,015 assertions, zero failures/errors.

The former broad-suite `expected node-id token {:x nil}` errors were stale test
lookups. CPS `let-cell` correctly restores its outer environment, so tests that
inspect or continue compiling inside the lexical block now explicitly use the
frame containing the compiled result. They no longer treat the restored outer
environment as a second authority for inner bindings.

Two graph-demo expectations for `prop:<->` and `prop:+` were also stale after
application declaration moved behind application context topology. They now
assert `ctx:<->` and `ctx:+`. The remaining demo failure was real: semantic
projection exposed the compiler-generated implicit-return route. The shared
declaration layer now exposes the closure's semantic body, while the raw graph
continues to show the lowered topology.

An earlier session receipt had 479 green assertions across compile-2, behavior,
and TMS before the final CPS lexical-scope restoration. It is historical only
and is superseded by the fresh result above.

This is a correctness receipt for the supported boundary, not a performance
receipt. The chained-GUR benchmark remains outstanding.

### Accepted lexical decision: one environment authority

The compound environment topology is the only semantic authority for lexical
names. Compiler state and network dictionaries must not hold an independent
answer to “which binding does this name mean?”

The working accessor inherited by `b4109c24` had one coherent input shape:

```text
compound frame
  :env/local-bindings
  :env/parent
  symbol -> binding descriptor -> binding cell
```

`p:lexical-access-local-first` could therefore decide whether to read locally,
wait for a declared-but-empty local, or continue to the parent by inspecting
the frame itself. At that checkpoint the accessor primitive was tested, but
general compiler symbol traversal and closure application had not yet been
fully migrated to live environment cells.

The current experiment split that authority:

- `p:declare-canonical-local` records `symbol -> binding ID` only in
  `lexical-topology-key`;
- `compile-symbol` reads that dictionary and returns a unique address directly;
- ambiguous or unknown lookup falls back to the compound-frame accessor;
- the compound frame can declare the name in `:env/local-bindings` without
  containing the named binding descriptor the accessor needs to read.

That split is rejected. A binding that exists only in the dictionary can work
through the compile-time fast path and still be invisible to delayed structural
lookup. It also makes correctness depend on whether dictionary metadata
survived a topology diff, closure activation, sub-environment execution, or
runtime rebuild.

The intended primitives are:

```clojure
p:declare-binding
;; [env-id symbol binding-id] -> binding declaration topology

p:lexical-access-local-first
;; [symbol env-id binding-answer-id] -> nearest binding descriptor

p:binding-value
;; [binding-answer-id value-answer-id] -> raw binding value connection
```

`p:declare-binding` is a proposed name for the smallest declaration operation;
it does not require a new datatype. It stores the symbol and binding descriptor
in the compound frame. It must not eagerly construct a lexical lookup accessor.
The accessor network is installed only when a lookup actually needs it.

Compiler symbol traversal is the composition of selection and dereference. A
known-address shortcut may be reintroduced only as a derived optimization of
the same compound-frame declaration. Removing the shortcut or deleting its
cache must not change semantics.

`lexical-topology-key` may remain for graph labels, stable-ID receipts,
profiling, and cached lookup indexes. Those entries are derived observations,
not lexical facts. Reservation ownership may also remain compiler bookkeeping,
but consuming a reservation must produce the compound-frame binding
declaration before the name is considered bound.

This decision does not restore reducer inheritance. Parent traversal remains a
local-first structural accessor over fixed compound frames. Scope provenance
remains an explicit accessor API rather than an envelope attached to every
ordinary compiler symbol.

### Runtime boundary repair

- Trace subscription identity is `[:trace/subscribe target-id]`, keeping one
  subscription stable across runtime retries. Trace results are active events
  whose values are semantic trace graphs; no behavior history participates.
- Runtime integration repairs only applications with an unresolved operator.
  Repair installs a named identity edge from the
  now-resolved binding to the existing unresolved operator cell. It preserves
  application/result IDs and does not leave a second compiled network behind.
- `block-at` now reads a block only in its two-argument form. Its explicit
  three-argument form writes the supplied source to the target block without a
  reverse copy into the source cell.
- Topology binding labels were added to semantic graph projection so canonical
  cells remain named without materializing lexical accessor networks.
- Runtime dynamic bindings such as `%` are fixed compound-frame declarations,
  so a client-local binding shadows its parent before the derived address index
  is consulted.
- Semantic trace requests retain semantic labels. They are no longer rewritten
  through the compiler environment, so operator nodes such as `block-at` remain
  traceable without coupling the tracer to compiler binding IDs.
- Compiler-2 behavior-history integration tests are reader-discarded. Event,
  TMS, ordinary closure, and the separate behavior compiler remain in scope.

### Resolved trace/display failure

The trace/display failure was not lexical lookup. Profiling a trace target
showed that it first held a valid update and
then became contradictory when the propagator named
`[:compound-object/network-slot :base]` activated. That reader belongs to the
layered behavior projection installed for the display path.

The direct cause was in the runtime effect evaluator: every
`:tui/write-display` payload was converted by `display-behavior-update` into a
`behavior/retained-value` before it is committed to the display cell. Therefore
an otherwise ordinary event/display boundary enters behavior history, layered
base projection, and behavior merge semantics.

The runtime boundary now preserves existing semantic partial information and
normalizes only raw display payloads into active events. Trace subscriptions
publish event-backed graphs directly. Headless tests prove that language
`trace` reacts to graph updates without a TUI.

### Implemented display boundary

`be:block` is a declarative event-backed display operator:

```text
source event
  -> be:block propagator
  -> :tui/write-display effect
  -> runtime commits the event update to the display cell
  -> the view projects the strongest active event value
```

The event cell protocol already merges active updates and retractions, and the
TUI annotation layer already projects event content. No behavior reducer or
layered behavior unwrap is needed. For source compatibility, a raw payload can
be normalized at the runtime boundary to
`(event/active-event display-id display-id tick payload)`; an existing event
fact, content value, or projection should pass through unchanged.

This keeps declaration separate from evaluation: the compiler installs the
`be:block` application topology, the propagator declares an external display
effect, and the runtime commits the event only after the propagation round.

### Design gaps to settle

1. **Single lexical declaration primitive.** Reuse or extract the smallest
   existing compound-slot declaration that records a named binding descriptor
   without installing a lookup accessor. Replace dictionary-only canonical
   declarations with it.
2. **Derived fast-path equivalence.** Prove that direct unique-address lookup
   and `p:lexical-access-local-first` select the same binding. Compilation must
   remain correct with the derived address cache removed.
3. **Display provenance.** Raw-value compatibility events currently use the
   display ID as both input and source. Carry the original source cell only if
   a concrete provenance consumer requires it.
4. **Display lifecycle.** Retraction and block-edit epoch coverage remains to
   be broadened even though raw and semantic partial-information preservation
   is now covered at the boundary.
5. **Trace subscription lifecycle.** Replacement, removal, and block-edit
   behavior still need dedicated tests for the stable target identity.
6. **Dynamic topology repair.** A previously unresolved operator is connected
   to the first later definition that resolves it. Already-resolved
   applications are not retargeted by subsequent same-name definitions.
7. **Application topology truth.** Retained application may choose an output
   only after the operator arrives. Its eventual input/output write set must be
   represented explicitly before graph reachability can support garbage
   collection.
8. **Performance acceptance.** The chained-GUR depths 1, 5, 10, and 50 still
   need fresh measurements after correctness is green.

### Next session, in order

1. Establish one compound-frame binding declaration primitive and route local,
   free, definition, closure, argument, and output declarations through it.
2. Make `compile-symbol` use local-first selection plus `p:binding-value` as the
   correctness baseline. Keep the direct address path disabled until parity is
   proven.
3. Keep tests reading final lexical results through `:cell` and inner
   definitions through the explicit inner or captured environment; outer
   lookup remains valid only for names actually declared in the outer frame.
4. Keep the focused, broad compiler, semantic graph, and full-manifest gates
   green while changing lexical declarations.
5. Keep the runtime-server gate green. The post-repair split receipt covers all
   73 tests: 315 assertions, zero failures/errors; four loopback socket tests
   were run outside the restricted sandbox.
6. Run the chained-GUR benchmark at depths 1, 5, 10, and 50.

## Supported boundary

Compiler-2 currently supports:

- core closures, including capture, escape, recursion, explicit outputs, and
  multiple outputs;
- ordinary values and events;
- distributed TMS premise, retraction, and closure paths;
- lists, compound slots, and lazy recursive GUR topology;
- local CPS compiler composition through delayed work.

Compiler-2 behavior-history integration is deferred. The separate behavior
compiler, behavior values, history algebra, and their isolated tests remain
supported.

## Architectural invariants

- CPS compilation declares topology; runtime owns evaluation.
- The compound environment is the sole semantic authority for lexical names.
  Network dictionaries may contain only derived indexes and diagnostics.
- Compiler-created lexical frames use `p:scope-frame` and do not inherit the
  reducer.
- `p:sub-env` remains eager compatibility only.
- Ordinary compiler symbols expose raw or canonical cells, never scope
  envelopes.
- Scope provenance remains available through explicit scoped accessors.
- Selection and dereference are separate declarations:
  `p:lexical-access-local-first` chooses a binding descriptor and
  `p:binding-value` connects its addressed cell to a raw value cell.
- `->`, `<->`, list, slot, event, and TMS operators are propagator strategies,
  not compiler special forms.
- Delayed closure and lazy topology retain the selected local compiler.
- Runtime topology realization is additive and does not patch the scheduler.

## Current repair state

### Fixed frames and topology lookup

`p:scope-frame` declares parent, scope, chain, depth, and local-name topology.
`p:lexical-access` is again the structural scoped frame walker;
`p:reducer-lexical-access` is the explicit reducer API.

Compiler symbol traversal now distinguishes four cases:

1. a current unambiguous fixed address returns its canonical cell directly;
2. a known-missing name in a compiler frame reserves one canonical free-input
   cell;
3. ambiguous or unknown topology composes local-first binding selection with
   raw binding dereference;
4. explicit scoped access remains separate and preserves source, chain,
   binding address, and dependencies.

The final symbol of a form therefore appears directly in `:cell` and
`compiler/result` when it has a canonical address. No accessor is materialized
and no ordinary propagator unwraps a scope value.

This four-way traversal is the current experiment, not the accepted final
authority model. Its dictionary-only canonical declaration is the exact seam
to replace. The desired baseline always declares the binding in the compound
frame and composes local-first selection with raw dereference; a direct result
cell is then an optimization or compiler-level result syntax, not a second
lexical representation.

### Reservation lifecycle

- `let-cell` reserves each fixed address.
- A same-compilation `def` consumes an unconsumed same-frame reservation.
- Named closures reserve their name before constructing the closure value, so
  self-reference sees the live environment.
- Free inputs are reservations and remain writable by later runtime messages.
- Reservations carry the compilation seed. A later compile does not consume an
  older reservation and therefore creates a fresh current address unless
  `:reuse-existing-bindings?` is true.
- Frame topology records both historical addresses and the current address.
  Earlier applications remain wired to their original cells; applications
  declared after a new definition use the new current address.

### Closure and application declaration

- Closure values contain the live lexical environment ID.
- Retained applications no longer capture lexical value-address maps or unwrap
  operators.
- Lazy guards listen to their compiled raw/canonical condition cell.
- Closure frames receive raw closure cells.
- Once runtime knows a retained closure, it declares the addressed frame and
  installs `runtime.closure-frame/p:apply-closure-with`.
- The named closure-frame propagator exclusively prepares and emits closure-body
  topology, including when the closure was already known by retained application.
- A portable closure value whose captured environment cell is absent from the
  receiving network falls back to the application context's live environment.
- Known propagator operators use their static declaration strategy and retain
  `:primitive` application lowering. Unknown operator cells retain
  `:closure-cell` lowering until runtime resolution.

## Deferred compiler-2 behavior-history tests

The following integration tests are reader-discarded in
`propagators.compile-2-test`:

- `compiler-2-main-can-build-behavior-with-compiler-closure-reducer`;
- `compiler-2-behavior-merge-can-use-low-level-operators`;
- `compiler-2-behavior-prefixed-constructor-builds-behavior`;
- `compiler-2-behavior-syntax-history-slices-return-behaviors`.

Previously deferred behavior-history arithmetic, projection, behavior-cell,
and execute-sub-environment integration tests remain deferred as well. Re-enable
them only after core closure/event/TMS correctness and bounded topology growth
are established independently.

## Current evidence

Green focused receipts:

- closure-frame, composition, CPS, organization, and GUR linked-list gate:
  137 assertions, zero failures/errors;
- separate behavior compiler and TMS data-structure suites: 82 assertions,
  zero failures/errors;
- semantic REPL plus headless trace/display boundary: 44 assertions, zero
  failures/errors;
- focused runtime cases for ordinary semantic tracing, `block-at` tracing,
  late closure definition, late free-cell definition, top-to-bottom block
  order, and cross-client shared environments: zero failures/errors;
- complete runtime-server split gate: 73 tests and 315 assertions, zero
  failures/errors (four loopback socket tests run outside the sandbox);
- focused core slices cover false values, reservation reuse, named recursion,
  later same-scope declarations, lists, canonical final symbols, topology
  lookup plus raw dereference, explicit provenance, constraints, and late
  closure/operator/input arrival.

The full compiler gate is still being re-established. Do not treat the focused
receipts as the release gate.

### Correctness-phase performance observation

The five-element recursive list-map diagnostic is semantically correct but
expensive:

| Map depth | Settled result | Cells | Propagation time |
| ---: | ---: | ---: | ---: |
| 1 | 10 | 993 | about 35 s |
| 2 | 20 | 1,667 | about 52 s |
| 3 | 40 | 2,341 | about 73 s |

The observed growth from depths 1 through 3 is approximately linear in cells
and wall time, not exponential, but its constant cost is unacceptable. Per the
correctness-first plan, no optimization is applied until all supported semantic
gates pass.

## Remaining priorities

### P0: finish the supported correctness gates

Run in order:

```bash
clojure -M:test \
  propagators.compiler-2-closure-frame-test \
  propagators.compiler-2-composition-test \
  propagators.compiler-2-cps-test \
  propagators.compiler-2-organization-test \
  propagators.compiler-2-gur-linked-list-test

clojure -M:test \
  propagators.compile-2-test \
  propagators.behavior-compiler-test \
  propagators.tms-test

clojure -M:test \
  graph.vijual.compiler-2-semantic-repl-test \
  graph.vijual.compiler-2-runtime-server-test

clojure -M:test
```

Any supported semantic failure is P0. A long-running test is not counted as a
pass until it completes.

### P1: benchmark only after P0 is green

Run the chained-GUR benchmark at depths 1, 5, 10, and 50. Record preparation,
retraction, bring-in, cell count, propagator count, and post-update growth.
Report whether growth is bounded, linear, polynomial, or explosive. Do not add
a new threshold in this repair.

### P2: reduce fixed-frame declaration cost

After correctness, profile the recursive list-map and 100-hop sync cases with
the existing additive activation profiler. Optimize only a named declaration
or activation hot path. Do not restore reducer inheritance, materialize
accessors, or patch the scheduler to hide the cost.

### P3: behavior-history integration

Resume only after P0 and P1. The first acceptance case is same-timestamp point
arithmetic through one compiler closure and one sync edge, with correct history
and bounded activation/topology growth.
