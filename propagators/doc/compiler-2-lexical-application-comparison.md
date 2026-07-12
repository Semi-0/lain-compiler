# Compiler-2 Lexical Application Comparison

## Implemented boundary

Compatibility compilation still accepts `:application-installer` and defaults
to `compiler-2.application/p:apply-application`. Retained lexical closure calls
do not use that seam. In closure-frame mode they install only:

```clojure
(p:apply-lexical-closure closure-id arg-ids out-id)
```

The propagator selects a scoped closure candidate, constructs its private
pre-bound environment, and delegates execution to the existing two-cell
`closure-frame/p:apply-closure`. It declares no application IR, argument-object
cell, or context cell.

The lexical environment now also carries a reducer-backed `:env/bindings`
projection. Its reducer retains declarations by `[symbol source]`, groups them
by symbol, and leaves nearest-scope selection to `scope-source`. The original
frame walker remains available as `p:structural-lexical-access`.

## Correctness evidence

Focused tests establish:

- scope-source equivalent candidates union dependencies;
- dependency-only refinement wakes neighbors;
- reducer and structural access retain parent and child candidates while the
  child is strongest;
- binding dereference preserves the lexical envelope;
- layered argument and operator provenance joins the existing `:provenance`
  layer;
- the existing compiler-2 closure, explicit-output, recursive `when`, cdr-gated,
  and map-chain suite remains green.

The attempted nested `map-tree` proof exposed a remaining correctness boundary:
the first compound frame can be refreshed by a retained captured-binding
watcher, but recursive `when` bodies are still compiled inside the transient
closure activation network. Consequently depths 3 and 5 do not yet retain
their applied child topology in the outer network. No passing proof or speed
claim is recorded for that unfinished case.

A follow-up outer-frame trial successfully declared the root closure frame and
wired its inner `step` application to the live lexical binding cell. The trial
then stopped one layer lower: the effect-declared application propagator was
present in the outer graph, but its retained application-IR cell remained
`nothing`, so normal application validation correctly emitted no topology.
The next fix is therefore to make application IR seeding part of the same
bounded declaration effect as its application propagator; changing `when`
semantics is not required.

The subsequent two-cell closure-frame proof removes application IR from this
execution path entirely. `closure-frame/p:apply-closure` accepts only a closure
cell and a pre-bound environment cell, reuses application.clj's closure-body
preparation, and exports its topology diff. Accessor-backed chains at depths
1, 3, and 5 pass; a delayed tail grows only the missing recursive suffix, and
unchanged reruns add no topology.

Generic lexical operator selection is now connected to that primitive. In
closure-frame mode, an unknown operator cell is lowered through the lexical
application installer for that call only. When scope selection produces a
closure, the lexical runtime builds a pre-bound private frame and delegates to
`closure-frame/p:apply-closure`; ordinary structural primitives continue using
their existing application strategy. The focused chained proof observes
`[2 3 4]` from the parent `step`, then `[2 4 6]` after adding the nearer child
`step`. The child declaration adds retained candidate topology, while an
unchanged rerun adds no cells. This proves generic live operator refinement for
the retained recursive closure chain; layered nested-object provenance remains
a separate proof.

## Historical performance evidence

The previous installer benchmark measured the now-removed six-argument lexical
application adapter. Its snapshot is retained below only as historical evidence;
it does not measure the current direct lexical-closure path and its obsolete
benchmark entry point has been removed.

| Strategy | Depth | Median ms | Cells | Props |
|---|---:|---:|---:|---:|
| existing | 1 | 4.443 | 7 | 1 |
| existing | 3 | 4.570 | 19 | 3 |
| existing | 5 | 5.033 | 31 | 5 |
| lexical | 1 | 3.750 | 7 | 1 |
| lexical | 3 | 4.219 | 19 | 3 |
| lexical | 5 | 4.498 | 31 | 5 |

These small differences are not evidence that lexical application is generally
faster. Both strategies declare identical topology for raw primitive chains;
the values mainly bound the cost of the installer seam.

## Extensibility and limits

The reducer-backed design localizes a new lexical selection policy to the
scope-source strongest function; reducer retention, application IR, and compiler
dispatch need not change. New data or operator provenance layers join through
the layered `:provenance` bridge.

The structural path couples lookup to parent-frame topology and must change its
routing functions for a new selection policy. It allocates topology proportional
to lexical depth, while reducer-backed access has a constant composition shape.

The parallel application runtime adds stable candidate identity and
private-output projection primitives. Retained closure compilation and
effectful `when` now grow recursive accessor-backed chains in the outer network.
The current lexical proof selects directly from a generic scope-source operator
cell; connecting the reducer-backed symbol accessor as its producer, proving
layered provenance throughout a nested compound object, irreversible external
effects, and incomparable active chains remain out of scope.
