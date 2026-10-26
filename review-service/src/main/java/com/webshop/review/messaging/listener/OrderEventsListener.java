package com.webshop.review.messaging.listener;

import com.rabbitmq.client.Channel;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.consumer.ReliableMessageProcessor;
import com.webshop.review.messaging.event.OrderConfirmedEvent;
import com.webshop.review.service.PurchaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Ghi nhận lượt mua từ OrderConfirmed: nguồn duy nhất cho quyền viết đánh giá. Không gọi Order Service lúc user bấm
 * "Viết đánh giá" → review-service vẫn chạy khi Order Service down, và không cần API "user X đã mua Y chưa" ở Order.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventsListener {

    /** Tên consumer trong Inbox — KHÔNG được đổi, nếu không message cũ sẽ bị xử lý lại. */
    static final String CONSUMER = "review-order-events";

    private final ReliableMessageProcessor processor;
    private final PurchaseService purchaseService;

    @RabbitListener(queues = RoutingKeys.Q_REVIEW_ORDER_EVENTS, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) {
        processor.process(message, channel, CONSUMER, this::handle);
    }

    void handle(EventEnvelope envelope) {
        if (!"OrderConfirmed".equals(envelope.eventType())) {
            return; // tolerant reader
        }
        if (envelope.eventVersion() == 1) {
            // v1 (trước SKU) không có productId của SPU → không biết sản phẩm nào để ghi nhận. Bỏ qua, không DLQ.
            log.info("Skipping OrderConfirmed v1 eventId={} (no productId)", envelope.eventId());
            return;
        }
        OrderConfirmedEvent event = processor.payload(envelope, OrderConfirmedEvent.class, 2);
        purchaseService.recordPurchases(event, envelope.occurredAt() != null ? envelope.occurredAt() : Instant.now());
    }
}
