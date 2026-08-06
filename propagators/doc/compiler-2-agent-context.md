# Compiler-2 adoption context and future-agent prompt

Status snapshot: 2026-07-21.

This document is the shortest complete model of the current compiler-2 and
live-runtime experiment. It is intended to be pasted into a new agent task or
read before changing compiler, closure, TMS, tracing, or TUI code.

## Ready-to-paste adoption prompt

```text
You are adopting the compiler-2 and propagator runtime in cloj-leapfrog.

Start by reading:

1. propagators/doc/compiler-2-agent-context.md
2. propagators/compiler_2/README.md
3. propagators/doc/compiler-2-lain-agent-runbook.md when extending a live
   environment or operating blocks
4. propagators/doc/compiler-2-progress-and-priorities.md
5. the focused tests named by the section you intend to change

Before editing, run `git status --short` and inspect the current branch. The
workspace may contain intentional uncommitted work; preserve unrelated and
user-owned changes. Search with `rg`, edit additively with `apply_patch`, and
prefer small named functions plus composition over new records, protocols, or
strategy datatypes.

Maintain these boundaries:

- compilation declares topology; evaluation and message production belong to
  the runtime;
- the compound environment is the sole semantic lexical authority;
- compiler symbols expose raw/canonical cells, not materialized accessors or
  scope envelopes;
- the CPS compiler and trampoline are the production compiler;
- delayed closure, lazy, list, and sub-environment paths must retain the
  selected compiler;
- compiler-2 closures apply through the retained application and
  p:apply-closure path;
- cells merge partial information and expose a strongest projection; do not
  move TMS, event, behavior, or provenance policy into the scheduler;
- external IO occurs only at a runtime boundary after propagation reaches
  quiescence;
- retained topology and TMS evidence are monotone; retraction changes support,
  it does not delete history.

When extending the system, first ask whether the feature is:

1. an ordinary operator (prefer an environment binding),
2. a compiler syntax/lowering rule (only if raw syntax matters),
3. a retained language closure (prefer network/compound/def-net),
4. a kernel compound propagator (only for a host-level subnet transformer),
5. a boundary effect (declare it in propagation, execute it in the runtime),
6. or an inspection projection (do not couple it to compilation or TUI state).

Do not claim success from `clojure -M:test` alone. Run the focused suite for
the changed layer and check the explicitly unregistered .lain loader suite.
Document any baseline failure rather than changing an expectation until the
runtime value and topology prove the expectation wrong.
```

## Current repository state

At this snapshot the active development branch is
`codex/tms-indexed-content-experiment`. It includes the namespace organization
that moved reusable live-runtime code from `graph/compiler_2_runtime/**` to
`propagators/compiler_2/runtime/**`. The graph namespace remains a deprecated
compatibility facade and presentation/server code remains under `graph`.

Do not recreate or reverse that move. Re-run `git status --short` and inspect
the current commit because the branch name and status in this paragraph will
eventually age.

The most recent receipts on that working tree were:

- registered full manifest: 2,225 assertions, zero failures/errors;
- compiler-focused gate: 596 assertions, zero failures/errors;
- environment-IO plus runtime-server gate: 339 assertions, zero
  failures/errors;
- semantic REPL: 35 assertions, zero failures/errors;
- explicit `.lain` loader suite: 11 assertions passed, 3 failures and 2 errors.

The `.lain` suite is not registered in the full manifest, so “the full suite is
green” is not equivalent to “every known gate is green.” Its known failures are
listed under Remaining problems.

## Mental model

The system has four deliberately separate stages:

```text
source
  -> parser and AST
  -> CPS compiler declares immutable network topology
  -> scheduler evaluates named propagators to quiescence
  -> runtime projects cells and performs boundary effects
```

A `Net` contains a graph, an environment of cells/propagators, and a dictionary
of annotations/indexes. Cells retain partial information. A propagator reads
cells and returns messages; it does not mutate the scheduler or live session.
Cell merge retains information, while `network-cell-strongest` returns the
currently usable projection.

The compiler's internal traversal is continuation based:

