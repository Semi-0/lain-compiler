# Compiler 2

Source files:

- `propagators/compiler_2/parser.clj`
- `propagators/compiler_2/ast.clj`
- `propagators/compiler_2/core.clj`
- `propagators/compiler_2/application.clj`
- `propagators/compiler_2/closure_value.clj`
- `propagators/compiler_2/context.clj`
- `propagators/compiler_2/env.clj`
- `propagators/compiler_2/helpers.clj`
- `propagators/compiler_2/tms_behavior.clj`
- `propagators/datastructures/scope_source.clj`
- `propagators/datastructures/dependency.clj`
- `propagators/gur.clj`
- `test/propagators_compile_2_test.clj`
- `test/propagators/compiler_2_gur_linked_list_test.clj`

## Status

Compiler 2 treats compilation as network expansion. It does not turn an AST into
an opaque runtime function. It declares cells, retained IR data, slot topology,
application propagators, and closure data inside the propagator network.

The current surface language is intentionally small:

```clojure
(+ 1 2)

(let-cell [out]
  (<-> out (+ a 2))
  out)

(:: [x]
  (+ x 1))

(cell [x]
  (+ x 1))

(network [x] [out]
  (<-> (+ x 1) out))

(def-net inc [x] [out]
  (<-> (+ x 1) out))

(def apply-out
  (distributed-premise-closure
    (network [f x] [out]
      (f x out))
    premise-id
    epoch))
```

`::` and `cell` are zero-output closure forms: applying them returns the
closure body's result cell. `network` and `def-net` are declared-output network
forms: applying them requires explicit output cells as the tail of the applicant
list. The parser still produces AST data; `core.clj` compiles that AST directly.

For example:

```clojure
(let-cell [same next]
  ((network [x] [same next]
     (<-> x same)
     (<-> (+ x 1) next))
   4 same next)
  next)
```

The call above supplies `x`, `same`, and `next` as applicants. The network body
declares relationships among those cells. Returning `next` is a separate source
expression; the network call does not synthesize a hidden result object with
`same` / `next` slots.

Distributed TMS is the default compiler-2 path. The compiler-facing TMS
operators live in `propagators.compiler-2.tms`, behavior operators live in
`propagators.compiler-2.behavior`, and the old
`propagators.compiler-2.tms-behavior` namespace is now only a compatibility
facade. `default-env` binds distributed premise/content inputs, premise
believe/retract, `tms-closure`, and distributed `premise-closure`.
`distributed-premise-closure` remains as the explicit long name for the same
operator.

The behavior+TMS env also binds `behavior-point`, which constructs a behavior
value from ordinary compiler-2 source. Because it is just an operator, it can be
wrapped in a normal compiler-2 network closure:

```clojure
(let-cell [a b out]
  (def-net make-point [t v] [out]
    (behavior-point t v out))
  (make-point 6 2 a)
  (make-point 6 7 b)
  (<-> (+ a b) out)
  out)
```

For reducer-shaped behavior, the same env binds `behavior`, `behavior-event`,
`behavior-empty-state`, `behavior-add-event`, and `behavior-retain-last`.
`behavior` takes an event source and a compiler-2 network closure; each reducer
step runs that closure as the one-time merge network:

```clojure
(let-cell [events out]
  (def-net retain-event [acc update] [out]
    (behavior-add-event acc update out))
  (behavior-event 6 2 events)
  (behavior-event 8 3 events)
  (behavior events retain-event (behavior-empty-state) out)
  out)
```

The merge network can also be written from lower-level behavior operators when
the policy should be explicit in compiler-2 source:

```clojure
(def-net retain-event-low [acc update] [out]
  (let-cell [known tick value next]
    (behavior-state-events acc known)
    (behavior-update-tick update tick)
    (behavior-update-value update value)
    (behavior-assoc-event known tick value next)
    (behavior-state-from-events next out)))
```

Changing the reducer closure changes retention policy while keeping the same
behavior construction path:

