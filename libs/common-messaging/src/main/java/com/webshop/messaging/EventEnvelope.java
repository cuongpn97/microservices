package com.webshop.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope có version cho mọi message (event và command) trên RabbitMQ.
 *
 * @param eventId       định danh duy nhất của message — khóa dedup ở Inbox phía consumer.
 * @param eventType     tên loại message (ví dụ "UserRegistered", "ReserveInventory").
 * @param eventVersion  version của schema payload; consumer từ chối version không hỗ trợ.
 * @param correlationId nối mọi message/HTTP request của cùng một chuỗi nghiệp vụ.
 * @param causationId   eventId của message đã "gây ra" message này (null nếu khởi phát từ HTTP).
 * @param sagaId        định danh Saga (null nếu không thuộc Saga).
 */
public record EventEnvelope(UUID eventId,
                            String eventType,
                            int eventVersion,
                            Instant occurredAt,
                            String aggregateType,
                            String aggregateId,
                            String correlationId,
                            String causationId,
                            String sagaId,
                            JsonNode payload) {
}
