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
- OpenTelemetry SDK and OTLP HTTP exporter
- OpenTelemetry Collector 0.162.0
- Grafana Tempo 3.1.0
- Grafana 13.1.3

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

The implemented operation is `POST /orders`. Additional CRUD operations are
outside the current learning focus.

The domain exists only to provide realistic boundaries where tracing concepts
can be explored.

Do not increase domain complexity unless required by a learning objective.

## Current Progress

The application uses a minimal MVC HTTP flow with explicit manual tracing:

```text
POST /orders (SERVER)
└── create-order
    ├── process-payment
    └── persist-order
```

`order` contains the controller, service, entity, and JPA repository. `payment`
contains a dummy approval service without external calls or persistence. `Order`
has a generated `Long id` and a server-generated `LocalDateTime creationDate`.
POST requires no body and returns HTTP 201 with the saved entity. Other CRUD
operations are not implemented. Hibernate uses `ddl-auto: update` for the lab.
The container uses UTC; creation dates have no timezone offset.

Instrumentation uses constructor-injected `Tracer`, try-with-resources scopes,
and spans ended in `finally`. The request span covers the controller handler,
excluding response serialization and the wider servlet lifecycle. Persistence
covers `saveAndFlush()`. Incoming trace headers are not extracted.

Span data:

- `create-order` and `persist-order` have numeric `order.id` after successful persistence.
- `process-payment` records the timestamped `payment-approved` event, independently
  of the application log. Approval remains recorded if persistence later fails.
- Failed persistence records an `exception` event with type, message, and stack
  trace, separately sets `StatusCode.ERROR`, and rethrows the same exception.
  Parent spans do not duplicate the exception or inherit its status.
- Successful spans retain `UNSET` status.
- `Resource.getDefault()` is merged with `service.name` from
  `spring.application.name` (`manual-tracing`), preserving SDK metadata.
  The tracer name `com.github.yanzord.manualtracing` identifies instrumentation scope.

The SDK uses two `SimpleSpanProcessor` instances. `InspectingSpanExporter` logs
completed span IDs, parents, timing, kind, status, attributes, events, resource,
and scope synchronously. `OtlpHttpSpanExporter` sends Protobuf over HTTP and
completes sends asynchronously; the processor does not batch. Spring explicitly
reads `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, defaulting to
`http://localhost:4318/v1/traces`; Compose uses the Collector service address.

The Compose stack contains app, PostgreSQL, Collector, Tempo, and Grafana:

```text
Application → OTLP HTTP → Collector → OTLP HTTP → Tempo ← queries ← Grafana
```

The Collector has an OTLP HTTP receiver, detailed `debug` exporter, and
`otlp_http/tempo` exporter, without processors. Tempo runs in monolithic mode,
receiving OTLP on port 4318 and serving queries on port 3200, with local WAL and
block storage. Grafana provisions the Tempo datasource and grants anonymous
Admin access at <http://localhost:3000/explore>; the login form is disabled.
Viewer access does not grant Explore. All published ports bind localhost.
Named volumes retain PostgreSQL, Tempo, and Grafana data.

Credentials are stored in ignored `.env` files; `.env` and all `.env.*` variants
are excluded from Git and the Docker build context. The Dockerfile builds with
Java 21 JDK and runs with Java 21 JRE. Rebuild after application source changes.
Restart services after mounted configuration changes; recreate a service after
changing its Compose environment. See [README](README.md) for commands.

Concepts established: creating a span does not make it current;
`Context.with(span)` derives an immutable context; closing a scope restores the
previous context; ending a span separately triggers export. Parent relationships
are preserved across OTLP export.

Validation performed in the corresponding implementation stages:

- Six Java tests passed with PostgreSQL and the environment configured, covering
  HTTP creation, real persistence, parent IDs, attributes, events, error status,
  exception propagation, scope restoration, and span cleanup. Persistence tests
  clear the JPA cache before reloading and roll back their data.
- JDK HTTP receiver tests verified OTLP Protobuf delivery, HTTP 400 rejection,
  configured service identity, and preserved SDK resource attributes.
- Docker build and startup passed. Real HTTP requests returned 201; SQL confirmed
  persistence, and application and Collector logs showed the four related spans.
- Tempo readiness and Grafana health passed. TraceQL search found a request;
  Tempo and Grafana's datasource proxy returned all four spans with correct
  parents, `order.id`, and `payment-approved`. Search visibility took about
  30 seconds in validation.
- After correcting anonymous access, the Explore page returned HTTP 200 and its
  frontend bootstrap confirmed Admin, `datasources:explore`, and Explore enabled.
  Query API access alone had not validated the UI permission.

Java tests were not rerun for the infrastructure-only visualization milestone.

## Completed Milestones

- [x] Create the Spring Boot project
- [x] Add the initial OpenTelemetry API/SDK dependencies
- [x] Implement minimal OpenTelemetry SDK configuration
- [x] Understand the existing tracing pipeline
- [x] Create the first manual span
- [x] Understand `Scope` and the current span
- [x] Understand OpenTelemetry `Context`
- [x] Create parent/child spans
- [x] Trace POST /orders with dummy payment and PostgreSQL persistence
- [x] Add span attributes
- [x] Add span events
- [x] Record exceptions
- [x] Set span status
- [x] Inspect exported spans
- [x] Configure OTLP export
- [x] Introduce OpenTelemetry Collector
- [x] Identify the telemetry service through Resource
- [x] Visualize traces in a tracing backend

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

Inspect successful traces in Grafana Explore: parent relationships, timeline,
resource attributes, order attributes, and payment events. The initial learning
sequence is implemented and validated. The recommended next experiment is to
compare `SimpleSpanProcessor` and `BatchSpanProcessor`, after reviewing the UI.
Retain manual instrumentation and the minimal domain.

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
