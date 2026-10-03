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

A runnable synchronous experiment creates `create-order` and `process-payment`.
It derives a context with `Context.with(span)`, activates it with `makeCurrent()`,
closes scopes using try-with-resources, and ends spans in `finally`.
The exporter prints completed spans and their trace, span, and parent IDs.

Creating a context does not activate it. Scope closure restores the prior
context and does not end the span. Propagation and OTLP export are distinct.
Thread and remote propagation are not implemented.

Validation: `./gradlew test run` compiled and executed the baseline successfully.
After adding the scope-failure case, `./gradlew test` passed both JUnit tests.
They verify span relationships, restoration of an existing caller, context
derivation without activation, and scope restoration after an exception.

## Completed Milestones

- [x] Create the standalone Java/Gradle POC and local span exporter.
- [x] Validate the synchronous baseline and scope restoration with two tests.

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

Milestone 2: demonstrate context loss across executor threads without propagation.
Compare the worker's current span, trace ID, and parent ID with the synchronous
baseline. Do not implement this milestone until explicitly requested.

## Not Yet In Scope

- Executor propagation and W3C carriers until their respective milestones.
- HTTP transport until milestone 5.
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
