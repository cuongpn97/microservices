package com.webshop.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.test.SharedContainers;
import com.webshop.messaging.test.TestJwts;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {TestJwts.SECRET_PROPERTY, "webshop.messaging.outbox.scheduling-enabled=false"})
@AutoConfigureMockMvc
class ReviewIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerPostgres(registry);
        SharedContainers.registerRabbit(registry);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void readingIsPublicButWritingRequiresAConfirmedPurchase() throws Exception {
        UUID product = UUID.randomUUID();
        mvc.perform(get("/api/reviews/products/" + product)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get("/api/reviews/products/" + product + "/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ratingCount").value(0))
                .andExpect(jsonPath("$.histogram.5").value(0));

        String token = TestJwts.user(UUID.randomUUID());
        review(null, product, 5).andExpect(status().isUnauthorized());
        review(token, product, 5).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_PURCHASED"));
    }

    @Test
    void buyerCanReviewOnceAndEditingRecalculatesTheSummaryAndEmitsAnEvent() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        String token = TestJwts.user(userId);
        UUID eventId = UUID.randomUUID();
        orderConfirmed(eventId, userId, product, "Đen / M");
        orderConfirmed(eventId, userId, product, "Đen / M"); // giao trùng → Inbox chặn

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/reviews/products/" + product + "/mine")
                                .header("Authorization", TestJwts.bearer(token)))
                        .andExpect(jsonPath("$.canReview").value(true)));

        review(token, product, 4).andExpect(status().isOk())
                .andExpect(jsonPath("$.variantName").value("Đen / M"))
                .andExpect(jsonPath("$.authorName").value(org.hamcrest.Matchers.endsWith("***@example.com")));
        review(token, product, 2).andExpect(status().isOk()); // sửa, không tạo bản thứ hai

        mvc.perform(get("/api/reviews/products/" + product + "/summary"))
                .andExpect(jsonPath("$.ratingCount").value(1))
                .andExpect(jsonPath("$.ratingAvg").value(2.0))
                .andExpect(jsonPath("$.histogram.2").value(1));

        List<JsonNode> events = ratingEvents(product);
        assertThat(events).hasSize(2);
        assertThat(events.get(1).get("payload").get("version").asLong()).isEqualTo(2);
        assertThat(events.get(1).get("payload").get("ratingSum").asLong()).isEqualTo(2);
    }

    @Test
    void hiddenReviewsAreNotListedNorCountedAndOnlyAdminsModerate() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        String token = TestJwts.user(userId);
        orderConfirmed(UUID.randomUUID(), userId, product, "");
        await().atMost(Duration.ofSeconds(10)).until(() -> purchases(userId) == 1);
        String body = review(token, product, 1).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        String reviewId = objectMapper.readTree(body).get("id").asText();

        String hide = "{\"status\":\"HIDDEN\"}";
        mvc.perform(put("/api/reviews/moderation/" + reviewId + "/status").header("Authorization",
                        TestJwts.bearer(token)).contentType(MediaType.APPLICATION_JSON).content(hide))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/reviews/moderation/" + reviewId + "/status").header("Authorization",
                        TestJwts.bearer(TestJwts.admin(UUID.randomUUID())))
                        .contentType(MediaType.APPLICATION_JSON).content(hide))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HIDDEN"));

        mvc.perform(get("/api/reviews/products/" + product)).andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get("/api/reviews/products/" + product + "/summary"))
                .andExpect(jsonPath("$.ratingCount").value(0));
        // Người viết sửa bài bị ẩn: vẫn ẩn (kiểm duyệt không bị "lách" bằng cách sửa).
        review(token, product, 5).andExpect(jsonPath("$.status").value("HIDDEN"));
    }

    private ResultActions review(String token, UUID product, int rating) throws Exception {
        var request = put("/api/reviews/products/" + product + "/mine").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":" + rating + ",\"title\":\"Ổn\",\"content\":\"Dùng một tuần, rất hài lòng.\"}");
        if (token != null) {
            request.header("Authorization", TestJwts.bearer(token));
        }
        return mvc.perform(request);
    }

    private void orderConfirmed(UUID eventId, UUID userId, UUID productId, String variant) throws Exception {
        Map<String, Object> payload = Map.of("orderId", UUID.randomUUID(), "userId", userId, "totalVnd", 100,
                "items", List.of(Map.of("skuId", UUID.randomUUID(), "productId", productId,
                        "productName", "P", "variantName", variant, "quantity", 1)));
        EventEnvelope envelope = new EventEnvelope(eventId, "OrderConfirmed", 2, Instant.now(), "Order", "o",
                "corr", null, null, objectMapper.valueToTree(payload));
        rabbit.send("webshop.events", RoutingKeys.ORDER_CONFIRMED,
                MessageBuilder.withBody(objectMapper.writeValueAsBytes(envelope))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON).setMessageId(eventId.toString()).build());
    }

    private int purchases(UUID userId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM purchase WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    private List<JsonNode> ratingEvents(UUID product) {
        return jdbc.queryForList("SELECT payload::text FROM outbox_event WHERE aggregate_id = ? "
                        + "AND event_type = 'ProductRatingChanged' ORDER BY (payload->'payload'->>'version')::int",
                        String.class, product.toString())
                .stream().map(json -> {
                    try {
                        return objectMapper.readTree(json);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
    }
}
