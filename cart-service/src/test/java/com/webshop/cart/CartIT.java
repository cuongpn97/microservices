package com.webshop.cart;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webshop.cart.client.ProductClient;
import com.webshop.cart.client.ProductClient.ProductInfo;
import com.webshop.common.http.DependencyUnavailableException;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.test.SharedContainers;
import com.webshop.messaging.test.TestJwts;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {TestJwts.SECRET_PROPERTY, "webshop.messaging.outbox.scheduling-enabled=false"})
@AutoConfigureMockMvc
class CartIT {

    static final UUID KEYBOARD = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID MOUSE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID DISCONTINUED = UUID.fromString("55555555-5555-5555-5555-555555555555");

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
    @MockitoBean
    ProductClient products;

    @BeforeEach
    void stubProducts() {
        ProductInfo keyboard = new ProductInfo(KEYBOARD, "Keyboard", 1_500_000, true);
        ProductInfo mouse = new ProductInfo(MOUSE, "Mouse", 450_000, true);
        given(products.find(KEYBOARD)).willReturn(Optional.of(keyboard));
        given(products.find(MOUSE)).willReturn(Optional.of(mouse));
        given(products.find(DISCONTINUED)).willReturn(Optional.of(new ProductInfo(DISCONTINUED, "Old", 1, false)));
        given(products.findAll(anyCollection())).willReturn(List.of(keyboard, mouse));
    }

    @Test
    void cartRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/cart")).andExpect(status().isUnauthorized());
    }

    @Test
    void addItemsAndReadCartWithAuthoritativePrices() throws Exception {
        String token = TestJwts.user(UUID.randomUUID());
        add(token, KEYBOARD, 2).andExpect(status().isOk());
        add(token, KEYBOARD, 1).andExpect(status().isOk());
        add(token, MOUSE, 1).andExpect(status().isOk());

        mvc.perform(get("/api/cart").header("Authorization", TestJwts.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[?(@.productId=='" + KEYBOARD + "')].quantity").value(3))
                .andExpect(jsonPath("$.estimatedTotalVnd").value(3 * 1_500_000 + 450_000))
                .andExpect(jsonPath("$.revision").value(3));
    }

    @Test
    void quantitiesMustBePositiveAndProductsActive() throws Exception {
        String token = TestJwts.user(UUID.randomUUID());
        add(token, KEYBOARD, 0).andExpect(status().isBadRequest());
        add(token, KEYBOARD, -3).andExpect(status().isBadRequest());
        add(token, DISCONTINUED, 1).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_UNAVAILABLE"));
        mvc.perform(put("/api/cart/items/" + KEYBOARD).header("Authorization", TestJwts.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void clientCannotTouchAnotherUsersCart() throws Exception {
        String alice = TestJwts.user(UUID.randomUUID());
        String bob = TestJwts.user(UUID.randomUUID());
        add(alice, KEYBOARD, 1).andExpect(status().isOk());
        mvc.perform(get("/api/cart").header("Authorization", TestJwts.bearer(bob)))
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void cartStillReadableWithoutPricesWhenProductServiceIsDown() throws Exception {
        String token = TestJwts.user(UUID.randomUUID());
        add(token, KEYBOARD, 1).andExpect(status().isOk());
        given(products.findAll(anyCollection()))
                .willThrow(new DependencyUnavailableException("product-service", "circuit breaker is OPEN", null));

        mvc.perform(get("/api/cart").header("Authorization", TestJwts.bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricingAvailable").value(false))
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void addingItemWhenProductServiceIsDownReturns503() throws Exception {
        String token = TestJwts.user(UUID.randomUUID());
        given(products.find(any())).willThrow(new DependencyUnavailableException("product-service", "timeout", null));
        add(token, KEYBOARD, 1).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void orderConfirmedRemovesOnlyPurchasedQuantitiesAndDuplicatesAreIgnored() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = TestJwts.user(userId);
        add(token, KEYBOARD, 2).andExpect(status().isOk());
        add(token, MOUSE, 1).andExpect(status().isOk());

        // Đơn hàng mua 1 keyboard + 1 mouse; sau checkout user thêm 1 keyboard nữa (vẫn phải còn lại).
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("orderId", UUID.randomUUID(), "userId", userId,
                "items", List.of(Map.of("productId", KEYBOARD, "quantity", 1), Map.of("productId", MOUSE, "quantity", 1)));
        sendOrderConfirmed(eventId, payload);
        sendOrderConfirmed(eventId, payload); // giao trùng

        await().atMost(Duration.ofSeconds(10)).until(() -> quantity(userId, MOUSE) == null);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
                .until(() -> Integer.valueOf(1).equals(quantity(userId, KEYBOARD)));
    }

    private Integer quantity(UUID userId, UUID productId) {
        return jdbc.queryForList("SELECT quantity FROM cart_item WHERE user_id = ? AND product_id = ?",
                Integer.class, userId, productId).stream().findFirst().orElse(null);
    }

    private void sendOrderConfirmed(UUID eventId, Object payload) throws Exception {
        EventEnvelope envelope = new EventEnvelope(eventId, "OrderConfirmed", 1, Instant.now(), "Order", "o",
                "corr", null, null, objectMapper.valueToTree(payload));
        rabbit.send("webshop.events", RoutingKeys.ORDER_CONFIRMED,
                MessageBuilder.withBody(objectMapper.writeValueAsBytes(envelope))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON).setMessageId(eventId.toString()).build());
    }

    private org.springframework.test.web.servlet.ResultActions add(String token, UUID productId, int qty)
            throws Exception {
        return mvc.perform(post("/api/cart/items").header("Authorization", TestJwts.bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + productId + "\",\"quantity\":" + qty + "}"));
    }
}
