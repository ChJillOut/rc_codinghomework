package com.example.notificationdispatcher.controller;

import com.example.notificationdispatcher.config.VendorProperties;
import com.example.notificationdispatcher.config.VendorProperties.VendorConfig;
import com.example.notificationdispatcher.model.NotificationMessage;
import com.example.notificationdispatcher.model.NotificationRequest;
import com.example.notificationdispatcher.service.NotificationProducer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller providing API endpoints to submit notification tasks from business systems.
 * Mapped to the root endpoint {@code /api/notifications}.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationProducer producer;
    private final VendorProperties vendorProperties;

    /**
     * Accepts a notification request from a business system.
     * Validates the request, resolves vendor config, builds a NotificationMessage,
     * and publishes it to RabbitMQ for async processing.
     *
     * @param request the inbound notification request containing vendor ID, body, and configuration overrides
     * @return a {@link ResponseEntity} containing the generated notification ID and status (202 Accepted)
     */
    @PostMapping("/notifications")
    public ResponseEntity<Map<String, String>> submitNotification(
            @Valid @RequestBody NotificationRequest request) {

        // Resolve vendor config
        VendorConfig vendorConfig = vendorProperties.getVendors().get(request.getVendorId());
        if (vendorConfig == null) {
            log.warn("Unknown vendorId={}", request.getVendorId());
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Unknown vendor: " + request.getVendorId()));
        }

        // Resolve target URL: request override > vendor default
        String targetUrl = (request.getUrl() != null && !request.getUrl().isBlank())
                ? request.getUrl()
                : vendorConfig.getUrl();

        // Resolve HTTP method: request override > default POST
        String httpMethod = (request.getHttpMethod() != null && !request.getHttpMethod().isBlank())
                ? request.getHttpMethod().toUpperCase()
                : "POST";

        // Merge headers: vendor defaults + request overrides (request takes precedence)
        Map<String, String> mergedHeaders = new HashMap<>();
        if (vendorConfig.getDefaultHeaders() != null) {
            mergedHeaders.putAll(vendorConfig.getDefaultHeaders());
        }
        if (request.getHeaders() != null) {
            mergedHeaders.putAll(request.getHeaders());
        }

        // Build the internal message
        String notificationId = UUID.randomUUID().toString();
        NotificationMessage message = NotificationMessage.builder()
                .notificationId(notificationId)
                .vendorId(request.getVendorId())
                .httpMethod(httpMethod)
                .targetUrl(targetUrl)
                .headers(mergedHeaders)
                .body(request.getBody())
                .retryCount(0)
                .createdAt(Instant.now())
                .build();

        // Publish to RabbitMQ
        producer.send(message);

        log.info("Notification accepted, notificationId={} vendorId={} targetUrl={}",
                notificationId, request.getVendorId(), targetUrl);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of(
                        "notificationId", notificationId,
                        "status", "accepted"
                ));
    }
}