```clojure
(def-net retain-window [acc update] [out]
  (let-cell [full]
    (behavior-add-event acc update full)
    (behavior-retain-last full 2 out)))
```

The live runtime env also binds XR widget IO operators for browser/XR-driven
behavior sources. Widget IO does not let the browser write arbitrary cells; it
registers view/event-source pairs and the runtime injects epoch-keyed behavior
events only through those declared channels:

```clojure
(let-cell [a-events b-events c-events a b c out widget]
  (def-net retain-event [acc update] [out]
    (behavior-add-event acc update out))
  (behavior a-events retain-event (behavior-empty-state) a)
  (behavior b-events retain-event (behavior-empty-state) b)
  (behavior c-events retain-event (behavior-empty-state) c)
  (slider-panel-io "mix"
    "a" a a-events
    "b" b b-events
    "c" c c-events
    widget)
  (<-> (- (+ a b) c) out)
  out)
```

For a panel, each user edit re-emits the latest known channel values at one
runtime-owned epoch, so sparse behavior arithmetic receives aligned events
while still preserving the underlying event history.

`premise-closure` is sugar for a premise-marked network operator. It takes a
wrapped `network`, a premise cell, and an epoch cell. Application runs the
wrapped closure against an internal output cell, then emits only the
premise-marked distributed TMS update to the explicit output cell:

```clojure
(let-cell [x out]
  (def value 5)
  (def input-premise :premise/input)
  (def definition-premise :definition/inc)
  (def epoch0 0)
  (premise-input value input-premise epoch0 x)
  (def-net inc [x] [out]
    (<-> (+ x 1) out))
  (def apply-inc
    (premise-closure
      (network [f x] [out]
        (f x out))
      definition-premise
      epoch0))
  (apply-inc inc x out)
  out)
```

The sugar does not patch `p:apply-application`; it uses the ordinary primitive
operator metadata path and the existing closure application helper. For
declared-output networks, it marks the explicit output applicant, not the hidden
application result cell.

Later `(premise-retract definition-premise epoch1 out)` or
`(premise-retract input-premise epoch2 x)` adds new premise-state facts. Old
claim facts remain in the output cell; distributed strongest projection returns
`nothing` while the needed premise is inactive.

Repeated `def` / `def-net` / `def-cell` forms shadow the name with the newly
compiled value cell. They do not reuse the old name cell. That means an earlier
application keeps the operator cell it already referenced, while later
applications see the new binding:

```clojure
(let-cell [out]
  (def p-one :definition/plus-one)
  (def p-ten :definition/plus-ten)
  (def epoch0 0)
  (def op
    (premise-closure
      (network [f x] [out] (f x out))
      p-one
      epoch0))
  (op plus-one x out)
  (def op
    (premise-closure
      (network [f x] [out] (f x out))
      p-ten
      epoch0))
  (op plus-ten x out)
  out)
```

The two applications emit separate distributed TMS claims because the
premise-marked closure claim identity includes the premise. Retraction/bring-in
of `p-one` and `p-ten` selects the active definition through distributed TMS
projection.

Centralized reducer-cell TMS is still available as legacy compatibility through
`propagators.compiler-2.tms-behavior/legacy-central-tms-env` or the thin
`helpers/legacy-central-tms-env` export. That path overrides `premise-closure`
with the legacy centralized implementation, which emits TMS reducer-cell
claim/premise facts into an explicit storage cell:

```clojure
(let-cell [out]
  (def p :definition/inc)
  (def tms)
  (def apply-out
    (premise-closure
      (network [f x] [out]
        (f x out))
      p
      tms))
  (def-net inc [x] [out]
    (<-> (+ x 1) out))
  (apply-out inc 4 out)
  tms)
```

## Two-Stage Compilation Model

Compiler 2 now has an explicit conceptual split:

1. Retain an inspectable IR in the network.
2. Lower that IR into executable propagator topology.

Today those two stages still happen in one `compile-source` / `compile-expr`
call, but the data boundary is present. Every application gets an application
IR cell, and one uniform application propagator is installed alongside it.

