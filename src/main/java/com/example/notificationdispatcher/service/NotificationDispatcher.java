package com.example.notificationdispatcher.service;

import com.example.notificationdispatcher.exception.DispatchException;
import com.example.notificationdispatcher.model.NotificationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Slf4j
@Service
public class NotificationDispatcher {

    private final RestClient restClient;

    public NotificationDispatcher() {
        this.restClient = RestClient.create();
    }

    /**
     * Dispatches a notification to the target vendor URL.
     *
     * @param message the notification message containing URL, method, headers, and body
     * @throws DispatchException if the request fails (with retryable flag set appropriately)
     */
    public void dispatch(NotificationMessage message) {
        log.info("Dispatching notification, notificationId={} vendorId={} url={} method={}",
                message.getNotificationId(), message.getVendorId(),
                message.getTargetUrl(), message.getHttpMethod());

        try {
            restClient.method(HttpMethod.valueOf(message.getHttpMethod().toUpperCase()))
                    .uri(message.getTargetUrl())
                    .headers(httpHeaders -> {
                        if (message.getHeaders() != null) {
                            message.getHeaders().forEach(httpHeaders::set);
                        }
                    })
                    .body(message.getBody())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        log.warn("Vendor returned client error, notificationId={} status={} url={}",
                                message.getNotificationId(), response.getStatusCode().value(), message.getTargetUrl());
                        throw new DispatchException(
                                "Vendor returned client error: " + response.getStatusCode().value(),
                                false,
                                response.getStatusCode().value()
                        );
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        log.warn("Vendor returned server error, notificationId={} status={} url={}",
                                message.getNotificationId(), response.getStatusCode().value(), message.getTargetUrl());
                        throw new DispatchException(
                                "Vendor returned server error: " + response.getStatusCode().value(),
                                true,
                                response.getStatusCode().value()
                        );
                    })
                    .toBodilessEntity();

            log.info("Notification dispatched successfully, notificationId={} vendorId={}",
                    message.getNotificationId(), message.getVendorId());

        } catch (ResourceAccessException e) {
            // Timeout or connection error — retryable
            log.warn("Dispatch failed due to connection/timeout error, notificationId={} vendorId={} url={}",
                    message.getNotificationId(), message.getVendorId(), message.getTargetUrl(), e);
            throw new DispatchException("Connection/timeout error: " + e.getMessage(), true, 0, e);
        } catch (DispatchException e) {
            // Re-throw our own exceptions
            throw e;
        } catch (Exception e) {
            // Unexpected error — retryable (conservative)
            log.error("Unexpected dispatch error, notificationId={} vendorId={} url={}",
                    message.getNotificationId(), message.getVendorId(), message.getTargetUrl(), e);
            throw new DispatchException("Unexpected error: " + e.getMessage(), true, 0, e);
        }
    }
}