```clojure
compile-k [state expr k] -> thunk
k         [state binding] -> thunk-or-result
compile*  [state expr]    -> [state binding]
```

`compile*` is synchronous only at its public boundary: it trampolines CPS
thunks, so deeply nested compiler traversal does not consume the JVM stack.
Its assembly is in `propagators.compiler-2.cps-core`; handlers are ordinary
functions in `compiler/handlers.clj` and dispatch is composed with
`cps/on`/`cps/compose-rules`.

The compiler produces topology, result cell IDs, propagator IDs, retained
application IR, annotations, and stable path-derived IDs. Running those
propagators is an explicit later operation.

## Namespace map

The canonical ownership is:

- `propagators.compiler-2.main`: public compatibility facade.
- `propagators.compiler-2.cps-core`: production compiler assembly and compile
  entrypoints.
- `propagators.compiler-2.compiler.predicates`: AST predicates.
- `propagators.compiler-2.compiler.handlers`: CPS traversal handlers.
- `propagators.compiler-2.compiler.declarations`: topology declarations and
  lowerings.
- `propagators.compiler-2.compiler.basis`: stable IDs, default operators, and
  operator construction helpers.
- `propagators.compiler-2.language`: parser and slot-backed AST.
- `propagators.compiler-2.model`: environments, closure values, application
  values, context, and operator values.
- `propagators.compiler-2.runtime`: public reusable session facade.
- `propagators.compiler-2.runtime.application`: retained application
  evaluation and sub-environment execution.
- `propagators.compiler-2.runtime.closure-frame`: compiler-2 closure frame
  declaration and `p:apply-closure` integration.
- `propagators.compiler-2.runtime.tui`: block models, rewrites, version history,
  and versioned commits.
- `propagators.compiler-2.runtime.boundary`: runtime effect evaluation.
- `propagators.compiler-2.runtime.inspection`: cells, trace subscriptions, and
  temperature data.
- `propagators.compiler-2.operators`: call graph, distributed TMS, block
  premises, versioned definitions, and behavior bridges.
- `propagators.propagator`, `propagators.network`,
  `propagators.network-builder`, and `propagators.core`: kernel construction and
  evaluation.
- `graph.*`: presentation, Charm TUIs, socket servers, XR adapters, and the
  still graph-owned semantic projector/REPL.

The old synchronous compiler namespaces are deprecated compatibility shims.
Do not make production code depend on them.

## Supported compiler-2 expressions

`compile-source` reads exactly one source form. Multiple body expressions are
represented internally as a sequence and compile left-to-right. The surface
form `do` is intentionally not supported.

| Expression | Meaning and important output rule |
|---|---|
| literals | Seed a new result cell with the literal. |
| symbols | Resolve the nearest lexical binding or reserve a free input cell. The result is a raw/canonical cell. |
| ordinary lists, for example `(+ x 1)` | Compile operator and operands left-to-right, retain application IR, then declare/install the operator topology. |
| `(let-cell [a b] body...)` | Declare fixed local cells in a child lexical frame; final result escapes, compiler environment is restored afterward. |
| `(let [a expr ...] body...)` | Pure lowering into compiler-2 declarations and sequence traversal. |
| `(when condition body...)` | Delay topology installation until the condition has a usable value. This is a topology gate, not Clojure truth-only branching: `false` is usable. |
| `(:: [inputs] body...)` | Anonymous implicit-output retained closure. |
| `(cell [inputs] body...)` | Alias shape for an implicit-output retained closure. |
| `(network [inputs] [outputs] body...)` | Anonymous retained closure with explicit applicant output cells. |
| `(compound [inputs] output body...)` | Retained closure with one symbol or vector of explicit outputs. A map spec `{:inputs [...] :output ...}` is also accepted. |
| `(def-net name [inputs] [outputs] body...)` | Define a named explicit-output retained closure. |
| `(def-constraint name [applicants] body...)` | Define a callable constraint; applicants remain addressable cells and the last applicant is its result convention. |
| `(def name)` | Declare a free/storage cell. |
| `(def name expr)` | Compile `expr` and define `name` to its binding. |
| `(def-cell name)` / `(def-cell name expr)` | Storage/value declaration aliases. |
| `(def-cell name [inputs] body...)` | Define an implicit-output callable cell closure. |
| `(def-cells a b ...)` | Lower to a sequence of free cell declarations. |
| `(if condition then else)` | Lower to application of the `if` operator. |
| `(cond [c1 e1 ... else e])` | Lower recursively to `if`/`switch` applications. |

