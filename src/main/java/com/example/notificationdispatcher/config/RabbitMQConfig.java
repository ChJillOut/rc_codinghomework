package com.example.notificationdispatcher.config;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class RabbitMQConfig {

    private final VendorProperties vendorProperties;

    // Main exchange and queue
    public static final String EXCHANGE = "notification.exchange";
    public static final String QUEUE = "notification.queue";
    public static final String ROUTING_KEY = "notification";

    // Retry exchange and queues
    public static final String RETRY_EXCHANGE = "notification.retry.exchange";
    public static final String RETRY_QUEUE_1 = "notification.retry.queue.1";
    public static final String RETRY_QUEUE_2 = "notification.retry.queue.2";
    public static final String RETRY_QUEUE_3 = "notification.retry.queue.3";
    public static final String RETRY_ROUTING_KEY_1 = "retry.1";
    public static final String RETRY_ROUTING_KEY_2 = "retry.2";
    public static final String RETRY_ROUTING_KEY_3 = "retry.3";

    // Dead-letter exchange and queue
    public static final String DLX_EXCHANGE = "notification.dlx.exchange";
    public static final String DLQ = "notification.dlq";
    public static final String DLQ_ROUTING_KEY = "dlq";

    // ── Exchanges ────────────────────────────────────────────────────────────

    @Bean
    public DirectExchange notificationExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public DirectExchange retryExchange() {
        return new DirectExchange(RETRY_EXCHANGE);
    }

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE);
    }

    // ── Main Queue ───────────────────────────────────────────────────────────

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    public Binding notificationBinding(Queue notificationQueue, DirectExchange notificationExchange) {
        return BindingBuilder.bind(notificationQueue).to(notificationExchange).with(ROUTING_KEY);
    }

    // ── Retry Queues ─────────────────────────────────────────────────────────
    // Each retry queue has a TTL after which the message is dead-lettered back
    // to the main notification exchange for re-processing.

    @Bean
    public Queue retryQueue1() {
        long ttl = (vendorProperties.getRetry() != null && vendorProperties.getRetry().getDelays() != null && vendorProperties.getRetry().getDelays().size() > 0)
                ? vendorProperties.getRetry().getDelays().get(0)
                : 5_000L;
        return QueueBuilder.durable(RETRY_QUEUE_1)
                .withArgument("x-message-ttl", ttl)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue retryQueue2() {
        long ttl = (vendorProperties.getRetry() != null && vendorProperties.getRetry().getDelays() != null && vendorProperties.getRetry().getDelays().size() > 1)
                ? vendorProperties.getRetry().getDelays().get(1)
                : 30_000L;
        return QueueBuilder.durable(RETRY_QUEUE_2)
                .withArgument("x-message-ttl", ttl)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue retryQueue3() {
        long ttl = (vendorProperties.getRetry() != null && vendorProperties.getRetry().getDelays() != null && vendorProperties.getRetry().getDelays().size() > 2)
                ? vendorProperties.getRetry().getDelays().get(2)
                : 120_000L;
        return QueueBuilder.durable(RETRY_QUEUE_3)
                .withArgument("x-message-ttl", ttl)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding retryBinding1(Queue retryQueue1, DirectExchange retryExchange) {
        return BindingBuilder.bind(retryQueue1).to(retryExchange).with(RETRY_ROUTING_KEY_1);
    }

    @Bean
    public Binding retryBinding2(Queue retryQueue2, DirectExchange retryExchange) {
        return BindingBuilder.bind(retryQueue2).to(retryExchange).with(RETRY_ROUTING_KEY_2);
    }

    @Bean
    public Binding retryBinding3(Queue retryQueue3, DirectExchange retryExchange) {
        return BindingBuilder.bind(retryQueue3).to(retryExchange).with(RETRY_ROUTING_KEY_3);
    }

    // ── Dead-Letter Queue ────────────────────────────────────────────────────

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Binding dlqBinding(Queue deadLetterQueue, DirectExchange dlxExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(dlxExchange).with(DLQ_ROUTING_KEY);
    }

    // ── Message Converter & Template ─────────────────────────────────────────

    @Bean
    public MessageConverter jacksonJsonMessageConverter() {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();
        org.springframework.amqp.support.converter.DefaultJacksonJavaTypeMapper typeMapper =
                new org.springframework.amqp.support.converter.DefaultJacksonJavaTypeMapper();
        typeMapper.setTrustedPackages("com.example.notificationdispatcher.model", "java.util", "java.lang");
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jacksonJsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jacksonJsonMessageConverter);
        return template;
    }
}
