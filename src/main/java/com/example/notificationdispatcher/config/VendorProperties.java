package com.example.notificationdispatcher.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Configuration properties class mapping properties prefixed with {@code notification.*} from
 * {@code application.yml} into strongly typed Java beans. Includes mapping configurations for
 * outbound vendor endpoints and internal backoff retries.
 */
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
