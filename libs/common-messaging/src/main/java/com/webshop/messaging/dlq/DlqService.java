package com.webshop.messaging.dlq;

import com.rabbitmq.client.GetResponse;
import com.webshop.messaging.MessageHeaders;
import com.webshop.messaging.MessagingProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.amqp.rabbit.support.MessagePropertiesConverter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Kiểm tra và redrive DLQ do chính service này sở hữu.
 * <p>
 * Redrive: lấy từng message (basic.get, chưa ack) → publish lại VÀO ĐÚNG queue gốc qua default exchange
 * (không publish lại vào topic exchange để tránh giao trùng cho consumer khác) với publisher confirm → ack bản DLQ.
 * messageId và payload giữ nguyên nên Inbox vẫn chặn trùng nếu message thực ra đã được xử lý.
 * Header attempt được reset về 1 để message có đủ số lượt retry mới; x-redrive-count tăng dần.
 */
public class DlqService {

    private static final Logger log = LoggerFactory.getLogger(DlqService.class);

    private final RabbitTemplate rabbitTemplate;
    private final RabbitAdmin rabbitAdmin;
    private final MessagingProperties properties;
    private final List<String> ownedDlqs;
    private final MessagePropertiesConverter converter = new DefaultMessagePropertiesConverter();

    public DlqService(RabbitTemplate rabbitTemplate, RabbitAdmin rabbitAdmin, MessagingProperties properties,
                      List<Declarables> declarables, MeterRegistry meters) {
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitAdmin = rabbitAdmin;
        this.properties = properties;
        this.ownedDlqs = declarables.stream()
                .flatMap(d -> d.getDeclarablesByType(Queue.class).stream())
                .map(Queue::getName)
                .filter(name -> name.endsWith(".dlq"))
                .distinct()
                .toList();
        for (String dlq : ownedDlqs) {
            Gauge.builder("webshop.messaging.dlq.depth", this, s -> s.depth(dlq))
                    .tag("queue", dlq)
                    .description("Số message đang nằm trong DLQ")
                    .register(meters);
        }
    }

    public List<String> ownedDlqs() {
        return ownedDlqs;
    }

    public long depth(String dlq) {
        try {
            Properties info = rabbitAdmin.getQueueProperties(dlq);
            if (info == null) {
                return 0;
            }
            Object count = info.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
            return count instanceof Number n ? n.longValue() : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Xem tối đa {@code max} message đầu DLQ mà không lấy chúng ra (get rồi nack requeue=true). */
    public List<Map<String, Object>> peek(String dlq, int max) {
        requireOwned(dlq);
        return rabbitTemplate.execute(channel -> {
            List<Map<String, Object>> result = new ArrayList<>();
            List<Long> tags = new ArrayList<>();
            for (int i = 0; i < max; i++) {
                GetResponse response = channel.basicGet(dlq, false);
                if (response == null) {
                    break;
                }
                tags.add(response.getEnvelope().getDeliveryTag());
                MessageProperties props = converter.toMessageProperties(response.getProps(),
                        response.getEnvelope(), "UTF-8");
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("messageId", props.getMessageId());
                item.put("correlationId", props.getCorrelationId());
                item.put("eventType", props.getHeader(MessageHeaders.EVENT_TYPE));
                item.put("originalRoutingKey", props.getHeader(MessageHeaders.ORIGINAL_ROUTING_KEY));
                item.put("attempt", props.getHeader(MessageHeaders.ATTEMPT));
                item.put("failureReason", props.getHeader(MessageHeaders.FAILURE_REASON));
                item.put("lastError", props.getHeader(MessageHeaders.LAST_ERROR));
                item.put("failedAt", props.getHeader(MessageHeaders.FAILED_AT));
                item.put("redriveCount", props.getHeader(MessageHeaders.REDRIVE_COUNT));
                item.put("body", new String(response.getBody(), StandardCharsets.UTF_8));
                result.add(item);
            }
            for (Long tag : tags) {
                channel.basicNack(tag, false, true);
            }
            return result;
        });
    }

    /** Chuyển tối đa {@code max} message từ DLQ về queue gốc. Trả về số message đã redrive. */
    public int redrive(String dlq, int max) {
        requireOwned(dlq);
        String targetQueue = dlq.substring(0, dlq.length() - ".dlq".length());
        Integer moved = rabbitTemplate.execute(channel -> {
            int count = 0;
            for (int i = 0; i < max; i++) {
                GetResponse response = channel.basicGet(dlq, false);
                if (response == null) {
                    break;
                }
                long tag = response.getEnvelope().getDeliveryTag();
                MessageProperties props = converter.toMessageProperties(response.getProps(),
                        response.getEnvelope(), "UTF-8");
                Object previous = props.getHeader(MessageHeaders.REDRIVE_COUNT);
                int redriveCount = previous instanceof Number n ? n.intValue() + 1 : 1;
                props.setHeader(MessageHeaders.REDRIVE_COUNT, redriveCount);
                props.setHeader(MessageHeaders.ATTEMPT, 1);
                props.getHeaders().remove(MessageHeaders.FAILURE_REASON);
                Message message = new Message(response.getBody(), props);
                try {
                    CorrelationData correlation = new CorrelationData(props.getMessageId());
                    rabbitTemplate.send("", targetQueue, message, correlation);
                    CorrelationData.Confirm confirm = correlation.getFuture()
                            .get(properties.outbox().confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
                    if (!confirm.isAck() || correlation.getReturned() != null) {
                        throw new IllegalStateException("redrive publish not confirmed");
                    }
                    channel.basicAck(tag, false);
                    count++;
                } catch (Exception e) {
                    log.error("Redrive failed for messageId={}, leaving it in {}: {}", props.getMessageId(), dlq,
                            e.toString());
                    channel.basicNack(tag, false, true);
                    break;
                }
            }
            return count;
        });
        log.info("Redrove {} message(s) from {} to {}", moved, dlq, targetQueue);
        return moved != null ? moved : 0;
    }

    private void requireOwned(String dlq) {
        if (!ownedDlqs.contains(dlq)) {
            throw new IllegalArgumentException("Unknown or not-owned DLQ: " + dlq);
        }
    }
}
