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

The initial experiment runs synchronously, without Spring, a database, network
calls, or an observability backend. It uses the OpenTelemetry API/SDK 1.62.0 and
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

This establishes a baseline. It does not yet propagate context across threads
or HTTP. The next experiment will show context loss in an executor before
introducing explicit propagation.

## References

- [OpenTelemetry Java Context API](https://opentelemetry.io/docs/languages/java/api/#context-api)
- [Context propagation](https://opentelemetry.io/docs/concepts/context-propagation/)

Context propagation carries tracing identity with application work. OTLP export
sends completed telemetry to a backend; these are separate mechanisms.
