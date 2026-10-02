# Manual Tracing with OpenTelemetry

## Project

This is the first POC of `java-observability-lab`.

The goal is to understand the fundamentals of OpenTelemetry tracing in Java
through explicit manual instrumentation.

This POC intentionally avoids automatic instrumentation so that the lifecycle
of traces and spans can be observed directly.

## Stack

- Java 21
- Spring Boot
- Gradle
- Spring Web
- Spring Data JPA
- PostgreSQL
- OpenTelemetry API
- OpenTelemetry SDK

## Main Goal

Understand how distributed tracing works using the OpenTelemetry API and SDK,
starting from manual span creation and gradually progressing toward exporting
traces through the OpenTelemetry ecosystem.

The focus is understanding concepts rather than building production-ready
observability infrastructure.

## Application Scope

Keep the application domain intentionally simple.

The intended flow is approximately:

HTTP Request
↓
Controller
↓
OrderService
├── PaymentService
└── Repository
↓
PostgreSQL

Example operations may include:

- `POST /orders`
- `GET /orders/{id}`

The domain exists only to provide realistic boundaries where tracing concepts
can be explored.

Do not increase domain complexity unless required by a learning objective.

## Current Progress

The Spring Boot project has been created.

A minimal OpenTelemetry SDK configuration was implemented previously.

A `CommandLineRunner` now creates `first-experiment` and ends it in `finally`.
The first execution failed before the runner because no datasource was configured.
The `tracing-experiment` profile disables datasource auto-configuration for this
database-free experiment. Run it with:

`./gradlew bootRun --args='--spring.profiles.active=tracing-experiment'`

The user validated successful execution and span export in the terminal.
The pipeline is `OpenTelemetrySdk` → `SdkTracerProvider` → `Tracer` → `Span`
→ `SimpleSpanProcessor` → `LoggingSpanExporter`.
The tracer name identifies the instrumentation scope. No explicit `Resource`
or `service.name` is configured. The experiment does not call `makeCurrent()`.

## Completed Milestones

- [x] Create the Spring Boot project
- [x] Add the initial OpenTelemetry API/SDK dependencies
- [x] Implement minimal OpenTelemetry SDK configuration
- [x] Understand the existing tracing pipeline
- [x] Create the first manual span
- [ ] Understand `Scope` and the current span
- [ ] Understand OpenTelemetry `Context`
- [ ] Create parent/child spans
- [ ] Add span attributes
- [ ] Add span events
- [ ] Record exceptions
- [ ] Set span status
- [ ] Inspect exported spans
- [ ] Configure OTLP export
- [ ] Introduce OpenTelemetry Collector
- [ ] Visualize traces in a tracing backend

## Concepts to Learn

### Trace

Understand how multiple related operations are represented as a single trace.

### Span

Understand:

- span lifecycle;
- span name;
- start and end timestamps;
- parent span;
- child spans;
- attributes;
- events;
- status;
- exception recording.

### SpanContext

Understand the role of:

- Trace ID
- Span ID
- trace flags
- propagation state

### Tracer

Understand how spans are created and how a `Tracer` is obtained from
OpenTelemetry.

### TracerProvider

Understand the responsibility of `SdkTracerProvider` and how it participates in
creating configured tracers.

### Scope

Understand what happens when a span is made current and why scopes must be
closed correctly.

### Context

Understand how OpenTelemetry associates the current span with execution flow
and how parent/child relationships depend on context.

### SpanProcessor

Understand how completed spans move from the SDK toward exporters.

Compare the responsibilities of processors such as:

- `SimpleSpanProcessor`
- `BatchSpanProcessor`

when the learning sequence reaches that point.

### SpanExporter

Understand how finished span data leaves the SDK.

Initially, prefer an exporter that makes the generated spans easy to inspect.

Later, use OTLP.

### Resource

Understand how telemetry is associated with the service that generated it,
including `service.name`.

## Learning Sequence

### Stage 1 — SDK Pipeline

Inspect and understand the existing configuration.

Be able to explain:

OpenTelemetrySdk
↓
SdkTracerProvider
↓
Tracer
↓
Span
↓
SpanProcessor
↓
SpanExporter

Do not change the architecture just to complete this stage.

### Stage 2 — First Manual Span

Create one manual span around a simple application operation.

Example:

`create-order`

Focus only on:

- obtaining the `Tracer`;
- starting a span;
- making it current when necessary;
- ending it correctly.

### Stage 3 — Parent/Child Relationships

Expand the initial trace into something similar to:

create-order
├── validate-order
├── process-payment
└── persist-order

Understand how OpenTelemetry determines the parent span.

### Stage 4 — Span Information

Gradually introduce:

- attributes;
- events;
- status;
- exception recording.

Understand when each should be used rather than adding them mechanically.

### Stage 5 — Export Pipeline

Inspect the generated spans and understand how they move through:

Span
↓
SpanProcessor
↓
SpanExporter

### Stage 6 — OTLP

Replace or complement the initial exporter with OTLP.

Understand what OTLP is and what is actually transmitted.

### Stage 7 — OpenTelemetry Collector

Introduce the Collector and change the flow to:

Application
↓ OTLP
OpenTelemetry Collector
↓
Tracing Backend

Understand receivers, processors, exporters, and pipelines at a basic level.

### Stage 8 — Trace Visualization

Add a tracing backend and inspect complete traces visually.

The backend should be selected only when this stage is reached.

## Current Focus

Understand `Scope` and the current span.

Compare `Span.current()` before, during, and after a scope created with
`span.makeCurrent()`. Observe that closing the scope restores the previous
context, while `span.end()` separately ends the span.

Do not implement the next experiment until explicitly requested.

## Not Yet In Scope

Do not introduce these concepts yet:

- OpenTelemetry Java Agent
- automatic instrumentation
- Micrometer Tracing
- Spring Boot observability abstractions
- custom instrumentation libraries
- complex sampling strategies
- baggage
- cross-service context propagation
- Kafka context propagation
- production observability architecture

They should be introduced by later POCs or later stages when their underlying
concepts can be compared against manual instrumentation.

## Future POCs

Possible future POCs in `java-observability-lab`:

- context propagation
- distributed HTTP tracing
- Kafka tracing
- automatic instrumentation
- metrics
- logs and trace correlation

Do not implement these as part of this POC.

## Project Principles

Understand first → abstract later.

Manual first → automatic later.

Small experiments → incremental complexity.

Observability concepts are the subject of the POC; the business domain is only
supporting infrastructure.

## Context Maintenance

After completing a meaningful learning milestone:

1. Update `Current Progress`.
2. Mark the corresponding item in `Completed Milestones`.
3. Change `Current Focus` to the next learning objective.
4. Record important decisions or discoveries if they affect future work.
5. Remove or rewrite information that is no longer accurate.

Keep this file concise enough that it can be read at the beginning of every
future session.
