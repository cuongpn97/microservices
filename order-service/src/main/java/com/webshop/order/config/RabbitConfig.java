package com.webshop.order.config;

import com.webshop.messaging.MessagingProperties;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.consumer.RetryTopology;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    /** Một queue nhận reply của cả Inventory và Payment cho Saga (kèm retry tiers + DLQ). */
    @Bean
    public Declarables sagaReplyQueue(MessagingProperties properties) {
        return RetryTopology.consumerQueue(properties, RoutingKeys.Q_ORDER_SAGA_REPLIES,
                RoutingKeys.INVENTORY_REPLY, RoutingKeys.PAYMENT_REPLY);
    }
}
