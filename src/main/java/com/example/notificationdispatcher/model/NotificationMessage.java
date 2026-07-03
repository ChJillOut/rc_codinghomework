package com.example.notificationdispatcher.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

/**
 * Represents the internal message format stored and routed through RabbitMQ queues.
 * Carries resolved configuration defaults merged with request overrides, execution progress,
 * and timestamp metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationMessage implements Serializable {

    private String notificationId;
    private String vendorId;
    private String httpMethod;
    private String targetUrl;
    private Map<String, String> headers;
    private String body;

    @Builder.Default
    private int retryCount = 0;

    @Builder.Default
    private Instant createdAt = Instant.now();
}
