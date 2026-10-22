package com.webshop.common.http;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.function.Supplier;

/**
 * Bọc lời gọi HTTP đồng bộ bằng Resilience4j:
 * <pre>
 *   Retry( CircuitBreaker( httpCall ) )
 * </pre>
 * - Circuit breaker RIÊNG cho từng dependency (tên instance = tên service), cấu hình trong resilience4j.circuitbreaker.instances.
 * - Retry nằm NGOÀI breaker: mỗi attempt được breaker ghi nhận; khi breaker OPEN thì CallNotPermittedException
 *   không được retry (fail fast).
 * - Timeout do HTTP client (connect/read timeout) đảm nhiệm, xem {@link RestClientFactory}.
 * - Lỗi 4xx (validation, auth, not found) KHÔNG retry và KHÔNG tính là lỗi của breaker
 *   (cấu hình ignoreExceptions/recordExceptions trong YAML).
 */
public class ResilientCaller {

    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;
    private final HttpRetryProperties retryProperties;

    public ResilientCaller(CircuitBreakerRegistry circuitBreakers, RetryRegistry retries,
                           HttpRetryProperties retryProperties) {
        this.circuitBreakers = circuitBreakers;
        this.retries = retries;
        this.retryProperties = retryProperties;
    }

    /** Thao tác idempotent (GET): circuit breaker + bounded retry. */
    public <T> T idempotent(String dependency, Supplier<T> call) {
        CircuitBreaker breaker = circuitBreakers.circuitBreaker(dependency);
        Retry retry = retries.retry(dependency, this::retryConfig);
        Supplier<T> decorated = Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker, call));
        return execute(dependency, decorated);
    }

    /** Thao tác KHÔNG idempotent: chỉ circuit breaker, không retry. */
    public <T> T nonIdempotent(String dependency, Supplier<T> call) {
        CircuitBreaker breaker = circuitBreakers.circuitBreaker(dependency);
        return execute(dependency, CircuitBreaker.decorateSupplier(breaker, call));
    }

    private <T> T execute(String dependency, Supplier<T> decorated) {
        try {
            return decorated.get();
        } catch (CallNotPermittedException e) {
            throw new DependencyUnavailableException(dependency, "circuit breaker is OPEN", e);
        } catch (ResourceAccessException e) {
            throw new DependencyUnavailableException(dependency, "I/O error or timeout", e);
        } catch (HttpServerErrorException e) {
            throw new DependencyUnavailableException(dependency, "server error " + e.getStatusCode(), e);
        }
    }

    private RetryConfig retryConfig() {
        return RetryConfig.custom()
                .maxAttempts(retryProperties.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        retryProperties.initialDelay(),
                        retryProperties.multiplier(),
                        retryProperties.jitterFactor(),
                        retryProperties.maxDelay()))
                .retryExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .ignoreExceptions(CallNotPermittedException.class)
                .build();
    }
}
