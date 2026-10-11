# Kafka Tracing with OpenTelemetry

## Project

The fourth POC in `java-observability-lab`, following `manual-tracing`,
`context-propagation`, and `distributed-http-tracing`.

## Stack

- Java 21, Gradle 9.7.1, Spring Boot 4.1.1.
- Spring Web MVC on order-service and Spring Kafka on both services.
- OpenTelemetry API/SDK with Spring-managed versions and local inspecting exporters.
- Existing JUnit Jupiter/Mockito conventions.
- Docker Compose, Apache Kafka 4.1.2 in combined KRaft mode, Python standard-library demo.

## Main Goal

Understand how manual W3C context propagation through Kafka record headers
connects PRODUCER and CONSUMER spans across asynchronous service boundaries.

## Scope

`order-service → Kafka → payment-service`. POST /orders publishes a generated
order ID as a string key/payload to `orders`; payment logs dummy approval.
Independent applications have separate SDKs, tracers, and service resources.
No persistence, extra business rules, or telemetry backend.

## Current Progress

Milestone 1 is implemented and validated. Order creates a root
SERVER and a child PRODUCER, waits for broker acknowledgement, then returns
HTTP 202 with the order ID. The record has no tracing headers. Payment logs
`traceparent=null` and creates a root CONSUMER. Scopes close with
try-with-resources; spans end in finally. Failed sends are recorded on PRODUCER
and rethrown. Kafka observation is explicitly disabled on template and listener.

Compose creates a single-partition topic before the services start. Payment
acknowledges each successfully processed record. The Python demo waits for
consumer completion rather than assuming HTTP response implies payment approval.
It checks two messages and removes its isolated containers/network afterward.

Validation: `./gradlew test bootJar` passed with Java 21: three JUnit tests
cover local PRODUCER ancestry and empty headers, independent CONSUMER roots,
exact scope restoration on a reused worker, and failed-send cleanup/error data.
`python3 demo.py` built both images and passed with real Kafka and separate JVMs:
two HTTP 202 responses, two approved payments with absent `traceparent`, local
SERVER → PRODUCER relationships, distinct CONSUMER traces, correct service
resources, and fresh traces on repeated messages. Demo containers/network were
removed. Python syntax validation and `git diff --check` also passed.

Observed: successful delivery does not imply trace continuity. An order ID can
correlate business operations while the SDKs still produce separate traces.

## Completed Milestones

- [x] Create independent Spring Boot services, manual SDKs, and reproducible Kafka Compose.
- [x] Validate messaging without propagation through JUnit and real Kafka in two JVMs.

## Concepts to Learn

- Current local Context versus serialized remote SpanContext.
- Kafka record headers as byte-valued carriers; UTF-8 conversion.
- W3C Trace Context, `traceparent`, trace flags, and remote parent identity.
- Manual injection on PRODUCER and extraction from root on CONSUMER.
- Local and remote parent-child relationships; Trace ID continuity.
- Broker acknowledgement versus asynchronous consumer completion.
- Context loss, independent traces, and scope cleanup on reused workers.
- Propagation versus telemetry export; order ID versus trace identity.

## Learning Sequence

1. **Baseline without propagation:** validate actual delivery, absent headers,
   local SERVER → PRODUCER parentage, and an independent root CONSUMER.
2. **Manual W3C propagation:** inject the current PRODUCER into record headers,
   extract from root on payment, explicitly set CONSUMER's parent; validate
   shared Trace ID and the remote PRODUCER parent and compare with milestone 1.
3. **Propagation failures:** absent/malformed/zero-ID headers and missing
   extraction; verify independent roots, recovery, and worker scope restoration.

## Current Focus

Milestone 2: inject the current PRODUCER context manually into Kafka record
headers, extract from root in payment, and set the CONSUMER's remote parent.
Compare Trace IDs and parent Span IDs against the validated baseline.
Do not implement this next milestone until explicitly requested.

## Not Yet In Scope

Manual injection/extraction and failure experiments remain for later milestones.
Excluded: automatic instrumentation, Java Agent, Micrometer Tracing, OTLP,
Collector/Tempo/Grafana, baggage, databases, retries/DLT, transactions, batch
consumption, span links, complex sampling, and production architecture.

## Project Principles

Explicit behavior → understanding → abstraction. Keep Tracer, Span, Scope,
and eventually Context visible. Follow existing Gradle, packages, constructor
injection, and test conventions. Implement one learning milestone at a time.

## Context Maintenance

After each validated milestone, update Current Progress, Completed Milestones,
and Current Focus. Rewrite outdated information; never mark unvalidated work
complete or automatically implement the next learning step.