Parser preprocessing accepts only `::` as a list head. `be:/` is not reader
syntax; use `be:divide` in an environment that explicitly binds behavior
arithmetic. `do` is explicitly removed and produces a parser error. Former
parser spellings such as `def-behavior`, `app->`, `let-network`, and
`let-compound` have no parser semantics and are ordinary applications. Vectors
are recursively parsed as syntax carriers; do not assume arbitrary top-level
vector data is a supported literal.

Closure output semantics matter:

- an implicit-output closure gets a hidden return symbol and compiler-level
  routing from the final body result;
- a single explicit output receives the final body result automatically;
- multiple explicit outputs must be wired by the body;
- an explicit-output network call must include its output applicant cells;
- declaring a closure is data-only and installs no body propagators until the
  closure is applied.

The default operator environment contains arithmetic/comparison/string
operators (`+`, `-`, `*`, `/`, `<`, `<=`, `>`, `>=`, `=`, `not`, `str`),
control (`switch`, `if`, `branch`), type/value predicates, list/compound
operators (`list`, `cons`, `car`, `cdr`, `p:cons`, `p:slot`, `p:car`, `p:cdr`),
transport (`->`, `<->`), `execute-sub-env`, `call-graph`, and distributed-TMS
operators. Runtime environments add block, trace, widget, client, XR, and other
boundary-oriented operators.

## Compile and run a form

Compilation and propagation are separate:

```clojure
(require '[propagators.compiler-2.main :as compiler]
         '[propagators.network-builder :as nb]
         '[propagators.network :as net])

(def compiled (compiler/compile-source "(+ 2 3)"))
(def settled (nb/run-propagators (:net compiled) (:props compiled)))

(net/network-cell-strongest settled (:cell compiled))
;; => 5
```

Useful compiled-map fields are `:net`, `:env`, `:cell`, `:props`, and
`:applications`. Public accessors and `g:compile`, `g:apply`, and `g:advance`
remain compatibility surfaces; new implementation code should use the
responsibility-specific namespaces.

## Extend the language with an ordinary primitive operator

Most new operations do not need a compiler handler. Bind an operator value in
a compiler environment. `basis/primitive-operator` is the smallest supported
entrypoint for a pure, single-output host function:

```clojure
(require '[propagators.compiler-2.main :as compiler]
         '[propagators.compiler-2.compiler.basis :as basis]
         '[propagators.compiler-2.model.env :as env]
         '[propagators.network-builder :as nb]
         '[propagators.network :as net])

(def custom-env
  (env/bind-at (basis/default-env)
               'square
               (basis/primitive-operator #(* % %))
               0))

(def compiled (compiler/compile-source "(square 4)" custom-env))
(def settled (nb/run-propagators (:net compiled) (:props compiled)))
(net/network-cell-strongest settled (:cell compiled))
;; => 16
```

This wrapper deliberately does more than `apply`:

- waits for partial inputs;
- understands compiler scope, dependency, event, behavior, and distributed-TMS
  layers at the operator boundary;
- preserves upstream dependencies and TMS support in its result;
- lifts event updates when inputs are event content.

For explicit ports, multiple outputs, contextual dependencies, or custom
message semantics, use
`propagators.compiler-2.model.operator-value/propagator-operator`:

```clojure
(operator-value/propagator-operator
 {:name 'my-operator
  :input-selector  (fn [arg-ids fallback-id context-id] ...)
  :output-selector (fn [arg-ids fallback-id] ...)
  :contextual? true
  :activate
  (fn [network input-ids output-ids context-id]
    ;; Return a vector of propagators.message/message values.
    ;; Do not mutate or recursively run the live scheduler here.
    ...)})
```

