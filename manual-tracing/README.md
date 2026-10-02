# Manual tracing

Minimal MVC application with manual OpenTelemetry instrumentation and PostgreSQL.
Requires Docker Compose. Running or testing outside Docker also requires Java 21.

## Run

Run commands from this directory. If `.env` does not exist, create it with a
`POSTGRES_PASSWORD` variable containing a local password. Environment files and
their variants are ignored by Git.
Compose reads `.env` automatically. Build and start the application, database, and Collector:

```bash
docker compose up --build -d
docker compose logs -f app
```

The Dockerfile builds the JAR with Java 21 and runs it in a separate JRE image.
The application waits for the PostgreSQL healthcheck before starting. It connects
to the `postgres` service on the Compose network; the local configuration still
uses `localhost` when running outside Docker.

### Run locally or in IntelliJ

If the application container is running, stop it to free port 8080. Then start
PostgreSQL and the Collector and export the environment before running Gradle:

```bash
docker compose stop app
docker compose up -d --wait postgres collector
set -a
source .env
set +a
./gradlew bootRun
```

For IntelliJ, configure the run configuration to load this directory's `.env`
file into its environment variables.

Hibernate creates or updates the `orders` table using `ddl-auto: update` for this
learning environment. PostgreSQL data is retained in a named Docker volume.

In another terminal:

```bash
curl -i -X POST http://localhost:8080/orders
```

No request body is needed. The response is HTTP 201 with generated `id` and
`creationDate` (server-local date and time without a timezone):

The container uses UTC by default; local execution uses the JVM's local timezone.

```json
{"id":1,"creationDate":"2026-10-02T16:00:00"}
```

Each request creates this trace:

```text
POST /orders (SERVER)
└── create-order
    ├── process-payment
    └── persist-order
```

Payment is a dummy approval logged locally. There is no external payment call
or payment table. The request span covers the controller handler, not the full
HTTP lifecycle. The persistence span covers `saveAndFlush()` and its transaction
when called outside an existing transaction. There is no automatic JDBC tracing
or extraction of incoming trace headers.

Inspect the application logs for four exported spans sharing a trace ID. Since
export occurs when each span ends, child spans appear before their parents.
After persistence, `create-order` and `persist-order` include the numeric custom
attribute `order.id`. Span names remain stable across requests. Payment and
request spans do not include this attribute. Inspect the exporter output for
`{order.id=...}` alongside the tracing identifiers.
The `process-payment` span includes a timestamped `payment-approved` event after
dummy approval. This is separate from the application log message. The event
remains recorded even if subsequent persistence fails.

If persistence throws a runtime exception, `persist-order` records it with
`recordException()` before rethrowing the same exception. Exported span data
includes an `exception` event with type, message, stack trace, and timestamp.
The order and request spans do not duplicate the event. The same catch block
explicitly sets the persistence span status to `ERROR`. Successful spans keep
the default `UNSET`; no explicit `OK` is set. Status does not propagate to parent
spans, so order and request remain `UNSET` in this experiment even when the
exception reaches them. Exceptions continue to propagate through Spring MVC's
default error handling.

## Inspect exported spans

`InspectingSpanExporter` prints completed span data through the application logger:
name, trace ID, span ID, parent span ID, kind, start/end epoch nanoseconds,
duration in milliseconds, status, attributes, events, resource, and instrumentation
scope. A root span has parent ID `0000000000000000`. Match a child's parent ID
to another span's span ID to reconstruct the tree. All spans of the request share
the same trace ID, even though children are logged before parents.

Inspect `events` on `process-payment` for `payment-approved`. On persistence
failure, `persist-order` shows `statusCode=ERROR` and an `exception` event with
exception attributes. Each event includes its own timestamp. The simulated
failure test validates this output without disrupting the running database:

```bash
./gradlew test --tests '*TracingExperimentTests.persistenceFailureEndsSpansAndRestoresContext'
```

The exporter logs synchronously through `SimpleSpanProcessor` when a span ends.
It has no network connection or buffered batch. This is a local inspection
exporter; OTLP export is a later step. Rebuild the image after source changes:

```bash
docker compose up --build -d app
docker compose logs -f app
```

## OTLP export

The SDK has two `SimpleSpanProcessor` instances: one for local inspection and
one for `OtlpHttpSpanExporter`. The official `opentelemetry-exporter-otlp`
dependency serializes completed spans as Protobuf and sends HTTP POST requests
to `/v1/traces`. This is telemetry traffic, separate from `POST /orders`.
The processor does not batch spans; the HTTP exporter completes sends asynchronously.

The endpoint is read explicitly through Spring's configuration into the manual
SDK builder. Set `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` to override the default
`http://localhost:4318/v1/traces`. Compose sets
`http://collector:4318/v1/traces`, using its service DNS name.

`collector-config.yaml` defines an OTLP HTTP receiver on port 4318 and a detailed
`debug` exporter. The traces pipeline connects the two. There are no Collector
processors, external tracing backend, or automatic instrumentation. The Collector
prints received spans but does not provide persistent trace storage or a UI.

```bash
docker compose up --build -d
curl -i -X POST http://localhost:8080/orders
docker compose logs -f collector
```

Compare the Collector's trace IDs, parent IDs, span names, attributes, events,
and status with `docker compose logs app`. The IDs should match: serialization
and transmission do not create a new trace or change parent relationships.
A failed OTLP export is a telemetry delivery failure, separate from the result
of creating an order; the exporter reports it through its export result and logs.

## Service identity

The tracer name `com.github.yanzord.manualtracing` identifies the instrumentation
scope. `Resource` identifies the application that produced the telemetry.
The SDK explicitly maps `spring.application.name` to resource attribute
`service.name`, so both local and Collector logs show `manual-tracing`.
Merging the service resource with `Resource.getDefault()` preserves
`telemetry.sdk.name`, `telemetry.sdk.language`, and `telemetry.sdk.version`.

Resource attributes are shared by the spans from this provider. They are not
operation-specific attributes such as `order.id`. This configuration does not
add automatic Spring instrumentation or resource detection.

## Test

With PostgreSQL running and the environment loaded as above:

```bash
./gradlew test
```

Tests cover HTTP creation and real persistence, exported span relationships,
and span cleanup when persistence fails. HTTP transport tests use a temporary
JDK HTTP server to verify Protobuf delivery and rejection handling, without
additional test dependencies. The database test rolls back its data.

To inspect persisted orders:

```bash
docker compose exec postgres psql -U manual_tracing -d manual_tracing -c 'SELECT id, creation_date FROM orders;'
```

For local execution, stop the application with Ctrl+C. Stop Compose services
without removing their data:

```bash
docker compose stop
```
