# Distributed HTTP tracing

Two independent Spring Boot services with explicit OpenTelemetry instrumentation.
Requires Java 21. Run commands from this directory.

## Run

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

## Second milestone: manual W3C propagation

The two SDKs now produce one shared trace:

```text
order-service
POST /orders (SERVER, root)
└── POST /payments (CLIENT)
    └── POST /payments (SERVER, payment-service, remote parent)
```

The order controller keeps its SERVER span as a root; this milestone propagates
only the outgoing payment request. While the CLIENT span is current, it calls
`W3CTraceContextPropagator.inject(Context.current(), builder, setter)` before
building the HTTP request. The setter writes headers through `builder.header()`.

Payment uses a `TextMapGetter<HttpServletRequest>` with the servlet's
case-insensitive `getHeader()` lookup. It calls `extract(Context.root(), request,
GETTER)` and passes that context to `setParent()` before starting its SERVER
span. Extraction alone does not activate a context: `server.makeCurrent()`
activates the new local span, and closing its scope restores the previous context.

Payment logs the received `traceparent` and whether the extracted parent is
remote. A version 00 header has this format:

```text
00-<32 hex Trace ID>-<16 hex CLIENT Span ID>-<2 hex trace flags>
```

The header's parent ID identifies the sending CLIENT, not the receiving SERVER.
The payment SERVER has its own Span ID. `parentRemote=true` means its parent
context was reconstructed from the carrier across a remote boundary. The full
in-process Context and Scope are not transmitted.

Compare both terminals: all three spans share `traceId`; the payment SERVER's
`parentSpanId` equals the order CLIENT's `spanId`, and only that parent is remote.
Resource `service.name` still distinguishes the two services. Each new order
request starts a fresh trace. Trace flags are propagated with the identifiers.

## Compare with the baseline

Milestone 1 (commit `80e3ffd`) sent no tracing headers and created two traces:

```text
trace A: order SERVER → order CLIENT
trace B: payment SERVER (root)
```

With manual propagation, the payment root becomes a child of the CLIENT within
trace A. HTTP success remains HTTP 200 `approved` in both cases: propagation
changes telemetry relationships, independently of the business response.
Missing and malformed headers will be explored in the next milestone.

Manual controller spans cover handlers only, excluding serialization and the
wider servlet lifecycle. CLIENT spans cover the HTTP call and local client
cleanup. Export is synchronous to local logs. There is no Java Agent, automatic
instrumentation, Micrometer Tracing, or tracing backend.

## Validate

```bash
./gradlew test bootJar
```

The JUnit integration test starts two separate Spring application contexts with
independent tracer providers and real HTTP servers on ephemeral ports. It checks
the response, CLIENT/SERVER kinds, local parent identity, service identity,
shared trace IDs, remote payment parents, propagated trace flags, distinct span
IDs, and fresh traces on repeated requests. The test runs
in one JVM; running the commands above uses two independent JVMs.

See [CONTEXT.md](CONTEXT.md) for the next learning milestone.
