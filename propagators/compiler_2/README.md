# Compiler 2 layout

- `compiler/` — CPS traversal, predicates, handlers, declarations, dispatch,
  and the default operator basis.
- `language/` — parser and AST representation.
- `model/` — compiler environment and retained closure/application/operator
  values.
- `runtime/` — delayed application, closure frames, retained/lexical
  application, and lazy topology execution.
- `operators/` — behavior, TMS, and reducer operator families.
- `deprecated/` — retained synchronous compiler implementation.

`main.clj` is the public compiler façade. Root-level `core.clj`,
`cps_core.clj`, and `predicate_core.clj` are compatibility façades; new code
should depend on `compiler.core` or the responsibility-specific namespaces.
