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
- Docker Compose and a Python standard-library demonstration script.

## Main Goal

Understand how OpenTelemetry context crosses HTTP service boundaries through
manual W3C injection and extraction, preserving trace identity and establishing
a remote parent relationship.

## Scope

`order-service → HTTP → payment-service`. POST /orders calls POST /payments,
which returns `approved`. Each Gradle module has its own executable application,
SDK, service resource, and exporter. No persistence or additional business rules.

## Current Progress

The baseline without propagation was completed in commit `80e3ffd`: payment
started an independent root trace. Manual W3C propagation is now implemented.
Order listens on 8081 and payment on 8082. Each application has its own SDK and
resource; `payment.url` / `PAYMENT_URL` configures the outgoing endpoint.

The order SERVER remains a root. Its child CLIENT becomes current before
`W3CTraceContextPropagator.inject()` writes HTTP headers. Payment extracts from
`Context.root()` through a servlet header getter and passes the extracted
context to `setParent()` for its SERVER span. All three spans now share one trace;
the payment SERVER has the CLIENT as its remote parent. The header carries the
CLIENT Span ID, not the receiving SERVER ID. Extraction does not activate context;
the new SERVER span is made current explicitly. Scopes close through
try-with-resources and spans end in finally.

Payment logs incoming `traceparent` and extracted remote identity. Both inspecting
exporters show `parentRemote` alongside IDs and service resources. Local scopes
and full execution Context are not serialized. There are no new dependencies or
automatic instrumentation. Request spans cover controller handlers only.

Validation: `./gradlew test bootJar --offline` passed with Java 21. The updated
JUnit test uses separate Spring contexts/SDKs and real HTTP, checking two requests,
HTTP 200 `approved`, shared trace IDs, distinct span IDs, CLIENT/SERVER parent
linkage, remote parent identity, propagated flags, resources, and fresh traces.
Both executable JARs also passed two requests in independent JVMs: the received
version 00 header matched the CLIENT trace/span IDs, sampling was set, and the
payment SERVER linked to that CLIENT with a remote parent. Validation processes
were stopped afterward.

`Dockerfile` builds each service with Java 21 and runs it in a JRE container.
Compose connects the services through the payment service name and publishes
temporary loopback ports. `demo.py` starts a separate Compose project, shows and
validates two requests and their traceparent/span relationships, then removes
its containers and network. It needs Docker Compose and Python, without a local
JDK. This supports the completed propagation milestone; milestone 3 remains
unimplemented. Validation: `python3 demo.py` successfully built both images,
waited for healthy containers, displayed and validated two HTTP 200 responses
and the complete cross-service span tree, then removed its containers and
network. `docker compose config --quiet` and Python compilation also passed.

## Completed Milestones

- [x] Create the two independently executable Spring Boot services.
- [x] Validate the HTTP baseline without propagation through JUnit and two JVMs.
- [x] Inject/extract W3C headers manually and validate shared traces and remote parents.
- [x] Containerize both services and validate the runnable Python demonstration.

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

Milestone 3: compare valid propagation with absent and malformed headers.
Validate independent server roots, repeated requests, and scope restoration.
Do not implement this milestone until explicitly requested.

## Not Yet In Scope

Missing/malformed header comparisons and explicit scope cleanup experiments
belong to milestone 3.
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