`propagators.compiler-2.main/compile-source` remains the raw compiler-2 entry
and uses `helpers/default-env`. To compile with behavior arithmetic plus
distributed TMS primitives by default, use:

```clojure
(main/compile-source-with-behavior-tms source {:net n})
```

The matching AST entry is `main/compile-expr-with-behavior-tms`.

The retained application object has slots:

```clojure
{:application/operator-ast  operator-ast
 :application/operator-cell operator-cell
 :application/args          argument-object-cell-id
 :application/arg-cells     [arg-cell-ids...]
 :application/output        result-cell-id
 :application/context       context-cell-id
 :application/lowering      :primitive-or-closure-cell}
```

This is a declaration fact. It exists before scheduler evaluation and can be
inspected without running the application. Executable lowering is owned by
`compiler_2.application/p:apply-application`:

- primitive applications evaluate through operator metadata on activation
- closure-cell applications delegate to the closure application path
- both retain the same application IR shape and scheduler wakeup behavior

This removes the previous asymmetry where closure applications were inspectable
but primitive calls disappeared into lowered edges. The direct primitive
installer functions still exist as compatibility helpers, but compiler-2
application compilation no longer calls them directly.

## Closure Values Are Data

A compiler-2 closure cell stores a compound object, not a
`propagators.closure/Closure` runtime function:

```clojure
{:closure/env    lexical-env
 :closure/body   body-ast
 :closure/inputs [x y]
 :closure/output out-or-nil
 :closure/scope  lexical-scope}
```

The lexical environment is also attached with `compound-object/p:slot` under
`:closure/env`. This keeps closure data slot-backed while application dispatch
uses the retained closure value and scheduled argument cell ids directly.

Declaration and evaluation stay separate:

- closure declaration creates data and slot topology
- closure declaration does not compile or run the body
- closure application is the only place body evaluation happens

## Application Flow

`core/g:apply` has two declaration branches:

- primitive operators from the environment
- closure-valued operator cells

Both branches create the same application IR object and install
`p:apply-application`. Primitive operators are placed in an operator cell, so
the application propagator can read them like any other operator value.
Contextual primitives receive one hidden context cell in addition to user
operands at activation time. Primitive calls retain `:primitive` as the lowering
tag.

Closure-valued operators use the original operator cell. Arguments are recorded
in an argument object cell, and the application prop is wired to the application
cell, operator cell, argument object cell, argument cells, context cell, and
output cell. For zero-output closures, that output cell is the implicit result
cell. For declared-output `network` / `def-net` closures, the formal output
symbols are bound to the tail argument cells instead. Closure calls retain the
same application IR object shape with a `:closure-cell` lowering tag. That
wiring is important: later operator or argument updates wake the same
application through normal scheduler adjacency.

At activation time `p:apply-application`:

1. reads the retained application object
2. reads the operator cell
3. dispatches by operator value
4. for primitive operators, emits primitive result messages directly
5. for closure values, delegates to the closure application path

For zero-output closure values, the closure application path:

1. reads the retained closure value and scheduled argument cell ids
2. waits while the closure value or required input argument values are unusable
3. creates one activation network containing:
   - input boundary avatars
   - an output boundary avatar
   - body-local cells and body propagators
   - a result-to-output adapter propagator
4. runs that activation network to quiescence
5. diffs only the inner output avatar back to the outer output cell

This is the boundary that prevents inner local variables from writing to outer
cells except through the declared output/result. The result-to-output adapter is
a direct propagator link from the body result cell to the boundary output avatar;
it no longer materializes host compound records.

For declared-output network values, the runtime first splits application
applicants into input cells and output cells. Only input cells must have values
before activation. Output cells are boundary outputs, so late output flow is not
blocked by their initial `nothing` value. The declared output symbols bind to
those explicit output cells; no structural output slots are created under the
application result.

## Runtime Blocks And Semantic Tracing

The current runtime prototype is in:

