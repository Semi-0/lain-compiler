# Compiler-2 Syntax Design

Current checkpoint: 2026-07-10.

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
- linked list construction through `list`, lowered to `p:cons` topology;
- generic compound slot access through `p:slot`;
- value-level conditionals: `switch`, `if`, `branch`, and `cond`;
- topology-lazy presence-gated `when`;
- arithmetic/comparison primitives, boolean `not`, variadic `->`, and
  variadic `<->`;
- behavior/TMS default entry through
  `propagators.compiler-2.main/compile-source-with-behavior-tms`;
- runtime/TUI/XR operators through the compiler-2 runtime env.

## Naming Prefixes

Current convention:

- `p:` names expose low-level propagator/slot topology directly, for example
  `p:cons`, `p:car`, `p:cdr`, and `p:slot`.
- `be:` names expose explicit behavior operators. `be` stands for behavior;
  behavior-history arithmetic is spelled `be:+`, `be:-`, `be:*`, and source
  spelling `be:/` (internally `be:divide`). Plain `+`, `-`, `*`, and `/` remain
  current-value arithmetic and lift over event-current values.
- `io:` names expose boundary resources. The target direction is that every
  `io:*` form returns an instance/receipt cell for the external resource it
  registers.
- Unprefixed names are ordinary language conveniences over the same machinery,
  for example `cons`, `car`, `cdr`, `list`, `block`, `trace`, `behavior`, and
  `latest`.

Implemented behavior/TMS surface:

- distributed TMS is the default compiler-2 path; centralized reducer-cell TMS
  remains legacy compatibility;
- `premise-believe`, `premise-retract`, `premise-content-input`,
  `tms-closure`, and `premise-closure` operate through distributed premise
  facts carried by ordinary cells;
- `behavior-cell` builds a behavior from compiler-2 code using reducer protocol
  inputs `[acc next]` and one output;
- `behavior` / `behavior-cell` and their prefixed aliases `be:behavior` /
  `be:behavior-cell` are the current behavior constructors;
- `def-behavior`, `def-behaviors`, `define-behaviors`, `let-behavior`, and
  `let-behaviour` lower to ordinary event-source cells plus latest-retaining
  behavior pipelines;
- behavior merge closures reuse the compiler-2 reducer adapter;
- `behavior`, `latest`, `last`, `history`, `history-take`, `history-drop`, and
  `history-split-at` are compiler-2-backed. `be:latest`, `be:last`, and
  `be:history` are prefixed aliases. `(be:latest)` creates an empty
  latest-retaining behavior value; `(be:latest behavior)` projects an existing
  behavior to its latest record; `(be:latest event)` promotes event content to a
  latest-held behavior. `latest` / `be:latest` and `last` / `be:last` return
  behavior values and remain composable with explicit `be:*` arithmetic.

Still design/prototype work:

- structural recursion syntax, recursive `for`, and structural `reduce` need
  GUR/compiler integration;
- `history-reduce`, `history-map`, `history-filter`, predicate-based history
  operators, generic/layered extension syntax, reflection, search, networking,
  and macros remain design targets.

## Application

### Primitive And Operator Application

```clojure
(p:<propagator> <cell> ...)
(+ 1 2)
(-> value out)
(-> a b c d)
(<-> a b)
(<-> a b c d)
```

Current implementation:

- ordinary list application is the default application syntax;
- primitive/operator applications compile to retained compiler-2 application
  data plus application propagators;
- `->` is one-way sync. With more than two arguments it installs an adjacent
  one-way chain: `(-> a b c)` means `a -> b` and `b -> c`;
- `<->` is bidirectional sync. With more than two arguments it installs
  adjacent bidirectional links: `(<-> a b c)` means `a <-> b` and `b <-> c`;
- both forms return the last cell in the chain;
- `@` and `@once` remain reserved until they have semantics distinct from
  ordinary application.

Verified in tests:

- `compile-2-supports-forward-sync-chain`;
- `compile-2-supports-bi-sync-chain`.

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
- named closure values capture a copied lexical env value. The copy includes the
  closure's own binding and receives later same-scope declarations, so ordinary
  self-application can be used inside lazy topology without `def-recursive`,
  `recur`, recursion detection, or tail-position analysis.

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

