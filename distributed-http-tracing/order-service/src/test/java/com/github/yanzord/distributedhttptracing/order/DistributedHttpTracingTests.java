package com.github.yanzord.distributedhttptracing.order;

import com.github.yanzord.distributedhttptracing.payment.PaymentServiceApplication;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class DistributedHttpTracingTests {

    private static final List<SpanData> spans = new CopyOnWriteArrayList<>();

    @Test
    void missingPropagationCreatesTwoIndependentTracesOverHttp() throws Exception {
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
                assertFalse(paymentServer.getParentSpanContext().isValid());
                assertFalse(paymentServer.getParentSpanContext().isRemote());
                assertNotEquals(client.getTraceId(), paymentServer.getTraceId());
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