Selectors declare the ports; `:activate` evaluates them. Always give the
operator and installed propagator a semantic name so call graphs and activation
profiles remain intelligible.

Use `operator-value/operator-closure` only when the operator also needs a
special install or compiler strategy. `:direct-compiler` has CPS contract
`[compile-k state operand-forms out-id k] -> thunk`; it is appropriate when
raw operand syntax matters, as for compiler-built lists. A legacy
`:direct-installer` is an opaque synchronous fallback and cannot make arbitrary
recursive installer code stack safe. Avoid either hook for an ordinary value
operator.

### Kernel-level primitive propagator

Below compiler-2, a named primitive is an installer over cell IDs:

```clojure
(prop/construct-propagator
 :example/square
 (prop/concrete-propagator
  (fn [[in-id] [out-id] network]
    [(message out-id
              (let [x (net/network-cell-strongest network in-id)]
                (* x x)))]))
 [in-id]
 [out-id])
```

The installer has shape `(fn [network] [prop-id network'])`; activation has
shape `[input-ids output-ids network] -> messages`. Ensure cells, install the
propagator, then run it explicitly with `nb/run-propagators`.

`prop/primitive-propagator` is the low-level variadic single-output helper.
`prop/concrete-primitive-propagator` waits until every input is usable. These
helpers do not automatically implement compiler-2's layered/TMS conventions,
so prefer `basis/primitive-operator` for a language operator.

## Extend the system with a compound propagator

“Compound propagator” currently names two distinct layers. Choose deliberately.

### Preferred: a compiler-2 retained network closure

Use the language closure forms for reusable topology:

```clojure
(def-net sum2 [x y] [out]
  (-> (+ x y) out))

(let-cell [answer]
  (sum2 2 3 answer)
  answer)
```

For one implicit output:

```clojure
(def-cell square [x]
  (* x x))

(square 5)
```

The closure value stores inputs, outputs, body AST, and captured live lexical
environment. Application retains IR, creates an addressed closure frame, and
uses the existing compiler-2 `p:apply-closure` path. Delayed compilation starts
its own trampoline using the captured compiler. Extend that path rather than
creating a second closure evaluator.

Use explicit outputs when the network relation is naturally multi-directional
or multi-output. Use an implicit output when the last expression is the
function-like result.

### Lower-level: a kernel compound propagator

`propagators.propagator/compound-propagator` installs a host-level propagator
whose closure cell contains `propagators.closure/Closure`. On activation it:

1. builds input/output avatars,
2. applies the closure's network transformer,
3. evaluates the internal network to quiescence,
4. diffs internal output cells into outer messages.

Construct values with `closure/closure`, `closure/primitive-closure`, or
`closure/guarded-primitive-closure`; install with
`closure/p:apply-closure`. This API is useful for a reusable subnet transformer
outside compiler-2. It is not the representation of compiler-2's retained
language closures, so do not substitute it into compiler-2 application code.

## Add a new compiler syntax form

Only add syntax when the compiler must see structure that an ordinary operator
cannot express.

1. Add or reuse an AST constructor/accessor in `language/ast.clj`.
2. Parse and validate the form in `language/parser.clj`.
3. Add a named predicate in `compiler/predicates.clj`.
4. Add a small CPS handler in `compiler/handlers.clj`.
5. Add its `cps/on` rule to `cps-core/compiler-dispatch` before the application
   fallback.
6. Put topology construction/lowering in `compiler/declarations.clj`, not in
   traversal code.
7. For child traversal use `cps/call`, `cps/continue`, `cps/compile-seq`, or
   `cps/compile-args`; never call the CPS function recursively on the JVM stack.
8. Test path restoration, left-to-right state threading, delayed activation,
   a local compiler override, and a depth case when the form is recursive.

## Premises, alternatives, retraction, and premise closures

Compiler-2's default environment uses distributed TMS. It represents claims
and premise states as monotone partial information:

- `premise-input value premise epoch [out]` adds a value claim supported by a
  premise;
- `premise-content-input` does the same while preserving the full upstream
  cell content;
- `premise-believe premise epoch out` makes that premise active at a newer
  epoch;
