package com.github.yanzord.manualtracing;

import com.github.yanzord.manualtracing.order.Order;
import com.github.yanzord.manualtracing.order.OrderController;
import com.github.yanzord.manualtracing.order.OrderRepository;
import com.github.yanzord.manualtracing.order.OrderService;
import com.github.yanzord.manualtracing.payment.PaymentService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TracingExperimentTests {

    private final List<SpanData> exportedSpans = new ArrayList<>();
    private final OrderRepository repository = mock(OrderRepository.class);
    private SdkTracerProvider provider;
    private OrderController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SpanExporter exporter = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                exportedSpans.addAll(spans);
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
        provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        var tracer = provider.get("com.github.yanzord.manualtracing");
        var service = new OrderService(repository, new PaymentService(tracer), tracer);
        controller = new OrderController(service, tracer);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    void requestCreatesOrderAndExportsRelatedSpans() throws Exception {
        when(repository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var previousSpanContext = Span.current().getSpanContext();

        mockMvc.perform(post("/orders"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.creationDate").isString());

        verify(repository).saveAndFlush(any(Order.class));
        assertEquals(List.of("process-payment", "persist-order", "create-order", "POST /orders"),
                exportedSpans.stream().map(SpanData::getName).toList());
        SpanData payment = exportedSpans.get(0);
        SpanData persistence = exportedSpans.get(1);
        SpanData order = exportedSpans.get(2);
        SpanData request = exportedSpans.get(3);
        assertEquals(SpanKind.SERVER, request.getKind());
        assertFalse(request.getParentSpanContext().isValid());
        assertEquals(request.getSpanId(), order.getParentSpanId());
        assertEquals(order.getSpanId(), payment.getParentSpanId());
        assertEquals(order.getSpanId(), persistence.getParentSpanId());
        assertTrue(exportedSpans.stream().allMatch(span -> span.getTraceId().equals(request.getTraceId())));
        assertEquals(4, exportedSpans.stream().map(SpanData::getSpanId).distinct().count());
        assertEquals(previousSpanContext, Span.current().getSpanContext());
    }

    @Test
    void persistenceFailureEndsSpansAndRestoresContext() {
        var failure = new DataAccessResourceFailureException("Database unavailable");
        when(repository.saveAndFlush(any(Order.class))).thenThrow(failure);
        var previousSpanContext = Span.current().getSpanContext();

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, controller::createOrder));

        assertEquals(List.of("process-payment", "persist-order", "create-order", "POST /orders"),
                exportedSpans.stream().map(SpanData::getName).toList());
        assertEquals(previousSpanContext, Span.current().getSpanContext());
    }

}
