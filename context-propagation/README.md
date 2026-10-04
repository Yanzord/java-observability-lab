# Context propagation

An incremental Java POC for understanding how OpenTelemetry preserves tracing
relationships across execution boundaries. Read [CONTEXT.md](CONTEXT.md) before
resuming the learning sequence.

## Run and validate

Requires a Java 21 JDK. Run from this directory:

```bash
./gradlew run
./gradlew test
```

The program runs a synchronous baseline, executor experiments with and without
explicit propagation, a W3C map carrier experiment, and a loopback HTTP request,
without Spring, a database, or an observability backend.
It uses the OpenTelemetry API/SDK 1.62.0 and
JUnit Jupiter 6.0.3, matching the versions resolved by the existing manual-tracing
POC. The Gradle wrapper is also reused from that POC.

## First experiment: the current context

`Context` is an immutable collection of execution-scoped values, including the
current span. `previous.with(order)` creates a context containing the order span
without changing `Context.current()`. `makeCurrent()` activates that context;
closing its `Scope` restores the previous one. Ending the span is a separate
operation that triggers synchronous export in this example.

The program creates:

```text
create-order
└── process-payment
```

Observe that:

- Creating the order context leaves the current context unchanged.
- Both exported spans share a `traceId`, but have distinct `spanId` values.
- The payment's `parentSpanId` equals the order's `spanId`.
- Closing the payment scope restores the order context; closing the order scope
  restores the context that existed before the experiment.
- Payment is exported first because it ends before the order.

Tests verify completed span relationships and restoration both without an
existing parent and when called inside an active caller span.
They also verify that deriving a context does not activate it and that scope
closure restores the previous context after an exception.

## Second experiment: context loss across threads

`runWithoutPropagation` makes the order span current on the caller thread and
submits payment work to a single-thread executor. OpenTelemetry's default context
storage is thread-local: the worker does not inherit the caller's current span.
No context is captured, attached, or explicitly provided as the payment's parent.

Observe the caller and worker thread names. Before payment starts,
`Span.current().getSpanContext().isValid()` is `false` on the worker. The exported
payment and order spans have different `traceId` values and both have an invalid
parent, printed as `parentSpanId=0000000000000000` in this standalone run.
They represent two independent traces, although payment is logically part of
order creation.

The caller waits with `Future.get()` so payment finishes before the order ends.
Waiting coordinates completion; it does not propagate context. The executor is
closed, the spans are ended, and the caller's previous context is restored.
The new test verifies the worker's missing current span, independent root spans,
different trace IDs, and caller context restoration.

## Third experiment: explicit thread propagation

`runWithPropagation` captures `Context.current()` while the order span is current
on the caller, before submitting work. Inside the worker, `captured.makeCurrent()`
activates that context. The payment span then inherits the order as its parent.
Capturing inside the worker would retrieve the worker's context instead.

Compare the exported IDs with the second experiment: payment and order now share
a trace ID, and payment's parent ID equals the order's span ID. No automatic
executor instrumentation or task wrapping is used.

The payment scope closes first, restoring the propagated order context. The
propagated scope then closes, restoring the worker's original context. Span
ending remains separate and happens in `finally`. A subsequent uninstrumented
task on the same worker reports no valid current span.

The test exercises successful and failing payment operations on the same
single-thread executor. It verifies the parent relationship, completed spans,
preservation of an existing caller context, propagation of the original failure
through `ExecutionException`, and restoration of the same worker context before
the thread is reused. The caller owns and closes the executor.

This milestone focuses on context and lifecycle cleanup. A payment failure is
re-thrown; recording exception events and setting span error status are not added
to this experiment.

## Fourth experiment: W3C map carrier

`W3CPropagationExperiment` injects `Context.current()` into a
`Map<String, String>` using `W3CTraceContextPropagator` and `Map::put` as the
`TextMapSetter`. The receiver uses a `TextMapGetter` to read that map and extracts
into `Context.root()`. It explicitly passes the extracted context to
`spanBuilder(...).setParent(extracted)` before starting payment.

This simulates a remote boundary in the same JVM without network transport.
Extraction from root prevents an unrelated current local span from becoming
the parent when headers are absent or invalid. Extraction does not itself make
the context current.

The carrier contains `traceparent` in the format
`version-traceId-parentSpanId-traceFlags`. The injected parent span ID identifies
the sending order span. `tracestate` carries vendor trace state when present;
the default standalone example has none. The entire in-process context and the
span object are not serialized.

Observe matching trace IDs, payment's parent ID equal to the order's span ID,
and a parent marked `remote=true` after extraction. Tests also verify sampled
flags and a `tracestate` round trip. Missing, malformed, or zero-ID `traceparent`
values produce a new root payment span, even with an unrelated local span active.
Both valid and invalid input paths restore the previous context.

## Fifth experiment: HTTP propagation

`HttpPropagationExperiment` starts a JDK `HttpServer` bound to `127.0.0.1` on an
ephemeral port and sends a real `POST /payments` using the JDK `HttpClient`.
The program stops the server and closes its executor and client after use.
No external service or additional dependency is required.

```text
create-order (INTERNAL)
└── POST /payments (CLIENT)
    └── POST /payments (SERVER)
        └── process-payment (INTERNAL)
```

The client makes its request span current before injecting into the HTTP request
builder. The server extracts from request headers using `getFirst`, which handles
HTTP header names without case sensitivity, and uses the extracted context as
the server span's parent. Extraction starts from root to isolate each request.
Server and payment spans are made current with scopes and ended in `finally`.
The response is HTTP 200 with body `approved`.

Observe four distinct span IDs in one trace: the server's remote parent is the
client span, not the order span. This is a real HTTP boundary inside one JVM;
it demonstrates header transport, not deployment of separate services.

Tests send two instrumented requests through the same server worker, verify the
entire span tree and separate trace IDs per request, and check restoration of
caller and worker contexts. Additional real HTTP requests with absent or invalid
`traceparent` create independent server root spans. Tests collect spans in a
thread-safe list and wait for server-handler completion before inspecting them.

All five milestones are implemented. Review the differences between thread-local
activation, thread propagation, W3C serialization, and HTTP transport before
starting a new learning sequence.

## References

- [OpenTelemetry Java Context API](https://opentelemetry.io/docs/languages/java/api/#context-api)
- [Context propagation](https://opentelemetry.io/docs/concepts/context-propagation/)

Context propagation carries tracing identity with application work. OTLP export
sends completed telemetry to a backend; these are separate mechanisms.
