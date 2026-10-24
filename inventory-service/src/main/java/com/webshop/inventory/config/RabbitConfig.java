package com.webshop.inventory.config;

import com.webshop.messaging.MessagingProperties;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.consumer.RetryTopology;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    /** Queue command của Inventory + retry tiers + DLQ (service sở hữu queue mà nó consume). */
    @Bean
    public Declarables inventoryCommandQueue(MessagingProperties properties) {
        return RetryTopology.consumerQueue(properties, RoutingKeys.Q_INVENTORY_COMMANDS,
                RoutingKeys.INVENTORY_COMMANDS_PATTERN);
    }
}