- `premise-retract premise epoch out` makes it inactive at a newer epoch;
- `tms-closure closure` preserves distributed input state across a closure;
- `premise-closure closure premise epoch` and
  `distributed-premise-closure` attach definition support to a callable
  closure without changing the underlying closure evaluator.

The strongest distributed projection follows ordinary TMS rules:

- no active supported claim: `nothing`;
- one active claim: its value;
- multiple active claims agreeing: that value;
- multiple active claims disagreeing: contradiction.

Claims and old premise facts are retained. Retraction does not remove their
cells or topology; the newest epoch for a premise changes which claims are
active.

This compact form proves alternative selection:

```clojure
(let-cell [out]
  (premise-input 10 :choice/a 0 out)
  (premise-input 20 :choice/b 0 out)
  ;; With both active, out is contradiction.
  (premise-retract :choice/a 1 out)
  out)
;; strongest distributed base value => 20
```

To explore multiple procedures, premise their definitions:

```clojure
(let-cell [x out]
  (premise-input 5 :input/x 0 x)

  (def-net inc [x] [out]
    (<-> (+ x 1) out))

  (def apply-inc
    (premise-closure
     (network [f x] [out]
       (f x out))
     :definition/inc
     0))

  (apply-inc inc x out)
  out)
```

Sending a later `premise-retract :definition/inc 1 out` makes the result
unsupported; `premise-believe :definition/inc 2 out` brings it back. Input and
definition supports are unioned, so retracting either support can remove the
current projection.

Important limitation: this is not an automatic search or backtracking engine.
The network can retain mutually exclusive possible claims, expose agreement or
contradiction, and react to premise updates. A user, controller, or future
reasoner must decide which premises to believe/retract. `amb`, automatic branch
enumeration, and a solution-search policy are not implemented.

The versioned TUI builds on the same model. A block version has premise
`[client-id block-id version]`; commits append history, retract the previous
version premise, and retain old topology. Block-level editable definitions use
stable public bindings plus retained versioned candidates. Explicit
`premise-closure` forms are detected structurally and are not double wrapped.

## Inspect closure call graphs

`propagators.compiler-2.operators.call-graph` is an additive reactive
propagator. It combines:

- potential call sites extracted from a retained closure's body AST;
- realized calls published from retained application IR.

At language level:

```clojure
(call-graph (:: [x] (+ (* x 2) 1)))
```

or with an explicit destination:

```clojure
(let-cell [f graph]
  (def-net f [x] [out]
    (+ x 1))
  (call-graph f graph)
  graph)
```

The result is a `semantic-trace/graph-union` value with nodes, edges, and
`:call/status` values of `:potential` or `:realized`. Recursive calls form a
finite cycle. If an operator cell arrives later, the graph refines reactively.

For pure syntax inspection without running a network, call
`call-graph/call-sites` on an AST. For lower-level installation use
`call-graph/p:call-graph closure-id out-id`.

Call graph is structural evidence. It is not a chronological activation log
and it does not prove a call produced a value.

## Inspect runtime execution and history

There is intentionally no single generic “history plug-in propagator” yet.
Use the observation plane that answers the actual question:

| Question | Current tool |
|---|---|
| What may/can this closure call? | Reactive `call-graph` propagator. |
| Which propagator tasks ran and what messages did they emit? | `propagators.debug/run-tasks-debug`. |
| Which propagator is repeatedly activated or slow? | `activation-profile`, `with-activation-profile`, and `activation-profile-report`. |
| What did clients commit over time? | Append-only versioned TUI commit log/version history. |
| How did a semantic value vary over logical time? | Event/behavior history data structures, not the task scheduler log. |
| What graph is upstream of a runtime target? | Language/runtime `trace` subscription and semantic graph projection. |
| What is the accumulating GUR runner doing? | Dynamic observers in `gur.accumulating.runner.instrumentation`. |

### Step-by-step task trace

```clojure
(debug/run-tasks-debug
 prop-ids
 network
 {:max-steps 1000
  :log-fn println
  :snapshot (fn [n]
              (str "out=" (net/network-cell-strongest n out-id)))})
```

