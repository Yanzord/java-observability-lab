# Context Propagation with OpenTelemetry

## Project

The second POC in `java-observability-lab`, following `manual-tracing`.
That POC establishes manual spans, scope lifecycle, and OTLP export, but does
not extract incoming headers or propagate context between threads.

## Stack

- Java 21 and Gradle 9.7.1
- OpenTelemetry API and SDK 1.62.0
- JUnit Jupiter 6.0.3
- Local inspecting exporter with `SimpleSpanProcessor`
- JDK HTTP client and server on loopback

Versions match the existing POC's resolved dependencies. No Spring or external
infrastructure is needed for the initial execution-context experiments.

## Main Goal

Understand how execution context preserves trace identity and parent/child
relationships across synchronous calls, threads, and eventually HTTP headers,
using explicit OpenTelemetry instrumentation.

## Scope

Use order creation and payment as operation names only. No business rules,
persistence, or additional domain architecture. Introduce one execution boundary
at a time; add HTTP transport only after carrier propagation is understood.

## Current Progress

The POC is complete: all five milestones are implemented, validated, and their
console output has been reviewed. No implementation work remains in this sequence.

A runnable synchronous experiment creates `create-order` and `process-payment`.
It derives a context with `Context.with(span)`, activates it with `makeCurrent()`,
closes scopes using try-with-resources, and ends spans in `finally`.
The exporter prints completed spans and their trace, span, and parent IDs.

Creating a context does not activate it. Scope closure restores the prior
context and does not end the span. Propagation and OTLP export are distinct.
The second experiment submits payment work to a single-thread executor without
propagation while the order span is current on the caller. The worker has no
valid current span before payment, so payment and order are independent root
spans with different trace IDs. `Future.get()` waits for payment completion but
does not propagate context. The executor closes and caller context is restored.
The third experiment captures `Context.current()` on the caller before
submission and activates it in the worker using `makeCurrent()`. Payment shares
the order's trace and has the order as its parent. Nested scopes restore the
worker's original context, including on payment failure; the next task on the
same thread sees no valid current span. The caller owns the executor. Failures
are propagated through `ExecutionException`; this experiment does not add
exception events or error status.

The fourth experiment injects into a string map with the W3C propagator and
extracts from `Context.root()`. The receiver explicitly uses the extracted
context as payment's parent. Trace identity, flags, and trace state survive the
carrier round trip; the extracted parent is remote. Missing or invalid
`traceparent` creates a root span instead of inheriting a current local span.
This simulates a remote boundary in one JVM.

The fifth experiment starts a JDK HTTP server on `127.0.0.1` with an ephemeral
port and sends a real HTTP POST with manually injected W3C headers. The server
extracts from root using a case-insensitive HTTP header getter. The span tree is
`create-order (INTERNAL) → POST /payments (CLIENT) → POST /payments (SERVER) →
process-payment (INTERNAL)`. All spans share one trace; the server's parent is
remote and identifies the client span. The response is HTTP 200 `approved`.
The server and client run in the same JVM. Resources close after each experiment;
no external infrastructure or dependencies were added.

Validation: `./gradlew test run` compiled and executed all five experiments
successfully. Eight JUnit tests cover synchronous span relationships, restoration
of an existing caller, context derivation without activation, scope restoration
after an exception, independent executor spans without propagation, and explicit
propagation with success/failure cleanup on the same reused worker thread,
W3C trace identity/flags/state with a remote parent, and missing, malformed,
or zero-ID trace headers with an unrelated local span active. Real HTTP tests
validate the complete client/server tree, distinct traces for repeated requests,
caller and reused server-worker context restoration, and independent server
roots with absent or invalid headers. HTTP tests collect spans in a thread-safe
list and wait for handler completion before asserting exported data.

## Completed Milestones

- [x] Create the standalone Java/Gradle POC and local span exporter.
- [x] Validate the synchronous baseline and scope restoration with two tests.
- [x] Demonstrate and validate context loss across executor threads.
- [x] Propagate context explicitly and validate cleanup after success and failure.
- [x] Inject/extract W3C context through a map and validate remote parent identity.
- [x] Propagate W3C context over real HTTP and validate client/server relationships.

## Concepts to Learn

- Immutable `Context`, current span, and `Scope` restoration.
- Default thread-local context storage and context loss across executors.
- Capturing context before scheduling and attaching it inside a worker.
- Scope cleanup when workers fail or executor threads are reused.
- `SpanContext` versus the full in-process `Context`.
- Carrier, `TextMapSetter`, `TextMapGetter`, injection, and extraction.
- W3C `traceparent`, `tracestate`, and remote parent identity.
- Context propagation versus telemetry export.

## Learning Sequence

1. **Synchronous baseline:** derive and activate context; validate shared trace,
   parent identity, completed spans, and restoration of an existing caller.
2. **Context loss across threads:** submit payment work to an executor without
   propagation; validate the missing parent and different trace IDs.
3. **Explicit thread propagation:** capture `Context.current()` before submission,
   activate it inside the worker, and validate parent identity, failure cleanup,
   and absence of context leakage when the worker is reused.
4. **W3C carrier propagation:** manually inject into and extract from a map;
   validate remote parent identity and absent or invalid trace headers.
5. **HTTP boundary:** apply the learned injection/extraction to a minimal Java
   HTTP client/server flow; validate a shared trace with client/server spans.

## Current Focus

Completed. The examples demonstrate current-context activation, explicit thread
propagation, W3C serialization, and HTTP transport, including scope cleanup and
remote parent relationships. Choose a new learning sequence explicitly before
expanding the POC; Kafka context propagation is a possible next POC.

## Not Yet In Scope

- Baggage, messaging, Kafka, reactive execution, and virtual-thread comparisons.
- Automatic instrumentation, Java Agent, and Micrometer Tracing.
- Collector, Tempo, Grafana, OTLP export, and production infrastructure.
- Complex sampling and custom instrumentation abstractions.

## Project Principles

Explicit behavior → understanding → abstraction.
Keep `Tracer`, `Span`, `Scope`, and `Context` visible in each experiment.
Reuse established Gradle and JUnit conventions. Keep the original POC independent.

## Context Maintenance

After validating a meaningful milestone, update Current Progress, Completed
Milestones, and Current Focus. Rewrite outdated information and record relevant
technical decisions. Never mark unvalidated implementation as complete.
