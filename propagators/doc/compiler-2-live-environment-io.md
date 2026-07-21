# Compiler-2 live environment and `.lain` IO

For a command-oriented agent workflow, including the important distinction
between convenience block submission and premise-versioned commits, see
[`compiler-2-lain-agent-runbook.md`](compiler-2-lain-agent-runbook.md).

Compiler-2 file IO extends the runtime environment, not the parser or compiler.
A `.lain` file is consecutive normal S-expressions (or one outer list of normal
S-expressions). There is no manifest reader and no second evaluator.

## Ordinary bootstrap operators

```clojure
(load-primitive-environment file entry [revision])
(load-lain file [revision])
(save-environment file mode checkpoint-id)
(load-blocks file [revision])
(save-blocks file mode selection checkpoint-id)
```

These are named `propagator-operator` values. They only put declarative requests
in the boundary outbox. The boundary service performs filesystem/compiler work
and returns a receipt containing status, canonical path, SHA-256 digest,
revision or checkpoint, form count, and diagnostics. Its effect ledger makes a
repeated identity mutation-free while still delivering the saved receipt to a
new result cell.

## Primitive modules

A trusted Clojure module exports a zero-argument function returning ordered
`[symbol operator]` pairs:

```clojure
(ns extensions.project-math
  (:require [propagators.compiler-2.compiler.basis :as basis]))

(defn primitive-bindings []
  [['square (basis/primitive-operator #(* % %))]
   ['average (basis/primitive-operator #(/ (+ %1 %2) 2))]])
```

Load it from ordinary source:

```clojure
(load-primitive-environment
 "extensions/project_math.clj"
 :extensions.project-math/primitive-bindings
 0)
```

All pairs are validated before publication. A successful import declares a
fixed compound child frame over the current `:program/env`; the compound object
remains the only lexical authority. Later forms see the bindings, while already
compiled applications retain their resolved cells. A changed revision creates
a new child frame for intentional future shadowing. Primitive modules are
trusted executable Clojure and must not be exposed to untrusted clients.

## Sequential loading

`load-lain` runs this loop:

```text
read one form → CPS compile → propagate to equilibrium
→ drain new boundary effects → publish environment/topology → next form
```

Thus a first form may load `square` and a second may call it. Nested loads finish
before their parent continues. Canonical paths detect cycles; nesting is capped
at 32 and effect draining at 256 rounds. A failing form is not published, while
its successful prefix and diagnostics are returned.

`load-blocks` uses the same staging but appends one visible versioned block per
top-level form in the invoking client.

## Saving

`save-blocks :source` writes exact current source for `:all` blocks or a vector
of indexes. `save-environment` writes the shared semantic order, with primitive
imports first. Files contain only S-expressions: no `Net`, generated NodeId,
queue, socket, executor, or JVM function is serialized. Transient control,
display, and trace forms are excluded from semantic environment export.

`:commit-supported` requires exactly one active definition candidate, emits its
canonical `def`/`def-cell`/`def-net`/`def-constraint`, recompiles the generated
source privately, then replaces the target atomically. A checkpoint is
idempotent. Unsupported or ambiguous definitions fail before writing.

`:preserve-premises` currently retains every candidate source under a hygienic
private definition and emits the current public definition. Inactive application
versions are archived as private zero-input closures. Receipts explicitly report
`:preserved-candidates-archived` and
`:preserved-applications-archived` when applicable.

One design gap remains: those archived candidates do not yet automatically
reactivate after reload. The direct multi-candidate router experiment exposed a
transient contradiction before the old premise retraction reached the public
output; downstream monotone topology retained it. A correct follow-up needs a
declarative candidate gate whose premise-state input settles before its value
claim becomes eligible, while preserving upstream support and multiple outputs.
The exporter reports this limitation instead of writing semantically unsafe
source.

## Block inspection and terminal focus

```clojure
{:op :agent/blocks :client-id "versioned-1"}
{:op :agent/block :client-id "versioned-1" :index 7 :detail? true}
{:op :agent/send-block :client-id "versioned-1" :text "(+ 1 2)"}

{:op :tui/focus-block
 :client-id "versioned-1"
 :index 7
 :request-id "uuid"}
```

Detailed inspection includes source/version/value, premise state, warnings,
definition candidates, result/application/propagator IDs, and active/inactive
TMS claim counts. Focus is ephemeral per client with a monotone sequence. The
TUI selects and scrolls on polling; while editing, it queues focus until the
draft ends.

Thin CLI commands are:

```text
block-list PORT CLIENT
block-show PORT CLIENT INDEX
block-focus PORT CLIENT INDEX
block-send PORT CLIENT SOURCE
block-load PORT CLIENT FILE [REVISION]
block-save PORT CLIENT FILE MODE SELECTION CHECKPOINT
```

`block-load`/`block-save` only submit the corresponding S-expression; they are
not a second loader. An agent can load experiments as visible blocks, inspect
and focus a result, revise blocks, save exact source, then export the uniquely
supported environment with `:commit-supported`.

Focused coverage is
`propagators.compiler-2.runtime.environment-io-test`. It verifies live primitive
extension, staged visibility, cyclic load failure, visible block import,
checkpoint replay, committed candidate selection, preservation diagnostics,
and queued focus.

## Benchmark receipt

Run `clojure -M:wired/environment-io-bench`. A 2026-07-21 diagnostic produced:

| forms | load ms | save ms | cells | propagators | exported |
|---:|---:|---:|---:|---:|---:|
| 1 | 1346.3 | 830.2 | 138 | 35 | 1 |
| 10 | 1239.0 | 1178.6 | 237 | 152 | 10 |
| 50 | 4158.4 | 4119.0 | 677 | 672 | 50 |

Topology grew linearly (about 11 cells and 13 propagators per added simple
definition after the first). Timings include JVM/compiler warm-up and private
save recompilation and are diagnostic, not thresholds.
