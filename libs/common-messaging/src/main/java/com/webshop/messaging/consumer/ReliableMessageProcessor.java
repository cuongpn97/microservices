package com.webshop.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.webshop.common.web.CorrelationIds;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.MessageHeaders;
import com.webshop.messaging.MessagingProperties;
import com.webshop.messaging.PermanentMessageException;
import com.webshop.messaging.inbox.InboxStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Khung xử lý message tin cậy dùng cho mọi consumer (ack thủ công).
 *
 * <pre>
 * nhận message
 *   ├─ parse envelope lỗi ─────────────────────────────► DLQ (PERMANENT) + ack
 *   ├─ TX { inbox.markProcessed ; handler.handle } commit
 *   │     ├─ đã xử lý trước đó (duplicate) ─────────────► ack (không làm gì)
 *   │     └─ thành công ─────────────────────────────────► ack
 *   ├─ PermanentMessageException ───────────────────────► DLQ + ack
 *   └─ lỗi tạm thời
 *         ├─ attempt < maxAttempts ─► publish sang &lt;queue&gt;.retry.&lt;attempt&gt; (TTL = backoff + jitter) + ack
 *         └─ hết lượt ──────────────► DLQ (RETRIES_EXHAUSTED) + ack
 * </pre>
 * Ack chỉ xảy ra SAU khi transaction commit. Publish sang retry/DLQ chờ publisher confirm rồi mới ack bản gốc;
 * nếu app chết giữa hai bước → bản gốc được giao lại → Inbox chặn trùng. Không bao giờ requeue ngay lập tức
 * (tránh vòng lặp nóng với poison message).
 */
