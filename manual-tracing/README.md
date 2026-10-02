# Manual tracing

Minimal MVC application with manual OpenTelemetry instrumentation and PostgreSQL.
Requires Docker Compose. Running or testing outside Docker also requires Java 21.

## Run

Run commands from this directory. If `.env` does not exist, create it with a
`POSTGRES_PASSWORD` variable containing a local password. Environment files and
their variants are ignored by Git.
Compose reads `.env` automatically. Build and start the application and database:

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
only PostgreSQL and export the environment before running Gradle:

```bash
docker compose stop app
docker compose up -d --wait postgres
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
dummy approval. This is separate from the application log message. The logging
exporter summary does not display events; tests inspect exported span data to
verify the event and its timestamp. The event remains recorded even if subsequent
persistence fails.

If persistence throws a runtime exception, `persist-order` records it with
`recordException()` before rethrowing the same exception. Exported span data
includes an `exception` event with type, message, stack trace, and timestamp.
The exporter summary does not display this event; the failure test inspects it.
The order and request spans do not duplicate the event. The same catch block
explicitly sets the persistence span status to `ERROR`. Successful spans keep
the default `UNSET`; no explicit `OK` is set. Status does not propagate to parent
spans, so order and request remain `UNSET` in this experiment even when the
exception reaches them. The exporter summary does not display status; tests
inspect exported span data. Exceptions continue to propagate through Spring
MVC's default error handling.

## Test

With PostgreSQL running and the environment loaded as above:

```bash
./gradlew test
```

Tests cover HTTP creation and real persistence, exported span relationships,
and span cleanup when persistence fails. The database test rolls back its data.

To inspect persisted orders:

```bash
docker compose exec postgres psql -U manual_tracing -d manual_tracing -c 'SELECT id, creation_date FROM orders;'
```

For local execution, stop the application with Ctrl+C. Stop Compose services
without removing their data:

```bash
docker compose stop
```
