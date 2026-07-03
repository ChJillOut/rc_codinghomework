package com.example.notificationdispatcher.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Data Transfer Object representing an inbound notification request sent by calling business systems.
 * Contains vendor information, payload body, and optional HTTP configuration overrides.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRequest {

    @NotBlank(message = "vendorId is required")
    private String vendorId;

    /**
     * HTTP method for the vendor call. Defaults to POST if not specified.
     */
    private String httpMethod;

    /**
     * Optional URL override. If not provided, uses vendor's configured URL.
     */
    private String url;

    /**
     * Optional header overrides. Merged with vendor's default headers.
     */
    private Map<String, String> headers;

    @NotBlank(message = "body is required")
    private String body;
}
