package com.webshop.review.config;

import com.webshop.messaging.MessagingProperties;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.consumer.RetryTopology;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    /** Queue nhận OrderConfirmed để ghi nhận "ai đã mua gì" (kèm retry tiers + DLQ). */
    @Bean
    public Declarables reviewOrderEventsQueue(MessagingProperties properties) {
        return RetryTopology.consumerQueue(properties, RoutingKeys.Q_REVIEW_ORDER_EVENTS, RoutingKeys.ORDER_CONFIRMED);
    }
}
