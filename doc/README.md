# Compiler 2 layout

- [Flat GUR and Compiler Application](flat-gur-compiler-application.md)
  describes same-network application topology, live compound lexical scope,
  named availability, and the extracted port provenance.

- `cps_core.clj` — canonical stack-safe compiler assembly and public compile
  entrypoints.
- `compiler/` — CPS predicates, handlers, declarations, dispatch, the default
  operator basis, and a deprecated `core` namespace shim.
- `language/` — parser and AST representation.
- `model/` — compiler environments and closure/application/operator values.
- `lowering/` — flat-GUR application, recursive lexical access support, lazy
  topology, and child-environment execution.
- `operators/` — behavior, TMS, and reducer operator families.
- `deprecated/` — retained synchronous compiler implementation and its old
  compatibility core.

`main.clj` is the public compiler façade. Root-level `core.clj` and
`predicate_core.clj` plus `compiler/core.clj` are compatibility façades. New
compiler entrypoint code should depend on `cps-core`; implementation code
should depend on the responsibility-specific namespaces under `compiler/`.

The live runtime belongs to `lain-runtime-clojure`; TUI, server, dashboard, and
presentation entrypoints belong to `wired`. This compiler repository depends
only on `lain-infrastructure`.

Current implementation status, unresolved correctness issues, and their
dependency order are tracked in
[`../doc/compiler-2-progress-and-priorities.md`](../doc/compiler-2-progress-and-priorities.md).
