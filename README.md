# java-observability-lab

Small, incremental POCs for learning observability through explicit instrumentation.

All three POCs have completed their planned learning sequences. See each POC's
README for implementation details, execution instructions, and validation.

## Manual tracing

The [manual-tracing POC](manual-tracing/README.md) traces order creation with
OpenTelemetry, PostgreSQL, Collector, Tempo, and Grafana.

## Context propagation

The [context-propagation POC](context-propagation/README.md) studies execution
context with manual OpenTelemetry instrumentation in Java. It starts with a
synchronous baseline, then progresses through threads, W3C carriers, and HTTP.

## Distributed HTTP tracing

The [distributed-http-tracing POC](distributed-http-tracing/README.md) studies
manual context propagation between independent order and payment Spring Boot
services. It compares an initial baseline without propagation with manual W3C
injection/extraction and shared traces, then validates invalid headers and scope
cleanup.
