# Agent runbook: extend compiler-2 through `.lain`

This is the operational guide for an agent extending a running compiler-2
environment without adding compiler syntax. Read
[`compiler-2-live-environment-io.md`](compiler-2-live-environment-io.md) for
the implementation model and limitations; use this document for the concrete
author, load, inspect, commit, save, and reload loop.

## Preserve these boundaries

- A `.lain` file is only a sequence of ordinary S-expressions. It has no
  manifest, special reader, or alternate evaluator.
- Add host primitives by extending the runtime environment. Do not add a CPS
  handler when an ordinary operator binding expresses the feature.
- An environment operator declares a boundary request. It does not read or
  write files, compile source, or mutate a live session itself.
- The runtime executes boundary requests only after propagation reaches
  equilibrium. The effect ledger makes repeated request identities
  mutation-free.
- A successful primitive import creates a compound child frame. The compound
  environment remains the only lexical authority; dictionaries are metadata,
  not a second name resolver.
- Existing applications retain the cells they already resolved. A later
  import or definition shadows names only for future compilation.
- Premise retraction changes which retained claim is supported. It does not
  delete cells, propagators, history, or source records.

The extension pipeline is therefore:

```text
Clojure binding factory
  -> load-primitive-environment boundary request
  -> child compound environment
  -> later ordinary .lain forms compile with canonical CPS
  -> propagation reaches equilibrium
  -> boundary receipts and block projections are published
```

## 1. Author a primitive module

A trusted Clojure file exposes a zero-argument function returning ordered
`[symbol operator]` pairs:

```clojure
(ns extensions.project-math
  (:require [propagators.compiler-2.compiler.basis :as basis]))

(defn square [x]
  (* x x))

(defn average [x y]
  (/ (+ x y) 2))

(defn primitive-bindings []
  [['square  (basis/primitive-operator square)]
   ['average (basis/primitive-operator average)]])
```

Keep the returned sequence ordered. Every key must be a symbol and every value
must be a compiler-2 operator value. The runtime validates the whole result
before installing anything. The Clojure file is trusted executable code; never
offer this loader to an untrusted client.

Use a compound operator when the extension must declare a subnet or retain
topology. Use `basis/primitive-operator` only for a direct host procedure.
Neither case requires a parser or compiler-dispatch change.

### Reactive wall clock example

The repository includes a boundary-backed extension whose operator remains
declarative while the runtime owns wall-clock IO and scheduling:

```clojure
(load-primitive-environment
 "extensions/runtime_clock.clj"
 :extensions.runtime-clock/primitive-bindings
 0)

(clock-in 1000)
```

`clock-in` accepts an interval in milliseconds and an optional explicit output
cell. Its result is an ordinary source-aware event cell, so the next TUI block
shows Unix wall-clock milliseconds and updates reactively. The daemon
subscription is keyed by the declared effect identity, stops when its owning
block premise retracts, and emits a final event retraction. The loadable module
never starts a thread or reads the clock itself.

## 2. Write normal `.lain` source

Imports and their consumers can live in one file because loading is staged one
form at a time:

```clojure
(load-primitive-environment
 "extensions/project_math.clj"
 :extensions.project-math/primitive-bindings
 0)

(def-cell radius 4)
(def-cell area (square radius))
(average area 20)
```

Each form is compiled, propagated to equilibrium, and allowed to drain nested
boundary effects before the next form is read. Consequently `square` is
available to the following form. A nested `(load-lain ...)` also finishes
before its parent file continues.

The optional revision is part of the import identity. Reusing the same path,
entry, and revision is idempotent. Increment the revision only when an edited
module should create a new child frame for future forms:

```clojure
(load-primitive-environment
 "extensions/project_math.clj"
 :extensions.project-math/primitive-bindings
 1)
```

Load hidden sequential source with:

```clojure
(load-lain "experiments/circle.lain" 0)
```

Load the same top-level forms as visible, premise-versioned blocks with:

```clojure
(load-blocks "experiments/circle.lain" 0)
```

`load-blocks` appends one versioned block per top-level form to the invoking
client. Use it when an agent and a person need to inspect and revise the same
experiment. Use `load-lain` for library-like hidden setup.

Canonical paths are used for cycle detection, nesting is bounded, and a
failing form is not published. Earlier successful forms remain installed and
the receipt reports the successful prefix and failing form.

## 3. Start a runtime and register an agent client

Start the shared runtime on the default EDN port `45555`:

```bash
clojure -M:wired/runtime
```

Register a stable premise-versioned client before committing blocks:

```bash
clojure -M -m graph.compiler-2-runtime-server request 45555 \
  '{:op :tui/register, :client-id "agent-1", :mode :versioned-premise}'
```

Client identity is embedded in each automatic block premise, so multiple
clients can share one environment without confusing equally numbered blocks.
The logical premise identity is derived from client, block, and version.

## 4. Inspect and focus blocks

The thin commands are convenient for read-only inspection:

```bash
clojure -M -m graph.compiler-2-runtime-server block-list 45555 agent-1
clojure -M -m graph.compiler-2-runtime-server block-show 45555 agent-1 0
clojure -M -m graph.compiler-2-runtime-server block-focus 45555 agent-1 0
```

The corresponding EDN requests are:

```clojure
{:op :agent/blocks :client-id "agent-1"}

{:op :agent/block
 :client-id "agent-1"
 :index 0
 :detail? true}

{:op :tui/focus-block
 :client-id "agent-1"
 :index 0
 :request-id "focus-0001"}
```

