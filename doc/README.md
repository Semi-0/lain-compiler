# Compiler 2 layout

- [Flat GUR and Compiler Application](flat-gur-compiler-application.md)
  describes same-network application topology, live compound lexical scope,
  named availability, and the extracted port provenance.
- [Compiler Environment API](compiler-environment-api.md) describes the
  live-only `NodeId` environment contract and topology declaration API.

- `cps_core.clj` — canonical stack-safe compiler assembly and public compile
  entrypoints.
- `compiler/` — CPS predicates, handlers, declarations, dispatch, and the
  default operator basis. Application handlers explicitly compile the
  operator, declare its context, compile operand cells, and declare the
  application topology.
- `language/` — parser and AST representation.
- `model/` — live compound environments, closure values, and operator
  declarations. Syntax-aware operators expose one `compiler-operands`
  callback; ordinary operators flow through the same topology declaration.
- `lowering/` — flat-GUR application, recursive lexical access support, lazy
  topology, and child-environment execution.
- `operators/` — behavior, TMS, and reducer operator families.

`main.clj` is the public compiler façade. New compiler entrypoint code should
depend on `cps-core`; implementation code should depend on the
responsibility-specific namespaces under `compiler/`.

The live runtime belongs to `lain-runtime-clojure`; TUI, server, dashboard, and
presentation entrypoints belong to `wired`. This compiler repository depends
only on `lain-infrastructure`.

Current implementation status, unresolved correctness issues, and their
dependency order are tracked in
[`../doc/compiler-2-progress-and-priorities.md`](../doc/compiler-2-progress-and-priorities.md).