- `graph/compiler_2_runtime.clj`
- `graph/compiler_2_runtime_server.clj`
- `graph/compiler_2_tui.clj`
- `graph/compiler_2_semantic_repl.clj`
- `propagators/semantic_trace.clj`

The runtime owns one growing compiler-2 program environment. Multiple
TUI/socket clients can connect to that same runtime. Each client instance owns
its own block list and view state; it is not a shared document UI. Authored
blocks from those instance-local lists are still compiled into the same runtime
env/net, so definitions from one instance can become part of the shared program
state seen by later rebuilds.

What exists now is a live runtime surface, not just a REPL transcript:

- blocks are runtime cells in linked instance-local block lists;
- `(def name)` creates a named free cell that later blocks can constrain;
- `def`, `def-net`, and `def-cell` bind names to newly compiled value cells;
  later definitions shadow earlier bindings instead of mutating the old name
  cell;
- `block-at` lets compiler-2 code read and write block cells through the same
  propagator network;
- `trace` produces a semantic graph value that can itself be stored in a block
  cell and rendered by the TUI;
- trace blocks are recompiled after a full rebuild so they can react to later
  upstream relationships;
- trace traversal follows semantic nodes backed by the same runtime cell across
  different source blocks, so a chain like `(+ 1 2) -> b -> a` appears when
  tracing upstream of `a`;
- clients can communicate through block cells by writing values or graph traces
  into another client's block with `block-at` and `instance`;
- TUI rendering uses Vijual stress-majorization with the current compiler-2
  semantic opts: spacing `1.7`, stress iterations `200`, refine iterations
  `200`.

Normal blocks contain compiler-2 source. There are no runtime source special
forms: authored block text always goes through compiler-2. Runtime reflection is
available only through operators installed into the compiler environment. Each
client instance is bound by client id, and `%` is bound to the instance that owns
the block currently being compiled.

```clojure
(let-cell [v]
  (block-at % 1 v)
  (block-at (instance tui-b) 1 v)
  v)

(let-cell [next g]
  (inc1 4 next)
  (trace next g)
  (block-at (instance tui-b) 1 g)
  g)
```

`block-at` is a compiler primitive operator over an instance's linked block
list. Blocks are dumb cells: text, graph values, contradictions, and `nothing`
are displayed directly from the block cell. No runtime path creates generated
output blocks or copies compiler results into display blocks.

For REPL ergonomics, a normal expression block with a following block is compiled
with an implicit language-level output relation to that following block. Typing:

```clojure
(+ 1 2)
```

behaves like:

```clojure
(let-cell [out]
  (<-> (+ 1 2) out)
  (block-at % 1 out)
  out)
```

Top-level `def-net` blocks are not wrapped, so definitions still extend the
growing compiler environment rather than being hidden inside a local cell scope.

A second instance can use a network declared by the first instance, trace a
local application cell, and write the graph into one of its own blocks with
normal compiler-2 code:

```clojure
(let-cell [next g]
  (inc1 4 next)
  (trace next g)
  (block-at (instance tui-b) 1 g)
  g)
```

`trace` is also a compiler primitive operator; direction defaults to upstream,
and `(trace next :downstream g)` follows outgoing semantic graph edges.
Rendering is not part of propagation: trace propagators produce graph data, and
the TUI/view layer renders graph values with Vijual stress-majorization layout.
The trace graph is topology plus projection data, not a cell-content dump. Cell
`content` is internal merge evidence; cell `strongest` is the readable truth.
TUI and XR renderers may show only strongest-derived lightweight summaries, and
runtime UI pulses should use explicit changed cell/node ids from the completed
transaction rather than diffing raw serialized cell values.
Top-level `(trace a :upstream g)` traces the semantic label `"a"` rather than
only the local input cell of the trace expression. Concrete cell tracing remains
available through explicit trace requests at the runtime API boundary.

There are also installed traces for reactive inspection. An installed trace
stores a tracing propagator with a clock/epoch cell. The epoch ticks on the
configured interval, so downstream traces can expand as the aggregate semantic
graph grows.

