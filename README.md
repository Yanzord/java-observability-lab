# java-observability-lab

Small, incremental POCs for learning observability through explicit instrumentation.

## Manual tracing

The [manual-tracing POC](manual-tracing/README.md) traces order creation with
OpenTelemetry, PostgreSQL, Collector, Tempo, and Grafana. Start with its README
for setup and use [CONTEXT.md](manual-tracing/CONTEXT.md) to resume the learning
sequence from the current focus.

## Context propagation

The [context-propagation POC](context-propagation/README.md) studies execution
context with manual OpenTelemetry instrumentation in Java. It starts with a
synchronous baseline, then progresses through threads, W3C carriers, and HTTP.
See [CONTEXT.md](context-propagation/CONTEXT.md) for the current learning focus.
