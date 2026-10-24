package com.webshop.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.webshop.common.http.DependencyUnavailableException;
import com.webshop.messaging.test.TestJwts;
import com.webshop.order.client.dto.CartSnapshot;
import com.webshop.order.client.dto.ProductInfo;
import com.webshop.order.enums.SagaState;
import com.webshop.order.scheduler.SagaTimeoutWatcher;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CheckoutSagaIT extends AbstractOrderIT {

    static final UUID KEYBOARD = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID MOUSE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    SagaTimeoutWatcher watcher;
    @Autowired
    EntityManagerFactory entityManagerFactory;

    private String userWithCart(long revision) {
        UUID userId = UUID.randomUUID();
        given(cartClient.getCart(anyString())).willReturn(new CartSnapshot(userId, revision, List.of(
                new CartSnapshot.Line(KEYBOARD, 2), new CartSnapshot.Line(MOUSE, 1))));
        given(productClient.findAll(anyCollection())).willReturn(List.of(
                new ProductInfo(KEYBOARD, "Mechanical Keyboard", 1_500_000, true),
                new ProductInfo(MOUSE, "Wireless Mouse", 450_000, true)));
        return TestJwts.user(userId);
    }

    private UUID startCheckout(String token) throws Exception {
        String body = checkout(token, "key-" + UUID.randomUUID())
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("orderId").asText());
    }

    // ----------------------------------------------------------------- security & validation

    @Test
    void checkoutRejectsUnauthenticatedRequests() throws Exception {
        mvc.perform(post("/api/orders/checkout").header("Idempotency-Key", "key-12345678"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders/checkout").header("Idempotency-Key", "key-12345678")
                        .header("Authorization", "Bearer " + TestJwts.expired(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void checkoutRequiresIdempotencyKey() throws Exception {
        checkout(userWithCart(1), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void inactiveProductOrEmptyCartIsRejectedAndDependencyOutageIs503() throws Exception {
        String token = userWithCart(1);
        given(productClient.findAll(anyCollection())).willReturn(List.of(
                new ProductInfo(KEYBOARD, "Keyboard", 1_500_000, false),
                new ProductInfo(MOUSE, "Mouse", 450_000, true)));
        checkout(token, "key-inactive-1").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_UNAVAILABLE"));

        given(productClient.findAll(anyCollection()))
                .willThrow(new DependencyUnavailableException("product-service", "circuit breaker is OPEN", null));
        checkout(token, "key-outage-1").andExpect(status().isServiceUnavailable());

        UUID emptyUser = UUID.randomUUID();
        given(cartClient.getCart(anyString())).willReturn(new CartSnapshot(emptyUser, 0, List.of()));
        checkout(TestJwts.user(emptyUser), "key-empty-1").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CART_EMPTY"));
    }

    // ----------------------------------------------------------------- happy path

    @Test
    void successfulSagaConfirmsOrderWithPriceSnapshotAndEmitsOrderConfirmed() throws Exception {
        String token = userWithCart(1);
        String body = checkout(token, "key-happy-0001")
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.sagaState").value("PENDING"))
                .andExpect(jsonPath("$.totalVnd").value(2 * 1_500_000 + 450_000))
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(objectMapper.readTree(body).get("orderId").asText());

        // Order + Saga + ReserveInventory đã ghi nguyên tử.
        assertThat(commands(orderId, "ReserveInventory")).isEqualTo(1);
        JsonNode reserve = objectMapper.readTree(jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE aggregate_id = ? AND event_type = 'ReserveInventory'",
                String.class, orderId.toString()));
        assertThat(reserve.get("sagaId").asText()).isEqualTo(orderId.toString());
        assertThat(reserve.get("correlationId").asText()).isNotBlank();
        assertThat(reserve.get("payload").get("items")).hasSize(2);

        reply("InventoryReserved", orderId, null);
        awaitState(orderId, SagaState.INVENTORY_RESERVED);
        JsonNode credit = objectMapper.readTree(jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE aggregate_id = ? AND event_type = 'ReserveCredit'",
                String.class, orderId.toString()));
        assertThat(credit.get("payload").get("amountVnd").asLong()).isEqualTo(3_450_000L);

        reply("CreditReserved", orderId, null);
        awaitState(orderId, SagaState.CONFIRMING);
        assertThat(commands(orderId, "CommitInventory")).isEqualTo(1);
        assertThat(commands(orderId, "CaptureCredit")).isEqualTo(1);

        reply("CreditCaptured", orderId, null);
        reply("InventoryCommitted", orderId, null);
        awaitState(orderId, SagaState.COMPLETED);
        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        assertThat(commands(orderId, "OrderConfirmed")).isEqualTo(1);

        mvc.perform(get("/api/orders/" + orderId).header("Authorization", TestJwts.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.items[0].unitPriceVnd").value(1_500_000));
        // Người khác không xem được đơn này.
        mvc.perform(get("/api/orders/" + orderId).header("Authorization",
                        TestJwts.bearer(TestJwts.user(UUID.randomUUID()))))
                .andExpect(status().isNotFound());
        List<String> transitions = jdbc.queryForList(
                "SELECT to_state FROM saga_transition WHERE saga_id = ? ORDER BY id", String.class, orderId);
        assertThat(transitions).containsSubsequence("PENDING", "INVENTORY_RESERVED", "CONFIRMING", "COMPLETED");
    }

    // ----------------------------------------------------------------- idempotency

    @Test
    void sameIdempotencyKeyReturnsSameOrderEvenUnderConcurrency() throws Exception {
        String token = userWithCart(7);
        String key = "key-dup-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    String body = checkout(token, key).andReturn().getResponse().getContentAsString();
                    return objectMapper.readTree(body).get("orderId").asText();
                }));
            }
            start.countDown();
            List<String> ids = new ArrayList<>();
            for (Future<String> f : futures) {
                ids.add(f.get());
            }
            assertThat(ids).containsOnly(ids.getFirst());
        } finally {
            pool.shutdownNow();
        }
        checkout(token, key).andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));
        UUID userId = UUID.fromString(com.nimbusds.jwt.SignedJWT.parse(token).getJWTClaimsSet().getSubject());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(1);
    }

    @Test
    void listingOrdersUsesConstantNumberOfQueriesRegardlessOfOrderCount() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = TestJwts.user(userId);
        given(productClient.findAll(anyCollection())).willReturn(List.of(
                new ProductInfo(KEYBOARD, "Mechanical Keyboard", 1_500_000, true),
                new ProductInfo(MOUSE, "Wireless Mouse", 450_000, true)));
        for (long revision = 1; revision <= 5; revision++) {
            // Mỗi đơn một cart revision khác nhau để không vướng ux_orders_active_cart.
            given(cartClient.getCart(anyString())).willReturn(new CartSnapshot(userId, revision, List.of(
                    new CartSnapshot.Line(KEYBOARD, 2), new CartSnapshot.Line(MOUSE, 1))));
            startCheckout(token);
        }

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        try {
            mvc.perform(get("/api/orders").header("Authorization", TestJwts.bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(5))
                    .andExpect(jsonPath("$[0].items.length()").value(2))
                    .andExpect(jsonPath("$[0].sagaState").value("PENDING"));
            // orders + order_item (batch IN) + checkout_saga (findAllById) — không tăng theo số đơn.
            assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(3);
        } finally {
            stats.setStatisticsEnabled(false);
        }
    }

    @Test
    void differentKeysForSameCartRevisionCannotCreateTwoActiveOrders() throws Exception {
        String token = userWithCart(3);
        startCheckout(token);
        checkout(token, "key-second-" + UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHECKOUT_ALREADY_IN_PROGRESS"));
    }

    @Test
    void duplicateReplyMessagesDoNotAdvanceSagaTwice() throws Exception {
        UUID orderId = startCheckout(userWithCart(1));
        UUID replyId = reply("InventoryReserved", orderId, null);
        replyWithId(replyId, "InventoryReserved", orderId, null); // broker giao lại y hệt
        reply("InventoryReserved", orderId, null);                  // reply cho command gửi lại
        awaitState(orderId, SagaState.INVENTORY_RESERVED);

        UUID creditId = reply("CreditReserved", orderId, null);
        replyWithId(creditId, "CreditReserved", orderId, null);
        awaitState(orderId, SagaState.CONFIRMING);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
                .until(() -> commands(orderId, "ReserveCredit") == 1 && commands(orderId, "CaptureCredit") == 1);
    }

    // ----------------------------------------------------------------- compensation

    @Test
    void creditRejectionReleasesInventoryAndFailsOrder() throws Exception {
        String token = userWithCart(11);
        UUID orderId = startCheckout(token);
        reply("InventoryReserved", orderId, null);
        awaitState(orderId, SagaState.INVENTORY_RESERVED);

        reply("CreditReservationRejected", orderId, "INSUFFICIENT_FUNDS");
        awaitState(orderId, SagaState.COMPENSATING);
        assertThat(commands(orderId, "ReleaseInventory")).isEqualTo(1);
        assertThat(orderStatus(orderId)).as("chưa FAILED khi bồi hoàn chưa xong").isEqualTo("PENDING");

        reply("InventoryReleased", orderId, null);
        awaitState(orderId, SagaState.FAILED);
        assertThat(orderStatus(orderId)).isEqualTo("FAILED");
        assertThat(commands(orderId, "CaptureCredit")).isZero();
        mvc.perform(get("/api/orders/" + orderId).header("Authorization", TestJwts.bearer(token)))
                .andExpect(jsonPath("$.failureReason").value("CREDIT_REJECTED:INSUFFICIENT_FUNDS"));

        // Đơn FAILED không chặn checkout lại cùng giỏ.
        checkout(token, "key-retry-" + UUID.randomUUID()).andExpect(status().isAccepted());
    }

    @Test
    void inventoryRejectionFailsWithoutReservingCredit() throws Exception {
        UUID orderId = startCheckout(userWithCart(1));
        reply("InventoryReservationRejected", orderId, "INSUFFICIENT_STOCK:" + KEYBOARD);
        awaitState(orderId, SagaState.FAILED);
        assertThat(commands(orderId, "ReserveCredit")).isZero();
        assertThat(orderStatus(orderId)).isEqualTo("FAILED");
    }

    // ----------------------------------------------------------------- timeouts, restart, crash windows

    @Test
    void sagaResumesFromPersistedStateWhenRepliesAreMissing() throws Exception {
        // Tương đương "Order Service restart giữa Saga": state nằm trong DB, watcher tiếp tục từ deadline.
        UUID orderId = startCheckout(userWithCart(1));
        assertThat(commands(orderId, "ReserveInventory")).isEqualTo(1);

        clock.advance(Duration.ofSeconds(11));
        watcher.processDueSagas();
        assertThat(commands(orderId, "ReserveInventory")).as("gửi lại cùng reservationId").isEqualTo(2);
        assertThat(sagas.findById(orderId).orElseThrow().getStepAttempts()).isEqualTo(1);

        reply("InventoryReserved", orderId, null);
        awaitState(orderId, SagaState.INVENTORY_RESERVED);
    }

    @Test
    void reserveTimeoutsExhaustedCompensateAndLateReplyIsIgnored() throws Exception {
        UUID orderId = startCheckout(userWithCart(1));
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMinutes(5));
            watcher.processDueSagas();
        }
        awaitState(orderId, SagaState.COMPENSATING);
        assertThat(commands(orderId, "ReleaseInventory")).isEqualTo(1);

        reply("InventoryReserved", orderId, null); // reply muộn của lần gửi trước
        reply("InventoryReleased", orderId, null);
        awaitState(orderId, SagaState.FAILED);
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("INVENTORY_TIMEOUT");
        assertThat(commands(orderId, "ReserveCredit")).isZero();
    }

    @Test
    void captureSucceededButReplyLost_watcherResendsOnlyCaptureAndOrderConfirms() throws Exception {
        UUID orderId = startCheckout(userWithCart(1));
        reply("InventoryReserved", orderId, null);
        awaitState(orderId, SagaState.INVENTORY_RESERVED);
        reply("CreditReserved", orderId, null);
        awaitState(orderId, SagaState.CONFIRMING);
        reply("InventoryCommitted", orderId, null);
        await().atMost(Duration.ofSeconds(10)).until(() ->
                sagas.findById(orderId).orElseThrow().getInventory().name().equals("CONFIRMED"));

        // Payment đã capture nhưng Order Service chết trước khi ghi nhận → reply coi như mất.
        clock.advance(Duration.ofSeconds(11));
        watcher.processDueSagas();
        assertThat(commands(orderId, "CaptureCredit")).isEqualTo(2);
        assertThat(commands(orderId, "CommitInventory")).as("không gửi lại bước đã xong").isEqualTo(1);
        assertThat(orderStatus(orderId)).isEqualTo("PENDING");

        // Payment trả lời lại (idempotent, không trừ tiền lần 2 — xem WalletIT) → đơn hoàn tất.
        reply("CreditCaptured", orderId, null);
        awaitState(orderId, SagaState.COMPLETED);
        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
    }

    @Test
    void openApiSpecAndSwaggerUiArePublicAndDescribeBearerAuth() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/orders/checkout'].post").exists())
                .andExpect(jsonPath("$.servers[0].url").value("/"));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void orderItemSnapshotIsImmutable() throws Exception {
        UUID orderId = startCheckout(userWithCart(1));
        assertThatThrownBy(() -> jdbc.update("UPDATE order_item SET unit_price_vnd = 1 WHERE order_id = ?", orderId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void cartIsReadWithTheCallersOwnToken() throws Exception {
        String token = userWithCart(1);
        startCheckout(token);
        // Order Service chuyển tiếp token của user; Cart Service tự xác định giỏ theo "sub" trong token đó.
        org.mockito.Mockito.verify(cartClient, org.mockito.Mockito.atLeastOnce()).getCart(token);
    }
}