public class ReliableMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(ReliableMessageProcessor.class);

    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final InboxStore inbox;
    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    public ReliableMessageProcessor(ObjectMapper objectMapper, TransactionTemplate tx, InboxStore inbox,
                                    RabbitTemplate rabbitTemplate, MessagingProperties properties,
                                    MeterRegistry meters, Clock clock) {
        this.objectMapper = objectMapper;
        this.tx = tx;
        this.inbox = inbox;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    public void process(Message message, Channel channel, String consumer, MessageHandler handler) {
        MessageProperties props = message.getMessageProperties();
        long deliveryTag = props.getDeliveryTag();
        String queue = props.getConsumerQueue();
        int attempt = attemptOf(props);

        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message.getBody(), EventEnvelope.class);
            if (envelope.eventId() == null || envelope.eventType() == null) {
                throw new PermanentMessageException("Envelope missing eventId/eventType");
            }
        } catch (Exception e) {
            log.error("Unparseable message on queue={} messageId={}: {}", queue, props.getMessageId(), e.toString());
            routeToDlqThenAck(message, channel, queue, attempt, "UNPARSEABLE", e);
            return;
        }

        putMdc(envelope);
        try {
            Boolean firstTime = tx.execute(status -> {
                if (!inbox.markProcessed(consumer, envelope.eventId())) {
                    return false;
                }
                handler.handle(envelope);
                return true;
            });
            if (Boolean.TRUE.equals(firstTime)) {
                meters.counter("webshop.messaging.processed", "queue", queue).increment();
            } else {
                meters.counter("webshop.messaging.duplicates", "queue", queue).increment();
                log.info("Duplicate message skipped eventId={} type={}", envelope.eventId(), envelope.eventType());
            }
            ack(channel, deliveryTag);
        } catch (PermanentMessageException e) {
            log.error("Permanent failure eventId={} type={}: {}", envelope.eventId(), envelope.eventType(),
                    e.getMessage());
            routeToDlqThenAck(message, channel, queue, attempt, "PERMANENT", e);
        } catch (Exception e) {
            int maxAttempts = properties.retry().maxAttempts();
            if (attempt < maxAttempts) {
                log.warn("Transient failure eventId={} type={} attempt={}/{} -> retry: {}", envelope.eventId(),
                        envelope.eventType(), attempt, maxAttempts, e.toString());
                routeToRetryThenAck(message, channel, queue, attempt, e);
            } else {
                log.error("Retries exhausted eventId={} type={} attempts={} -> DLQ: {}", envelope.eventId(),
                        envelope.eventType(), attempt, e.toString());
                routeToDlqThenAck(message, channel, queue, attempt, "RETRIES_EXHAUSTED", e);
            }
        } finally {
            MDC.remove(CorrelationIds.MDC_CORRELATION_ID);
            MDC.remove(CorrelationIds.MDC_SAGA_ID);
        }
    }

    /** Đọc payload theo kiểu cụ thể; version không hỗ trợ là lỗi vĩnh viễn. */
    public <T> T payload(EventEnvelope envelope, Class<T> type, int supportedVersion) {
        if (envelope.eventVersion() != supportedVersion) {
            throw new PermanentMessageException("Unsupported version " + envelope.eventVersion() + " for "
                    + envelope.eventType());
        }
        try {
            return objectMapper.treeToValue(envelope.payload(), type);
        } catch (Exception e) {
            throw new PermanentMessageException("Invalid payload for " + envelope.eventType(), e);
        }
    }

    private void routeToRetryThenAck(Message message, Channel channel, String queue, int attempt, Exception error) {
        Duration delay = retryDelay(attempt);
        MessageProperties props = message.getMessageProperties();
        preserveOrigin(props, queue);
        props.setHeader(MessageHeaders.ATTEMPT, attempt + 1);
        props.setHeader(MessageHeaders.LAST_ERROR, truncate(error.toString()));
        props.setExpiration(String.valueOf(delay.toMillis()));
        String retryQueue = RetryTopology.retryQueueName(queue, attempt);
        try {
            publishConfirmed("", retryQueue, message);
            meters.counter("webshop.messaging.retries", "queue", queue).increment();
            ack(channel, props.getDeliveryTag());
        } catch (Exception publishError) {
            nackToDeadLetter(channel, props.getDeliveryTag(), publishError);
        }
    }

    private void routeToDlqThenAck(Message message, Channel channel, String queue, int attempt, String reason,
                                   Exception error) {
        MessageProperties props = message.getMessageProperties();
        preserveOrigin(props, queue);
        props.setHeader(MessageHeaders.ATTEMPT, attempt);
        props.setHeader(MessageHeaders.FAILURE_REASON, reason);
        props.setHeader(MessageHeaders.LAST_ERROR, truncate(error.toString()));
        props.setHeader(MessageHeaders.FAILED_AT, clock.instant().toString());
        props.setExpiration(null);
        try {
            publishConfirmed(properties.deadLetterExchange(), RetryTopology.dlqName(queue), message);
            meters.counter("webshop.messaging.dead-lettered", "queue", queue, "reason", reason).increment();
            ack(channel, props.getDeliveryTag());
        } catch (Exception publishError) {
            nackToDeadLetter(channel, props.getDeliveryTag(), publishError);
        }
    }

    private void preserveOrigin(MessageProperties props, String queue) {
        if (props.getHeader(MessageHeaders.ORIGINAL_ROUTING_KEY) == null) {
            props.setHeader(MessageHeaders.ORIGINAL_ROUTING_KEY, props.getReceivedRoutingKey());
            props.setHeader(MessageHeaders.ORIGINAL_EXCHANGE, props.getReceivedExchange());
        }
        props.setHeader(MessageHeaders.ORIGINAL_QUEUE, queue);
    }

    private void publishConfirmed(String exchange, String routingKey, Message message) throws Exception {
        CorrelationData correlation = new CorrelationData(message.getMessageProperties().getMessageId());
        rabbitTemplate.send(exchange, routingKey, message, correlation);
        CorrelationData.Confirm confirm = correlation.getFuture()
                .get(properties.outbox().confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
        if (!confirm.isAck() || correlation.getReturned() != null) {
            throw new IllegalStateException("Republish to " + routingKey + " not confirmed");
        }
    }

    /**
     * Phương án cuối: không publish được sang retry/DLQ (broker gặp sự cố) → nack(requeue=false);
     * queue chính có x-dead-letter-exchange nên broker tự chuyển message vào DLQ, không mất message.
     */
    private void nackToDeadLetter(Channel channel, long deliveryTag, Exception error) {
        log.error("Cannot republish message, falling back to broker dead-lettering: {}", error.toString());
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (IOException e) {
            log.error("basicNack failed; message will be redelivered after channel recovery", e);
        }
    }

    Duration retryDelay(int attempt) {
        MessagingProperties.Retry retry = properties.retry();
        double base = retry.initialDelay().toMillis() * Math.pow(retry.multiplier(), attempt - 1);
        double capped = Math.min(base, retry.maxDelay().toMillis());
        double jitter = capped * retry.jitterFactor() * (ThreadLocalRandom.current().nextDouble() * 2 - 1);
        return Duration.ofMillis(Math.max(1, Math.round(capped + jitter)));
    }

    private static int attemptOf(MessageProperties props) {
        Object value = props.getHeader(MessageHeaders.ATTEMPT);
        if (value instanceof Number n) {
            return Math.max(1, n.intValue());
        }
        return 1;
    }

    private void putMdc(EventEnvelope envelope) {
        if (envelope.correlationId() != null) {
            MDC.put(CorrelationIds.MDC_CORRELATION_ID, envelope.correlationId());
        }
        if (envelope.sagaId() != null) {
            MDC.put(CorrelationIds.MDC_SAGA_ID, envelope.sagaId());
        }
    }

    private static void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            // Transaction đã commit; nếu ack mất, broker giao lại và Inbox sẽ chặn trùng.
            log.warn("basicAck failed (message may be redelivered, inbox will dedupe): {}", e.toString());
        }
    }

    private static String truncate(String value) {
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
