package com.github.yanzord.manualtracing.order;

import com.github.yanzord.manualtracing.payment.PaymentService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class OrderService {

    private final OrderRepository repository;
    private final PaymentService paymentService;
    private final Tracer tracer;

    public OrderService(OrderRepository repository, PaymentService paymentService, Tracer tracer) {
        this.repository = repository;
        this.paymentService = paymentService;
        this.tracer = tracer;
    }

    public Order createOrder() {
        Span span = tracer.spanBuilder("create-order").startSpan();
        try (Scope scope = span.makeCurrent()) {
            Order order = new Order(LocalDateTime.now());
            paymentService.processPayment();
            Span persistenceSpan = tracer.spanBuilder("persist-order").startSpan();
            try (Scope persistenceScope = persistenceSpan.makeCurrent()) {
                Order savedOrder = repository.saveAndFlush(order);
                persistenceSpan.setAttribute("order.id", savedOrder.getId());
                span.setAttribute("order.id", savedOrder.getId());
                return savedOrder;
            } catch (RuntimeException exception) {
                persistenceSpan.recordException(exception);
                throw exception;
            } finally {
                persistenceSpan.end();
            }
        } finally {
            span.end();
        }
    }

}
