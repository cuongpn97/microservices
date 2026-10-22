package com.webshop.common.http;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Circuit breaker + retry với HTTP server thật (JDK HttpServer) — không mock RestClient
 * để kiểm chứng cả việc map lỗi 5xx/4xx/timeout.
 */
class ResilientCallerTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 500;
    private volatile long delayMs = 0;
    private ResilientCaller caller;
    private CircuitBreakerRegistry breakers;
    private RestClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();

        breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .ignoreExceptions(HttpClientErrorException.class)
                .build());
        HttpRetryProperties props = new HttpRetryProperties(2, Duration.ofMillis(10), 2.0, Duration.ofMillis(50),
                0.5, Duration.ofMillis(500), Duration.ofMillis(300));
        caller = new ResilientCaller(breakers, RetryRegistry.ofDefaults(), props);
        client = new RestClientFactory(RestClient.builder(), props)
                .create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void circuitOpensAfterFailureThresholdAndThenFailsFastWithoutCallingRemote() {
        status = 500;
        // 2 lời gọi logic × 2 attempt = 4 lời gọi HTTP lỗi → đủ minimumNumberOfCalls, failure rate 100% → OPEN
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> caller.idempotent("product-service", this::get))
                    .isInstanceOf(DependencyUnavailableException.class);
        }
        assertThat(hits.get()).isEqualTo(4);
        assertThat(breakers.circuitBreaker("product-service").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> caller.idempotent("product-service", this::get))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasMessageContaining("circuit breaker is OPEN");
        assertThat(hits.get()).as("fail fast: remote không bị gọi khi OPEN").isEqualTo(4);
    }

    @Test
    void breakersAreIsolatedPerDependency() {
        status = 500;
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> caller.idempotent("product-service", this::get))
                    .isInstanceOf(DependencyUnavailableException.class);
        }
        assertThat(breakers.circuitBreaker("product-service").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breakers.circuitBreaker("cart-service").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void clientErrorsAreNeitherRetriedNorCountedAsFailures() {
        status = 404;
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> caller.idempotent("product-service", this::get))
                    .isInstanceOf(HttpClientErrorException.NotFound.class);
        }
        assertThat(hits.get()).as("4xx không retry").isEqualTo(5);
        assertThat(breakers.circuitBreaker("product-service").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void readTimeoutIsTreatedAsTransientAndRetriedBoundedly() {
        status = 200;
        delayMs = 600; // > readTimeout 300ms
        assertThatThrownBy(() -> caller.idempotent("slow-service", this::get))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasMessageContaining("I/O error or timeout");
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void nonIdempotentCallsAreNeverRetried() {
        status = 503;
        assertThatThrownBy(() -> caller.nonIdempotent("payment", this::get))
                .isInstanceOf(DependencyUnavailableException.class);
        assertThat(hits.get()).isEqualTo(1);
    }

    private String get() {
        return client.get().uri("/x").retrieve().body(String.class);
    }
}
