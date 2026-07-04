# Compiler-2 Syntax Design

Current checkpoint: 2026-07-04.

Compiler-2 is a propagator-language prototype. The syntax should expose ordinary
propagator topology, closure/network values, behavior/TMS composition, and
runtime IO while keeping reflective/compiler experiments behind explicit env
boundaries.

## Implementation Summary

Implemented compiler-2 surface:

- ordinary application, `let-cell`, `let`, `def`, `def-cell`, `def-cells`,
  `def-net`, `def-constraint`, `network`, `cell`, and `::`;
- declared-output closure application;
- pair access through `cons`, `car`, and `cdr`;
- generic compound slot access through `p:slot`;
- value-level conditionals: `switch`, `if`, `branch`, and `cond`;
- arithmetic, `->`, and `<->`;
- behavior/TMS default entry through
  `propagators.compiler-2.main/compile-source-with-behavior-tms`;
- runtime/TUI/XR operators through the compiler-2 runtime env.

Implemented behavior/TMS surface:

- distributed TMS is the default compiler-2 path; centralized reducer-cell TMS
  remains legacy compatibility;
- `premise-believe`, `premise-retract`, `premise-content-input`,
  `tms-closure`, and `premise-closure` operate through distributed premise
  facts carried by ordinary cells;
- `behavior-cell` builds a behavior from compiler-2 code using reducer protocol
  inputs `[acc next]` and one output;
- behavior merge closures reuse the compiler-2 reducer adapter;
- `behavior`, `latest`, `last`, `history`, `history-take`, `history-drop`, and
  `history-split-at` are compiler-2-backed. `latest` and `last` return behavior
  values and remain composable with behavior arithmetic.

Still design/prototype work:

- topology-lazy `when` should lower to accumulating GUR. It is not switch sugar;
- recursion syntax, recursive `for`, and structural `reduce` need GUR/compiler
  integration;
- `history-reduce`, `history-map`, `history-filter`, predicate-based history
  operators, generic/layered extension syntax, reflection, search, networking,
  and macros remain design targets.

## Application

### Primitive And Operator Application

```clojure
(p:<propagator> <cell> ...)
(+ 1 2)
(-> value out)
(<-> a b)
```

Current implementation:

- ordinary list application is the default application syntax;
- primitive/operator applications compile to retained compiler-2 application
  data plus application propagators;
- `->` is one-way sync, and `<->` is bidirectional sync;
- `@` and `@once` remain reserved until they have semantics distinct from
  ordinary application.

### Network Closure Application

```clojure
((cell [x]
   (+ x 1))
 4)
```

```clojure
(let-cell [same next]
  ((network [x] [same next]
     (<-> x same)
     (<-> (+ x 1) next))
   4 same next)
  next)
```

Current implementation:

- zero-output closures created with `cell` or `::` allocate/return an
  application output cell when called;
- the closure body result is projected into that returned output cell;
- declared-output closures consume explicit output cells as tail applicants;
- declared-output network calls do not create hidden result objects;
- callers return/read the desired output cell explicitly.

## Local Cells And Bindings

### `def`

```clojure
(def signal)
(def answer (+ 1 2))
```

Current implementation:

- `(def name)` creates a named free cell;
- `(def name expr)` binds `name` directly to the result cell of `expr`;
- a later `def` with the same name creates a fresh binding rather than mutating
  an old name cell.

### `def-cell`

```clojure
(def-cell out)
(-> (+ 1 2) out)
```

```clojure
(def-cell inc
  (cell [x]
    (+ x 1)))
```

```clojure
(def-cell inc [x]
  (+ x 1))
```

Current implementation:

- `(def-cell name)` is a free-cell declaration, equivalent in binding behavior
  to `(def name)`;
- `(def-cell name (cell ...))` binds `name` to a cell-producing expression;
- the older `(def-cell name [args] body)` form remains as shorthand for a
  zero-output cell closure.

### `def-cells`

```clojure
(def-cells a b c out)
```

Current implementation:

- shorthand for repeated free-cell declarations;
- equivalent to `(def-cell a)`, `(def-cell b)`, etc.

