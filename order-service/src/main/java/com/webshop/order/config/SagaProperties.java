package com.webshop.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "webshop.saga")
public record SagaProperties(Duration stepTimeout, Duration maxStepTimeout, Integer maxReserveAttempts,
                             Integer attentionThreshold, Long watcherIntervalMs) {

    public SagaProperties {
        stepTimeout = stepTimeout != null ? stepTimeout : Duration.ofSeconds(15);
        maxStepTimeout = maxStepTimeout != null ? maxStepTimeout : Duration.ofMinutes(2);
        maxReserveAttempts = maxReserveAttempts != null ? maxReserveAttempts : 3;
        attentionThreshold = attentionThreshold != null ? attentionThreshold : 5;
        watcherIntervalMs = watcherIntervalMs != null ? watcherIntervalMs : 2000L;
    }

    /** Deadline cho lần chờ thứ n (n = số lần đã gửi lại): stepTimeout * 2^n, có trần. */
    public Duration timeoutForAttempt(int attempt) {
        long millis = stepTimeout.toMillis() * (1L << Math.min(attempt, 16));
        return Duration.ofMillis(Math.min(millis, maxStepTimeout.toMillis()));
    }
}
