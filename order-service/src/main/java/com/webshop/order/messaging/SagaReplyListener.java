package com.webshop.order.messaging;

import com.rabbitmq.client.Channel;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.consumer.ReliableMessageProcessor;
import com.webshop.order.saga.CheckoutSagaOrchestrator;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Nhận reply từ Inventory và Payment. Inbox + cập nhật Saga + Outbox command kế tiếp trong một transaction;
 * ack sau commit. Process crash giữa chừng → message chưa ack được giao lại → xử lý lại an toàn.
 */
@Component
public class SagaReplyListener {

    static final String CONSUMER = "order-saga-replies";

    private final ReliableMessageProcessor processor;
    private final CheckoutSagaOrchestrator orchestrator;

    public SagaReplyListener(ReliableMessageProcessor processor, CheckoutSagaOrchestrator orchestrator) {
        this.processor = processor;
        this.orchestrator = orchestrator;
    }

    @RabbitListener(queues = RoutingKeys.Q_ORDER_SAGA_REPLIES, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) {
        processor.process(message, channel, CONSUMER, this::handle);
    }

    public void handle(EventEnvelope envelope) {
        CheckoutSagaOrchestrator.ReplyPayload reply =
                processor.payload(envelope, CheckoutSagaOrchestrator.ReplyPayload.class, 1);
        orchestrator.onReply(envelope, reply);
    }
}