Detailed block inspection includes source, version, strongest value, block
premise and active state, warnings, definition candidates, result cell,
application and propagator IDs, and active/inactive TMS claim counts. Focus is
ephemeral UI control. If the person is editing, the TUI queues it until the
draft closes and never overwrites the draft.

## 5. Commit a premise-versioned block

Read the block first and use its current version as `expected-version`. A blank
new block has `nil`; the first successful version is `0`.

```bash
clojure -M -m graph.compiler-2-runtime-server request 45555 \
  '{:op :tui/commit-version,
    :commit-id "86b733d8-d056-4c3b-9f13-a035d567be32",
    :client-id "agent-1",
    :index 0,
    :expected-version nil,
    :text "(def-cell radius 4)"}'
```

For a later edit, generate a new commit UUID and pass the version that was
read immediately before the edit:

```clojure
{:op :tui/commit-version
 :commit-id "4a26f797-46ef-449a-8251-92d70c745b61"
 :client-id "agent-1"
 :index 0
 :expected-version 0
 :text "(def-cell radius 5)"}
```

Keep one `commit-id` for retries of the same payload. An exact retry returns
the original receipt and declares no duplicate topology. Reusing that ID with
different source, client, block, or expected version is a collision. An unknown
ID with a stale expected version is rejected.

`block-send` and `:agent/send-block` are legacy/convenience submit operations;
they are not substitutes for `:tui/commit-version`. Use the explicit commit
request whenever premise history, optimistic concurrency, retraction, and
idempotency matter.

To ask the runtime itself to import visible blocks, submit this ordinary form
through a versioned commit:

```clojure
(load-blocks "experiments/circle.lain" 2)
```

The `block-load` CLI is a convenient submit wrapper, but an automated agent
that requires version history should put the same expression in an explicit
`:tui/commit-version` request.

## 6. Save source or commit semantic state

Save exact current block source for later editing:

```clojure
(save-blocks
 "checkpoints/agent-1-source.lain"
 :source
 :all
 "agent-1-source-0001")
```

A selection can be a vector of block indexes:

```clojure
(save-blocks
 "checkpoints/selected.lain"
 :source
 [0 2 7]
 "selected-0001")
```

Commit the uniquely supported shared semantic environment to reloadable source:

```clojure
(save-environment
 "checkpoints/supported.lain"
 :commit-supported
 "supported-0001")
```

`:commit-supported` waits for equilibrium, requires one compatible supported
candidate for every exported definition, generates ordinary canonical source,
recompiles that source in a private candidate environment, and atomically
replaces the file. Unsupported, contradictory, or ambiguous state writes
nothing.

Checkpoint IDs are idempotency keys. Retry the same checkpoint unchanged; use
a new checkpoint ID after semantic state changes.

Explore/archive alternatives with:

```clojure
(save-environment
 "checkpoints/possibilities.lain"
 :preserve-premises
 "possibilities-0001")
```

Current limitation: preserve mode archives all candidate and inactive
application sources under hygienic private names, but reloading does not yet
automatically reactivate every archived candidate. Its receipt reports
`:preserved-candidates-archived` and
`:preserved-applications-archived`. Do not present this as full live-router
round-trip parity. Use `:commit-supported` when a reliable executable
checkpoint is required.

Reload a committed checkpoint through the normal compiler:

```clojure
(load-lain "checkpoints/supported.lain" 0)
```

The file contains only ordinary S-expressions. It intentionally does not
serialize NodeIds, `Net` values, queues, sockets, JVM procedures, or generated
application frames.

## 7. Recommended agent loop

1. Register a named `:versioned-premise` client.
2. Load experimental source with a versioned block containing `(load-blocks
   file revision)`.
3. Use `block-list` and `block-show` before every edit.
4. Commit with a fresh UUID and the observed `expected-version`.
5. Re-read the edited block and any dependent output blocks after equilibrium.
6. Use semantic trace/call-graph inspection when a value is surprising; do
   not infer topology from the TUI projection alone.
7. Save exact editable source with `save-blocks :source`.
8. When the possibility space has one supported result, export it with
   `save-environment :commit-supported` and a new checkpoint ID.
9. Reload the emitted `.lain` into a private/test runtime and compare strongest
   values before handing the checkpoint to a user.
10. Focus the relevant block in the user's terminal only after preserving any
    current draft.

## 8. Verification before extending this subsystem

Run the focused environment and server tests:

```bash
clojure -M:test \
  propagators.compiler-2.runtime.environment-io-test \
  graph.vijual.compiler-2-runtime-server-test
```

Then run the registered manifest and formatting check:

```bash
clojure -M:test
git diff --check
```

For topology-growth diagnostics:

```bash
clojure -M:wired/environment-io-bench
```

The benchmark loads and saves 1, 10, and 50 forms and reports compilation,
propagation/effect/filesystem latency plus cell, propagator, and exported-form
growth. It is a diagnostic receipt, not a timing threshold.

When adding a new boundary capability, preserve the existing layering:

1. define one small declarative operator constructor;
2. add it to the runtime primitive environment;
3. implement one boundary handler over an immutable candidate state;
4. key it through the effect ledger;
5. return a receipt suitable for a normal result cell;
6. test declaration separately from effect execution and session publication.
