package com.webshop.product.messaging.listener;

import com.rabbitmq.client.Channel;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.consumer.ReliableMessageProcessor;
import com.webshop.product.messaging.event.ProductRatingChangedEvent;
import com.webshop.product.service.ProductRatingService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Nhận tóm tắt đánh giá từ review-service. Lưu bản sao (rating_count/rating_sum) thay vì gọi review-service mỗi lần
 * hiển thị: trang sản phẩm và search không phụ thuộc review-service còn sống hay không.
 */
@Component
@RequiredArgsConstructor
public class ReviewEventsListener {

    /** Tên consumer trong Inbox — KHÔNG được đổi, nếu không message cũ sẽ bị xử lý lại. */
    static final String CONSUMER = "product-review-events";

    private final ReliableMessageProcessor processor;
    private final ProductRatingService productRatingService;

    @RabbitListener(queues = RoutingKeys.Q_PRODUCT_REVIEW_EVENTS, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) {
        processor.process(message, channel, CONSUMER, this::handle);
    }

    void handle(EventEnvelope envelope) {
        if (!"ProductRatingChanged".equals(envelope.eventType())) {
            return; // tolerant reader
        }
        ProductRatingChangedEvent event = processor.payload(envelope, ProductRatingChangedEvent.class, 1);
        productRatingService.applyRating(event.productId(), event.ratingCount(), event.ratingSum(), event.version());
    }
}
