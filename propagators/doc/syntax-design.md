First compiler-2 prototype scope:

- first slice: ordinary application, `let-cell`, `def-net`, `network`, `cell`,
  existing `::`, and existing `switch`.
- sugar after that: Clojure-like `let`, `def-cell`, and `when`.
- later: recursion syntax, compound data syntax, predicates, reflection, TMS,
  behavior history APIs, IO, networking, and macros.
- key reflective target: `compile` / `evaluate` should compile expressions into
  an isolated sub-env, so experiments can extend language behavior without
  mutating the core env.

0. Application 

0.1 Network Application
(p:<propagator> <cell> ...)

0.2 Cell Based Application
(<closure> <cell> <cell> ...) -> <cell>

Declared-output closures consume explicit output cells as the tail applicants:

```clojure
(let-cell [same next]
  ((network [x] [same next]
     (<-> x same)
     (<-> (+ x 1) next))
   4 same next)
  next)
```

The network call builds relationships among supplied cells. It does not create
a hidden result object. To return a cell from the surrounding expression, return
that cell explicitly, as `next` above.

apply
(@ <closure> [args]) 
(@once <closure-cell> [args])

Prototype: ordinary list application is enough first. Keep `@` and `@once`
until they have semantics different from normal application.

1. let

(let-cell [<cell>] <body>)

;; clojure like expr
(let [<cell> <expr>/<cell>] <body>)

Prototype: keep `let-cell`; add `let` only as sugar over named cells and sync.

2. network declaration

;; net-name is also the cell that owns the definition closure
(def-net <net-name> 
  [<input-cells> ...]
  [<output-cells> ...]
  <body>
)

Prototype: implement this first as named closure data. The name cell owns the
definition closure.

(both def-net and def cell supports native tail recursion)

Later: tail recursion is not part of the first syntax slice.

(def-constraint <net-name>
 [<constraint-cells>]
  <body>
)

(def-cell <cell-name>
  [<input-cells> ...]
  <body>
)

annoymous network
(net [<input-cells> ...] [<output-cells> ...]
  <body>
)

Prototype: support this with the same closure representation as `def-net`.
Declared outputs must be supplied explicitly at application sites.

(body can be a one time network since we can use network as a value)
(cell [input-cells] <body>) 
or (:: [<input-cells>] <body>)

`cell` / `::` are zero-output closure forms. Applying them returns the body
result cell.

3. conditional network 

(when <condition-cell> <body-network>)

(switch <condition-cell> <input> <output>)

Prototype: keep `switch` first because it already exists as an operator.
Add `when` only after it lowers to existing lazy topology.

(if <condition-cell> <then-cell> <else-cell>)

(branch <condition-cell> <then-in> <then-out> <else-in> <else-out>)

(cond [<condition-a> <body-a>
       <condition-b> <body-b>
       <else> <out-else>])

4. recursion

tail recursion is defaultly supported

if we successfully implemented behavior based iteration,
we can define cheap iteration like

(for <cell in range>
  <body-network>
)

(reduce <reducer-cell> <accumulator> <initial> <body>)

5. compound data
(cons <cell> <cell>)
(car <collection> <cell>)
(cdr <collection> <cell>)

other data structure is to be supported

6. predicates
nothing?
contradiction?
value?

symbol?
string?
number?
boolean?

tms?
behavior?
network?
closure?
cell?
propagator-rep?
graph?

7. built-in functions
basic arithmetic (+ - * /)

sync ->
bi-sync <->

Self Reflectivity:
(neighbors <cell/closure/propagator-rep> <out>)
(content <cell> <out>)
(name <cell/propagator> <out>)
(compile <expr-string> <env>)
(evaluate <expr> <env>)

Power: these are not just introspection helpers. They should create and run
compiler-2 expressions inside a sub-env isolated from the core env. The passed
env is the extension boundary: user/compiler experiments can add syntax,
operators, or bindings there without changing the trusted base environment.

;; dangerous!!
(env-snap <out>)


TMS:
(assert <condition-cell> <premises-cell>)
(negate <condition-cell> <premises-cell>)

Reactivity:
(behavior <behavior-cell> <initial> <body>)
(last <behavior-cell> <index> <out>)
(history <behavior-cell> <start> <end> <out>)
(history-reduce <behavior-cell> <reducer-cell> <accumulator> <initial> <out>)
(history-map <behavior-cell> <mapper-cell> <out>)
(history-filter <behavior-cell> <filter-cell> <out>)
(history-take <behavior-cell> <count> <out>)
(history-drop <behavior-cell> <count> <out>)
(history-take-while <behavior-cell> <predicate-cell> <out>)
(history-drop-while <behavior-cell> <predicate-cell> <out>)
(history-split-with <behavior-cell> <predicate-cell> <out>)
(history-split-at <behavior-cell> <index> <out>)
(history-split-by <behavior-cell> <predicate-cell> <out>)

Extend:
(make-generic-propagator <generic-closure-cell>)
(define-generic-propagator-handler <generic-closure> <arg-matcher> <handler-closure>)
;; layered dataum can both be a list or a closure 
(make-layered-datum <store> <a-list>)

Search:
(binary-amb <range> <out>)
(amb <range> <out>)

;; more primitive can be extended with primitive propagator package at runtime

8. interaction
(value-io <cell> <out>)  ;; value io can be the default one for TUI/CLIs

(plot <cell> <out>)
(graph <graph> <out>)
(network-io <graph> <out>)
(slider-io <cell> <out>)
(button-io <cell> <out>)

9. networking
(share-io <collection-cell> <p2p-instance>)

10. macro extension
(defmacro <macro-name> <args> <body>)
