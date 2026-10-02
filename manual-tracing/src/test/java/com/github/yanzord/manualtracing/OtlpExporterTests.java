package com.github.yanzord.manualtracing;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtlpExporterTests {

    private HttpServer server;
    private String endpoint;
    private volatile int responseStatus = 200;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<byte[]> body = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            method.set(exchange.getRequestMethod());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sdkExportsCompletedSpanAsProtobufOverHttp() {
        try (var sdk = new OpenTelemetryConfiguration().openTelemetrySdk(endpoint, "test-service")) {
            var span = sdk.getTracer("otlp-test").spanBuilder("otlp-experiment").startSpan();
            span.setAttribute("order.id", 42L);
            span.addEvent("payment-approved");
            span.end();

            var resource = ((ReadableSpan) span).toSpanData().getResource();
            assertEquals("test-service", resource.getAttribute(AttributeKey.stringKey("service.name")));
            assertEquals("java", resource.getAttribute(AttributeKey.stringKey("telemetry.sdk.language")));
            assertEquals("opentelemetry", resource.getAttribute(AttributeKey.stringKey("telemetry.sdk.name")));

            var result = sdk.getSdkTracerProvider().forceFlush().join(10, TimeUnit.SECONDS);
            assertTrue(result.isSuccess());
            assertEquals("POST", method.get());
            assertEquals("application/x-protobuf", contentType.get());
            String payload = new String(body.get(), StandardCharsets.ISO_8859_1);
            assertTrue(payload.contains("otlp-experiment"));
            assertTrue(payload.contains("order.id"));
            assertTrue(payload.contains("payment-approved"));
            assertTrue(payload.contains("service.name"));
            assertTrue(payload.contains("test-service"));
        }
    }

    @Test
    void exporterReportsFailureWhenReceiverRejectsPayload() {
        responseStatus = 400;
        try (var provider = SdkTracerProvider.builder().build();
             var exporter = OtlpHttpSpanExporter.builder().setEndpoint(endpoint).build()) {
            var span = provider.get("otlp-test").spanBuilder("rejected-span").startSpan();
            span.end();

            var result = exporter.export(List.of(((ReadableSpan) span).toSpanData()))
                    .join(10, TimeUnit.SECONDS);

            assertFalse(result.isSuccess());
            assertEquals("POST", method.get());
        }
    }

}
