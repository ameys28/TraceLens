package com.tracelens.platform.config;

import com.tracelens.platform.core.RollupAccumulator;
import com.tracelens.platform.core.TailSamplingPolicy;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class PlatformConfig implements WebMvcConfigurer {

    @Bean
    public NewTopic spanTopic(TraceLensProperties props) {
        // Keyed by traceId, so all spans of a trace land on the same partition (ordering + locality).
        return TopicBuilder.name(props.spanTopic()).partitions(3).replicas(1).build();
    }

    @Bean
    public TailSamplingPolicy tailSamplingPolicy(TraceLensProperties props) {
        return new TailSamplingPolicy(props.sampling().baselineRate());
    }

    @Bean
    public RollupAccumulator rollupAccumulator() {
        return new RollupAccumulator();
    }

    /** Lets the UI (nginx on :3000, or the Vite dev server on :5173) call the API with POST/DELETE. */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173", "http://localhost:3000", "http://127.0.0.1:3000")
                .allowedMethods("*");
    }
}
