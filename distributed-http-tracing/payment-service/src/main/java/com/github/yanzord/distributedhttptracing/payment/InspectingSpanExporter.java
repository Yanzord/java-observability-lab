package com.github.yanzord.distributedhttptracing.payment;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

public class InspectingSpanExporter implements SpanExporter {

    private static final Logger logger = LoggerFactory.getLogger(InspectingSpanExporter.class);

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        for (SpanData span : spans) {
            logger.info("Span name={} traceId={} spanId={} parentSpanId={} parentRemote={} kind={} startEpochNanos={} endEpochNanos={} durationMs={} status={} attributes={} events={} resource={} scope={}",
                    span.getName(), span.getTraceId(), span.getSpanId(), span.getParentSpanId(),
                    span.getParentSpanContext().isRemote(), span.getKind(), span.getStartEpochNanos(), span.getEndEpochNanos(),
                    (span.getEndEpochNanos() - span.getStartEpochNanos()) / 1_000_000.0,
                    span.getStatus(), span.getAttributes(), span.getEvents(),
                    span.getResource(), span.getInstrumentationScopeInfo());
        }
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

}