- implemented as compiler-2 surface syntax;
- compiles the condition immediately and delays the body topology;
- `nothing` waits and installs nothing;
- contradiction propagates contradiction to the delayed topology result;
- any other usable concrete value, including `false`, installs body topology
  once;
- distinct from `switch`: `when` is a topology builder, not a value predicate.

Boolean guards should be expressed with `switch`, because `false` is still a
usable value:

```clojure
(when (switch true base?)
  (-> n out))

(when (switch true recur?)
  (fib (- n 1) a)
  (fib (- n 2) b)
  (-> (+ a b) out))
```

Verified in tests:

- `compile-2-presence-when-delays-body-topology`;
- `compile-2-presence-when-supports-direct-recursive-style`;
- `compile-2-presence-when-supports-fib-style-gur`.

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

- user-facing `for`, structural `reduce`, `def-recursive`, and `recur` syntax
  are not implemented;
- ordinary self-application inside topology-lazy `when` is implemented for
  scalar GUR-style recursion;
- named closures capture a copied lexical env, not a global runtime snapshot;
- structural list/map GUR remains a design boundary. The current linked-list
  representation can build lists with `list`/`cons`/`car`/`cdr`, but a recursive
  map-style GUR currently needs a safer identity seed and tail-presence
  protocol before it should be documented as supported.

Example:

```clojure
(let-cell [out]
  (def-net fib [n] [out]
    (let-cell [base? recur? a b]
      (-> (<= n 1) base?)
      (-> (not base?) recur?)
      (when (switch true base?)
        (-> n out))
      (when (switch true recur?)
        (fib (- n 1) a)
        (fib (- n 2) b)
        (-> (+ a b) out))))
  (fib 6 out)
  out)
```

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

### Linked List Construction

```clojure
(list a b c)
```

```clojure
(let-cell [xs tail]
  (def xs (list 1 2 3))
  (p:cdr tail xs)
  (+ (p:car xs) (p:car tail)))
```

Current implementation:

- `list` is a compiler-2 operator that lowers to a chain of `p:cons`
  topology;
- the terminal tail is the internal marker `:compiler-2/list-empty`;
- `list` exists so higher-level syntax can pass normal linked structures
  instead of pretending reader vectors are cells.

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
(<= a b)
(not done?)
```

```clojure
(-> value out)
(-> a b c d)
(<-> a b)
(<-> a b c d)
```

Current implementation:

- arithmetic is available in default compiler-2 env;
- comparison primitives `<`, `<=`, `>`, `>=`, `=`, and boolean `not` are
  available in the default compiler-2 env;
- behavior arithmetic is explicit through `be:+`, `be:-`, `be:*`, and `be:/`;
- `->` performs one-way sync and supports adjacent chains;
- `<->` performs bidirectional sync and supports adjacent chains;
- both return the last cell in the chain.

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

### Premise-Closed Network Definitions

Current lower-level shape:

```clojure
(let-cell [out]
  (def-net plus-one [x] [out]
    (<-> (+ x 1) out))
  (def p-one :definition/plus-one)
  (def e0 0)
  (def op
    (premise-closure
      (network [f x] [out]
        (f x out))
      p-one
      e0))
  (op plus-one 5 out)
  out)
```

Design target for the front of `def-net`:

```clojure
(def-net ^{:premise :definition/plus-one
           :epoch 0}
  transform [x] [out]
  (<-> (+ x 1) out))

(def-net ^{:premise :definition/plus-ten
           :epoch 0}
  transform [x] [out]
  (<-> (+ x 10) out))
```

Equivalent desugaring target:

```clojure
(def-net transform:plus-one [x] [out]
  (<-> (+ x 1) out))

(def transform
  (premise-closure
    (network [x] [out]
      (transform:plus-one x out))
    :definition/plus-one
    0))
