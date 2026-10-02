package com.github.yanzord.manualtracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
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
            System.out.println("Before creation: " + Span.current().getSpanContext().getSpanId());
            Span span = tracer.spanBuilder("scope-experiment").startSpan();
            try {
                System.out.println("Created span: " + span.getSpanContext().getSpanId());
                System.out.println("Before scope: " + Span.current().getSpanContext().getSpanId());
                try (Scope scope = span.makeCurrent()) {
                    System.out.println("Inside scope: " + Span.current().getSpanContext().getSpanId());
                }
                System.out.println("After scope: " + Span.current().getSpanContext().getSpanId());
                System.out.println("Recording after scope: " + span.isRecording());
            } finally {
                span.end();
            }
            System.out.println("Recording after end: " + span.isRecording());
        };
    }

}
