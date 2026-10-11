import os
import re
import subprocess
import time
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parent
SPAN_PATTERN = re.compile(
    r"Span name=(?P<name>.*?) traceId=(?P<trace>\w+) spanId=(?P<id>\w+) "
    r"parentSpanId=(?P<parent>\w+) parentRemote=(?P<remote>\w+) kind=(?P<kind>\w+)"
)


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def spans_for(output, order_id):
    return [match.groupdict() for line in output.splitlines()
            if f"order.id={order_id}" in line
            for match in SPAN_PATTERN.finditer(line)]


def main():
    compose = ["docker", "compose", "-p", f"kafka-tracing-demo-{os.getpid()}"]

    def run_compose(*args, capture=False):
        return subprocess.run([*compose, *args], cwd=ROOT, check=True,
                              text=True, capture_output=capture).stdout

    try:
        print("Building independent Spring Boot services and starting Kafka...", flush=True)
        run_compose("up", "--build", "-d", "--wait", "--wait-timeout", "180")
        address = run_compose("port", "order-service", "8081", capture=True).strip()
        seen_traces = set()
        for attempt in range(1, 3):
            request = urllib.request.Request(f"http://{address}/orders", method="POST")
            with urllib.request.urlopen(request, timeout=20) as response:
                check(response.status == 202, "Expected HTTP 202 after broker acknowledgement")
                order_id = response.read().decode()
            deadline = time.monotonic() + 60
            while True:
                order_output = run_compose("logs", "--no-color", "order-service", capture=True)
                payment_output = run_compose("logs", "--no-color", "payment-service", capture=True)
                orders = spans_for(order_output, order_id)
                payments = spans_for(payment_output, order_id)
                if len(orders) == 2 and len(payments) == 1:
                    break
                check(time.monotonic() < deadline, f"Timed out waiting for completed spans for {order_id}")
                time.sleep(0.5)
            server = next(span for span in orders if span["kind"] == "SERVER")
            producer = next(span for span in orders if span["kind"] == "PRODUCER")
            consumer = payments[0]
            check(consumer["kind"] == "CONSUMER", "Expected a CONSUMER span")
            check(server["trace"] == producer["trace"], "Producer lost its local trace")
            check(producer["parent"] == server["id"], "Producer must be a child of SERVER")
            check(server["parent"] == consumer["parent"] == "0" * 16, "Expected two independent roots")
            check(all(span["remote"] == "false" for span in [server, producer, consumer]),
                  "Baseline must have no remote parents")
            check(consumer["trace"] != producer["trace"], "Baseline unexpectedly propagated context")
            check(server["trace"] not in seen_traces and consumer["trace"] not in seen_traces,
                  "Repeated messages must create fresh traces")
            check(f"Payment approved orderId={order_id} traceparent=null" in payment_output,
                  "Expected successful payment without traceparent")
            check('service.name="order-service"' in order_output
                  and 'service.name="payment-service"' in payment_output, "Missing service resources")
            seen_traces.update([server["trace"], consumer["trace"]])
            print(f"\nOrder {attempt}: HTTP 202, orderId={order_id}")
            print(f"trace A: {server['trace']} SERVER spanId={server['id']}")
            print(f"  PRODUCER spanId={producer['id']} parent={producer['parent']}")
            print(f"trace B: {consumer['trace']} CONSUMER parent={consumer['parent']}")
            print("Payment approved; traceparent absent; tracing continuity lost.", flush=True)
        print("\nValidated the baseline through real Kafka and two independent JVMs.", flush=True)
    except (OSError, RuntimeError, subprocess.CalledProcessError):
        subprocess.run([*compose, "logs", "--no-color", "--tail", "30"], cwd=ROOT)
        raise
    finally:
        run_compose("down")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        raise SystemExit(130)
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"Demo failed: {error}")
