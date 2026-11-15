package com.tracelens.demo.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo")
public record DemoProperties(String role, Urls urls) {
    public record Urls(String order, String inventory, String payment) {}
}