Useful commands:

```bash
clojure -M -m graph.compiler-2-semantic-repl \
'(let-cell [same next] ((network [x] [same next] (<-> x same) (<-> (+ x 1) next)) 4 same next) next)'

clojure -M -m graph.compiler-2-runtime-server server 45555

clojure -M -m graph.compiler-2-runtime-server request 45555 \
'{:op :compile/source :source "(let-cell [same next] ((network [x] [same next] (<-> x same) (<-> (+ x 1) next)) 4 same next) next)"}'

clojure -M -m graph.compiler-2-runtime-server graph 45555
clojure -M -m graph.compiler-2-runtime-server trace 45555 next

clojure -M -m graph.compiler-2-runtime-server request 45555 \
'{:op :semantic/trace/install :label "next" :direction :upstream :interval-ms 5000}'

clojure -M -m graph.compiler-2-tui 45555 tui-1
```

Shortcut aliases:

```bash
clojure -M:wired/server
clojure -M:wired/client -name A
clojure -M:wired/xr
```

`:wired/server` starts the shared compiler-2 runtime on the default port. When
compiled code installs an `xr-io` propagator and it emits an XR launch effect,
the server starts the XR/browser projection on demand against the same runtime
session.
`:wired/client` starts a TUI client against that runtime; `-name A` selects the
client instance name. `:wired/xr` starts a standalone browser/XR projection
server for isolated XR testing.

## Parallel GUR Linked-List Probe

`test/propagators/compiler_2_gur_linked_list_test.clj` is a prototype slice, not
the active compiler-2 lowering. It keeps the existing compiler path intact and
demonstrates the next target shape with canonical accumulating GUR and public
compound-object linked-list accessors.

The test builds the declaration source as cells plus `obj/p:cons` /
`obj/p:car` / `obj/p:cdr`:

```clojure
[:compound add-bias x + x bias]
```

It does not seed a materialized `subenv/cons-list-value` or read the source list
back into Clojure data during compilation. The experiment declarations use the
existing `compile/def-recursive` source DSL, which now targets accumulating
GUR. A small GUR compiler closure walks that linked-list declaration through
accessor topology, emits a GUR closure value, then applies that closure with
`propagators.gur/p:apply-closure`.

The compiled closure demonstrates lexical access without materializing the
environment. Its body receives an accessor-backed env cell, installs
`compiler-2.env/p:lexical-access` for `bias`, and composes the result with
stdlib `prop/+`. The passing assertion is:

```clojure
((compiled-add-bias 5) with bias = 10) => 15
```

This proves the short path: linked-list declaration traversal, compound
propagator declaration as a GUR closure value, GUR application, and accessor
lexical lookup. It does not yet prove general compiler-2 lowering, dynamic AST
operator dispatch, recursive construction of arbitrary lexical accessors, or
replacement of the current materializing closure application path.

## Prototype Readiness

The current system is good enough to continue building compiler-2 as a
prototype on top of accumulating GUR, with a narrow target. GUR should be used
for recursive compiler machinery: walking linked-list/AST declarations through
`obj/p:cons` / `obj/p:car` / `obj/p:cdr`, constructing recursive lexical
accessors, expanding macro-like declarations, and declaring higher-order
compiler topology. Ordinary compiled programs should still prefer primitive
propagators, iterative behavior operators, explicit behavior reducers, and
retained application/closure data.

The prototype boundary is still real. Compiler-2 should not yet assume a final
general recursion substrate for all user code, automatic GC of accumulated GUR
frames, or dynamic dispatch over arbitrary AST operators. Those are migration
targets. The safe next step is to incrementally replace hard-coded compiler-2
probes with GUR-backed declaration traversal and lexical accessor construction
while keeping existing compiler behavior green.

## Semantic Shortcut Ledger

This ledger is the cleanup order for compiler-2 shortcuts that make propagation
look correct locally while losing semantic identity or provenance.

