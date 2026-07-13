# Compiler 2 layout

- `cps_core.clj` — canonical stack-safe compiler assembly and public compile
  entrypoints.
- `compiler/` — CPS predicates, handlers, declarations, dispatch, the default
  operator basis, and a deprecated `core` namespace shim.
- `language/` — parser and AST representation.
- `model/` — compiler environment and retained closure/application/operator
  values.
- `runtime/` — delayed application, closure frames, retained/lexical
  application, and lazy topology execution.
- `operators/` — behavior, TMS, and reducer operator families.
- `deprecated/` — retained synchronous compiler implementation and its old
  compatibility core.

`main.clj` is the public compiler façade. Root-level `core.clj` and
`predicate_core.clj` plus `compiler/core.clj` are compatibility façades. New
compiler entrypoint code should depend on `cps-core`; implementation code
should depend on the responsibility-specific namespaces under `compiler/`.

Current implementation status, unresolved correctness issues, and their
dependency order are tracked in
[`../doc/compiler-2-progress-and-priorities.md`](../doc/compiler-2-progress-and-priorities.md).
