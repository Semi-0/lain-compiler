# propagators-compiler

Parser, compiler, semantic graph, behavior syntax, and lowering for the
propagator system. The project depends only on `propagators-infra`.

## Public API

```clojure
(require '[propagators.compiler.api :as compiler])

(compiler/compile-form form environment options)
(compiler/compile-source source environment options)
```

Compiler results contain the network, environment, installed propagator IDs,
application metadata, result cell, and diagnostics. Invalid options,
unsupported forms, and missing lowering support return explicit errors.

## Verify

```bash
clojure -M:test
```

The committed dependency uses a pinned Git SHA. A multi-repository workspace
may supply a local-root override without changing this file.