### `let-cell`

```clojure
(let-cell [x y out]
  (-> 1 x)
  (-> (+ x 2) y)
  (<-> y out)
  out)
```

Current implementation:

- creates scoped named cells;
- body compiles in a child lexical compiler env;
- useful when the code should declare cells first and wire topology manually.

### `let`

```clojure
(let [x 1
      y (+ x 2)]
  (+ y 3))
```

Current implementation:

- sugar over scoped cells and ordinary `->` binding;
- each binding creates a local cell and syncs the expression result into it;
- the final body expression is the returned result.

## Network Declaration

### `def-net`

```clojure
(def-net inc [x] [out]
  (<-> (+ x 1) out))

(let-cell [out]
  (inc 4 out)
  out)
```

Current implementation:

- defines a named closure value;
- the name cell owns the definition closure;
- declared outputs must be supplied explicitly at application sites;
- native tail recursion is not part of the implemented surface yet.

### Anonymous Network

```clojure
(network [x] [out]
  (<-> (+ x 1) out))
```

```clojure
(net [x] [out]
  (<-> (+ x 1) out))
```

Current implementation:

- `network` is implemented as declared-output closure data;
- `net` remains the intended spelling in the design notes but is not the active
  parser head today;
- declared outputs must be supplied by callers.

### Cell Closure

```clojure
(cell [x]
  (+ x 1))
```

```clojure
(:: [x]
  (+ x 1))
```

Current implementation:

- `cell` and `::` are zero-output closure forms;
- applying them returns an output cell;
- the body result is synced/projected into that output cell.

### `def-constraint`

```clojure
(def-constraint same [a b]
  (<-> a b))

(same x y)
```

```clojure
(def-constraint add-bias [x out]
  (<-> (+ x bias) out))
```

Current implementation:

- binds a direct compiler-2 installer, not a normal declared-output closure;
- applying a constraint uses each applicant as both input and output;
- no doubled applicants are required;
- lexical capture works through the compiler env where the constraint was
  declared;
- this exposes compound/bidirectional topology, not a separate constraint
  solver.

## Conditionals

### `switch`

```clojure
(switch value condition out)
(def gated (switch value condition))
```

Current implementation:

- value-level gate;
- if `condition` is true, forwards `value` to `out`;
- if `condition` is false or absent, no topology is lazily created;
- preserves behavior/TMS content through the current sync path.

### `if`

```clojure
(if condition then-value else-value)
```

Current implementation:

- value-level conditional operator;
- chooses then/else value based on the strongest condition;
- does not lazily build branch topology.

### `branch`

```clojure
(branch condition then-in then-out else-in else-out)
```

Current implementation:

- explicit-output value branch;
- forwards `then-in` to `then-out` when true;
- forwards `else-in` to `else-out` when false.

### `cond`

```clojure
(cond [condition-a body-a
       condition-b body-b
       else body-else])
```

Current implementation:

- parser sugar that lowers to nested value-level `if`;
- `else` must be the final clause.

### Topology-Lazy `when`

```clojure
(when condition
  <body-network>)
```

Current implementation:

- not implemented as compiler-2 surface syntax;
- design target is accumulating GUR lazy topology;
- distinct from `switch`: `when` is a topology builder, not a value predicate.

## Recursion And Iteration

```clojure
(for cell in range
  <body-network>)
```

```clojure
(reduce reducer-cell accumulator initial
  <body>)
```

Current implementation:

- user-facing recursive syntax is not implemented;
- accumulating GUR is the intended substrate for recursive topology, recursive
  AST/list traversal, macro-like expansion, and topology-lazy `when`;
- a self-recursive `def-net` currently does not provide productive recursion.

## Compound Data

### Pair Access

```clojure
(cons head tail)
(car pair)
(cdr pair)
```

```clojure
(let-cell [pair]
  (def pair (cons 1 2))
  (+ (car pair) (cdr pair)))
```

Current implementation:

- `cons`, `car`, and `cdr` are available in compiler-2;
- `p:cons`, `p:car`, and `p:cdr` expose the lower-level explicit-output
  operators;
