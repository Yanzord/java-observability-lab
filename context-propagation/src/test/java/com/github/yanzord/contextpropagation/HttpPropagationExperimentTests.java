package com.github.yanzord.contextpropagation;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpPropagationExperimentTests {

    private final List<SpanData> spans = new CopyOnWriteArrayList<>();

    private SdkTracerProvider createProvider() {
        var exporter = new InspectingSpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> completedSpans) {
                spans.addAll(completedSpans);
                return CompletableResultCode.ofSuccess();
            }
        };
        return SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    }

    @Test
    void httpHeadersConnectClientServerAndPaymentAndRestoreReusedWorker() throws Exception {
        try (SdkTracerProvider provider = createProvider(); var executor = Executors.newSingleThreadExecutor()) {
            var tracer = provider.get("com.github.yanzord.contextpropagation");
            Context previous = Context.current();
            Context workerPrevious = executor.submit(Context::current).get();
            Thread worker = executor.submit(Thread::currentThread).get();
            var server = HttpPropagationExperiment.startServer(tracer, executor);
            try {
                URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/payments");
                String lastTrace = null;
                for (int attempt = 0; attempt < 2; attempt++) {
                    spans.clear();
                    var response = HttpPropagationExperiment.sendPayment(tracer, endpoint);
                    assertSame(workerPrevious, executor.submit(Context::current).get());
                    assertSame(worker, executor.submit(Thread::currentThread).get());
                    assertSame(previous, Context.current());
                    assertEquals(200, response.statusCode());
                    assertEquals("approved", response.body());
                    assertEquals(4, spans.size());
                    SpanData order = spans.stream().filter(span -> span.getName().equals("create-order")).findFirst().orElseThrow();
                    SpanData client = spans.stream().filter(span -> span.getKind() == SpanKind.CLIENT).findFirst().orElseThrow();
                    SpanData receiver = spans.stream().filter(span -> span.getKind() == SpanKind.SERVER).findFirst().orElseThrow();
                    SpanData payment = spans.stream().filter(span -> span.getName().equals("process-payment")).findFirst().orElseThrow();
                    assertTrue(spans.stream().allMatch(span -> span.getTraceId().equals(order.getTraceId())));
                    assertEquals(4, spans.stream().map(SpanData::getSpanId).distinct().count());
                    assertFalse(order.getParentSpanContext().isValid());
                    assertEquals(order.getSpanId(), client.getParentSpanId());
                    assertEquals(client.getSpanId(), receiver.getParentSpanId());
                    assertTrue(receiver.getParentSpanContext().isRemote());
                    assertEquals(receiver.getSpanId(), payment.getParentSpanId());
                    assertNotEquals(lastTrace, order.getTraceId());
                    lastTrace = order.getTraceId();
                }
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void missingOrInvalidHttpHeadersCreateIndependentServerRoots() throws Exception {
        try (SdkTracerProvider provider = createProvider(); var executor = Executors.newSingleThreadExecutor();
             HttpClient client = HttpClient.newHttpClient()) {
            var tracer = provider.get("com.github.yanzord.contextpropagation");
            Context workerPrevious = executor.submit(Context::current).get();
            var server = HttpPropagationExperiment.startServer(tracer, executor);
            Span local = tracer.spanBuilder("unrelated-local-operation").startSpan();
            try (Scope scope = local.makeCurrent()) {
                Context previous = Context.current();
                URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/payments");
                for (String header : List.of("", "invalid")) {
                    spans.clear();
                    var builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                            .POST(HttpRequest.BodyPublishers.noBody());
                    if (!header.isEmpty()) {
                        builder.header("traceparent", header);
                    }
                    var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                    assertSame(workerPrevious, executor.submit(Context::current).get());
                    assertEquals(200, response.statusCode());
                    assertEquals(2, spans.size());
                    SpanData receiver = spans.stream().filter(span -> span.getKind() == SpanKind.SERVER).findFirst().orElseThrow();
                    assertFalse(receiver.getParentSpanContext().isValid());
                    assertNotEquals(local.getSpanContext().getTraceId(), receiver.getTraceId());
                    assertSame(previous, Context.current());
                }
            } finally {
                local.end();
                server.stop(0);
            }
        }
    }
}
