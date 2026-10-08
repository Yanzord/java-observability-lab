package com.github.yanzord.distributedhttptracing.order;

import com.github.yanzord.distributedhttptracing.payment.PaymentServiceApplication;
import com.github.yanzord.distributedhttptracing.payment.PaymentController;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class DistributedHttpTracingTests {

    private static final List<SpanData> spans = new CopyOnWriteArrayList<>();

    @Test
    void manualPropagationConnectsIndependentServicesOverHttp() throws Exception {
        spans.clear();
        try (var payment = new SpringApplicationBuilder(PaymentServiceApplication.class, CaptureConfiguration.class)
                .run("--server.port=0", "--spring.application.name=payment-service");
             var order = new SpringApplicationBuilder(OrderServiceApplication.class, CaptureConfiguration.class)
                     .run("--server.port=0", "--spring.application.name=order-service",
                             "--payment.url=http://127.0.0.1:" + port(payment) + "/payments");
             HttpClient http = HttpClient.newHttpClient()) {
            String previousOrderTrace = null;
            String previousPaymentTrace = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                spans.clear();
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port(order) + "/orders"))
                        .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.noBody()).build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertEquals("approved", response.body());
                assertEquals(3, spans.size());
                SpanData orderServer = spans.stream().filter(s -> s.getName().equals("POST /orders")).findFirst().orElseThrow();
                SpanData client = spans.stream().filter(s -> s.getKind() == SpanKind.CLIENT).findFirst().orElseThrow();
                SpanData paymentServer = spans.stream().filter(s -> s.getKind() == SpanKind.SERVER
                        && s.getName().equals("POST /payments")).findFirst().orElseThrow();
                assertEquals(SpanKind.SERVER, orderServer.getKind());
                assertFalse(orderServer.getParentSpanContext().isValid());
                assertEquals(orderServer.getTraceId(), client.getTraceId());
                assertEquals(orderServer.getSpanId(), client.getParentSpanId());
                assertTrue(paymentServer.getParentSpanContext().isValid());
                assertTrue(paymentServer.getParentSpanContext().isRemote());
                assertEquals(client.getTraceId(), paymentServer.getTraceId());
                assertEquals(client.getSpanId(), paymentServer.getParentSpanId());
                assertEquals(client.getSpanContext().getTraceFlags(), paymentServer.getParentSpanContext().getTraceFlags());
                assertEquals(3, spans.stream().map(SpanData::getSpanId).distinct().count());
                assertEquals("order-service", client.getResource().getAttribute(AttributeKey.stringKey("service.name")));
                assertEquals("payment-service", paymentServer.getResource().getAttribute(AttributeKey.stringKey("service.name")));
                assertNotEquals(previousOrderTrace, orderServer.getTraceId());
                assertNotEquals(previousPaymentTrace, paymentServer.getTraceId());
                previousOrderTrace = orderServer.getTraceId();
                previousPaymentTrace = paymentServer.getTraceId();
            }
        }
    }

    private int port(org.springframework.context.ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    @Test
    void validMissingAndMalformedHeadersOverHttpDoNotReusePreviousParents() throws Exception {
        spans.clear();
        String traceId = "1234567890abcdef1234567890abcdef";
        String parentId = "1234567890abcdef";
        String valid = "00-" + traceId + "-" + parentId + "-01";
        List<String> headers = List.of(valid, "", "invalid",
                "00-00000000000000000000000000000000-0000000000000000-01", valid, "");
        try (var payment = new SpringApplicationBuilder(PaymentServiceApplication.class, CaptureConfiguration.class)
                .run("--server.port=0", "--spring.application.name=payment-service",
                        "--server.tomcat.threads.max=1", "--server.tomcat.threads.min-spare=1");
             HttpClient http = HttpClient.newHttpClient()) {
            String previousTrace = null;
            for (String header : headers) {
                spans.clear();
                var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port(payment) + "/payments"))
                        .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.noBody());
                if (!header.isEmpty()) {
                    builder.header("TrAcEpArEnT", header);
                }
                var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertEquals("approved", response.body());
                assertEquals(1, spans.size());
                SpanData server = spans.getFirst();
                assertEquals(SpanKind.SERVER, server.getKind());
                if (header.equals(valid)) {
                    assertEquals(traceId, server.getTraceId());
                    assertEquals(parentId, server.getParentSpanId());
                    assertTrue(server.getParentSpanContext().isRemote());
                } else {
                    assertFalse(server.getParentSpanContext().isValid());
                    assertFalse(server.getParentSpanContext().isRemote());
                    assertNotEquals(traceId, server.getTraceId());
                    assertNotEquals(previousTrace, server.getTraceId());
                }
                previousTrace = server.getTraceId();
            }
        }
    }

    @Test
    void paymentRestoresContextOnTheSameWorkerAndIgnoresUnrelatedLocalParent() throws Exception {
        spans.clear();
        try (var provider = new CaptureConfiguration().capturedProvider("payment-service");
             var worker = Executors.newSingleThreadExecutor()) {
            Tracer tracer = provider.get("scope-test");
            PaymentController controller = new PaymentController(tracer);
            Thread thread = worker.submit(Thread::currentThread).get();
            Context original = worker.submit(Context::current).get();
            worker.submit(() -> {
                Span unrelated = tracer.spanBuilder("unrelated-local-operation").startSpan();
                try (Scope scope = unrelated.makeCurrent()) {
                    Context previous = Context.current();
                    for (String header : List.of("00-1234567890abcdef1234567890abcdef-1234567890abcdef-01",
                            "", "invalid", "")) {
                        spans.clear();
                        var request = new MockHttpServletRequest();
                        if (!header.isEmpty()) {
                            request.addHeader("traceparent", header);
                        }
                        assertEquals("approved", controller.processPayment(request));
                        assertSame(previous, Context.current());
                        assertEquals(1, spans.size());
                        SpanData server = spans.getFirst();
                        assertNotEquals(unrelated.getSpanContext().getTraceId(), server.getTraceId());
                        if (header.startsWith("00-")) {
                            assertTrue(server.getParentSpanContext().isRemote());
                        } else {
                            assertFalse(server.getParentSpanContext().isValid());
                        }
                    }
                } finally {
                    unrelated.end();
                }
                assertSame(original, Context.current());
            }).get();
            assertSame(thread, worker.submit(Thread::currentThread).get());
            assertSame(original, worker.submit(Context::current).get());
        }
    }

    @Test
    void paymentHttpFailureEndsOrderSpansAndRestoresCallerContext() throws Exception {
        spans.clear();
        var payment = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        payment.createContext("/payments", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(500, -1);
            }
        });
        payment.start();
        try (var provider = new CaptureConfiguration().capturedProvider("order-service")) {
            Tracer tracer = provider.get("scope-test");
            var controller = new OrderController(tracer,
                    URI.create("http://127.0.0.1:" + payment.getAddress().getPort() + "/payments"));
            Context original = Context.current();
            Span unrelated = tracer.spanBuilder("unrelated-caller").startSpan();
            try (Scope scope = unrelated.makeCurrent()) {
                Context previous = Context.current();
                var error = assertThrows(IllegalStateException.class, controller::createOrder);
                assertEquals("Payment returned HTTP 500", error.getMessage());
                assertSame(previous, Context.current());
                assertEquals(2, spans.size());
                SpanData client = spans.getFirst();
                SpanData server = spans.getLast();
                assertEquals(SpanKind.CLIENT, client.getKind());
                assertEquals(SpanKind.SERVER, server.getKind());
                assertEquals(server.getSpanId(), client.getParentSpanId());
                assertFalse(server.getParentSpanContext().isValid());
            } finally {
                unrelated.end();
            }
            assertSame(original, Context.current());
        } finally {
            payment.stop(0);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CaptureConfiguration {

        @Bean(destroyMethod = "close")
        SdkTracerProvider capturedProvider(@Value("${spring.application.name}") String serviceName) {
            var exporter = new InspectingSpanExporter() {
                @Override
                public CompletableResultCode export(Collection<SpanData> completedSpans) {
                    spans.addAll(completedSpans);
                    return CompletableResultCode.ofSuccess();
                }
            };
            return SdkTracerProvider.builder()
                    .setResource(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), serviceName)))
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        }

        @Bean
        @Primary
        Tracer capturedTracer(SdkTracerProvider capturedProvider) {
            return capturedProvider.get("distributed-http-tracing-test");
        }
    }
}
