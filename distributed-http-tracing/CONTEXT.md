# Distributed HTTP Tracing with OpenTelemetry

## Project

The third POC in `java-observability-lab`, following `manual-tracing` and
`context-propagation`. Move from the previous single-JVM HTTP experiment to
two independent Spring Boot applications.

## Stack

- Java 21, Gradle 9.7.1, and Spring Boot 4.1.1.
- Spring Web MVC, OpenTelemetry API and SDK (Spring-managed versions).
- JDK HTTP client and JUnit Jupiter through the existing Spring test starter.
- Local inspecting exporters with `SimpleSpanProcessor`.

## Main Goal

Understand how OpenTelemetry context crosses HTTP service boundaries through
manual W3C injection and extraction, preserving trace identity and establishing
a remote parent relationship.

## Scope

`order-service → HTTP → payment-service`. POST /orders calls POST /payments,
which returns `approved`. Each Gradle module has its own executable application,
SDK, service resource, and exporter. No persistence or additional business rules.

## Current Progress

The initial baseline is implemented. Order listens on 8081 and payment on 8082.
Order creates a root SERVER span and a local child CLIENT span. Payment creates
an independent root SERVER span. The client does not inject headers; the servers
do not extract them. Spans are made current with try-with-resources scopes and
ended in finally. Inspecting exporters print trace and parent IDs plus resources.
The payment URL is configurable through `payment.url` / `PAYMENT_URL`.

Validation: `./gradlew test bootJar --offline` passed with Java 21. The JUnit
integration test validates two HTTP requests using separate Spring contexts and
SDKs. Both executable JARs were also started in independent JVMs on temporary
ports: two POST requests returned HTTP 200 `approved`, the order CLIENT had the
order SERVER as parent, and payment had an independent root with a different
trace ID. Repeated requests produced fresh traces in both services. All validation
processes were stopped afterward.

## Completed Milestones

- [x] Create the two independently executable Spring Boot services.
- [x] Validate the HTTP baseline without propagation through JUnit and two JVMs.

## Concepts to Learn

- Local current context versus context serialized across HTTP.
- W3C `traceparent`: version, trace ID, parent span ID, and trace flags.
- Manual injection with `TextMapSetter` and extraction with `TextMapGetter`.
- Extraction from `Context.root()` and remote `SpanContext` identity.
- CLIENT and SERVER spans, cross-service parents, and Trace ID continuity.
- Missing or malformed propagation and independent server roots.
- Service resources versus instrumentation scope; propagation versus export.

## Learning Sequence

1. **Baseline without propagation:** run two independent services; validate the
   HTTP result, local CLIENT parent, and different order/payment trace IDs.
2. **Manual W3C propagation:** inject the current CLIENT context into HTTP headers,
   extract from root on payment, and set the extracted context as SERVER parent.
   Validate the remote parent, shared trace ID, and exact client/server linkage;
   compare with the baseline.
3. **Propagation failures:** omit or corrupt headers and compare with the valid
   path. Validate independent roots, repeated requests, and scope cleanup.

## Current Focus

Milestone 2: manually inject W3C context from the order CLIENT span and extract
it in payment. Observe `traceparent`, confirm the extracted parent is remote,
and validate one shared trace with the payment SERVER parent matching the CLIENT.
Do not implement this milestone until explicitly requested.

## Not Yet In Scope

Manual injection/extraction and failure comparisons belong to later milestones.
Also excluded: Java Agent, automatic instrumentation, Micrometer Tracing,
OTLP/Collector/Tempo/Grafana, baggage, Kafka, databases, retries, complex sampling,
reactive execution, and production architecture.

## Project Principles

Explicit behavior → understanding → abstraction. Keep Tracer, Span, Scope, and
Context visible. Reuse existing Gradle, package, constructor injection, and JUnit
conventions. Introduce one learning milestone at a time.

## Context Maintenance

After each validated milestone, update Current Progress, Completed Milestones,
and Current Focus. Rewrite outdated information; keep this document concise.
Never mark unvalidated work complete or implement later milestones automatically.
