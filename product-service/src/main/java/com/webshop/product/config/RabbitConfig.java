package com.webshop.product.config;

import com.webshop.messaging.MessagingProperties;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.consumer.RetryTopology;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    /** Queue nhận ProductRatingChanged từ review-service (kèm retry tiers + DLQ). */
    @Bean
    public Declarables productReviewEventsQueue(MessagingProperties properties) {
        return RetryTopology.consumerQueue(properties, RoutingKeys.Q_PRODUCT_REVIEW_EVENTS,
                RoutingKeys.REVIEW_RATING_CHANGED);
    }
}
