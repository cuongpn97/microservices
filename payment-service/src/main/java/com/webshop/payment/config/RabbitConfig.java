package com.webshop.payment.config;

import com.webshop.messaging.MessagingProperties;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.consumer.RetryTopology;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Các queue Payment consume (kèm retry tiers + DLQ) — service sở hữu queue mà nó consume. */
@Configuration
public class RabbitConfig {

    @Bean
    public Declarables userRegisteredQueue(MessagingProperties properties) {
        return RetryTopology.consumerQueue(properties, RoutingKeys.Q_PAYMENT_USER_REGISTERED,
                RoutingKeys.USER_REGISTERED);
    }

    @Bean
    public Declarables paymentCommandQueue(MessagingProperties properties) {
        return RetryTopology.consumerQueue(properties, RoutingKeys.Q_PAYMENT_COMMANDS,
                RoutingKeys.PAYMENT_COMMANDS_PATTERN);
    }
}
