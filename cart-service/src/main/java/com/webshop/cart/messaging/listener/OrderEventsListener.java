package com.webshop.cart.messaging.listener;

import com.rabbitmq.client.Channel;
import com.webshop.cart.messaging.event.OrderConfirmedEvent;
import com.webshop.cart.messaging.event.OrderConfirmedEventV1;
import com.webshop.cart.service.CartService;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.consumer.ReliableMessageProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Dọn giỏ hàng CHỈ khi đơn đã CONFIRMED (event OrderConfirmed). Đơn FAILED → giỏ giữ nguyên để user thử lại.
 * Inbox đảm bảo giao trùng không trừ số lượng hai lần.
 */
@Component
@RequiredArgsConstructor
public class OrderEventsListener {

    /** Tên consumer trong Inbox — KHÔNG được đổi, nếu không message cũ sẽ bị xử lý lại. */
    static final String CONSUMER = "cart-order-events";

    private final ReliableMessageProcessor processor;
    private final CartService cartService;

    @RabbitListener(queues = RoutingKeys.Q_CART_ORDER_EVENTS, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) {
        processor.process(message, channel, CONSUMER, this::handle);
    }

    void handle(EventEnvelope envelope) {
        if (!"OrderConfirmed".equals(envelope.eventType())) {
            return; // tolerant reader: bỏ qua event không quan tâm
        }
        // v1 (trước SKU) vẫn có thể nằm trong queue/DLQ lúc nâng cấp → upcast thay vì từ chối.
        OrderConfirmedEvent event = envelope.eventVersion() == 1
                ? processor.payload(envelope, OrderConfirmedEventV1.class, 1).upcast()
                : processor.payload(envelope, OrderConfirmedEvent.class, 2);
        // Gộp theo skuId (Integer::sum) phòng khi một đơn có hai dòng cùng SKU.
        cartService.removePurchased(event.userId(), event.items().stream()
                .collect(Collectors.toMap(OrderConfirmedEvent.Item::skuId, OrderConfirmedEvent.Item::quantity,
                        Integer::sum)));
    }
}