This is an extension runner over the same cells and propagators; it does not
patch `eval-propagator`. It logs named tasks, inputs, outputs, emitted messages,
routes, and newly queued neighbors.

### Activation hot-path profile

```clojure
(def profile (debug/activation-profile))

(def final-net
  (debug/with-activation-profile profile
    (nb/run-propagators network prop-ids)))

(def report (debug/activation-profile-report profile))
(debug/describe-profiled-propagator
 final-net
 (first (:by-propagator report)))
```

The profile ranks propagator IDs and semantic names by calls and inclusive/
exclusive time. The implementation dynamically substitutes the task runner
inside the scope while preserving `core/eval-propagator`; it is diagnostic,
not production state.

`propagators.debugger/with-debugger` plus `set-sink!`/`report!` is a smaller
generic dispatch hook. It currently helps generic/application dispatch but is
not a complete compiler-2 execution history.

The accumulating GUR runner exposes `*prop-run-observer*` and
`*phase-observer*`. Bind them around a run to collect structured scheduling and
phase events without changing kernel evaluation.

Runtime language `trace` is a semantic upstream-graph subscription. Its
implementation is currently a hybrid boundary worker that writes graph updates
back into cells. Treat it as inspection data, not as compiler state. The open
design for a first-class async worker propagator is documented in
`todo/async-propagator-trace-runtime.md`.

## Write and load source files

`compile-source` accepts exactly one form. A `.lain` file is the runtime source
format for multiple forms. It accepts either consecutive top-level forms:

```clojure
(def-cells a b out)
(-> 20 a)
(-> (+ a 10) out)
```

or one outer list containing all top-level forms.

Programmatic loading uses the canonical runtime namespace:

```clojure
(require '[propagators.compiler-2.runtime :as runtime]
         '[propagators.compiler-2.runtime.session.file-loader :as loader])

(def session (runtime/new-session))
(loader/load-file! session "examples/lain/demo.lain" {:client-id "file"})
```

Useful pure/host helpers are:

- `loader/source-forms`: read all top-level forms;
- `loader/normalized-source`: normalize them for runtime compilation;
- `loader/load-source!` and `loader/load-file!`: mutate a supplied session
  atomically through the runtime source path;
- `loader/load-session-from-file`: create and populate a session;
- `loader/load-server-instance`: create a server instance from a file.

The socket server accepts `{:op :compile/load-file :file "..." :client-id
"..."}`. Its CLI supports:

```bash
clojure -M:wired/server \
  --load examples/lain/demo.lain \
  --load-client file \
  --load-blocks 1
```

Use `examples/lain/demo.lain`, `multi-client-messaging.lain`, and
`slider-panel.lain` as examples, but remember that the loader suite currently
has known failures.

### Export and replay a versioned live instance

The server also exposes line-delimited JSON, default port `45556`:

```bash
clojure -M:wired/server --json-port 45556
clojure -M -m graph.compiler-2-runtime-server json-export 45556 instance.json
clojure -M -m graph.compiler-2-runtime-server json-import 45556 instance.json
```

The authoritative JSON data is the ordered list of registered versioned
clients and successful commits. The snapshot is diagnostic and is never
imported. Exact replay is mutation-free; partial/divergent history is rejected;
import builds a private candidate session and publishes only if all commits
succeed.

For repository edits, create or change files with `apply_patch`, preserve the
dirty worktree, and avoid broad formatting or namespace rewrites unless the
task explicitly owns them.

## Versioned live-runtime semantics

The additive versioned client registers with mode `:versioned-premise`.
Successful commits are idempotent by client-generated commit ID and append an
immutable version record. The premise includes client identity, so different
clients can share one environment without premise collisions.

The block compiler is a local Meander term rewrite over canonical CPS:

- ordinary applications are wrapped in a dependency-declaration term;
- block-level definitions become stable versioned definition candidates;
- definitions inside closure bodies remain local to that closure version;
- explicit premise closures remain explicit;
- raw closure/operator/environment/compound values are not wrapped;
- scalar results pass through named block-premise/display gates.

