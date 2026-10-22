package com.webshop.messaging.outbox;

/**
 * Event cần ghi vào outbox.
 *
 * @param causationId eventId của message gây ra event này (nếu có) — giúp dựng lại chuỗi nhân quả khi debug.
 */
public record OutboxEvent(String aggregateType,
                          String aggregateId,
                          String eventType,
                          int eventVersion,
                          String routingKey,
                          Object payload,
                          String correlationId,
                          String causationId,
                          String sagaId) {

    public static OutboxEvent of(String aggregateType, String aggregateId, String eventType, String routingKey,
                                 Object payload) {
        return new OutboxEvent(aggregateType, aggregateId, eventType, 1, routingKey, payload, null, null, null);
    }

    public OutboxEvent withContext(String correlationId, String causationId, String sagaId) {
        return new OutboxEvent(aggregateType, aggregateId, eventType, eventVersion, routingKey, payload,
                correlationId, causationId, sagaId);
    }
}
