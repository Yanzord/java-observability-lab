package com.github.yanzord.manualtracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenTelemetryConfiguration {

    @Bean(destroyMethod = "close")
    public OpenTelemetrySdk openTelemetrySdk(
            @Value("${otel.exporter.otlp.traces.endpoint:http://localhost:4318/v1/traces}") String endpoint,
            @Value("${spring.application.name}") String serviceName) {
        Resource resource = Resource.getDefault().merge(Resource.create(
                Attributes.of(AttributeKey.stringKey("service.name"), serviceName)));
        OtlpHttpSpanExporter otlpExporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .build();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(SimpleSpanProcessor.create(new InspectingSpanExporter()))
                .addSpanProcessor(SimpleSpanProcessor.create(otlpExporter))
                .build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
    }

    @Bean
    public Tracer tracer(OpenTelemetrySdk openTelemetrySdk) {
        return openTelemetrySdk.getTracer("com.github.yanzord.manualtracing");
    }

}