Automatic result display uses one stable target block cell. Versions merge TMS
claims into it; the TUI reads the strongest projection after quiescence. It
does not advance an outbox epoch to choose a winner. External effects still use
the boundary outbox.

Editable callable definitions keep a stable public router and registry. New
candidates are installed through retained application and `p:apply-closure`;
old candidates and topology remain. Signature mismatches allocate stable
placeholders and attach warnings instead of destructively rewiring history.

This is monotone history, not garbage collection. Previously performed
irreversible IO cannot be retracted.

## Known problems and design gaps

These are current problems, not permission to redesign the kernel.

### 1. `.lain` runtime loader gate is red

The explicit suite
`propagators.compiler-2.runtime.session.file-loader-test` currently reports 11
passing assertions, 3 failures, and 2 errors:

- three display expectations receive `:bool4/nothing` instead of demo output;
- two lookups return nil and later fail with `expected node-id token {:x nil}`:
  binding `d` in the slider example and binding `f` in the block-by-block
  `def-net` example.

Determine whether the loader's form/session boundary loses lexical declarations
or whether tests inspect an inner binding from the restored outer environment.
Do not merely register or suppress this suite.

### 2. Premise support does not consistently follow canonical named cells

The live form:

```clojure
(def-cells a b c d)
(-> (+ a 10) c)
(-> c (block 5))
(-> 20 a)
(-> c (block 7))
```

can compute raw `a = 20` and `c = 30` while block 7 remains `nothing`.
Canonical cells carry raw values and dictionary premise metadata, but the
support is not always transported through `->`, `+`, and the explicit block
effect path. This is a compiler/runtime integration gap. The likely local seam
is support-preserving premise-aware writes/transport, not a scheduler rewrite.

### 3. Lexical authority still has a rejected dual-representation seam

The intended authority is the compound lexical frame. Current fast paths can
also consult dictionary canonical-address metadata. A future repair must
establish one declaration primitive that records a binding descriptor in the
compound frame, then make direct-address lookup a derived cache whose removal
cannot change semantics.

The correctness baseline is:

```text
p:declare-binding
  -> p:lexical-access-local-first
  -> p:binding-value
```

Do not restore reducer inheritance, attach scope envelopes to ordinary values,
or make the dictionary a second lexical environment.

### 4. Trace is not yet a first-class asynchronous runtime primitive

Trace subscriptions currently bridge through runtime-managed workers and
write graph updates back to cells. Replacement/removal/edit lifecycle coverage
is incomplete. A first-class worker/effect protocol should be designed at the
runtime boundary before changing the scheduler.

### 5. Application topology is not fully declarative before operator arrival

A retained application may learn its real output/write set only after the
operator cell resolves. That weakens semantic graph reachability and blocks a
sound future GC analysis. The application IR should eventually expose its
potential and realized write set without moving evaluation into the compiler.

### 6. Ordinary same-name redefinition is not dynamic rebinding

An unresolved application can repair against the first later definition. An
already resolved ordinary application remains wired to its original binding.
The versioned TUI's stable definition registry provides editable block-level
semantics; do not silently apply that policy to all lexical `def` forms.

### 7. Retained-history performance is linear and some workloads are expensive

Topology grows linearly by design because versions, claims, and propagators are
retained. The paired versioned definition/application benchmark at 50 edits
reported about 55 seconds total commit time, 8,667 cells, and 6,271 propagators.
A later input after 50 definition candidates took about 52 ms and activated
504 propagators. Dormant candidates still receive shared caller inputs; premise
gates suppress unsupported outputs after activation.

This is acceptable prototype behavior, not a proof of scalability. A local
future optimization is to route public arguments only to supported candidate
private boundaries. Do not add a global scheduler epoch or delete old TMS
evidence to hide the cost.

The recursive compiler-2 linked-list workload has also shown very high costs in
older receipts. Profile named propagators before selecting an optimization.

### 8. Compiler-2 behavior-history integration is deferred

The separate behavior compiler, behavior data structures, and isolated history
tests remain supported. Several compiler-2 behavior arithmetic/closure
integration tests are reader-discarded. Do not make behavior a prerequisite for
ordinary compiler-2 arithmetic, event display, TMS, or trace.

