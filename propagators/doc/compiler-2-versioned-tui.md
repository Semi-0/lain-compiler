# Compiler-2 versioned-premise TUI

`graph.compiler-2-versioned-tui` is an additive client of the existing
multi-client runtime. It does not replace `graph.compiler-2-tui`.

Run the shared runtime and the new client with:

```bash
clojure -M:wired/runtime
clojure -M:wired/versioned-client --client-id versioned-1
```

The client registers with mode `:versioned-premise`. Up and Down select a
block, Enter opens a local draft, Ctrl+T commits, and Esc discards the draft.
The earlier Ctrl+Up/Ctrl+Down and Space bindings remain compatibility aliases.
Polling may update displayed runtime values but never overwrites a draft.
If another client commits the selected block, the draft becomes stale and must
be discarded/refreshed before it can commit.

Source blocks render only their source and warnings. When an expression returns
a cell, the existing auto-output topology displays its value in the following
block, and the runtime retains one additional blank block for the next entry.
Ctrl+T has no effect outside edit mode; create a block by selecting that blank
entry with Up/Down, pressing Enter, and then committing its draft.

## Transaction boundary

`:tui/commit-version` accepts a client-generated UUID, block index, expected
version, and source. Successful requests append an immutable record. Replaying
the same ID and payload returns the first receipt without compiling or
propagating again. Reusing an ID for any different client, block, expected
version, or source is a collision. Version validation happens only after this
global ID check.

Candidate compilation is immutable. A compile error publishes neither topology
nor a history record. On success the runtime publishes the new topology and
history before performing boundary effects. History, premise evidence, cells,
and propagators are retained; retraction only makes claims unsupported.
Each history record retains the current compilation's propagator, application,
and result-cell receipt. The compiler runs that topology once to equilibrium;
versioned commits do not replay it during settlement. Older applications run
only when the ordinary scheduler observes a change to one of their actual
inputs; the legacy runtime settlement policy is unchanged. Ordinary commits
also defer semantic-graph projection. An explicit trace form performs the full
projection on demand, avoiding a complete scan of retained topology per edit.

Each version premise is `[client-id block-id version]`, so multiple clients can
compile into the same environment without sharing premise identity. The client
identity denotes the durable logical TUI client, not an individual socket
connection. Each version owns one stable distributed-TMS premise-state cell. A block-specific
Meander term rewrite wraps every application in an ordinary dependency term and
then delegates the transformed tree to the canonical CPS compiler. That term
installs a named `:compiler-2/application-premise` propagator whose inputs are
the raw application result and the relevant premise-state cells. It records the
context on the same raw binding and returns that binding unchanged; there is no
special application compiler handler. Scalar block results pass through named
`:compiler-2/block-premise` gates as compiler syntax sugar. Closure, operator,
environment, and compound topology values stay raw.
Retained closure evaluation therefore continues through the existing
`p:apply-closure` path.

### Block display cells

Automatic expression output is internal runtime topology, not an outbox
effect. Each compiled version installs one named `:runtime/tui-block-display`
propagator from its premise-supported result to the following block's existing
display cell. All versions targeting that block merge into that one cell. The
TUI reads the cell's strongest distributed-TMS projection after propagation:
retracted claims stay stored but inactive, one active claim displays its value,
and conflicting active claims display the ordinary contradiction with
provenance. No display epoch chooses a winner. The boundary outbox remains for
actual external effects and explicit legacy block writes.

### Replayable JSON debugging

The runtime server exposes a localhost line-delimited JSON socket on port
`45556` by default; pass `--json-port 0` in tests or another port at launch.
It accepts `instance/export` and `instance/import`. Export contains all
versioned clients plus the append-only successful commit log in execution
order. The snapshot is diagnostic only; import registers the clients and
replays the commit log through the same atomic `commit-version!` path.

```bash
clojure -M:wired/runtime --json-port 45556
clojure -M -m graph.compiler-2-runtime-server json-export 45556 instance.json
clojure -M -m graph.compiler-2-runtime-server json-import 45556 instance.json
```

An exact replay is mutation-free. A partial or divergent history is rejected,
and a failed candidate replay publishes none of its topology, premises, or
history. The fixture
`test/graph/compiler_2_runtime/fixtures/four_edits.json` reproduces four edits
of the same application; `graph.compiler-2-runtime.instance-replay-test`
verifies displayed values `11`, `12`, `13`, and `14`, one active plus three
inactive display claims, atomic import, multi-client ordering, and socket
cleanup.

## Editable definitions

Block-level `def-net`, callable `def-cell`, `def-constraint`, callable `def`,
and scalar `def` forms are rewritten idempotently. Definitions inside a closure
body remain private to that closure version. Each public definition has a
stable identity `[block-id name]`, stable binding cell, and stable distributed
TMS registry; the compound environment remains the only lexical authority.

Callable edits append private candidates to the registry. The public cell holds
one raw definition-router operator. A retained call watches the registry,
installs each unseen candidate through the ordinary runtime application path
and `p:apply-closure`, and gates private outputs through the candidate premise.
Old topology and claims remain present. Retracting one candidate merely makes
its output unsupported, while the new candidate can update an existing caller
without recompilation. Multiple active candidates are allowed to contradict in
the ordinary TMS rather than being resolved by a hidden latest-wins rule.

