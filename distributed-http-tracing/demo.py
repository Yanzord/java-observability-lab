import os
import re
import shutil
import subprocess
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


def show_trace(order_output, payment_output, attempt):
    order_spans = [match.groupdict() for match in SPAN_PATTERN.finditer(order_output)]
    payment_spans = [match.groupdict() for match in SPAN_PATTERN.finditer(payment_output)]
    headers = re.findall(r"Received traceparent=(\S+) parentRemote=(\w+)", payment_output)
    check(len(order_spans) == attempt * 2 and len(payment_spans) == len(headers) == attempt,
          "Expected two order spans, one payment span, and one received header per request")
    order = next(span for span in order_spans[-2:] if span["kind"] == "SERVER")
    client = next(span for span in order_spans[-2:] if span["kind"] == "CLIENT")
    payment = payment_spans[-1]
    header, remote = headers[-1]
    check(order["trace"] == client["trace"] == payment["trace"], "Trace IDs differ across services")
    check(order["parent"] == "0" * 16, "Order SERVER should be a root")
    check(client["parent"] == order["id"], "CLIENT parent must be the order SERVER")
    check(payment["kind"] == "SERVER" and payment["parent"] == client["id"],
          "Payment SERVER parent must be the order CLIENT")
    check(payment["remote"] == remote == "true" and order["remote"] == client["remote"] == "false",
          "Only the payment SERVER should have a remote parent")
    check(len({order["id"], client["id"], payment["id"]}) == 3, "Span IDs must be distinct")
    check(re.fullmatch(r"00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}", header), "Unexpected traceparent format")
    version, trace_id, parent_id, flags = header.split("-")
    check(trace_id == client["trace"] and parent_id == client["id"], "Header must identify the CLIENT context")
    check(int(flags, 16) & 1 == 1, "Expected the default SDK to sample this trace")
    print(f"\nRequest {attempt}: HTTP 200 approved")
    print(f"Received traceparent: {header}")
    print(f"  version={version}, traceId={trace_id}, parentId={parent_id}, flags={flags}")
    print(f"Shared Trace ID: {order['trace']}")
    print(f"order-service SERVER    spanId={order['id']} parent={order['parent']}")
    print(f"└── order-service CLIENT spanId={client['id']} parent={client['parent']}")
    print(f"    └── payment SERVER   spanId={payment['id']} parent={payment['parent']} remote=true")
    print("The header carries the CLIENT Span ID; payment creates its own span with that remote parent.", flush=True)
    return order["trace"]


def main():
    check(shutil.which("docker"), "Docker was not found. Install Docker with Compose.")
    compose = ["docker", "compose", "-p", f"distributed-http-demo-{os.getpid()}"]

    def run_compose(*args, capture=False):
        return subprocess.run(
            [*compose, *args], cwd=ROOT, check=True, text=True, capture_output=capture
        ).stdout

    print("Building and starting two Spring Boot containers with independent JVMs...", flush=True)
    try:
        run_compose("up", "--build", "-d", "--wait", "--wait-timeout", "120")
        address = run_compose("port", "order-service", "8081", capture=True).strip()
        payment_address = run_compose("port", "payment-service", "8082", capture=True).strip()
        print(f"\norder-service: http://{address}", flush=True)
        print("order-service calls http://payment-service:8082/payments through the Compose network.")
        previous_trace = None
        for attempt in range(1, 3):
            request = urllib.request.Request(f"http://{address}/orders", method="POST")
            with urllib.request.urlopen(request, timeout=10) as response:
                check(response.status == 200 and response.read() == b"approved", "Unexpected order response")
            order_output = run_compose("logs", "--no-color", "order-service", capture=True)
            payment_output = run_compose("logs", "--no-color", "payment-service", capture=True)
            trace = show_trace(order_output, payment_output, attempt)
            check(trace != previous_trace, "Each order request must start a fresh trace")
            previous_trace = trace
        print("\nCalling payment directly to isolate header extraction; these requests bypass order-service.")
        valid = "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01"
        cases = [("Missing header", None), ("Malformed header", "invalid"),
                 ("Zero IDs", "00-00000000000000000000000000000000-0000000000000000-01"),
                 ("Valid header after invalid requests", valid), ("Missing header after valid request", None)]
        seen_traces = {previous_trace}
        for index, (label, header) in enumerate(cases, start=3):
            headers = {} if header is None else {"traceparent": header}
            request = urllib.request.Request(f"http://{payment_address}/payments", headers=headers, method="POST")
            with urllib.request.urlopen(request, timeout=10) as response:
                check(response.status == 200 and response.read() == b"approved", "Unexpected payment response")
            output = run_compose("logs", "--no-color", "payment-service", capture=True)
            spans = [match.groupdict() for match in SPAN_PATTERN.finditer(output)]
            received = re.findall(r"Received traceparent=(\S+) parentRemote=(\w+)", output)
            check(len(spans) == len(received) == index, "Expected one completed payment span per request")
            server = spans[-1]
            check(server["kind"] == "SERVER", "Expected a payment SERVER span")
            check(received[-1][0] == (header if header is not None else "null"), "Unexpected received header")
            if header == valid:
                check(server["trace"] == valid.split("-")[1] and server["parent"] == valid.split("-")[2],
                      "Valid context must be preserved after invalid requests")
                check(server["remote"] == received[-1][1] == "true", "Expected a remote parent")
            else:
                check(server["parent"] == "0" * 16 and server["remote"] == received[-1][1] == "false",
                      "Missing or invalid context must create a root")
                check(server["trace"] not in seen_traces and server["trace"] != valid.split("-")[1],
                      "A root must not reuse a previous or supplied trace")
            seen_traces.add(server["trace"])
            print(f"\n{label}: HTTP 200 approved, received traceparent={received[-1][0]}")
            print(f"payment SERVER traceId={server['trace']} parent={server['parent']} remote={server['remote']}")
            print("Valid header: the supplied remote parent is preserved." if header == valid else
                  "No valid remote context: payment starts an independent root trace.", flush=True)
        print("\nValidated: propagation, absent/malformed headers, zero IDs, and no previous-parent reuse.")
        print("JUnit additionally checks exact Scope restoration on the same worker and after HTTP failure.", flush=True)
    except (OSError, RuntimeError, subprocess.CalledProcessError):
        subprocess.run([*compose, "logs", "--no-color", "--tail", "30"], cwd=ROOT)
        raise
    finally:
        run_compose("down")
        print("Demo containers and network removed.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        raise SystemExit(130)
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"Demo failed: {error}")