```

The primitive is still the declared-output `network` closure. The combinator is
`premise-closure`: it runs the wrapped closure through a hidden output and emits
only the premise-marked distributed update to the explicit output cell. The
front-of-definition syntax should therefore be additive sugar over closure data;
it should not introduce a second network definition runtime.

Multiple definitions for one public network name become multiple premise-closed
operator facts. Bringing a premise in or retracting it selects which definition
is currently active:

```clojure
(premise-retract :definition/plus-one 1 out)
(premise-believe :definition/plus-ten 2 out)
```

When exactly one definition premise is active, the output projects that
definition. When two active definitions disagree, the distributed strongest view
projects contradiction. When a definition premise is retracted, old facts remain
in content but the current projection drops that definition. This keeps
declaration separate from evaluation: definitions are closure values plus
premise evidence; premise state chooses which evidence is readable now.

Verified code paths:

- `compile-2-network-closure-is-data-only` and
  `compile-2-closure-declaration-alone-does-not-evaluate-body` verify closure
  declarations are data/topology, not eager body evaluation;
- `compile-2-supports-first-slice-network-and-def-net` and
  `compile-2-network-and-def-net-support-multiple-explicit-outputs` verify the
  declared-output network primitive;
- `compiler-2-distributed-tms-wraps-network-declaration-closure` verifies
  `tms-closure` around a `def-net` declaration;
- `compiler-2-redefined-premise-closure-operator-switches-by-premise` and
  `compiler-2-distributed-premise-closure-marks-network-output` verify the
  premise-closure selection/retraction shape.

### Versioned block definitions

The additive `:versioned-premise` TUI gives block-level definitions stable
public identities without changing this surface syntax. `def-net`, callable
`def-cell`, and `def-constraint` lower to private network candidates; scalar
`def` values use stable TMS-backed bindings. Editing appends a candidate and
retracts its predecessor's candidate-version premise. Existing callable
applications watch the definition registry and install the new candidate via
the normal retained-application and `p:apply-closure` path.

An explicit `(premise-closure closure premise epoch)` is recognized
structurally and kept unchanged. The versioned layer records its premise and
epoch and adds only the internal candidate-version support needed for editing.
Signature mismatches allocate stable missing cells and expose warnings rather
than rejecting the commit or deleting retained topology.

Verified by `definition-and-application-rewriting-is-idempotent`,
`edited-network-definition-reactivates-existing-application`,
`signature-repair-uses-stable-placeholders-and-visible-warnings`, and
`explicit-premise-definition-records-and-retracts-its-context`.

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

```clojure
(be:behavior-cell events (behavior-empty-state) retain-latest retained)
```

```clojure
(be:behavior events retain-latest (behavior-empty-state) retained)
```

Latest-retaining behavior sugar:

```clojure
(def-behavior a)
(def-behaviors a b c)
(define-behaviors a b c)

(let-behaviour [a b c]
  (<-> (be:- (be:+ a b) c) out))
```

Each behavior name declares the visible behavior cell and its sibling event
source, for example `a` and `a-events`, then promotes `a-events` through
`be:latest` into `a`.

Current implementation:

- behavior cells can be constructed from compiler-2-defined reducer closures;
- reducer closures use the normal `[acc next] -> out` protocol;
- behavior merge closures reuse the compiler-2 reducer adapter and generic
  reducer-subnet machinery;
- `be:behavior` and `be:behavior-cell` are implemented aliases for behavior
  construction. `be` stands for behavior;
- `def-behavior`, `def-behaviors`, `define-behaviors`, `let-behavior`, and
  `let-behaviour` are parser-level sugar over `def-cells`, `def-net`,
  `behavior`, `behavior-empty-state`, `behavior-add-event`, and
  `behavior-retain-last`.

Verified in tests:

- `compiler-2-behavior-prefixed-constructor-builds-behavior`;
- `compiler-2-behavior-prefixed-projections-are-behaviors`.

### Behavior History

```clojure
(latest behavior)
(last behavior index out)
(history behavior start end out)
(history-take behavior count out)
(history-drop behavior count out)
(history-split-at behavior index out)
```

```clojure
(be:latest)
(be:latest behavior)
(be:last behavior index out)
(be:history behavior start end out)
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
- `be:latest`, `be:last`, and `be:history` are implemented behavior-prefixed
  aliases;
