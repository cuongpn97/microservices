package com.webshop.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.test.SharedContainers;
import com.webshop.messaging.test.TestJwts;
import com.webshop.product.storage.ImageStorage;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {TestJwts.SECRET_PROPERTY, "webshop.messaging.outbox.scheduling-enabled=false"})
@AutoConfigureMockMvc
class ProductIT {

    static final String KEYBOARD_RED = "11111111-1111-1111-1111-111111111111";
    static final String DISCONTINUED = "55555555-5555-5555-5555-555555555555";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerPostgres(registry);
        SharedContainers.registerRabbit(registry);
    }

    /** Storage trong bộ nhớ thay cho object storage: test logic catalog, không test SDK S3. */
    @TestConfiguration
    static class FakeStorage {
        static final Set<String> OBJECTS = ConcurrentHashMap.newKeySet();

        @Bean
        @Primary
        ImageStorage inMemoryImageStorage() {
            return new ImageStorage() {
                public void put(String key, byte[] data, String contentType) {
                    OBJECTS.add(key);
                }

                public void delete(String key) {
                    OBJECTS.remove(key);
                }

                public String publicUrl(String key) {
                    return "http://cdn.test/" + key;
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    RabbitTemplate rabbit;

    // ------------------------------------------------------------------ storefront

    @Test
    void listingIsPublicActiveOnlyAndShowsPriceRangeAcrossSkus() throws Exception {
        mvc.perform(get("/api/products").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].active", everyItem(is(true))))
                .andExpect(jsonPath("$.items[?(@.slug=='mechanical-keyboard')].minPriceVnd").value(1_500_000))
                .andExpect(jsonPath("$.items[?(@.slug=='mechanical-keyboard')].maxPriceVnd").value(1_550_000));
    }

    @Test
    void detailBySlugHasOptionsSkusAndMarkdownDescriptionButDraftsAreHidden() throws Exception {
        mvc.perform(get("/api/products/ao-thun-cotton-basic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options", hasSize(2)))
                .andExpect(jsonPath("$.skus", hasSize(6)))
                .andExpect(jsonPath("$.skus[?(@.skuCode=='TEE-BLK-XL')].variantName").value("Đen / XL"))
                .andExpect(jsonPath("$.specifications[0].name").value("Chất liệu"));
        mvc.perform(get("/api/products/discontinued-item"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void skuLookupForCartAndOrderIncludesInactiveWithFlag() throws Exception {
        mvc.perform(get("/api/products/skus/" + KEYBOARD_RED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productName").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$.variantName").value("Red"))
                .andExpect(jsonPath("$.priceVnd").value(1_500_000))
                .andExpect(jsonPath("$.active").value(true));
        mvc.perform(get("/api/products/skus/" + DISCONTINUED))
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/products/skus/batch").param("ids", KEYBOARD_RED, DISCONTINUED,
                        UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/products/skus/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SKU_NOT_FOUND"));
    }

    @Test
    void everyProductHasACatalogDocumentForSearchAfterStartup() {
        Integer missing = jdbc.queryForObject("""
                SELECT count(*) FROM product p LEFT JOIN catalog_document d ON d.product_id = p.id
                WHERE d.product_id IS NULL""", Integer.class);
        assertThat(missing).isZero();
    }

    // ------------------------------------------------------------------ backoffice

    @Test
    void adminListingWorksWithAndWithoutFilters() throws Exception {
        String bearer = TestJwts.bearer(TestJwts.admin(UUID.randomUUID()));
        // Không có q/active: tham số null từng làm PostgreSQL lỗi "text ~~ bytea" (500).
        mvc.perform(get("/api/products/manage").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.slug=='discontinued-item')]").exists());
        mvc.perform(get("/api/products/manage").param("q", "KEYBOARD").header("Authorization", bearer))
                .andExpect(jsonPath("$.items[0].slug").value("mechanical-keyboard"));
        mvc.perform(get("/api/products/manage").param("active", "false").header("Authorization", bearer))
                .andExpect(jsonPath("$.items[*].active", everyItem(is(false))));
    }

    @Test
    void onlyAdminCanManageCatalog() throws Exception {
        String body = "{\"name\":\"Webcam\"}";
        mvc.perform(get("/api/products/manage")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/products/manage").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", TestJwts.bearer(TestJwts.user(UUID.randomUUID()))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/products/manage").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", TestJwts.bearer(TestJwts.expired(UUID.randomUUID()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminBuildsADraftWithVariantsThenPublishesIt() throws Exception {
        JsonNode created = json(admin(post("/api/products/manage"), """
                {"name":"Bình giữ nhiệt Đa Năng","shortDescription":"Giữ nóng 12 giờ",
                 "options":[{"name":"Dung tích","values":["500ml","750ml"]}],
                 "specifications":[{"name":"Chất liệu","value":"Inox 304"}]}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("binh-giu-nhiet-da-nang"))
                .andExpect(jsonPath("$.active").value(false)));
        String id = created.get("id").asText();
        String code = "BOT-" + id.substring(0, 6).toUpperCase();

        admin(post("/api/products/manage/" + id + "/skus"),
                "{\"skuCode\":\"" + code + "-5\",\"attributes\":{\"Dung tích\":\"500ml\"},\"priceVnd\":250000}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.skus", hasSize(1)));
        // Thiếu/sai thuộc tính so với options → 422; trùng mã SKU → 409.
        admin(post("/api/products/manage/" + id + "/skus"),
                "{\"skuCode\":\"" + code + "-X\",\"attributes\":{\"Màu\":\"Đỏ\"},\"priceVnd\":1}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CATALOG_CHANGE"));
        admin(post("/api/products/manage/" + id + "/skus"),
                "{\"skuCode\":\"" + code + "-5\",\"attributes\":{\"Dung tích\":\"750ml\"},\"priceVnd\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_CODE_TAKEN"));
        // Giá gạch ngang phải lớn hơn giá bán.
        admin(post("/api/products/manage/" + id + "/skus"), "{\"skuCode\":\"" + code
                + "-7\",\"attributes\":{\"Dung tích\":\"750ml\"},\"priceVnd\":300000,\"compareAtPriceVnd\":100}")
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(get("/api/products/" + id)).andExpect(status().isNotFound()); // bản nháp chưa công bố
        admin(put("/api/products/manage/" + id), "{\"name\":\"Bình giữ nhiệt Đa Năng\",\"active\":true,"
                + "\"options\":[{\"name\":\"Dung tích\",\"values\":[\"500ml\",\"750ml\"]}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("binh-giu-nhiet-da-nang")); // slug giữ nguyên
        mvc.perform(get("/api/products/binh-giu-nhiet-da-nang")).andExpect(status().isOk());

        // Bỏ giá trị "500ml" khỏi options trong khi SKU đang dùng → từ chối.
        admin(put("/api/products/manage/" + id), "{\"name\":\"X\",\"options\":[{\"name\":\"Dung tích\","
                + "\"values\":[\"750ml\"]}]}")
                .andExpect(status().isUnprocessableEntity());

        JsonNode doc = objectMapper.readTree(jdbc.queryForObject(
                "SELECT payload::text FROM catalog_document WHERE product_id = ?::uuid", String.class, id));
        assertThat(doc.get("active").asBoolean()).isTrue();
        assertThat(doc.get("minPriceVnd").asLong()).isEqualTo(250_000);
        assertThat(jdbc.queryForObject("SELECT version FROM catalog_document WHERE product_id = ?::uuid",
                Long.class, id)).isEqualTo(3); // tạo + thêm SKU + bật bán
    }

    @Test
    void imagesAreValidatedByContentReorderedAndDeletedFromStorage() throws Exception {
        String id = json(admin(post("/api/products/manage"), "{\"name\":\"Ảnh test " + UUID.randomUUID() + "\"}"))
                .get("id").asText();
        String bearer = TestJwts.bearer(TestJwts.admin(UUID.randomUUID()));

        mvc.perform(multipart("/api/products/manage/" + id + "/images")
                        .file(new MockMultipartFile("file", "evil.png", "image/png", "<script>".getBytes()))
                        .header("Authorization", bearer))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE"));

        String first = json(mvc.perform(multipart("/api/products/manage/" + id + "/images")
                        .file(new MockMultipartFile("file", "a.bin", "application/octet-stream", PNG))
                        .header("Authorization", bearer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.images[0].contentType").value("image/png"))
                .andExpect(jsonPath("$.images[0].url", startsWith("http://cdn.test/products/" + id))))
                .get("images").get(0).get("id").asText();
        String second = json(mvc.perform(multipart("/api/products/manage/" + id + "/images")
                        .file(new MockMultipartFile("file", "b.png", "image/png", PNG))
                        .header("Authorization", bearer)))
                .get("images").get(1).get("id").asText();

        admin(put("/api/products/manage/" + id + "/images/order"),
                "{\"imageIds\":[\"" + second + "\",\"" + first + "\"]}")
                .andExpect(jsonPath("$.images[0].id").value(second));

        String key = jdbc.queryForObject("SELECT object_key FROM product_image WHERE id = ?::uuid", String.class,
                first);
        assertThat(FakeStorage.OBJECTS).contains(key);
        mvc.perform(delete("/api/products/manage/" + id + "/images/" + first).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images", hasSize(1)));
        assertThat(FakeStorage.OBJECTS).doesNotContain(key);
    }

    @Test
    void ratingEventsUpdateTheSummaryAndStaleOnesAreIgnored() throws Exception {
        String productId = jdbc.queryForObject("SELECT id::text FROM product WHERE slug = 'wireless-mouse'",
                String.class);
        ratingChanged(productId, 2, 9, 2);
        ratingChanged(productId, 1, 5, 1); // đến muộn, cũ hơn

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/products/wireless-mouse"))
                        .andExpect(jsonPath("$.ratingCount").value(2))
                        .andExpect(jsonPath("$.ratingAvg").value(4.5)));
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mvc.perform(get("/api/products/wireless-mouse")).andExpect(jsonPath("$.ratingCount").value(2)));
    }

    private void ratingChanged(String productId, int count, long sum, long version) throws Exception {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("productId", productId, "ratingCount", count, "ratingSum", sum,
                "version", version);
        EventEnvelope envelope = new EventEnvelope(eventId, "ProductRatingChanged", 1, Instant.now(),
                "ProductRating", productId, "corr", null, null, objectMapper.valueToTree(payload));
        rabbit.send("webshop.events", RoutingKeys.REVIEW_RATING_CHANGED,
                MessageBuilder.withBody(objectMapper.writeValueAsBytes(envelope))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON).setMessageId(eventId.toString()).build());
    }

    private ResultActions admin(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                String body) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", TestJwts.bearer(TestJwts.admin(UUID.randomUUID()))));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
