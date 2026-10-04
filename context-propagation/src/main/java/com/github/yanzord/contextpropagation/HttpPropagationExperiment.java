package com.github.yanzord.contextpropagation;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

class HttpPropagationExperiment {

    private static final TextMapGetter<HttpExchange> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpExchange carrier) {
            return carrier.getRequestHeaders().keySet();
        }

        @Override
        public String get(HttpExchange carrier, String key) {
            return carrier == null ? null : carrier.getRequestHeaders().getFirst(key);
        }
    };

    static void runExperiment(Tracer tracer) throws IOException, InterruptedException {
        try (var executor = Executors.newSingleThreadExecutor()) {
            HttpServer server = startServer(tracer, executor);
            try {
                URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/payments");
                HttpResponse<String> response = sendPayment(tracer, endpoint);
                System.out.println("HTTP payment response: " + response.statusCode() + " " + response.body());
            } finally {
                server.stop(0);
            }
        }
    }

    static HttpServer startServer(Tracer tracer, Executor executor) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payments", exchange -> {
            Context previous = Context.current();
            Context extracted = W3CTraceContextPropagator.getInstance()
                    .extract(Context.root(), exchange, GETTER);
            Span request = tracer.spanBuilder("POST /payments")
                    .setSpanKind(SpanKind.SERVER).setParent(extracted).startSpan();
            try (exchange; Scope scope = request.makeCurrent()) {
                Span payment = tracer.spanBuilder("process-payment").startSpan();
                try (Scope paymentScope = payment.makeCurrent()) {
                    System.out.println("HTTP server payment trace: " + Span.current().getSpanContext().getTraceId());
                } finally {
                    payment.end();
                }
                byte[] body = "approved".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                request.end();
                System.out.println("HTTP server context restored: " + (Context.current() == previous));
            }
        });
        server.setExecutor(executor);
        server.start();
        return server;
    }

    static HttpResponse<String> sendPayment(Tracer tracer, URI endpoint) throws IOException, InterruptedException {
        Span order = tracer.spanBuilder("create-order").startSpan();
        try (Scope orderScope = order.makeCurrent(); HttpClient client = HttpClient.newHttpClient()) {
            Span request = tracer.spanBuilder("POST /payments").setSpanKind(SpanKind.CLIENT).startSpan();
            try (Scope requestScope = request.makeCurrent()) {
                var builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.noBody());
                W3CTraceContextPropagator.getInstance().inject(Context.current(), builder,
                        (carrier, key, value) -> carrier.header(key, value));
                return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            } finally {
                request.end();
            }
        } finally {
            order.end();
        }
    }
}