- updates are topology/accessor based, not host-map materialization.

### Generic Slot Access

```clojure
(p:slot :x value object)
(p:slot :x object)
```

Current implementation:

- `p:slot` works in ordinary expressions and inside compiler-2 network
  closures;
- explicit-output slot writes and expression-style slot reads are supported;
- fully dynamic nested slot topology remains a GUR/sub-env design target.

## Predicates

```clojure
(nothing? x)
(contradiction? x)
(value? x)
```

```clojure
(symbol? x)
(string? x)
(number? x)
(boolean? x)
```

```clojure
(tms? x)
(behavior? x)
(network? x)
(closure? x)
(cell? x)
```

Current implementation:

- `contradiction?`, `value?`, `symbol?`, `string?`, `number?`, `boolean?`,
  `cell?`, `network?`, `closure?`, `behavior?`, and `tms?` are implemented;
- `nothing?` is bound but intentionally waits on absent information instead of
  asserting a durable boolean from lack of evidence;
- `propagator-rep?` and `graph?` remain design targets.

## Built-In Functions

```clojure
(+ a b)
(- a b)
(* a b)
(/ a b)
```

```clojure
(-> value out)
(<-> a b)
```

Current implementation:

- arithmetic is available in default compiler-2 env;
- the behavior/TMS env replaces arithmetic with behavior/TMS-aware wrappers;
- `->` performs one-way sync;
- `<->` performs bidirectional sync.

## Self Reflectivity

```clojure
(neighbors cell-or-closure-or-propagator out)
(content cell out)
(name cell-or-propagator out)
```

```clojure
(compile expr-string env)
(evaluate expr env)
(execute-sub-env parent-env expr out)
(virtual-sub-env parent-env expr out)
```

```clojure
(env-snap out)
```

Future design targets:

```clojure
(env-snap env out)
(serialize value file-or-json out)
(deserialize file-or-json out)
```

Current implementation:

- `execute-sub-env` is implemented as the pragmatic trusted primitive;
- it reads a parent compiler env and an expression, creates a child env with
  `env/extend-env`, compiles against the child env, runs newly declared props
  once, and projects the result to `out`;
- child bindings are isolated in the child frame, and the parent env object is
  not extended in place;
- parent lexical cells remain ordinary reachable cells. If child code resolves a
  parent binding and installs topology that writes to it, that parent cell can
  still receive messages;
- `virtual-sub-env` is the design target for safe reflective code: parent
  bindings should enter through read avatars/projected values, and writes should
  leave only through declared outputs/effects;
- `env-snap` is intentionally marked dangerous and remains a design note.

Future design notes:

- `env-snap` should be mono-directional: it projects an env value into a cell
  but should not let that snapshot feed back into and mutate the source env.
- `env-snap` of itself should be contradiction, so the system cannot create an
  infinite self-containing env value.
- `serialize` / `deserialize` should support traced networks and closure values
  as JSON artifacts. The first target is export/import of semantic traces,
  compiler graph traces, call graph traces, and closure declarations.
- Serialization should preserve ids, aliases, closure/env metadata, and graph
  edge semantics needed for inspection. It should not serialize live scheduler
  thread/runtime state as executable authority.

## TMS

```clojure
(premise-believe premise epoch out)
(premise-retract premise epoch out)
(premise-content-input value premise epoch out)
```

```clojure
(tms-closure closure premise epoch)
(premise-closure closure premise epoch)
```

Design notes also mention:

```clojure
(assert condition premises)
(negate condition premises)
```

Current implementation:

- distributed TMS is the compiler-2 default;
- premise facts are carried by ordinary cells;
- central reducer-cell TMS remains legacy compatibility;
- `assert` and `negate` are still design-level names, not the active surface.

## Behavior And Reactivity

### Behavior Construction

```clojure
(def-net retain-latest [acc next] [out]
  (let-cell [full]
    (behavior-add-event acc next full)
    (behavior-retain-last full 1 out)))

(behavior-cell events (behavior-empty-state) retain-latest retained)
```

