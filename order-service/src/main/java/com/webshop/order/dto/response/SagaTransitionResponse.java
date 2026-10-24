package com.webshop.order.dto.response;

import java.time.Instant;

public record SagaTransitionResponse(
        String fromState,
        String toState,
        String trigger,
        String detail,
        Instant createdAt) {
}
