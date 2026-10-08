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

All three learning milestones are implemented and validated. No implementation
work remains in this sequence. The baseline without propagation
(commit `80e3ffd`) showed independent order and payment traces. Manual W3C
injection/extraction now connects them through a remote parent.

Order creates a root SERVER and child CLIENT. It injects the current CLIENT
context before sending HTTP. Payment extracts from `Context.root()` using a
case-insensitive servlet getter and explicitly sets the SERVER parent. Valid
headers preserve Trace ID and identify the CLIENT as remote parent; absent,
malformed, or zero-ID headers produce independent roots. Payment continues to
return HTTP 200 `approved`. No new production Java code or dependencies were
needed for fallback: the existing propagator and root extraction already provide
it. Order's incoming headers remain outside this experiment.

Scopes close through try-with-resources; spans end separately in finally.
Payment logs `traceparent` and extracted remote identity; exporters log span and
parent IDs, parentRemote, and service resources. Request spans cover controller
handlers only. SDKs remain independent with local synchronous export.

Java 21 validation: `./gradlew test bootJar --offline` passed all four JUnit tests:
- Complete order/payment HTTP traces, service identities, flags, and fresh traces.
- Real HTTP with valid, absent, malformed, zero-ID, valid-again, and absent-again
  headers, including mixed-case names and a single server worker configuration.
- Exact context restoration across repeated payment calls on one executor worker
  with an unrelated local parent active, then restoration on the next worker task.
- Order HTTP 500 propagation, ended CLIENT/SERVER spans, and restored caller context.

Compose runs independent Java 21 containers on temporary loopback ports. The
Python standard-library demo uses an isolated Compose project, displays two full
order traces, then directly calls payment to compare header fallback. Its valid
direct header represents a synthetic remote sender. It removes its containers
and network afterward. Docker Compose and Python are sufficient to run it.
Final validation: `python3 demo.py` built both images, checked two complete order
traces and five direct header scenarios, and removed its containers/network.
All seven HTTP responses were 200. Python compilation and `git diff --check`
also passed.

## Completed Milestones

- [x] Create the two independently executable Spring Boot services.
- [x] Validate the HTTP baseline without propagation through JUnit and two JVMs.
- [x] Inject/extract W3C headers manually and validate shared traces and remote parents.
- [x] Containerize both services and validate the runnable Python demonstration.
- [x] Validate absent/malformed/zero-ID headers, recovery, and independent roots.
- [x] Validate exact scope restoration on a reused worker and on order HTTP failure.

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

Completed. The POC demonstrates manual HTTP propagation between independent
services, remote parents, trace continuity, fallback, and scope cleanup.
Choose a new learning sequence explicitly before expanding it; Kafka context
propagation is a possible next POC.

## Not Yet In Scope

Excluded: Java Agent, automatic instrumentation, Micrometer Tracing,
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
