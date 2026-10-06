# Distributed HTTP tracing

Two independent Spring Boot services with explicit OpenTelemetry instrumentation.
Requires Java 21. Run commands from this directory.

## First milestone: no propagation

Start each service in a separate terminal:

```bash
./gradlew :payment-service:bootRun
```

```bash
./gradlew :order-service:bootRun
```

Send an order request:

```bash
curl -i -X POST http://localhost:8081/orders
```

Expected response: HTTP 200 with body `approved`. There is no persistence.
Payment listens on port 8082. Override its URL using `PAYMENT_URL` when needed.
Stop each service with Ctrl+C.

The HTTP call succeeds, but the logs show two separate traces:

```text
order-service — trace A
POST /orders (SERVER, root)
└── POST /payments (CLIENT)

payment-service — trace B
POST /payments (SERVER, root)
```

Compare `traceId`, `spanId`, `parentSpanId`, `kind`, and resource `service.name`
in both terminals. Roots have parent ID `0000000000000000`. The order CLIENT
shares the order SERVER trace and names that SERVER as its parent. The payment
SERVER has a different trace ID and no valid parent. Repeat the request: each
service creates a fresh trace.

`Scope` makes context current only within local execution. An HTTP request does
not serialize that context automatically. The client sends no `traceparent`,
and neither controller extracts incoming context. Both SERVER spans explicitly
start without a parent. Manual controller spans cover the handler only, excluding
serialization and the wider servlet lifecycle. The CLIENT spans cover the HTTP
call and local client cleanup. Export is synchronous to local logs only.

A `traceparent` header will carry a trace ID, parent span ID, and trace flags in
the next milestone. Injection and extraction are not implemented yet. No Java
Agent, automatic instrumentation, Micrometer Tracing, or tracing backend is used.

## Validate

```bash
./gradlew test bootJar
```

The JUnit integration test starts two separate Spring application contexts with
independent tracer providers and real HTTP servers on ephemeral ports. It checks
the response, CLIENT/SERVER kinds, local parent identity, service identity,
independent payment roots, and fresh traces on repeated requests. The test runs
in one JVM; running the commands above uses two independent JVMs.

See [CONTEXT.md](CONTEXT.md) for the next learning milestone.
