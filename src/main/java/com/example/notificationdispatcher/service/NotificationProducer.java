package com.example.notificationdispatcher.service;

import com.example.notificationdispatcher.model.NotificationMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * Publishes a notification message to the main notification exchange.
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
