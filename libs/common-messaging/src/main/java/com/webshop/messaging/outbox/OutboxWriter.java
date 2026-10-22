package com.webshop.messaging.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.MessagingProperties;
import com.webshop.common.web.CorrelationIds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Ghi event vào bảng outbox_event TRONG CÙNG transaction với thay đổi nghiệp vụ.
 * <p>
 * Propagation.MANDATORY: gọi ngoài transaction sẽ lỗi ngay — đây chính là bất biến cốt lõi của
 * Transactional Outbox (không có "ghi DB xong rồi mới gửi message" rời rạc).
 * <p>
 * Dùng JdbcTemplate (không phải JPA entity) để thư viện dùng chung không phụ thuộc entity scan của từng service.
 * JpaTransactionManager expose JDBC connection nên JdbcTemplate tham gia cùng transaction JPA.
 */
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final MessagingProperties properties;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper, MessagingProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(OutboxEvent event) {
        UUID eventId = UUID.randomUUID();
        Instant now = clock.instant();
        String correlationId = event.correlationId() != null ? event.correlationId() : CorrelationIds.currentOrNew();
        EventEnvelope envelope = new EventEnvelope(
                eventId,
                event.eventType(),
                event.eventVersion(),
                now,
                event.aggregateType(),
                event.aggregateId(),
                correlationId,
                event.causationId(),
                event.sagaId(),
                objectMapper.valueToTree(event.payload()));
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize outbox event " + event.eventType(), e);
        }
        jdbc.update("""
                        INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, event_version,
                                                  exchange, routing_key, payload, correlation_id, causation_id, saga_id,
                                                  created_at, next_attempt_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                        """,
                eventId, event.aggregateType(), event.aggregateId(), event.eventType(), event.eventVersion(),
                properties.exchange(), event.routingKey(), json, correlationId, event.causationId(), event.sagaId(),
                Timestamp.from(now), Timestamp.from(now));
        return eventId;
    }
}
