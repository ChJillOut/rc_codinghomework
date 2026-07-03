package com.example.notificationdispatcher.service;

import com.example.notificationdispatcher.config.VendorProperties;
import com.example.notificationdispatcher.exception.DispatchException;
import com.example.notificationdispatcher.model.NotificationMessage;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Collections;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationConsumerTest {

    private NotificationConsumer consumer;

    @Mock
    private NotificationDispatcher dispatcher;

    @Mock
    private NotificationProducer producer;

    @Mock
    private Channel channel;

    private VendorProperties vendorProperties;

    @BeforeEach
    void setUp() {
        vendorProperties = new VendorProperties();
        VendorProperties.RetryConfig retryConfig = new VendorProperties.RetryConfig();
        retryConfig.setMaxRetries(3);
        retryConfig.setDelays(Collections.singletonList(5000L));
        vendorProperties.setRetry(retryConfig);

        consumer = new NotificationConsumer(dispatcher, producer, vendorProperties);
    }

    @Test
    void consume_successfulDispatch_acknowledgesMessage() throws Exception {
        // Arrange
        NotificationMessage message = NotificationMessage.builder()
                .notificationId("msg-1")
                .vendorId("ad-system")
                .retryCount(0)
                .build();
        long deliveryTag = 123L;

        // Act
        consumer.consume(message, channel, deliveryTag);

        // Assert
        verify(dispatcher).dispatch(message);
        verify(channel).basicAck(deliveryTag, false);
        verifyNoInteractions(producer);
    }

    @Test
    void consume_permanentFailure_acknowledgesAndSendsToDlq() throws Exception {
        // Arrange
        NotificationMessage message = NotificationMessage.builder()
                .notificationId("msg-2")
                .vendorId("ad-system")
                .retryCount(0)
                .build();
        long deliveryTag = 456L;

        doThrow(new DispatchException("Permanent Error", false, 400))
                .when(dispatcher).dispatch(message);

        // Act
        consumer.consume(message, channel, deliveryTag);

        // Assert
        verify(channel).basicAck(deliveryTag, false);
        verify(producer).sendToDlq(message);
        verifyNoMoreInteractions(producer);
    }

    @Test
    void consume_transientFailure_acknowledgesAndRoutesToRetry() throws Exception {
        // Arrange
        NotificationMessage message = NotificationMessage.builder()
                .notificationId("msg-3")
                .vendorId("ad-system")
                .retryCount(0)
                .build();
        long deliveryTag = 789L;

        doThrow(new DispatchException("Transient Error", true, 503))
                .when(dispatcher).dispatch(message);

        // Act
        consumer.consume(message, channel, deliveryTag);

        // Assert
        verify(channel).basicAck(deliveryTag, false);
        verify(producer).sendToRetry(message, 1);
        verify(producer, never()).sendToDlq(any());
        // Verify retry count incremented
        assert message.getRetryCount() == 1;
    }

    @Test
    void consume_retryExhausted_acknowledgesAndSendsToDlq() throws Exception {
        // Arrange
        NotificationMessage message = NotificationMessage.builder()
                .notificationId("msg-4")
                .vendorId("ad-system")
                .retryCount(3) // Already at max retries
                .build();
        long deliveryTag = 101L;

        doThrow(new DispatchException("Transient Error", true, 503))
                .when(dispatcher).dispatch(message);

        // Act
        consumer.consume(message, channel, deliveryTag);

        // Assert
        verify(channel).basicAck(deliveryTag, false);
        verify(producer).sendToDlq(message);
        verify(producer, never()).sendToRetry(any(), anyInt());
    }

    @Test
    void consume_unexpectedException_treatedAsRetryableAndAcknowledgesAndRoutesToRetry() throws Exception {
        // Arrange
        NotificationMessage message = NotificationMessage.builder()
                .notificationId("msg-5")
                .vendorId("ad-system")
                .retryCount(1)
                .build();
        long deliveryTag = 202L;

        doThrow(new RuntimeException("Something went wrong"))
                .when(dispatcher).dispatch(message);

        // Act
        consumer.consume(message, channel, deliveryTag);

        // Assert
        verify(channel).basicAck(deliveryTag, false);
        verify(producer).sendToRetry(message, 2);
        verify(producer, never()).sendToDlq(any());
        assert message.getRetryCount() == 2;
    }
}
