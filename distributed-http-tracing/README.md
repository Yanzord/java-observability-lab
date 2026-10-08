# Distributed HTTP tracing

Two independent Spring Boot services with explicit OpenTelemetry instrumentation.
Requires Docker Compose for container execution, plus Python 3 for the guided
demo. Local Gradle execution requires Java 21. Run commands from this directory.

## Guided demo

```bash
python3 demo.py
```

The script builds the images, waits for both services, sends two real HTTP
order requests, followed by direct payment requests with absent, malformed,
zero-ID, valid, and absent-again headers. It prints the received `traceparent`,
its fields, and the span tree with actual IDs. It validates shared trace IDs,
distinct spans, remote parent linkage, sampling, fresh root traces, and fallback
without reusing a previous parent. It explains how the result compares with the
baseline without propagation.

Only Python's standard library is used. Java and Gradle run inside the build
image. The script resolves its directory, so it also works when invoked from
another directory. It uses its own Compose project and temporary localhost
ports, then removes its containers and network, including on failure or Ctrl+C.
Other Compose projects are unaffected. Docker retains the built images.

## Run with Docker Compose

```bash
docker compose up --build -d --wait
```

The published localhost ports are assigned dynamically to avoid conflicts with
other POCs. Find the order address, then send a request:

```bash
docker compose port order-service 8081
curl -i -X POST http://127.0.0.1:<published-port>/orders

docker compose logs -f order-service payment-service
```

Inside the Compose network, order calls `http://payment-service:8082/payments`.
Both applications run in separate Java 21 containers. TCP healthchecks gate
startup; there are no additional application endpoints or dependencies.
Payment also publishes a temporary localhost port, discoverable with
`docker compose port payment-service 8082`.

Remove these services when finished:

```bash
docker compose down
```

## Run locally

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
Missing and malformed headers are compared in the final milestone below.

Manual controller spans cover handlers only, excluding serialization and the
wider servlet lifecycle. CLIENT spans cover the HTTP call and local client
cleanup. Export is synchronous to local logs. There is no Java Agent, automatic
instrumentation, Micrometer Tracing, or tracing backend.

## Final milestone: missing and invalid context

The demo first validates the complete order → payment trace, then calls payment
directly to isolate extraction. The valid direct request uses a fixed synthetic
remote context; no span for that hypothetical sender is created by the demo.

| Incoming traceparent | Payment SERVER result |
| --- | --- |
| Valid | Same supplied Trace ID, supplied parent Span ID, remote parent |
| Absent | New Trace ID, zero parent ID, no remote parent |
| Malformed (`invalid`) | New independent root, HTTP 200 |
| All-zero trace/span IDs | Invalid context; new independent root, HTTP 200 |
| Valid after invalid | Supplied context is accepted again |
| Absent after valid | New root; the previous remote parent is not reused |

The propagator rejects unusable context. Because extraction starts from
`Context.root()`, an invalid header cannot fall back to an unrelated local span.
No new header-validation code or special error response is needed. Payment
still returns `approved`: losing trace continuity does not reject the operation.

Repeated requests help expose context leakage. JUnit additionally calls the
payment controller on the same executor worker with an unrelated local span
active, verifies that exact Context is restored after every invocation, and
checks the worker's original Context again in a later task. Another test makes
the order HTTP client receive HTTP 500: the exception propagates, both order
spans end, and the caller's previous Context is restored. Scope closure and span
completion are separate operations, including on failure.

All three learning milestones are complete. Automatic instrumentation and
telemetry backend infrastructure remain outside this POC.

## Validate

`python3 demo.py` validates the containerized flow without a local JDK. For the
existing JUnit tests and JAR build with Java 21:

```bash
./gradlew test bootJar
```

The JUnit integration test starts two separate Spring application contexts with
independent tracer providers and real HTTP servers on ephemeral ports. It checks
the response, CLIENT/SERVER kinds, local parent identity, service identity,
shared trace IDs, remote payment parents, propagated trace flags, distinct span
IDs, and fresh traces on repeated requests. The test runs
in one JVM; the demo runs two independent JVMs in containers. Additional tests
cover real HTTP header fallback (including mixed-case header names), a reused
worker, and context cleanup on payment HTTP failure.

See [CONTEXT.md](CONTEXT.md) for the completed learning sequence.