- `(be:latest)` creates an empty latest-retaining behavior value with no
  current value before events arrive;
- `(be:latest behavior)` projects an existing behavior to a behavior retaining
  only the latest record;
- `latest`, `be:latest`, `last`, and `be:last` return behavior values, so they
  compose with behavior arithmetic;
- closure/predicate history operators remain design targets.

Verified in tests:

- `compiler-2-behavior-syntax-latest-and-last-are-behaviors`;
- `compiler-2-behavior-prefixed-projections-are-behaviors`;
- `compiler-2-be-latest-zero-arg-builds-empty-latest-behavior`;
- `compiler-2-behavior-declaration-sugar-retains-latest-events`;
- `compiler-2-behavior-syntax-history-slices-return-behaviors`.

### TMS-Composed Behavior

The small, tested composition is: behavior owns temporal retention, TMS owns
which behavior definition is active. A TMS claim can carry a behavior value as
its proposition value:

```clojure
(claim :left-behavior
       :behavior
       <left-behavior-value>
       [(support :definition/left)])
```

The inverse strategy is also just ordinary composition: a behavior stream can
emit premise-state facts, or a behavior-valued network can be wrapped in
`premise-closure`. The syntax should keep those intentions separate:

```clojure
;; behavior declaration
(def-net retain-latest [acc next] [out]
  (let-cell [full]
    (behavior-add-event acc next full)
    (behavior-retain-last full 1 out)))

;; TMS authority over which behavior definition is active
(def-net ^{:premise :definition/left
           :epoch 0}
  chosen-behavior [events] [out]
  (behavior-cell events (behavior-empty-state) retain-latest out))
```

Here the behavior reducer is the strategy for retaining time, while the premise
annotation is the strategy for selecting authority. Retraction is not deletion:
the behavior history and the TMS premise facts remain monotone content; the
current strongest projection changes because latest premise state changes.

Verified code paths:

- `compiler-2-main-can-build-behavior-with-compiler-closure-reducer` and
  `compiler-2-behavior-cell-can-use-slot-based-merge-closure` verify
  compiler-2-defined behavior reducers;
- `compiler-2-behavior-syntax-latest-and-last-are-behaviors` and
  `compiler-2-behavior-syntax-history-slices-return-behaviors` verify behavior
  projections remain behavior values;
- `tms-selects-between-behavior-valued-claims` verifies TMS can select between
  behavior-valued claims and project contradiction when multiple active
  behavior definitions conflict.

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
(-> behavior-value (be:block 2))

(block-at (instance taro) 2 out)
(be:block-at (instance taro) 2 out)
```

Current implementation:

- ordinary non-declaration TUI blocks are expression blocks. The runtime wraps
  them with a generated output cell and `(be:block-at % <next-index> out)`, so
  the result displays in the next block;
- top-level declarations are not auto-wrapped. Neither are expressions that
  already declare an explicit destination, including `->`, `<->`, `be:block`,
  the output-taking arities of `block-at`/`be:block-at`, and IO operators;
- `->` and `<->` remain ordinary sync operators rather than declaration forms;
  suppression here is only a TUI presentation policy that avoids inventing a
  second destination;
- in a premise-versioned client, `(block index)` is syntax sugar for a named
  `p:block` topology constructor. It returns a proxy cell and mono-syncs that
  cell into the addressed block's display cell; `->` itself is unchanged;
- in a legacy client, `(block index)` retains its block-text compatibility
  behavior;
- `(block-at instance index)` returns the addressed instance's block text cell;
- `(block index)` may bind future blocks; the runtime creates missing blank
  blocks so the target can update immediately;
- `(block index)` rejects non-empty past targets. Empty past blocks are allowed;
- `block-at` writes through the normal monotone block text path;
- the older `(block-at instance index value)` form remains available for
  compatibility and explicit cross-instance block binding;
- `(be:block index)` is the current-instance behavior display target. It
  returns a proxy cell; values written to that proxy are projected through the
  behavior display lane;
- `be:block-at` writes through a latest-behavior display lane and is intended
  for repeated behavior/XR updates;
- premise-versioned automatic output attaches a named propagator directly to
  the target block's display cell. The cell's distributed-TMS strongest value,
  rather than an outbox epoch, selects what the TUI displays. Retracted versions
  remain inactive evidence and simultaneous active versions contradict with
  provenance.
- `(-> behavior-value (be:block index))` is verified as the expression-style
  behavior block target.

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

```clojure
(def events)
(def out)

