package com.tracelens.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * One codebase that runs as any of five services. The role is picked with DEMO_ROLE
 * (gateway | order | inventory | payment | notification) and each role only registers
 * its own controllers/listeners via {@code @ConditionalOnProperty}.
 *
 * Tracing comes from the OpenTelemetry Java agent (auto-instrumentation of Spring MVC,
 * the JDK HTTP client and Kafka) plus manual spans via {@code @WithSpan}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
