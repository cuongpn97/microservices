package com.webshop.messaging.outbox;

import com.webshop.messaging.MessageHeaders;
import com.webshop.messaging.MessagingProperties;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Publish với publisher confirms (spring.rabbitmq.publisher-confirm-type=correlated)
 * và mandatory=true (publisher-returns) để phát hiện message không route được tới queue nào.
 */
public class RabbitOutboxSender implements OutboxSender {

    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;

    public RabbitOutboxSender(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @Override
    public void sendAndConfirm(OutboxRecord record) throws Exception {
        Message message = MessageBuilder.withBody(record.payloadJson().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(record.id().toString())
                .setCorrelationId(record.correlationId())
                .setType(record.eventType())
                .setHeader(MessageHeaders.EVENT_TYPE, record.eventType())
                .setHeader(MessageHeaders.SAGA_ID, record.sagaId())
                .build();
        CorrelationData correlation = new CorrelationData(record.id().toString());
        rabbitTemplate.send(record.exchange(), record.routingKey(), message, correlation);

        CorrelationData.Confirm confirm = correlation.getFuture()
                .get(properties.outbox().confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
        if (!confirm.isAck()) {
            throw new IllegalStateException("Broker NACK: " + confirm.getReason());
        }
        // Spring AMQP đảm bảo returned được set trước khi future confirm hoàn tất.
        if (correlation.getReturned() != null) {
            throw new IllegalStateException("Message unroutable (returned): " + correlation.getReturned().getReplyText()
                    + " routingKey=" + record.routingKey());
        }
    }
}