| Status | Shortcut | Symptom | Propagator-native replacement | Proving test |
| --- | --- | --- | --- | --- |
| fixed | `compiler_2.application/materialize-slot-object` in closure application | Closure calls inspect host-materialized closure/argument records, which can collapse accessor identity and make traces miss the applied body. | Use retained closure/application slots and argument cell ids; install only the activation topology when closure shape is known. | `compile-2-application-output-adapter-is-not-materializing`, nested and late compiler-2 application tests. |
| fixed | Runtime `trace` closes over a per-block graph sidecar. | `(trace out g)` can trace block plumbing instead of the runtime env graph. | Bind one stable runtime semantic graph cell in the compiler env and have `trace` read it. | `block-language-traces-def-net-application-dependence-graph`. |
| fixed | Runtime `block-at` uses host `head-id->blocks` lookup. | Cross-session block access depends on runtime maps rather than linked block cells. | Implement indexed linked-list access as a primitive propagator installed through compiler-2 env. | `cross-session-block-at-writes-only-target-block`. |
| fixed | Default arithmetic unwraps `scope-source` / dependency values. | `(+ scoped-x 1)` can lose scope/provenance. | Make the default primitive env provenance-aware or explicitly use the contextual primitive wrapper. | `compile-2-default-arithmetic-preserves-operand-dependencies`. |
| fixed | Runtime output/block copy and generated-block reset bridge. | Display block maintenance can look like semantic program flow. | Blocks are dumb cells; users connect values to blocks with compiler primitives like `block-at`. | `cross-session-block-at-writes-only-target-block`, `tui-view-is-monotone-linked-blocks`. |
| fixed | `semantic-trace` value-label fallback. | Equal values can conflate unrelated cells. | Trace by cell id or explicit label only. | `semantic-trace-does-not-target-by-equal-value`. |
| fixed | Per-block semantic graph ids collide. | Later trace blocks can overwrite labels from earlier expression graphs, hiding constants like `1` in `(<-> (+ 1 2) a)`. | Namespace semantic graph ids per source block before graph union. | `submitted-trace-keeps-upstream-literal-constants`. |
| fixed | Trace traversal treats same runtime cell in different blocks as unrelated nodes. | Tracing upstream of `a` through `b -> a` misses later upstream edges into `b`. | Preserve all semantic-node aliases per runtime cell and expand traversal through alias-equivalent nodes. | `trace-block-reacts-through-intermediate-cell-chain`. |
| fixed | TUI trace blocks compile before the complete runtime semantic graph exists. | `(trace a :upstream g)` can stay empty when upstream relations are added later. | Recompile trace source blocks after the full source-block rebuild pass and seed the stable runtime graph cell with the accumulated graph. | `trace-block-reacts-to-later-upstream-relationships`. |
| open TODO | Live runtime expression blocks are recompiled in a second pass after declarations. | A block that mentions a later `def-net` can compile to `nothing` without recording an error, so error-only retry misses it. The second pass proves behavior but is a design workaround. | Track block dependencies against env/runtime cell identities and invalidate/rebuild only affected expression/watch blocks when later declarations extend the environment. | `block-order-is-top-to-bottom`, `later-def-net-updates-earlier-free-cell-watch`. |
| open | Layered/generic procedure materialization. | Other procedure systems duplicate the same activation-local materialization pattern. | Later shared application substrate after compiler-2 closure application is stable. | Shared substrate tests, not part of this slice. |

## Lexical Environments

Environments are explicit lexical frames. A frame has metadata slots:

```clojure
:env/parent
:env/scope
:env/scope-chain
:env/depth
```

Each symbol slot stores a binding slot, not a scoped value:

```clojure
x -> {:value {:binding/type :cell, :binding/id x-cell}}
```

The env answers "which cell does this name designate?" The pointed-to cell
answers "what is its current value and provenance?" Refinement happens in the
cell, not by replacing the env slot.

`scope-source` is only lexical lookup metadata. It does not carry arithmetic
provenance or dependency information. Lexical access wraps the current binding
payload with the frame source and active chain:

