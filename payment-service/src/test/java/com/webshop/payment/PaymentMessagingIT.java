package com.webshop.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.test.TestJwts;
import com.webshop.payment.service.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentMessagingIT extends AbstractPaymentIT {

    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    WalletService wallets;
    @Autowired
    MockMvc mvc;

    @Test
    void duplicateUserRegisteredDeliveriesGrantInitialCreditOnce() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("userId", userId, "registeredAt", Instant.now().toString());

        send("UserRegistered", RoutingKeys.USER_REGISTERED, eventId, null, payload);   // lần đầu
        send("UserRegistered", RoutingKeys.USER_REGISTERED, eventId, null, payload);   // broker giao lại
        send("UserRegistered", RoutingKeys.USER_REGISTERED, UUID.randomUUID(), null, payload); // outbox gửi lại với id khác (giả định xấu nhất)

        await().atMost(Duration.ofSeconds(10)).until(() -> wallets.findWallet(userId).isPresent());
        await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(5)).until(() -> ledger(userId) == 1);
        assertThat(wallets.findWallet(userId).orElseThrow().balanceVnd()).isEqualTo(10_000_000L);
    }

    @Test
    void walletEndpointUsesUserIdFromJwtOnly() throws Exception {
        UUID userId = UUID.randomUUID();
        mvc.perform(get("/api/wallet/me").header("Authorization", TestJwts.bearer(TestJwts.user(userId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_INITIALIZED"));

        send("UserRegistered", RoutingKeys.USER_REGISTERED, UUID.randomUUID(), null, Map.of("userId", userId));
        await().atMost(Duration.ofSeconds(10)).until(() -> wallets.findWallet(userId).isPresent());

        mvc.perform(get("/api/wallet/me").header("Authorization", TestJwts.bearer(TestJwts.user(userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableVnd").value(10_000_000))
                .andExpect(jsonPath("$.userId").value(userId.toString()));
        mvc.perform(get("/api/wallet/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/wallets/" + userId)
                        .header("Authorization", TestJwts.bearer(TestJwts.user(UUID.randomUUID()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void reserveCommandProducesReplyWithSagaContext() throws Exception {
        UUID userId = UUID.randomUUID();
        send("UserRegistered", RoutingKeys.USER_REGISTERED, UUID.randomUUID(), null, Map.of("userId", userId));
        await().atMost(Duration.ofSeconds(10)).until(() -> wallets.findWallet(userId).isPresent());

        UUID orderId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        send("ReserveCredit", RoutingKeys.PAYMENT_RESERVE, commandId, orderId,
                Map.of("reservationId", orderId, "userId", userId, "amountVnd", 12_000_000L));

        await().atMost(Duration.ofSeconds(10)).until(() -> !jdbc.queryForList(
                "SELECT 1 FROM outbox_event WHERE aggregate_id = ?", orderId.toString()).isEmpty());
        Map<String, Object> reply = jdbc.queryForMap(
                "SELECT event_type, saga_id, causation_id, payload->'payload'->>'reason' AS reason "
                        + "FROM outbox_event WHERE aggregate_id = ?", orderId.toString());
        assertThat(reply.get("event_type")).isEqualTo("CreditReservationRejected");
        assertThat(reply.get("reason")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(reply.get("saga_id")).isEqualTo(orderId.toString());
        assertThat(reply.get("causation_id")).isEqualTo(commandId.toString());
    }

    private int ledger(UUID userId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM wallet_ledger l JOIN wallet w ON w.id = l.wallet_id "
                + "WHERE w.user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    private void send(String type, String routingKey, UUID eventId, UUID sagaId, Object payload) throws Exception {
        EventEnvelope envelope = new EventEnvelope(eventId, type, 1, Instant.now(), "Test", "agg",
                "corr-" + eventId, null, sagaId == null ? null : sagaId.toString(), objectMapper.valueToTree(payload));
        Message message = MessageBuilder.withBody(objectMapper.writeValueAsBytes(envelope))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(eventId.toString())
                .build();
        rabbit.send("webshop.events", routingKey, message);
    }
}
