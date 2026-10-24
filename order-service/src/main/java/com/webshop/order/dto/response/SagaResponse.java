package com.webshop.order.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Trạng thái đầy đủ của một Saga cho người vận hành (endpoint /admin/sagas).
 *
 * @param transitions rỗng ở danh sách /attention; chỉ có đủ khi xem chi tiết một Saga.
 */
public record SagaResponse(
        UUID sagaId,
        String state,
        String inventoryStep,
        String paymentStep,
        int stepAttempts,
        Instant deadlineAt,
        boolean needsAttention,
        String failureReason,
        String lastError,
        Instant updatedAt,
        List<SagaTransitionResponse> transitions) {
}
