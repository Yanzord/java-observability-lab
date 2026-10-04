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

The program runs a synchronous baseline followed by an executor experiment,
without Spring, a database, network calls, or an observability backend.
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

The next milestone will explicitly capture and activate context in the worker.
Thread propagation and HTTP propagation are not implemented yet.

## References

- [OpenTelemetry Java Context API](https://opentelemetry.io/docs/languages/java/api/#context-api)
- [Context propagation](https://opentelemetry.io/docs/concepts/context-propagation/)

Context propagation carries tracing identity with application work. OTLP export
sends completed telemetry to a backend; these are separate mechanisms.
