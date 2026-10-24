package com.webshop.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webshop.inventory.service.InventoryService;
import com.webshop.inventory.repository.InventoryItemRepository;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.test.TestJwts;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Command qua RabbitMQ thật: dedup theo messageId (Inbox) VÀ theo reservationId (nghiệp vụ). */
class InventoryMessagingIT extends AbstractInventoryIT {

    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    InventoryService inventory;
    @Autowired
    InventoryItemRepository items;
    @Autowired
    MockMvc mvc;

    @Test
    void duplicateCommandsDoNotRepeatBusinessEffectsButEveryDistinctCommandGetsAReply() throws Exception {
        UUID product = UUID.randomUUID();
        inventory.setStock(product, 10);
        UUID orderId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("reservationId", orderId,
                "items", List.of(Map.of("productId", product, "quantity", 4)));

        UUID firstEventId = UUID.randomUUID();
        send("ReserveInventory", RoutingKeys.INVENTORY_RESERVE, firstEventId, orderId, payload);
        send("ReserveInventory", RoutingKeys.INVENTORY_RESERVE, firstEventId, orderId, payload); // giao lại y hệt
        send("ReserveInventory", RoutingKeys.INVENTORY_RESERVE, UUID.randomUUID(), orderId, payload); // Saga gửi lại

        await().atMost(Duration.ofSeconds(10)).until(() -> replies(orderId) == 2);
        assertThat(items.findById(product).orElseThrow().getReservedQuantity()).isEqualTo(4);
        List<String> types = jdbc.queryForList(
                "SELECT event_type FROM outbox_event WHERE aggregate_id = ? ORDER BY created_at", String.class,
                orderId.toString());
        assertThat(types).containsOnly("InventoryReserved");
        Map<String, Object> reply = jdbc.queryForList(
                "SELECT saga_id, causation_id, routing_key FROM outbox_event WHERE aggregate_id = ?",
                orderId.toString()).getFirst();
        assertThat(reply.get("saga_id")).isEqualTo(orderId.toString());
        assertThat(reply.get("routing_key")).isEqualTo(RoutingKeys.INVENTORY_REPLY);
        assertThat(reply.get("causation_id")).isNotNull();

        send("CommitInventory", RoutingKeys.INVENTORY_COMMIT, UUID.randomUUID(), orderId,
                Map.of("reservationId", orderId));
        await().atMost(Duration.ofSeconds(10)).until(() -> replies(orderId) == 3);
        assertThat(items.findById(product).orElseThrow().getStockOnHand()).isEqualTo(6);
    }

    @Test
    void adminCanSetStockAndAnyoneCanReadIt() throws Exception {
        UUID product = UUID.randomUUID();
        mvc.perform(put("/api/inventory/" + product).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockOnHand\":7}")
                        .header("Authorization", TestJwts.bearer(TestJwts.user(UUID.randomUUID()))))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/inventory/" + product).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockOnHand\":7}")
                        .header("Authorization", TestJwts.bearer(TestJwts.admin(UUID.randomUUID()))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/inventory/" + product))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(7));
    }

    @Test
    void batchReturnsStockForKnownProductsAndSkipsUnknownOnes() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        inventory.setStock(a, 3);
        inventory.setStock(b, 9);
        mvc.perform(get("/api/inventory/batch")
                        .param("ids", a.toString(), b.toString(), UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.productId == '" + a + "')].available").value(3))
                .andExpect(jsonPath("$[?(@.productId == '" + b + "')].available").value(9));

        String tooMany = String.join(",", Collections.nCopies(101, UUID.randomUUID().toString()));
        mvc.perform(get("/api/inventory/batch").param("ids", tooMany))
                .andExpect(status().isBadRequest());
    }

    private int replies(UUID orderId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", Integer.class,
                orderId.toString());
        return n == null ? 0 : n;
    }

    private void send(String type, String routingKey, UUID eventId, UUID sagaId, Object payload) throws Exception {
        EventEnvelope envelope = new EventEnvelope(eventId, type, 1, Instant.now(), "Order", sagaId.toString(),
                "corr-" + sagaId, null, sagaId.toString(), objectMapper.valueToTree(payload));
        Message message = MessageBuilder.withBody(objectMapper.writeValueAsBytes(envelope))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(eventId.toString())
                .build();
        rabbit.send("webshop.events", routingKey, message);
    }
}
