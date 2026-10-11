# Kafka tracing

The fourth POC in `java-observability-lab` studies manual OpenTelemetry context
propagation between independent Spring Boot services through Kafka record headers.
Only milestone 1 is implemented: messaging **without context propagation**.

```text
POST /orders → order-service → Kafka topic orders → payment-service

trace A: POST /orders (SERVER)
         └── orders send (PRODUCER)
trace B: orders process (CONSUMER, root)
```

Java 21, Gradle 9.7.1, Spring Boot 4.1.1, and the manually configured
OpenTelemetry SDK follow the previous POC. Spring Kafka handles transport;
`Tracer`, `Span`, and `Scope` remain explicit. No Java Agent, automatic tracing,
Micrometer Tracing, or tracing backend is used. Both Kafka observation properties
are explicitly disabled; see [Spring Boot Kafka properties](https://docs.spring.io/spring-boot/4.0/appendix/application-properties/).
Kafka uses the [official Apache image](https://kafka.apache.org/41/getting-started/docker/)
at version 4.1.2, with one combined KRaft broker/controller and one topic partition.

## Run and observe the baseline

With Docker and Docker Compose available, from this directory:

```bash
python3 demo.py
```

The standard-library demo builds and starts Kafka and two independent JVMs in
an isolated Compose project. It sends two orders, waits for asynchronous payment
processing, checks span relationships and absent `traceparent`, and prints the
two traces per order. It removes its containers and network afterward.
Port 9092 must be free; the order HTTP port is allocated dynamically on loopback.
The broker data belongs to the container and is discarded on `compose down`.

To inspect the services manually:

```bash
docker compose up --build -d --wait
docker compose port order-service 8081
curl -i -X POST http://127.0.0.1:<allocated-port>/orders
docker compose logs --no-color order-service payment-service
docker compose down
```

`POST /orders` takes no body and returns HTTP 202 with a generated order ID.
The ID is both the Kafka key and string payload; no persistence or JSON schema
is needed. A one-shot Compose service explicitly creates `orders` before either
application starts. Payment uses a fixed group, earliest offsets for a new group,
and record acknowledgement after the listener returns successfully.

The producer waits for broker acknowledgement before ending its PRODUCER span
and returning HTTP 202. This does not wait for payment approval. The consumer
may start before or after that HTTP response; Kafka delivery and payment
execution are asynchronous relative to the request. The PRODUCER span covers
the local send through acknowledgement, rather than the payment operation.
Send failures are rethrown, recorded on the PRODUCER, and end both order spans.

The producer makes the PRODUCER current but sends a `ProducerRecord` with no
tracing headers. A current in-process context is not automatically serialized
into a Kafka record. The consumer logs the received `traceparent` (`null`) and
starts a root CONSUMER using `setNoParent()`. It logs dummy payment approval.
Both services export completed spans locally with separate SDKs and
`service.name` resources. Request spans cover the controller handler only.

For each order, compare the exported logs:

- SERVER and PRODUCER share a Trace ID; PRODUCER's parent is SERVER's Span ID.
- CONSUMER has a different Trace ID, zero parent ID, and `parentRemote=false`.
- `order.id` correlates business operations but does not establish trace ancestry.
- The message is processed successfully despite lost trace continuity.
- Scopes close independently of `span.end()` and do not leak into later work.

## Validate

With Java 21:

```bash
./gradlew test bootJar
```

JUnit uses the existing Jupiter/Mockito conventions. Tests cover producer
headers and local parent identity, independent consumer roots on a reused
worker, exact context restoration, and span completion/error recording after
a failed send. Java tests mock transport; `python3 demo.py` separately validates
actual Kafka delivery, HTTP 202, service identities, and fresh traces across
two messages in independent containers.

## Next milestones

1. **Baseline without propagation:** the currently implemented experiment.
2. **Manual W3C propagation:** inject the current PRODUCER context into record
   headers as UTF-8 bytes; extract from `Context.root()` on payment and use the
   extracted context as the CONSUMER parent. Compare shared Trace IDs and
   remote parent identity with this baseline. The version 00 `traceparent`
   should carry the PRODUCER Span ID, not the receiving CONSUMER Span ID.
3. **Propagation failures:** omit/corrupt headers and omit extraction; compare
   independent roots with the valid path and verify scope cleanup on reused workers.

Milestones 2 and 3 are intentionally pending. See [CONTEXT.md](CONTEXT.md).