```clojure
(behavior events retain-latest (behavior-empty-state) retained)
```

Current implementation:

- behavior cells can be constructed from compiler-2-defined reducer closures;
- reducer closures use the normal `[acc next] -> out` protocol;
- behavior merge closures reuse the compiler-2 reducer adapter and generic
  reducer-subnet machinery.

### Behavior History

```clojure
(latest behavior)
(last behavior index out)
(history behavior start end out)
(history-take behavior count out)
(history-drop behavior count out)
(history-split-at behavior index out)
```

Design targets:

```clojure
(history-reduce behavior reducer accumulator initial out)
(history-map behavior mapper out)
(history-filter behavior predicate out)
(history-take-while behavior predicate out)
(history-drop-while behavior predicate out)
(history-split-with behavior predicate out)
(history-split-by behavior predicate out)
```

Current implementation:

- `latest`, `last`, `history`, `history-take`, `history-drop`, and
  `history-split-at` are implemented;
- `latest` and `last` return behavior values, so they compose with behavior
  arithmetic;
- closure/predicate history operators remain design targets.

## Extension Surface

```clojure
(make-generic-propagator generic-closure-cell)
(define-generic-propagator-handler generic-closure arg-matcher handler-closure)
(make-layered-datum store a-list)
```

Future dynamic primitive package syntax:

```clojure
(define-primitive propagator-name
  [input-a input-b out]
  <clojure-code>)
```

```clojure
(load-primitive-package "path/to/package.clj" package-receipt)
```

Current implementation:

- these are design targets for user/compiler extension syntax;
- the current recommended extension path is env-bound compiler-2 operators and
  behavior/TMS env composition;
- layered datum surface syntax is not implemented here.

Future design notes:

- `define-primitive` should create an env-bound primitive propagator/operator.
- The parameter vector follows the primitive propagator convention: input cells
  first, output cell last.
- The Clojure code should compile/load as an explicit dynamic package, not as
  arbitrary ambient eval from normal user expressions.
- Loaded packages should extend only the target compiler env or virtual sub-env
  they are installed into. They should not mutate the trusted root env by
  default.
- A dynamic package should return a receipt/fact describing the installed
  primitive names and package identity.
- This surface is intentionally effectful and should be paired with
  `virtual-sub-env` for safe self-reflective experiments.

## Search

```clojure
(binary-amb range out)
(amb range out)
```

Current implementation:

- search syntax remains a design target.

## TUI And Interaction IO

### TUI Block Output

```clojure
(-> value (block 2))
(<-> value (block 2))
(-> value (block-at (instance taro) 2))

(block-at (instance taro) 2 out)
(be:block-at (instance taro) 2 out)
```

Current implementation:

- ordinary non-declaration TUI blocks are expression blocks. The runtime wraps
  them with a generated output cell and `(block-at % <next-index> out)`, so the
  result displays in the next block;
- top-level declarations and IO forms are not auto-wrapped: `def`, `def-cell`,
  `def-cells`, `def-net`, `def-constraint`, `->`, `<->`, `block`, `block-at`,
  `be:block-at`, `trace`, `io:xr`, `io:slider`, `io:slider-panel`, and the
  older compatibility forms `xr-io`, `slider-io`, and `slider-panel-io`;
- `(block index)` returns the current TUI instance's block text cell;
- `(block-at instance index)` returns the addressed instance's block text cell;
- `(block index)` may bind future blocks; the runtime creates missing blank
  blocks so the target can update immediately;
- `(block index)` rejects non-empty past targets. Empty past blocks are allowed;
- `block-at` writes through the normal monotone block text path;
- the older `(block-at instance index value)` form remains available for
  compatibility and explicit cross-instance block binding;
- `be:block-at` writes through a latest-behavior display lane and is intended
  for repeated behavior/XR updates.

Example:

```clojure
(def-cell out)
(-> (+ 1 2) out)
(-> out (block 2))
```

```clojure
(-> (+ 1 2) (block-at (instance taro) 2))
```

```clojure
(be:block-at (instance taro) 2 out)
```