Scalar definitions use one stable TMS-backed public cell. Primitive and premise
gate claim identities include their upstream support version, so changing a
definition creates a new immutable claim instead of trying to mutate an old
claim's value.

An explicit `premise-closure` or `distributed-premise-closure` is kept as the
candidate expression and is never wrapped in a second premise closure. Its user
premise and epoch are recorded in version history, while a distinct internal
candidate-version premise makes editing and reversion independently
controllable.

Signature changes are recoverable topology. Stable missing input/output cells
are allocated per call and candidate, extra caller cells remain connected only
to their retained topology, and structured warnings are annotated onto both
the definition and caller blocks. Editing the caller creates a new application
version; old topology is not rewired or deleted.

Named verification suites:

- `graph.compiler-2-runtime.block-compiler-test` covers idempotent lowering,
  callable declaration forms, and closure-local definitions;
- `edited-network-definition-reactivates-existing-application` covers registry
  routing and retained-call repair;
- `signature-repair-uses-stable-placeholders-and-visible-warnings` covers
  stable placeholders and TUI warning projection;
- `explicit-premise-definition-records-and-retracts-its-context` covers the
  explicit-premise branch;
- `definition-and-caller-premises-retract-and-recommit` covers stable scalar
  definitions and support-preserving reactivation;
- `four-edit-json-replay-keeps-tms-in-the-block-display-cell` covers replayable
  TMS-aware automatic output without an outbox epoch.

## Benchmark receipt

The simpler scalar-edit diagnostic exercises the direct display-cell path. A
2026-07-16 run produced:

| edits | total commit ms | cells | propagators | retained versions | display gates |
|---:|---:|---:|---:|---:|---:|
| 1 | 61.0 | 182 | 49 | 1 | 1 |
| 10 | 1038.7 | 713 | 445 | 10 | 10 |
| 50 | 5393.4 | 3073 | 2205 | 50 | 50 |

From 10 to 50 edits, cells grew 4.31x and propagators 4.96x for 5x retained
history. Total commit time grew 5.19x. The diagnostic therefore shows linear
topology and no activation explosion from inactive display claims; it is not a
fixed timing threshold.

Run `clojure -M:wired/versioned-bench` for the intentionally heavier matching
definition-and-application diagnostic. It also reports retained candidates,
placeholder cells, and warnings. A 2026-07-15 paired-edit receipt was:

| edits | commits | commit ms | cells | propagators | candidates | premise gates |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 2 | 194.7 | 239 | 97 | 1 | 6 |
| 10 | 20 | 3121.3 | 1787 | 1231 | 10 | 114 |
| 50 | 100 | 55262.9 | 8667 | 6271 | 50 | 594 |

Topology is linear from 10 to 50 edits (4.85x cells and 5.09x propagators for
5x retained versions). Latency is superlinear and remains a visible performance
receipt rather than a correctness threshold. The router only installs a new
candidate into the currently active caller version; inactive retained calls do
not form a candidate-by-call cross product.

The earlier application-only receipt was:

| edits | total commit ms | cells | propagators | versions | block gates | app premises |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 39.0 | 150 | 35 | 1 | 1 | 0 |
| 10 | 1158.7 | 681 | 422 | 10 | 10 | 18 |
| 50 | 13072.3 | 3041 | 2142 | 50 | 50 | 98 |

Topology growth is linear (59 cells and 43 propagators per additional retained
version after the first in this case). The term form adds one compiler
bookkeeping cell per rewritten application but no additional propagator versus
the handler experiment: 98 cells at 50 edits. Timing is diagnostic only; there
is no fixed threshold.
An earlier experiment chained eager `p:sub-env` instances and became unusable
before ten edits. Selecting the existing fixed scope-frame primitive removed
that explosion without changing the legacy client.

### Existing application after definition edits

The paired-edit receipt measures compilation and commit work. A separate
late-input benchmark keeps the caller block unchanged: it compiles one
application, edits only its definition block, and finally sends `7` into the
existing application's empty input cell. Run it with:

```bash
clojure -M -e \
  '(require (quote graph.compiler-2-versioned-tui-bench))
   (prn (graph.compiler-2-versioned-tui-bench/benchmark-definition-edits-existing-application-input 50))'
```

A local rerun on 2026-07-15 produced the correct current result, `56`, in
`51.5-53.8 ms`. The retained topology contained `3,522` cells and `2,596`
propagators; the late input caused `504` propagator activations:

| propagator | activations | distinct propagators |
|---|---:|---:|
| retained application | 100 | 50 |
| primitive | 50 | 50 |
| `->` | 150 | 100 |
| application premise | 101 | 101 |
| block premise | 101 | 101 |
| versioned definition call | 2 | 1 |

Candidate retraction is semantic rather than scheduler-level: all 50 retained
candidate applications receive the shared caller input, while premise gates
suppress the 49 unsupported results. Runtime input cost therefore grows with
retained definition history. For the current live-coding workload, roughly
`52 ms` after 50 definition edits is acceptable, so dormant candidate inputs
remain a future optimization rather than a release blocker. Revisit it if real
blocks make interaction latency visible; the local optimization seam is to
route public arguments only into the currently supported candidate's private
input boundary.

These figures are single-run diagnostic receipts, not stable performance
guarantees. The benchmark validates the result before reporting timing.

Irreversible external IO already performed by an old version is not undone.
Explicit event forms remain part of the language, but block versioning itself
creates distributed-TMS facts only.
