package com.github.yanzord.distributedhttptracing.order;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@RestController
public class OrderController {

    private final Tracer tracer;
    private final URI paymentUrl;

    public OrderController(Tracer tracer, @Value("${payment.url}") URI paymentUrl) {
        this.tracer = tracer;
        this.paymentUrl = paymentUrl;
    }

    @PostMapping("/orders")
    public String createOrder() throws Exception {
        Span server = tracer.spanBuilder("POST /orders")
                .setNoParent().setSpanKind(SpanKind.SERVER).startSpan();
        try (Scope scope = server.makeCurrent()) {
            Span client = tracer.spanBuilder("POST /payments").setSpanKind(SpanKind.CLIENT).startSpan();
            try (Scope clientScope = client.makeCurrent(); HttpClient http = HttpClient.newHttpClient()) {
                HttpRequest request = HttpRequest.newBuilder(paymentUrl).timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.noBody()).build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("Payment returned HTTP " + response.statusCode());
                }
                return response.body();
            } finally {
                client.end();
            }
        } finally {
            server.end();
        }
    }

}