Future design:

```clojure
(io:block blocks file)
```

`io:block` should bidirectionally bind a block collection to a file and support
bidirectional reload. Longer term, all `io:*` forms should return an instance
cell for the external resource they register.

### XR Trace And Projection

```clojure
(trace out graph)
(io:xr graph receipt)
```

Future pro tracer surface:

```clojure
(compiler-graph-trace target out)
(call-graph-trace target out)
(semantic-trace target out)
```

or, as explicit trace modes:

```clojure
(trace target :compiler-graph out)
(trace target :call-graph out)
(trace target :semantic out)
```

Current implementation:

- `trace` builds a semantic graph trace from a cell;
- `io:xr` emits a boundary effect that starts or refreshes the XR/browser
  projection;
- `xr-io` remains as the older compatibility spelling;
- the browser receives graph/widget projections and does not directly mutate
  arbitrary cells.

Future design notes:

- `compiler-graph-trace` should show compiler-produced topology and retained
  compiler application/closure declarations.
- `call-graph-trace` should show closure/operator applications and call
  structure without expanding every primitive edge by default.
- `semantic-trace` should show user-level semantic graph relationships and keep
  compound propagators collapsed until the user requests deeper expansion.
- Pro tracers should serialize to the same JSON graph artifact shape used by
  `serialize`.

Example:

```clojure
(let-cell [g receipt]
  (trace out g)
  (io:xr g receipt)
  receipt)
```

### Slider Widget IO

```clojure
(io:slider value-cell)
(io:slider widget-id value-cell)
```

Current implementation:

- registers one browser/XR slider with channel `"value"`;
- `value-cell` is used as both the widget view cell and the widget event-source
  cell;
- the one-argument form uses the env symbol name as the widget id, so
  `(io:slider gain)` registers widget `"gain"`;
- applying `io:slider` returns the same `value-cell`, so it can be used as an
  ordinary prefix expression;
- browser events are keyed by widget id and channel; the runtime resolves the
  registered event-source cell and injects monotone event records.

Example:

```clojure
(def-cell gain)
(io:slider gain)
```

The lower-level compatibility form is still available when widget feedback and
event input need separate cells:

```clojure
(slider-io widget-id view-cell event-source-cell out)
```

Example:

```clojure
(def-cell gain-events)
(def-cell gain)
(def-cell widget)

(def-net retain-event [acc update] [out]
  (behavior-add-event acc update out))

(behavior gain-events retain-event (behavior-empty-state) gain)
(slider-io "gain" gain gain-events widget)
```

### Slider Panel IO

```clojure
(io:slider-panel [a b c])
(io:slider-panel "mix" [a b c])
```

Current implementation:

- registers one browser/XR widget panel with one slider channel per cell;
- the explicit id form uses the first argument as the panel id;
- the one-argument form uses the default panel id `"panel"`;
- channel names default from env cell symbols, so `[a b c]` registers channels
  `"a"`, `"b"`, and `"c"`;
- each listed cell is both the view cell and event-source cell for its channel;
- applying `io:slider-panel` returns the generated panel descriptor cell.

Example:

```clojure
(def-cells a b c)
(io:slider-panel "mix" [a b c])
```

The lower-level compatibility form remains available for split view/event
channels:

```clojure
(slider-panel-io panel-id
  channel-a view-a events-a
  channel-b view-b events-b
  out)
```

Example:

```clojure
(def-cells a-events b-events c-events a b c out widget)

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
```

## Networking

```clojure
(share-io collection-cell p2p-instance)
```

Current implementation:

- networking syntax remains a design target.

## Macro Extension

```clojure
(defmacro macro-name args body)
```

Current implementation:

- macro syntax remains a design target;
- accumulating GUR is the intended substrate for macro-like declaration
  expansion.

## Projection

```clojure
(io:xr graph-io receipt)
```

Current implementation:

- `io:xr` is implemented in the compiler-2 runtime env;
- `xr-io` remains as the older compatibility spelling;
- it is a boundary-effect output path, not a browser mutation primitive.
