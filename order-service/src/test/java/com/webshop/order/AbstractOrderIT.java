package com.webshop.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.test.MutableClock;
import com.webshop.messaging.test.SharedContainers;
import com.webshop.messaging.test.TestJwts;
import com.webshop.order.client.CartClient;
import com.webshop.order.client.ProductClient;
import com.webshop.order.saga.CheckoutSagaRepository;
import com.webshop.order.saga.SagaState;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base cho IT của Order Service: PostgreSQL + RabbitMQ thật; Cart/Product client được mock (đã có test riêng cho
 * resilience HTTP); Inventory/Payment được "đóng vai" bằng cách publish reply thật lên RabbitMQ.
 * Outbox publisher và Saga watcher KHÔNG chạy tự động → test điều khiển thời gian bằng MutableClock (không sleep).
 */
@SpringBootTest(properties = {
        TestJwts.SECRET_PROPERTY,
        "webshop.messaging.outbox.scheduling-enabled=false",
        "webshop.saga.watcher-enabled=false",
        "webshop.saga.step-timeout=10s",
        "webshop.saga.max-reserve-attempts=3"
})
@AutoConfigureMockMvc
@Import(AbstractOrderIT.ClockConfig.class)
public abstract class AbstractOrderIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerPostgres(registry);
        SharedContainers.registerRabbit(registry);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        MutableClock clock() {
            return MutableClock.startingNow();
        }
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected RabbitTemplate rabbit;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected CheckoutSagaRepository sagas;
    @MockitoBean
    protected CartClient cartClient;
    @MockitoBean
    protected ProductClient productClient;

    protected ResultActions checkout(String token, String idempotencyKey) throws Exception {
        var request = post("/api/orders/checkout").header("Authorization", TestJwts.bearer(token));
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    /** Đóng vai Inventory/Payment: publish một reply thật lên RabbitMQ. Trả về eventId để có thể gửi trùng. */
    protected UUID reply(String type, UUID orderId, String reason) throws Exception {
        UUID eventId = UUID.randomUUID();
        replyWithId(eventId, type, orderId, reason);
        return eventId;
    }

    protected void replyWithId(UUID eventId, String type, UUID orderId, String reason) throws Exception {
        String routingKey = type.startsWith("Inventory") ? RoutingKeys.INVENTORY_REPLY : RoutingKeys.PAYMENT_REPLY;
        Map<String, Object> payload = reason == null
                ? Map.of("reservationId", orderId, "status", "X")
                : Map.of("reservationId", orderId, "status", "X", "reason", reason);
        EventEnvelope envelope = new EventEnvelope(eventId, type, 1, Instant.now(), "Reservation", orderId.toString(),
                "corr-test", null, orderId.toString(), objectMapper.valueToTree(payload));
        rabbit.send("webshop.events", routingKey, MessageBuilder.withBody(objectMapper.writeValueAsBytes(envelope))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).setMessageId(eventId.toString()).build());
    }

    protected void awaitState(UUID orderId, SagaState state) {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> sagas.findById(orderId).map(s -> s.getState() == state).orElse(false));
    }

    protected int commands(UUID orderId, String type) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?",
                Integer.class, orderId.toString(), type);
        return n == null ? 0 : n;
    }

    protected String orderStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }
}
