package com.webshop.cart.config;

import com.webshop.messaging.MessagingProperties;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.consumer.RetryTopology;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    /** Queue nhận OrderConfirmed để dọn giỏ (kèm retry tiers + DLQ). */
    @Bean
    public Declarables cartOrderEventsQueue(MessagingProperties properties) {
        return RetryTopology.consumerQueue(properties, RoutingKeys.Q_CART_ORDER_EVENTS, RoutingKeys.ORDER_CONFIRMED);
    }
}