### 9. Module boundary is improved but incomplete

Reusable runtime code is moving under `propagators.compiler-2.runtime`, but the
semantic graph projector/REPL remains graph-owned and is used by inspection.
Finish the current move before initiating another directory reorganization.

### 10. No garbage collection or automatic alternative search

Topology, claims, premise facts, and edit records are deliberately retained.
No GC proves unreachable historical topology yet. The TMS represents support
and alternatives but does not choose branches or backtrack automatically.

## Verification map

Run the smallest relevant gate first, then broaden:

```bash
# Production compiler, closures, CPS, composition, organization, lists/GUR
clojure -M:test \
  propagators.compiler-2-closure-frame-test \
  propagators.compiler-2-composition-test \
  propagators.compiler-2-cps-test \
  propagators.compiler-2-organization-test \
  propagators.compiler-2-gur-linked-list-test

# Core compiler, behavior compiler, distributed TMS
clojure -M:test \
  propagators.compile-2-test \
  propagators.behavior-compiler-test \
  propagators.tms-test

# Call graph
clojure -M:test propagators.compiler-2-call-graph-test

# Versioned runtime (names now live under propagators.compiler-2.runtime.*)
clojure -M:test \
  propagators.compiler-2.runtime.tui.block-compiler-test \
  propagators.compiler-2.runtime.tui.versioned-commit-test \
  propagators.compiler-2.runtime.session.instance-replay-test

# Presentation/runtime integration
clojure -M:test \
  graph.vijual.compiler-2-semantic-repl-test \
  graph.vijual.compiler-2-runtime-server-test

# Explicitly unregistered known-red gate
clojure -M:test \
  propagators.compiler-2.runtime.session.file-loader-test

# Registered manifest and whitespace check
clojure -M:test
git diff --check
```

When a test expectation is questioned, inspect all three facts before changing
it: the result cell's content/strongest value, the installed topology and named
propagators, and the intended environment frame. A green assertion against the
wrong environment is not correctness.

## Rules for future work

1. Prefer adding a named constructor/combinator over adding conditionals to a
   large existing function.
2. Prefer an environment-bound operator over a compiler special form.
3. Keep parsing, lowering, declaration, evaluation, and boundary effects in
   separate namespaces/functions.
4. Keep cells and closure/operator values raw; add semantic layers at explicit
   operator or boundary points.
5. Never materialize lexical accessors as the normal symbol result.
6. Never unwrap TMS/provenance inside transport propagators merely to make a
   display pass; preserve partial information to the host boundary.
7. Name every propagator that may appear in a benchmark or trace.
8. Preserve local compiler capture through all delayed work.
9. Treat stable IDs and dictionary annotations as public compatibility where
   tests already depend on them.
10. Benchmark after correctness, report topology and activation counts as well
    as wall time, and do not introduce an arbitrary timing threshold.
11. Pause and document before changing scheduler, cell protocol, lexical frame
    model, or closure runtime. Those are architectural decisions, not local
    fixes.

## Canonical evidence files

- `test/propagators/compile_2_test.clj`: broad compiler, closure, event, and TMS
  language evidence.
- `test/propagators/compiler_2_closure_frame_test.clj`: topology closure
  application and lexical frames.
- `test/propagators/compiler_2_call_graph_test.clj`: potential/realized/reactive
  call graph behavior.
- `test/propagators/compiler_2/runtime/tui/versioned_commit_test.clj`: editable
  definition, premise, and commit transaction behavior.
- `test/propagators/compiler_2/runtime/session/instance_replay_test.clj`: JSON
  replay and TMS-aware block output.
- `propagators/doc/compiler-2-versioned-tui.md`: versioned runtime semantics and
  benchmark receipts.
- `propagators/doc/compiler-2-progress-and-priorities.md`: lexical/runtime
  decisions and deferred work.
- `propagators/doc/multi-client-runtime-bottleneck.md`: watcher/runtime scaling
  evidence.
- `todo/async-propagator-trace-runtime.md`: trace worker boundary design gap.
