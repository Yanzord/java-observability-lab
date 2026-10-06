package com.github.yanzord.distributedhttptracing.order;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenTelemetryConfiguration {

    @Bean(destroyMethod = "close")
    public OpenTelemetrySdk openTelemetrySdk(@Value("${spring.application.name}") String serviceName) {
        Resource resource = Resource.getDefault().merge(Resource.create(
                Attributes.of(AttributeKey.stringKey("service.name"), serviceName)));
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(SimpleSpanProcessor.create(new InspectingSpanExporter()))
                .build();
        return OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    }

    @Bean
    public Tracer tracer(OpenTelemetrySdk sdk) {
        return sdk.getTracer("com.github.yanzord.distributedhttptracing.order");
    }

}
