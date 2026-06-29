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

(compound [x] out
  (+ x 1))
```

`::` is the first-class network closure form. `compound` is a compatibility
wrapper with an explicit output symbol. Ordinary lists are applications and
return a result cell. The parser still produces AST data; `core.clj` compiles
that AST directly.

## Two-Stage Compilation Model

Compiler 2 now has an explicit conceptual split:

1. Retain an inspectable IR in the network.
2. Lower that IR into executable propagator topology.

Today those two stages still happen in one `compile-source` / `compile-expr`
call, but the data boundary is present. Every application gets an application
IR cell, and one uniform application propagator is installed alongside it.

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
`:closure/env`. This keeps closure data slot-backed and lets application
materialize the current declared topology instead of relying on a stale direct
map read.

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
output cell. Closure calls retain the same application IR object shape with a
`:closure-cell` lowering tag. That wiring is important: later operator or
argument updates wake the same application through normal scheduler adjacency.

At activation time `p:apply-application`:

1. reads the retained application object
2. reads the operator cell
3. dispatches by operator value
4. for primitive operators, emits primitive result messages directly
5. for closure values, delegates to the closure application path

For closure values, the closure application path:

1. materializes closure slot topology for the operator closure cell
2. waits while the closure value, argument object, or argument values are
   unusable
3. creates one activation network containing:
   - input boundary avatars
   - an output boundary avatar
   - body-local cells and body propagators
   - a result-to-output adapter propagator
4. runs that activation network to quiescence
5. diffs only the inner output avatar back to the outer output cell

This is the boundary that prevents inner local variables from writing to outer
cells except through the declared output/result.

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
frames, dynamic dispatch over arbitrary AST operators, or removal of the current
closure-application materialization bridge. Those are migration targets. The
safe next step is to incrementally replace hard-coded compiler-2 probes with
GUR-backed declaration traversal and lexical accessor construction while keeping
existing compiler behavior green.

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