(def-net retain-event [acc update] [out]
  (behavior-add-event acc update out))

(behavior events retain-event (behavior-empty-state) out)
(-> out (be:block 2))
```

Verified in tests:

- `be-block-target-expression-displays-latest-update`.

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
(io:xr graph)
```

Trace surfaces:

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

Status quo:

- `trace` is implemented today and builds a semantic graph trace from a cell;
- `semantic-trace` is a planned explicit spelling for the same semantic-trace
  mode;
- `compiler-graph-trace` is still a design target. It should show
  compiler-produced topology and retained compiler application/closure
  declarations;
- `call-graph-trace` is still a design target. It should show closure/operator
  applications and call structure without expanding every primitive edge by
  default;
- `io:xr` takes one graph cell and returns a generated receipt cell;
- it emits a boundary effect that starts or refreshes the XR/browser
  projection;
- `xr-io` remains as the older compatibility spelling;
- the browser receives graph/widget projections and does not directly mutate
  arbitrary cells.

Design notes:

- semantic traces should show user-level semantic graph relationships and keep
  compound propagators collapsed until the user requests deeper expansion;
- pro tracers should serialize to the same JSON graph artifact shape used by
  `serialize`.

Example:

```clojure
(let-cell [g]
  (trace out g)
  (io:xr g))
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
(io:slider-panel a b c)
(io:slider-panel-name "mix" a b c)
```

Current implementation:

- registers one browser/XR widget panel with one slider channel per cell;
- `io:slider-panel` uses the default panel id `"slider-panel-0"`;
- `io:slider-panel-name` uses the first argument as the panel id;
- channel names default from env cell symbols, so `(io:slider-panel a b c)`
  registers channels `"a"`, `"b"`, and `"c"`;
- each cell is the view cell for its channel. If a sibling event-source cell
  exists by name, for example `a-events` for `a`, widget events route there;
  otherwise the same cell is both the view cell and event-source cell;
- widget updates are monotone event facts. Plain cells can feed default
  arithmetic directly as event-current values; behavior history still requires
  explicit promotion through `be:latest` or `define-behaviors`;
- `io:slider-panels` is accepted as a compatibility alias for
  `io:slider-panel`;
- applying `io:slider-panel` returns the generated panel descriptor cell;
- the old public `(io:slider-panel (list ...))` and
  `(io:slider-panel "id" (list ...))` forms are removed. Use varargs cells and
  `io:slider-panel-name` for explicit ids.

Example:

```clojure
(define-behaviors a b c)
(def out)
(io:slider-panel a b c)
(<-> (be:- (be:+ a b) c) out)
```

Plain event-current arithmetic does not require behavior promotion:

```clojure
(def-cells a b c d)
(-> (- (+ a c) b) d)
(def-cell g)
(trace d g)
(io:xr g)
(io:slider-panels a b c)
```

```clojure
(define-behaviors a b c)
(io:slider-panel-name "mix" a b c)
```

Verified in tests:

- `io-slider-panel-registers-channel-names-from-cell-symbols`;
- `io-slider-panel-defaults-panel-id-with-varargs`;
- `io-slider-panel-routes-behavior-views-to-sibling-event-sources`;
- `tui-slider-panel-events-feed-default-arithmetic`;
- `io-xr-tracks-default-event-arithmetic-from-slider-panel-alias`.

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
(io:xr graph-io)
```

Current implementation:

- `io:xr` is implemented in the compiler-2 runtime env;
- it takes one graph cell and returns the generated receipt cell;
- `xr-io` remains as the older compatibility spelling;
- it is a boundary-effect output path, not a browser mutation primitive.
