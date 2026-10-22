package com.webshop.messaging.outbox;

import java.util.UUID;

/** Một dòng outbox đã được "claim" bởi publisher. payloadJson là toàn bộ EventEnvelope. */
public record OutboxRecord(UUID id, String exchange, String routingKey, String eventType, String payloadJson,
                           String correlationId, String sagaId, int publishAttempts) {
}