```clojure
scope-source(source-frame, active-chain, payload-from-bound-cell)
```

Dependency/provenance should live in the bound cell's value, for example as a
`dependency-value`. The lexical access result can then be a `scope-source`
whose payload is already dependency-aware.

### Propagator Lexical Mindset

Propagation is simultaneous and monotonic. If both child and parent lexical
edges are installed, a parent value can fire before the child binding/value has
refined enough to make the parent path invalid. That earlier parent message
cannot be retracted; a later child value can only add information or contradict.

So lexical shadowing must be structural:

```text
wrong: install all frame reads, rank strongest values later
right: parent traversal is blocked by local binding presence
```

For lexical scope, the guard is "this frame binds `x`", not "this frame's `x`
cell currently has a value." Binding presence is structural and monotonic;
value availability is not.

The current experiment records that structural guard in each frame's
`:env/local-bindings` slot. `p:sub-env` creates an empty local declaration set,
and `p:bind-local` creates a same-scope binding frame whose local declaration
set contains the bound symbol. `p:lexical-access` reads this metadata through
slot accessors:

- if the frame declares the symbol, install only the local scope-source path;
- if the frame metadata is known and does not declare the symbol, recurse to
  `:env/parent`;
- if the metadata is unknown, emit nothing.

This keeps late binding payloads and late closure values possible: the binding
frame can exist before the cell it points at has refined. It does not solve
truly late same-frame declaration, such as replacing a frame's local binding
set from "does not bind `x`" to "binds `x`" after parent traversal has already
emitted. That case needs a monotonic declaration representation, reducer
claims, or TMS-style retraction later.

Do not write read-local `scope-source` back into the bound cell. The same cell
can be accessed from different lexical chains, so access context belongs on the
access result, while derivation provenance belongs in the cell value.

Practical rules:

- Env slots store binding addresses only.
- Bound cells carry value refinement and derivation provenance.
- Lexical access adds access-context as `scope-source`.
- Parent lexical traversal must be blocked by local binding presence.
- Contradiction means the graph allowed incompatible facts to meet; it is not
  something an imperative overwrite would have fixed.

## Dependency Arithmetic

`helpers/default-env` remains raw and compatible. For example:

```clojure
(main/compile-source "(+ 1 2)")
;; result strongest: 3
```

`helpers/dependency-env` installs contextual primitive operators. Each
application allocates an implicit context cell:

```clojure
{:context/scope       active-scope
 :context/chain       active-chain
 :context/application application-id
 :context/operator    operator-ast}
```

Contextual arithmetic unwraps operand bases, computes the base result, and emits
a dependency value:

```clojure
{:base               result
 :dependency/sources #{operand-sources active-context-source}}
```

The active context assigns the result dependency source. Operand dependency
layers contribute sources, but operand lexical scopes do not decide the result
scope. This keeps lexical lookup and arithmetic provenance separate.

## Shared Pattern Across The System

Compiler 2 now has the same broad shape as compound objects, layered procedures,
and generic procedures:

```text
declaration data
  -> slot-backed topology
  -> retained application/branch IR
  -> activation-local materialization
  -> branch/body network installation
  -> reducer or output adapter
  -> diff/copy selected output
```

### Compound Object

`compound_object.clj` is the lowest-level slot algebra.

- A compound value is a named network with public slot cells and internal
  indexes.
- `p:slot` is a bidirectional relation between a parent cell and a slot cell.
- `p:reduce` installs internal accessors for all public slots and reduces the
  materialized slot values.

Its key abstraction is "slot topology as durable partial information, effectful
execution as activation-local work."

### Layered Procedure

`layered.clj` uses compound-object slots for both data layers and procedure
layers.

- A procedure cell is a compound object whose slots are layer names.
- `install-layered-procedure!` declares slot topology only.
- `p:apply-layered` materializes procedure layers into an activation frame,
  installs one branch per active layer, writes branch results to a result bank,
  and reduces that bank into the final layered output.

Layered procedures are "slotful branch application plus layered-object
reduction."

