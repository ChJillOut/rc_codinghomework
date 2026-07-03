package com.example.notificationdispatcher.service;

import com.example.notificationdispatcher.model.NotificationMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Producer service responsible for publishing {@link NotificationMessage} objects to RabbitMQ exchanges
 * (main exchange, retry exchange, or dead-letter exchange).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * Publishes a notification message to the main notification exchange.
     *
     * @param message the notification message to publish
     */
    public void send(NotificationMessage message) {
        log.info("Publishing notification to queue, notificationId={} vendorId={}",
                message.getNotificationId(), message.getVendorId());
        rabbitTemplate.convertAndSend(
                "notification.exchange",
                "notification",
                message
        );
    }

    /**
     * Publishes a notification message to a specific retry queue.
     *
     * @param message the notification message to route
     * @param retryLevel the target retry level (1, 2, or 3) representing the retry queue
     */
    public void sendToRetry(NotificationMessage message, int retryLevel) {
        String routingKey = "retry." + retryLevel;
        log.info("Publishing notification to retry queue, notificationId={} vendorId={} retryLevel={}",
                message.getNotificationId(), message.getVendorId(), retryLevel);
        rabbitTemplate.convertAndSend(
                "notification.retry.exchange",
                routingKey,
                message
        );
    }

    /**
     * Publishes a notification message to the dead-letter queue.
     *
     * @param message the notification message to move to the DLQ
     */
    public void sendToDlq(NotificationMessage message) {
        log.info("Publishing notification to DLQ, notificationId={} vendorId={} retryCount={}",
                message.getNotificationId(), message.getVendorId(), message.getRetryCount());
        rabbitTemplate.convertAndSend(
                "notification.dlx.exchange",
                "dlq",
                message
        );
    }
}
