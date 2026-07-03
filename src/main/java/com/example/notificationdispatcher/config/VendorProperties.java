package com.example.notificationdispatcher.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "notification")
public class VendorProperties {

    private Map<String, VendorConfig> vendors;
    private RetryConfig retry;

    @Data
    public static class VendorConfig {
        private String url;
        private Map<String, String> defaultHeaders;
        private int timeoutSeconds;
    }

    @Data
    public static class RetryConfig {
        private int maxRetries;
        private List<Long> delays;
    }
}
