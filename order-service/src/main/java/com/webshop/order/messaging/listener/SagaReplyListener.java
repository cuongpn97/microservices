package com.webshop.order.messaging.listener;

import com.rabbitmq.client.Channel;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.consumer.ReliableMessageProcessor;
import com.webshop.order.messaging.event.SagaReplyEvent;
import com.webshop.order.service.CheckoutSagaOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Nhận reply từ Inventory và Payment. Inbox + cập nhật Saga + Outbox command kế tiếp trong một transaction;
 * ack sau commit. Process crash giữa chừng → message chưa ack được giao lại → xử lý lại an toàn.
 */
@Component
@RequiredArgsConstructor
public class SagaReplyListener {

    /** Tên consumer trong Inbox — KHÔNG được đổi, nếu không message cũ sẽ bị xử lý lại. */
    static final String CONSUMER = "order-saga-replies";

    private final ReliableMessageProcessor processor;
    private final CheckoutSagaOrchestrator checkoutSagaOrchestrator;

    @RabbitListener(queues = RoutingKeys.Q_ORDER_SAGA_REPLIES, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) {
        processor.process(message, channel, CONSUMER, this::handle);
    }

    public void handle(EventEnvelope envelope) {
        SagaReplyEvent reply = processor.payload(envelope, SagaReplyEvent.class, 1);
        checkoutSagaOrchestrator.onReply(envelope, reply);
    }
}
