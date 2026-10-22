package com.webshop.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webshop.messaging.consumer.ReliableMessageProcessor;
import com.webshop.messaging.dlq.DlqAdminController;
import com.webshop.messaging.dlq.DlqService;
import com.webshop.messaging.inbox.InboxStore;
import com.webshop.messaging.outbox.OutboxPublisher;
import com.webshop.messaging.outbox.OutboxScheduler;
import com.webshop.messaging.outbox.OutboxSender;
import com.webshop.messaging.outbox.OutboxWriter;
import com.webshop.messaging.outbox.RabbitOutboxSender;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

@AutoConfiguration(after = {RabbitAutoConfiguration.class, DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class})
@EnableConfigurationProperties(MessagingProperties.class)
@EnableScheduling
public class MessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Exchange chính (topic) và dead-letter exchange — mọi service publish/consume đều khai báo (idempotent). */
    @Bean
    public Declarables coreExchanges(MessagingProperties properties) {
        return new Declarables(
                new TopicExchange(properties.exchange(), true, false),
                new DirectExchange(properties.deadLetterExchange(), true, false));
    }

    @Bean
    @ConditionalOnMissingBean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    public OutboxWriter outboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper, MessagingProperties properties,
                                     Clock clock) {
        return new OutboxWriter(jdbc, objectMapper, properties, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxSender outboxSender(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        return new RabbitOutboxSender(rabbitTemplate, properties);
    }

    @Bean
    public OutboxPublisher outboxPublisher(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                           OutboxSender sender, MessagingProperties properties, Clock clock,
                                           MeterRegistry meterRegistry) {
        return new OutboxPublisher(jdbc, new TransactionTemplate(transactionManager), sender, properties, clock,
                meterRegistry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "webshop.messaging.outbox", name = "scheduling-enabled", matchIfMissing = true)
    public OutboxScheduler outboxScheduler(OutboxPublisher publisher, Clock clock) {
        return new OutboxScheduler(publisher, clock);
    }

    @Bean
    public InboxStore inboxStore(JdbcTemplate jdbc, Clock clock) {
        return new InboxStore(jdbc, clock);
    }

    @Bean
    public ReliableMessageProcessor reliableMessageProcessor(ObjectMapper objectMapper,
                                                             PlatformTransactionManager transactionManager,
                                                             InboxStore inbox, RabbitTemplate rabbitTemplate,
                                                             MessagingProperties properties, MeterRegistry meters,
                                                             Clock clock) {
        return new ReliableMessageProcessor(objectMapper, new TransactionTemplate(transactionManager), inbox,
                rabbitTemplate, properties, meters, clock);
    }

    @Bean
    public DlqService dlqService(RabbitTemplate rabbitTemplate, RabbitAdmin rabbitAdmin,
                                 MessagingProperties properties, ObjectProvider<Declarables> declarables,
                                 MeterRegistry meters) {
        List<Declarables> all = declarables.orderedStream().toList();
        return new DlqService(rabbitTemplate, rabbitAdmin, properties, all, meters);
    }

    @Bean
    public DlqAdminController dlqAdminController(DlqService dlqService) {
        return new DlqAdminController(dlqService);
    }
}
