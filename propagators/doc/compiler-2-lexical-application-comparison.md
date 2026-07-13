# Compiler-2 Retained Application Comparison

## Current boundary

`compiler-2.compiler.declarations` now defaults application installation to:

```clojure
propagators.compiler-2.runtime.retained-application/p:apply-application
```

The old transient strategy remains available through the compiler-state seam:

```clojure
{:application-installer
 propagators.compiler-2.runtime.application/p:apply-application}
```

The retained default keeps the existing application IR shape for ordinary
compiler calls. When the selected operator is a raw closure value, the retained
installer lowers it into a pre-bound frame and exports the prepared closure body
topology through bounded topology effects. Primitive applications still fall
back to the compatibility strategy.

Inside retained closure-frame mode the compiler uses the smaller two-cell path:

```clojure
(p:apply-closure closure-id env-id)
```

If the operator is a cell whose closure value is not known at compile time, the
generic selected-closure applicant handles both cases:

- raw closure answers declare a retained frame directly;
- scoped closure answers declare private candidate outputs and project them
  back through the scope envelope.

Implicit-return closures are compile-time sugar. The compiler rewrites the
final body expression into an ordinary output write. One-output explicit
closures use the same compile-time routing, replacing the old runtime
result-to-output adapter for this retained path. Multi-output explicit closures
still require explicit output cells.

## Correctness evidence

Verified commands:

```bash
clojure -M:test propagators-compiler-2-closure-frame-test
clojure -M:test propagators-compile-2-test
clojure -M:test propagators-compiler-2-gur-linked-list-test
```

Observed results:

```text
propagators.compiler-2-closure-frame-test: 31 pass, 0 fail, 0 error
propagators.compile-2-test: 431 pass, 0 fail, 0 error
propagators.compiler-2-gur-linked-list-test: 4 pass, 0 fail, 0 error
```

Coverage from those tests includes:

- retained two-cell closure frames;
- implicit-return closure normalization;
- escaped closure and delayed input behavior;
- explicit-output network and `def-net` calls;
- nested local compound declarations inside retained frames;
- chained compound-object GUR over depths 1, 3, and 5;
- delayed tail growth for the missing suffix only;
- unchanged reruns adding no duplicate frame topology in the retained-frame
  proof;
- compatibility transient closure behavior remaining green.

## Benchmark evidence

Benchmark command:

```bash
clojure -M:compiler-2-application-bench
```

Benchmark harness:

- 10 warmups;
- 30 measured samples;
- median elapsed time in milliseconds;
- both strategies compiled through the same source programs and the
  `:application-installer` seam.

Output snapshot:

```clojure
{:benchmark :compiler-2-application,
 :warmups 10,
 :samples 30,
 :results
 [{:case :primitive, :strategy :compat-transient, :result 3,
   :median-ms 5.083, :cells 7, :props 1,
   :compatibility-applications 1, :retained-applications 0,
   :declared-effects 0}
  {:case :primitive, :strategy :retained, :result 3,
   :median-ms 3.30075, :cells 7, :props 1,
   :compatibility-applications 0, :retained-applications 1,
   :declared-effects 0}
  {:case :implicit-closure, :strategy :compat-transient, :result 5,
   :median-ms 5.739875, :cells 7, :props 2,
   :compatibility-applications 1, :retained-applications 0,
   :declared-effects 0}
  {:case :implicit-closure, :strategy :retained, :result 5,
   :median-ms 5.799958, :cells 19, :props 4,
   :compatibility-applications 0, :retained-applications 1,
   :declared-effects 16}
  {:case :explicit-closure, :strategy :compat-transient, :result 5,
   :median-ms 5.087584, :cells 8, :props 2,
   :compatibility-applications 1, :retained-applications 0,
   :declared-effects 0}
  {:case :explicit-closure, :strategy :retained, :result 5,
   :median-ms 5.323583, :cells 20, :props 4,
   :compatibility-applications 0, :retained-applications 1,
   :declared-effects 16}
  {:case :nested-closures-1, :strategy :compat-transient, :result 2,
   :median-ms 7.071209, :cells 13, :props 3,
   :compatibility-applications 2, :retained-applications 0,
   :declared-effects 0}
  {:case :nested-closures-1, :strategy :retained, :result 2,
   :median-ms 7.879791, :cells 41, :props 9,
   :compatibility-applications 0, :retained-applications 2,
   :declared-effects 36}
  {:case :nested-closures-3, :strategy :compat-transient, :result 4,
   :median-ms 10.922042, :cells 13, :props 3,
   :compatibility-applications 2, :retained-applications 0,
   :declared-effects 0}
  {:case :nested-closures-3, :strategy :retained, :result 4,
   :median-ms 14.553792, :cells 85, :props 19,
   :compatibility-applications 0, :retained-applications 2,
   :declared-effects 90}
  {:case :nested-closures-5, :strategy :compat-transient, :result 6,
   :median-ms 13.699, :cells 13, :props 3,
   :compatibility-applications 2, :retained-applications 0,
   :declared-effects 0}
  {:case :nested-closures-5, :strategy :retained, :result 6,
   :median-ms 20.565875, :cells 129, :props 29,
   :compatibility-applications 0, :retained-applications 2,
   :declared-effects 144}]}
```

Interpretation:

| Case | Compatibility median ms | Retained median ms | Ratio | Evidence |
|---|---:|---:|---:|---|
| primitive | 5.083 | 3.301 | 0.65x | primitive still uses compatibility execution behavior |
| implicit closure | 5.740 | 5.800 | 1.01x | retained adds 16 declared effects and 2 final props |
| explicit closure | 5.088 | 5.324 | 1.05x | retained adds 16 declared effects and 2 final props |
| nested closures depth 1 | 7.071 | 7.880 | 1.11x | retained final topology is larger |
| nested closures depth 3 | 10.922 | 14.554 | 1.33x | retained topology grows with nested calls |
| nested closures depth 5 | 13.699 | 20.566 | 1.50x | retained topology grows with nested calls |

This benchmark does not show explosive slowdown for depths 1, 3, and 5. It
does show a real retained-topology cost: the retained strategy keeps cells and
props that the transient strategy hides inside activation-local networks.

Do not claim retained application is faster. The architectural advantage is
that retained frames can be incrementally refined and inspected; the measured
cost is larger final topology for closure-heavy programs.

## Extensibility evidence

Adding a new raw closure execution strategy now localizes to:

- `retained-application/application-messages` for outer application selection;
- `lexical-application/p:apply-lexical-closure-with` for retained-frame selected
  operators;
- `application/prepare-closure-frame` for shared closure body preparation.

Application IR did not change for ordinary compiler calls. Compiler dispatch
changed only at the installer seam and `:application/cell-declarer` strategy.

New procedure/data provenance layers should not require application IR changes:
scope projection remains outside the selected closure frame, and layered
procedure provenance remains inside the layered value path.

## Current limitations

- Primitive applications still use the compatibility path.
- The retained path costs more cells/props for closure-heavy programs.
- Scoped lexical closure candidates are supported, but the full reducer-backed
  symbol-access-to-nested-layered-GUR provenance proof is still separate from
  this migration.
- Incomparable active lexical chains are still deferred.
- Irreversible external effects remain out of scope.
