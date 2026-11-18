package com.tracelens.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TraceLens platform.
 * <pre>
 *  OTLP/JSON --> ingest --> Kafka (tracelens.spans, keyed by traceId)
 *                             --> processing: Redis trace buffer --> tail sampling
 *                                   --> rollups (all traffic)  --> PostgreSQL *_minute tables
 *                                   --> sampled spans/traces   --> PostgreSQL partitioned tables
 *  query API + alert evaluator read from PostgreSQL
 * </pre>
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class PlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
