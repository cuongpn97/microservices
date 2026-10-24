package com.webshop.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webshop.messaging.dlq.DlqMessage;
import com.webshop.messaging.dlq.DlqService;
import com.webshop.messaging.testapp.MessagingTestApplication;
import com.webshop.messaging.testapp.TestHandler;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Kiểm chứng reliable consumer với RabbitMQ + PostgreSQL thật:
 * dedup qua Inbox, retry có giới hạn, DLQ cho poison message, redrive giữ nguyên idempotency.
 */
class ReliableConsumerIT extends AbstractMessagingIT {

    private static final String DLQ = MessagingTestApplication.QUEUE + ".dlq";

    @Autowired
    RabbitTemplate rabbitTemplate;
    @Autowired
    RabbitAdmin rabbitAdmin;
    @Autowired
    TestHandler handler;
    @Autowired
    DlqService dlqService;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void purgeDlq() {
        rabbitAdmin.purgeQueue(DLQ, false);
    }

    @Test
    void duplicateDeliveryIsProcessedExactlyOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        double duplicatesBefore = duplicates();

        send(eventId);
        send(eventId);

        await().atMost(Duration.ofSeconds(10)).until(() -> duplicates() >= duplicatesBefore + 1);
        assertThat(handler.invocations(eventId)).isEqualTo(1);
        Integer inboxRows = jdbc.queryForObject(
                "SELECT count(*) FROM processed_message WHERE message_id = ?", Integer.class, eventId);
        assertThat(inboxRows).isEqualTo(1);
    }

    @Test
    void transientFailuresAreRetriedBoundedTimesThenDeadLettered() throws Exception {
        UUID eventId = UUID.randomUUID();
        handler.mode(eventId, TestHandler.Mode.TRANSIENT_ALWAYS);

        send(eventId);

        await().atMost(Duration.ofSeconds(15)).until(() -> dlqService.depth(DLQ) == 1);
        // max-attempts = 3 trong application-messaging-test.yml: lần đầu + 2 lần retry, không hơn.
        assertThat(handler.invocations(eventId)).isEqualTo(3);
        DlqMessage dead = dlqService.peek(DLQ, 1).getFirst();
        assertThat(dead.messageId()).isEqualTo(eventId.toString());
        assertThat(dead.failureReason()).isEqualTo("RETRIES_EXHAUSTED");
        assertThat(dead.originalRoutingKey()).isEqualTo("test.created");
        assertThat(dead.attempt()).isEqualTo(3);
        assertThat(dead.lastError()).contains("simulated transient failure");
        // Không có hiệu ứng nào được commit cho message thất bại.
        Integer inboxRows = jdbc.queryForObject(
                "SELECT count(*) FROM processed_message WHERE message_id = ?", Integer.class, eventId);
        assertThat(inboxRows).isZero();
    }

    @Test
    void permanentFailureGoesStraightToDlqWithoutRetry() throws Exception {
        UUID eventId = UUID.randomUUID();
        handler.mode(eventId, TestHandler.Mode.PERMANENT);

        send(eventId);

        await().atMost(Duration.ofSeconds(10)).until(() -> dlqService.depth(DLQ) == 1);
        assertThat(handler.invocations(eventId)).isEqualTo(1);
        assertThat(dlqService.peek(DLQ, 1).getFirst().failureReason()).isEqualTo("PERMANENT");
    }

    @Test
    void unparseableMessageGoesToDlq() {
        Message garbage = MessageBuilder.withBody("not-json".getBytes())
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(UUID.randomUUID().toString())
                .build();
        rabbitTemplate.send("webshop.events", "test.created", garbage);

        await().atMost(Duration.ofSeconds(10)).until(() -> dlqService.depth(DLQ) == 1);
        assertThat(dlqService.peek(DLQ, 1).getFirst().failureReason()).isEqualTo("UNPARSEABLE");
    }

    @Test
    void transientFailureRecoversOnRetry() throws Exception {
        UUID eventId = UUID.randomUUID();
        handler.mode(eventId, TestHandler.Mode.TRANSIENT_ONCE);

        send(eventId);

        await().atMost(Duration.ofSeconds(10)).until(() -> handler.succeeded(eventId));
        assertThat(handler.invocations(eventId)).isEqualTo(2);
        assertThat(dlqService.depth(DLQ)).isZero();
    }

    @Test
    void redrivenMessageIsProcessedOnceAndKeepsIdempotency() throws Exception {
        UUID eventId = UUID.randomUUID();
        handler.mode(eventId, TestHandler.Mode.TRANSIENT_ALWAYS);
        send(eventId);
        await().atMost(Duration.ofSeconds(15)).until(() -> dlqService.depth(DLQ) == 1);

        // "Sửa lỗi" rồi redrive.
        handler.mode(eventId, TestHandler.Mode.SUCCEED);
        assertThat(dlqService.redrive(DLQ, 10)).isEqualTo(1);
        await().atMost(Duration.ofSeconds(10)).until(() -> handler.succeeded(eventId));

        // Redrive lần nữa cùng message (ví dụ operator lỡ tay replay bản sao) → Inbox chặn, không xử lý lại.
        double duplicatesBefore = duplicates();
        send(eventId);
        await().atMost(Duration.ofSeconds(10)).until(() -> duplicates() >= duplicatesBefore + 1);
        assertThat(handler.invocations(eventId)).isEqualTo(4); // 3 lần lỗi + 1 lần thành công
    }

    private double duplicates() {
        var counter = meters.find("webshop.messaging.duplicates").tag("queue", MessagingTestApplication.QUEUE)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private void send(UUID eventId) throws Exception {
        EventEnvelope envelope = new EventEnvelope(eventId, "TestCreated", 1, Instant.now(), "Test",
                "agg-1", "corr-" + eventId, null, null, objectMapper.valueToTree(Map.of("value", List.of(1, 2))));
        Message message = MessageBuilder.withBody(objectMapper.writeValueAsBytes(envelope))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(eventId.toString())
                .setHeader(MessageHeaders.EVENT_TYPE, "TestCreated")
                .build();
        rabbitTemplate.send("webshop.events", "test.created", message);
    }
}
