package com.webshop.cart.messaging;

import com.rabbitmq.client.Channel;
import com.webshop.cart.application.CartService;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.consumer.ReliableMessageProcessor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Dọn giỏ hàng CHỈ khi đơn đã CONFIRMED (event OrderConfirmed). Đơn FAILED → giỏ giữ nguyên để user thử lại.
 * Inbox đảm bảo giao trùng không trừ số lượng hai lần.
 */
@Component
public class OrderEventsListener {

    static final String CONSUMER = "cart-order-events";

    private final ReliableMessageProcessor processor;
    private final CartService carts;

    public OrderEventsListener(ReliableMessageProcessor processor, CartService carts) {
        this.processor = processor;
        this.carts = carts;
    }

    @RabbitListener(queues = RoutingKeys.Q_CART_ORDER_EVENTS, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) {
        processor.process(message, channel, CONSUMER, this::handle);
    }

    void handle(EventEnvelope envelope) {
        if (!"OrderConfirmed".equals(envelope.eventType())) {
            return; // tolerant reader: bỏ qua event không quan tâm
        }
        OrderConfirmed event = processor.payload(envelope, OrderConfirmed.class, 1);
        carts.removePurchased(event.userId(), event.items().stream()
                .collect(Collectors.toMap(OrderConfirmed.Item::productId, OrderConfirmed.Item::quantity, Integer::sum)));
    }

    public record OrderConfirmed(UUID orderId, UUID userId, List<Item> items) {
        public record Item(UUID productId, int quantity) {
        }
    }
}
