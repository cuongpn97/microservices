package com.webshop.messaging.consumer;

import com.webshop.messaging.MessagingProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

import java.util.ArrayList;
import java.util.List;

/**
 * Khai báo topology cho MỘT queue consumer:
 * <pre>
 *  webshop.events (topic) ──bindingKeys──► Q   (durable, DLX = webshop.dlx / Q.dlq)
 *                                           │ lỗi tạm thời, attempt n
 *                                           ▼
 *                         default exchange ─► Q.retry.n (TTL theo từng message, DLX = "" / Q)
 *                                           │ hết TTL → broker dead-letter về Q
 *                                           ▼
 *                       hết lượt / lỗi vĩnh viễn ─► webshop.dlx ─► Q.dlq (durable, không TTL)
 * </pre>
 * Mỗi tầng retry một queue riêng → các message trong cùng tầng có TTL gần bằng nhau, giảm hiện tượng
 * head-of-line blocking của per-message TTL (RabbitMQ chỉ expire message ở đầu queue).
 */
public final class RetryTopology {

    private RetryTopology() {
    }

    public static String retryQueueName(String queue, int tier) {
        return queue + ".retry." + tier;
    }

    public static String dlqName(String queue) {
        return queue + ".dlq";
    }

    public static Declarables consumerQueue(MessagingProperties properties, String queue, String... bindingKeys) {
        List<Declarable> declarables = new ArrayList<>();
        TopicExchange exchange = new TopicExchange(properties.exchange(), true, false);
        DirectExchange dlx = new DirectExchange(properties.deadLetterExchange(), true, false);

        Queue main = QueueBuilder.durable(queue)
                .deadLetterExchange(properties.deadLetterExchange())
                .deadLetterRoutingKey(dlqName(queue))
                .build();
        declarables.add(main);
        for (String key : bindingKeys) {
            Binding binding = BindingBuilder.bind(main).to(exchange).with(key);
            declarables.add(binding);
        }

        Queue dlq = QueueBuilder.durable(dlqName(queue)).build();
        declarables.add(dlq);
        declarables.add(BindingBuilder.bind(dlq).to(dlx).with(dlqName(queue)));

        int tiers = Math.max(0, properties.retry().maxAttempts() - 1);
        for (int tier = 1; tier <= tiers; tier++) {
            declarables.add(QueueBuilder.durable(retryQueueName(queue, tier))
                    .deadLetterExchange("")
                    .deadLetterRoutingKey(queue)
                    .build());
        }
        return new Declarables(declarables);
    }
}
