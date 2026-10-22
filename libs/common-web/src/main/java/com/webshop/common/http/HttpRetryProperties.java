package com.webshop.common.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Chính sách retry cho lời gọi HTTP đồng bộ giữa các service.
 * Retry chỉ áp dụng cho thao tác idempotent (GET) và lỗi tạm thời (I/O, 5xx).
 * Exponential backoff + jitter: delay_n = min(initialDelay * multiplier^(n-1), maxDelay) ± jitterFactor.
 */
@ConfigurationProperties(prefix = "webshop.http.retry")
public record HttpRetryProperties(Integer maxAttempts, Duration initialDelay, Double multiplier,
                                  Duration maxDelay, Double jitterFactor,
                                  Duration connectTimeout, Duration readTimeout) {

    public HttpRetryProperties {
        maxAttempts = maxAttempts != null ? maxAttempts : 3;
        initialDelay = initialDelay != null ? initialDelay : Duration.ofMillis(100);
        multiplier = multiplier != null ? multiplier : 2.0;
        maxDelay = maxDelay != null ? maxDelay : Duration.ofSeconds(1);
        jitterFactor = jitterFactor != null ? jitterFactor : 0.5;
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(1);
        readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(2);
    }
}
