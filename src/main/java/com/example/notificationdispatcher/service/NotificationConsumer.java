package com.example.notificationdispatcher.service;

import com.example.notificationdispatcher.config.VendorProperties;
import com.example.notificationdispatcher.exception.DispatchException;
import com.example.notificationdispatcher.model.NotificationMessage;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationConsumer {

    private final NotificationDispatcher dispatcher;
    private final NotificationProducer producer;
    private final VendorProperties vendorProperties;

    /**
     * Consumes notification messages from the main queue.
     * On success: ACK the message.
     * On retryable failure: route to the appropriate retry queue based on retry count.
     * On permanent failure or max retries exceeded: route to DLQ.
     */
    @RabbitListener(queues = "notification.queue")
    public void consume(NotificationMessage message,
                        Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.info("Consumed notification, notificationId={} vendorId={} retryCount={}",
                message.getNotificationId(), message.getVendorId(), message.getRetryCount());

        try {
            dispatcher.dispatch(message);
            // Success — acknowledge the message
            channel.basicAck(deliveryTag, false);
            log.info("Notification processed successfully, notificationId={} vendorId={}",
                    message.getNotificationId(), message.getVendorId());

        } catch (DispatchException e) {
            handleDispatchFailure(message, channel, deliveryTag, e);
        } catch (Exception e) {
            // Unexpected error — treat as retryable
            log.error("Unexpected error consuming notification, notificationId={} vendorId={}",
                    message.getNotificationId(), message.getVendorId(), e);
            handleDispatchFailure(message, channel, deliveryTag,
                    new DispatchException("Unexpected error: " + e.getMessage(), true, 0, e));
        }
    }

    private void handleDispatchFailure(NotificationMessage message,
                                       Channel channel,
                                       long deliveryTag,
                                       DispatchException e) {
        try {
            // ACK the original message first (we'll re-route it ourselves)
            channel.basicAck(deliveryTag, false);

            int maxRetries = vendorProperties.getRetry().getMaxRetries();

            if (!e.isRetryable()) {
                // Permanent failure (e.g., 4xx) — send directly to DLQ
                log.warn("Permanent failure for notification, notificationId={} vendorId={} status={}, sending to DLQ",
                        message.getNotificationId(), message.getVendorId(), e.getStatusCode());
                producer.sendToDlq(message);
                return;
            }

            int currentRetry = message.getRetryCount();
            if (currentRetry >= maxRetries) {
                // Max retries exceeded — send to DLQ
                log.warn("Max retries exceeded for notification, notificationId={} vendorId={} retryCount={}, sending to DLQ",
                        message.getNotificationId(), message.getVendorId(), currentRetry);
                producer.sendToDlq(message);
                return;
            }

            // Increment retry count and route to the appropriate retry queue
            int nextRetry = currentRetry + 1;
            message.setRetryCount(nextRetry);
            log.info("Scheduling retry for notification, notificationId={} vendorId={} retryLevel={}",
                    message.getNotificationId(), message.getVendorId(), nextRetry);
            producer.sendToRetry(message, nextRetry);

        } catch (Exception ackException) {
            log.error("Failed to handle dispatch failure for notification, notificationId={} vendorId={}",
                    message.getNotificationId(), message.getVendorId(), ackException);
            // If we can't even ACK or re-route, the message will be redelivered by RabbitMQ
            // after the channel is closed — this is the safest fallback.
        }
    }
}
