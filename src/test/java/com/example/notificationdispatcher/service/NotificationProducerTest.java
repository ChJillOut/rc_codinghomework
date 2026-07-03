package com.example.notificationdispatcher.service;

import com.example.notificationdispatcher.model.NotificationMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationProducerTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private NotificationProducer producer;

    @Test
    void send_publishesToMainExchange() {
        // Arrange
        NotificationMessage message = NotificationMessage.builder()
                .notificationId("msg-1")
                .vendorId("ad-system")
                .build();

        // Act
        producer.send(message);

        // Assert
        verify(rabbitTemplate).convertAndSend(
                "notification.exchange",
                "notification",
                message
        );
    }

    @Test
    void sendToRetry_publishesToRetryExchangeWithRoutingKey() {
        // Arrange
        NotificationMessage message = NotificationMessage.builder()
                .notificationId("msg-2")
                .vendorId("crm-system")
                .build();
        int retryLevel = 2;

        // Act
        producer.sendToRetry(message, retryLevel);

        // Assert
        verify(rabbitTemplate).convertAndSend(
                "notification.retry.exchange",
                "retry.2",
                message
        );
    }

    @Test
    void sendToDlq_publishesToDlxExchange() {
        // Arrange
        NotificationMessage message = NotificationMessage.builder()
                .notificationId("msg-3")
                .vendorId("inventory-system")
                .build();

        // Act
        producer.sendToDlq(message);

        // Assert
        verify(rabbitTemplate).convertAndSend(
                "notification.dlx.exchange",
                "dlq",
                message
        );
    }
}
