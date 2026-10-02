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

The runner experiments have been replaced by a minimal MVC HTTP flow:

```text
POST /orders (SERVER)
└── create-order
    ├── process-payment
    └── persist-order
```

`order` contains the controller, service, entity, and JPA repository. `payment`
contains a dummy service that logs approval without external calls or persistence.
`Order` has only a generated `Long id` and a server-generated `LocalDateTime
creationDate` (date and time without timezone). POST has no request body and
returns HTTP 201 with the saved entity. Other CRUD operations are not implemented.

PostgreSQL 17 runs through `compose.yaml`, bound to localhost port 5432, with
persistent volume storage. Credentials live in an ignored `.env`; `.env` and all
`.env.*` variants are excluded from Git. A local `.env` was generated for validation. Hibernate
uses `ddl-auto: update` for the lab. No dependency was added. The obsolete
`tracing-experiment` profile was removed; database configuration is now required.
The Compose `app` service builds the JAR in a Java 21 JDK stage and runs it in
a Java 21 JRE stage, publishing localhost port 8080. It waits for PostgreSQL to
be healthy and overrides the datasource URL to use the `postgres` service name.
`.dockerignore` excludes environment files and local build artifacts.
Use `docker compose up --build -d` for both services, or start only `postgres`
when running through Gradle or IntelliJ. See `README.md` for all commands.

Instrumentation remains explicit, with constructor-injected `Tracer`,
try-with-resources scopes, and spans ended in `finally`. The request span covers
the controller handler, excluding response serialization and the wider servlet
lifecycle. `persist-order` covers the `saveAndFlush()` call. No incoming trace
headers are extracted. Exceptions propagate through Spring MVC's default error
handling; exception recording and error status remain future steps.

The SDK pipeline remains `OpenTelemetrySdk` → `SdkTracerProvider` → `Tracer`
→ `Span` → `SimpleSpanProcessor` → `LoggingSpanExporter`. No explicit `Resource`
or `service.name` is configured; the tracer name identifies instrumentation scope.

Earlier experiments established that creating a span does not make it current,
`Context.with(span)` creates an immutable derived context, closing a scope restores
the previous context, and ending the span triggers export separately.

Validation:

- `./gradlew test` with `.env` exported and PostgreSQL running: four tests passed.
- Real HTTP POST returned 201; SQL confirmed the generated ID and creation date.
- Logs showed all four spans sharing a trace ID.
- Docker build and Compose startup passed; an HTTP POST to the container returned
  201, SQL confirmed persistence, and container logs showed the four related spans.
- Tests verify exported parent IDs, distinct span IDs, context restoration, and
  span cleanup on persistence failure. The persistence test clears the JPA cache
  before reloading from PostgreSQL and rolls back its data.

The logging exporter summary does not display parent span IDs; the tests inspect
exported `SpanData` to validate relationships. Both Compose services were left
running for further experimentation. The application container uses UTC by
default; `creationDate` reflects the runtime's local timezone without an offset.

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

Review traces from real `POST /orders` requests, then add span attributes.
Explore how attributes describe an operation without changing its stable name
or parent/child relationships. Keep the domain limited to ID and creation date.

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
