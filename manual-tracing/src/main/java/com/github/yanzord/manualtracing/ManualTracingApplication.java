package com.github.yanzord.manualtracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ManualTracingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ManualTracingApplication.class, args);
    }

    @Bean
    public CommandLineRunner tracingExperiment(Tracer tracer) {
        return args -> {
            Span span = tracer.spanBuilder("first-experiment").startSpan();
            try {
                System.out.println("Running the first tracing experiment");
            } finally {
                span.end();
            }
        };
    }

}