### Generic Procedure

`generic_procedure.clj` also stores an extensible procedure as a compound
object.

- The generic cell has default and policy slots.
- Each method is a branch object attached under a generated method slot.
- Application materializes complete method branches, runs predicates and
  matchers in parallel, writes handler results into a result bank, and reduces
  with select-one semantics.

Generic procedures are "slotful method branches plus select-one reduction."

### Compiler 2

Compiler 2 uses the same pieces at the language level.

- A closure is a compound object with environment/body/port slots.
- An application is a compound object with operator/argument/output/context
  slots.
- An application materializes closure data, creates an activation-local network,
  compiles the body into that network, runs it, and diffs the declared output.
- Contextual operators are ordinary propagator relations with one hidden context
  cell, not special evaluator state.

Compiler 2 is "slotful closure/application data plus executable lowering and
activation-local network expansion."

## Possible Common Algebra

The duplication across these systems suggests a common algebra can be extracted
without changing the runtime model.

### 1. Slot Materialization Frame

Both `generic_procedure`, `layered`, and `compiler_2.application` need to copy a
collection cell plus declared parent cells into a local frame, reinstall declared
`p:slot` topology, and run it to read a materialized compound value.

Candidate extraction:

```clojure
(application/materialize-slots outer-net collection-id normalize)
;; => {:net frame-net
;;     :value materialized-value
;;     :slot-values {slot-key value}}
```

This would remove ad hoc `copy-outer-cell`, `install-declared-slot`, and
materialization code from procedure dispatch and compiler application.

### 2. Branch Application Builder

Layered and generic applications both:

- create a result bank
- install several branches
- write branch outputs to bank slots
- reduce the bank
- run the activation network
- diff one external output

`propagators.application/build-branch-application` already moves in this
direction. A stronger abstraction would expose:

```clojure
{:copy-cells ...
 :branch-source ...
 :install-branch ...
 :result-bank-policy ...}
```

Then layered procedures and generic procedures differ mainly by branch source
and reducer policy.

### 3. Reducer Policies As First-Class Algebra

`dispatch/layered-object-policy` and `dispatch/select-one-policy` are reducer
policies over result-bank slots. Compiler 2's result-to-output adapter is a
single-output policy rather than a bank reducer.

Candidate common interface:

```clojure
(install-output-policy policy frame result-source out-id)
```

Policies could include:

- copy one result
- externalize and copy escaped closure values
- select one usable branch
- copy all active layered slots
- merge dependency/provenance layers

### 4. Context-Passing Operators

Compiler 2 now distinguishes raw operators from contextual operators with
metadata. That distinction belongs to `p:apply-application`, not retained IR.
The retained application object is uniform; application activation decides
whether the executable operator receives the context cell. Layered arithmetic
and dependency arithmetic point at the same need: operator behavior often
depends on an implicit evaluation context.

Candidate extraction:

```clojure
(operator/raw f)
(operator/contextual f)
(operator/apply operator network context-id arg-ids out-id)
```

Layered arithmetic could eventually be expressed as contextual operators that
produce layered dependency/provenance data rather than bespoke closure records.

### 5. Procedure Values As Slotful Records

Layered procedures, generic procedures, and compiler closures are all slotful
records:

```text
record cell
  slots -> branch/config/env/body cells
application
  materialize record
  install activation topology
  reduce/copy output
```

A shared "slotful procedure" abstraction could define:

- how to enumerate branch/config slots
- how to validate a complete branch
- how to install a branch
- which output policy to use

Layered, generic, and compiler closures would then be specializations rather
than independent implementations.

## Cautions

The common algebra should not hide the core invariants:

- declaration must not run evaluation
- activation-local effects must not persist in durable compound values
- output copying/diffing must be explicit
- reducers must observe slots through accessors, not direct named-network peeks
- contextual metadata should be ordinary cell data, not hidden global state

The extraction should therefore start with small helpers around materialization
and output policy, not a broad inheritance hierarchy or a new macro language.
