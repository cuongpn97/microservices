package com.webshop.gateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Gateway thật + một backend giả (JDK HttpServer) thay cho các service phía sau. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "webshop.security.jwt.secret=" + GatewayIT.SECRET,
        "webshop.cors.allowed-origins=http://localhost:3000",
        "spring.cloud.gateway.server.webflux.default-filters[0].name=RequestSize",
        "spring.cloud.gateway.server.webflux.default-filters[0].args.maxSize=1KB"
})
class GatewayIT {

    static final String SECRET = "test-secret-that-is-at-least-32-bytes-long!!";
    static final HttpServer BACKEND;
    static final AtomicInteger HITS = new AtomicInteger();
    static final Map<String, String> LAST_HEADERS = new ConcurrentHashMap<>();

    static {
        try {
            BACKEND = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            BACKEND.createContext("/", exchange -> {
                HITS.incrementAndGet();
                LAST_HEADERS.clear();
                exchange.getRequestHeaders().forEach((k, v) -> LAST_HEADERS.put(k.toLowerCase(), v.getFirst()));
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{\"ok\":true}".getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            BACKEND.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + BACKEND.getAddress().getPort();
        for (String svc : List.of("AUTH", "PRODUCT", "CART", "INVENTORY", "PAYMENT", "ORDER", "SEARCH")) {
            registry.add(svc + "_SERVICE_URL", () -> url);
        }
    }

    @AfterAll
    static void stop() {
        BACKEND.stop(0);
    }

    @Autowired
    WebTestClient client;

    @BeforeEach
    void reset() {
        HITS.set(0);
    }

    @Test
    void publicRoutesWorkWithoutTokenAndCorrelationIdIsPropagated() {
        client.get().uri("/api/products").exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Correlation-Id");
        assertThat(LAST_HEADERS.get("x-correlation-id")).isNotBlank();

        client.get().uri("/api/products").header("X-Correlation-Id", "my-trace-123").exchange()
                .expectHeader().valueEquals("X-Correlation-Id", "my-trace-123");
        assertThat(LAST_HEADERS.get("x-correlation-id")).isEqualTo("my-trace-123");

        client.get().uri("/api/search/products?q=ban+phim").exchange().expectStatus().isOk();
    }

    @Test
    void protectedRoutesRejectMissingInvalidOrExpiredTokensBeforeReachingBackend() throws Exception {
        client.post().uri("/api/orders/checkout").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/cart").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/cart").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(-60, "webshop-auth"))
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/cart").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(600, "evil-issuer"))
                .exchange().expectStatus().isUnauthorized();
        assertThat(HITS.get()).isZero();
    }

    @Test
    void validTokenIsForwardedSoServicesCanVerifyItAgain() throws Exception {
        String jwt = token(600, "webshop-auth");
        client.get().uri("/api/cart").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .exchange().expectStatus().isOk();
        assertThat(LAST_HEADERS.get("authorization")).isEqualTo("Bearer " + jwt);
    }

    @Test
    void adminAndUnknownPathsAreNotExposed() throws Exception {
        String jwt = token(600, "webshop-auth");
        client.get().uri("/admin/dlq").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .exchange().expectStatus().isForbidden();
        assertThat(HITS.get()).isZero();
    }

    @Test
    void oversizedRequestBodyIsRejected() {
        client.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_LENGTH, "5000")
                .bodyValue("x".repeat(5000))
                .exchange().expectStatus().isEqualTo(413);
        assertThat(HITS.get()).isZero();
    }

    @Test
    void swaggerUiIsServedAndServiceSpecsAreProxiedWithoutToken() {
        client.get().uri("/docs/order/v3/api-docs").exchange().expectStatus().isOk();
        assertThat(HITS.get()).isEqualTo(1);
        client.get().uri("/v3/api-docs/swagger-config").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).contains("/docs/payment/v3/api-docs"));
        client.get().uri("/webjars/swagger-ui/index.html").exchange().expectStatus().isOk();
    }

    @Test
    void corsPreflightOnlyAllowsConfiguredOrigins() {
        client.options().uri("/api/orders/checkout")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Authorization,Idempotency-Key")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:3000");
        client.options().uri("/api/orders/checkout")
                .header("Origin", "http://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .exchange().expectStatus().isForbidden();
    }

    private static String token(long ttlSeconds, String issuer) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .issuer(issuer)
                .issueTime(Date.from(now.minusSeconds(ttlSeconds < 0 ? 600 : 0)))
                .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                .claim("roles", List.of("USER"))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes()));
        return jwt.serialize();
    }
}
