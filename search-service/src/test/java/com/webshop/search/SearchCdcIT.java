package com.webshop.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.webshop.search.config.SearchProperties;
import com.webshop.search.config.ProductIndexBootstrap;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Elasticsearch + Kafka thật; message được dựng đúng định dạng Debezium (JsonConverter, schemas.enable=false)
 * thay vì chạy Debezium — phần Debezium ↔ PostgreSQL là cấu hình (infra/debezium), phần cần test là projection.
 */
@SpringBootTest(properties = {
        "webshop.security.jwt.secret=test-secret-for-search-service-0123456789abcdef",
        "spring.kafka.consumer.properties.metadata.max.age.ms=1000"
})
@AutoConfigureMockMvc
class SearchCdcIT {

    static final String PRODUCTS = "cdc.catalog.public.catalog_document";

    static final ElasticsearchContainer ELASTIC = start(new ElasticsearchContainer(
            "docker.elastic.co/elasticsearch/elasticsearch:8.18.8")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms256m -Xmx256m"));
    static final KafkaContainer KAFKA = start(new KafkaContainer("apache/kafka:3.9.2"));

    private static <T extends org.testcontainers.containers.GenericContainer<?>> T start(T container) {
        container.start();
        return container;
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", () -> "http://" + ELASTIC.getHttpHostAddress());
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    KafkaTemplate<Object, Object> kafka;
    @Autowired
    ElasticsearchClient elastic;
    @Autowired
    KafkaListenerEndpointRegistry listeners;
    @Autowired
    ConsumerFactory<?, ?> consumerFactory;
    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Test
    void snapshotRowIsSearchableIgnoringVietnameseDiacriticsAndByPrefix() throws Exception {
        String id = UUID.randomUUID().toString();
        String tag = tag();
        product(id, "r", "Bàn phím cơ " + tag, 1_500_000, true, 0);

        // Gõ không dấu vẫn ra "Bàn phím". Kết quả không mang tồn kho (UI đọc từ Inventory Service).
        awaitSearch("ban phim " + tag, r -> r
                .andExpect(jsonPath("$.items[0].productId").value(id))
                .andExpect(jsonPath("$.items[0].available").doesNotExist()));

        // Gõ dở (tiền tố) cũng khớp.
        awaitSearch("ban ph " + tag, r -> r.andExpect(jsonPath("$.items[*].productId", hasItem(id))));
    }

    @Test
    void olderVersionReplayedAfterNewerOneDoesNotOverwrite() throws Exception {
        String id = UUID.randomUUID().toString();
        String tag = tag();
        product(id, "u", "Laptop " + tag + " v5", 20_000_000, true, 5);
        awaitSearch(tag, r -> r.andExpect(jsonPath("$.items[0].minPriceVnd").value(20_000_000)));

        // Ví dụ reset offset để replay: bản v2 (cũ) đến SAU v5 → bị bỏ qua. Bản v6 đến sau đó vẫn được áp dụng.
        product(id, "u", "Laptop " + tag + " v2", 9_000_000, true, 2);
        product(id, "u", "Laptop " + tag + " v6", 21_000_000, true, 6);
        awaitSearch(tag, r -> r.andExpect(jsonPath("$.items[0].minPriceVnd").value(21_000_000)));
        assertThat(source(id).get("docVersion")).isEqualTo(6);
    }

    @Test
    void newIndexIsBuiltBehindTheAliasAndAliasSwitchesOnlyWhenConsumerCatchesUp() throws Exception {
        // Mô phỏng nâng cấp mapping: alias riêng của test đang trỏ index "cũ"; bootstrap của index mới không được
        // chuyển alias ngay (index mới có thể còn thiếu dữ liệu) mà chờ tín hiệu listener idle.
        String alias = "alias-" + tag();
        String oldIndex = "old-" + tag();
        elastic.indices().create(c -> c.index(oldIndex).aliases(alias, a -> a));
        ProductIndexBootstrap upgrade = new ProductIndexBootstrap(elastic,
                new SearchProperties(alias, "products-v3", new SearchProperties.Topics(PRODUCTS)));

        upgrade.afterPropertiesSet();
        assertThat(upgrade.isAliasReady()).isFalse();
        assertThat(aliasTargets(alias)).containsExactly(oldIndex);

        upgrade.onListenerIdle(null);
        assertThat(upgrade.isAliasReady()).isTrue();
        assertThat(aliasTargets(alias)).containsExactly("products-v3");
    }

    @Test
    void deletedOrInactiveProductsAreNotSearchable() throws Exception {
        String deleted = UUID.randomUUID().toString();
        String inactive = UUID.randomUUID().toString();
        String tag = tag();
        product(deleted, "c", "Chuột " + tag, 450_000, true, 0);
        product(inactive, "c", "Chuột cũ " + tag, 100_000, false, 0);
        awaitSearch(tag, r -> r.andExpect(jsonPath("$.totalElements").value(1)));

        delete(deleted);
        kafka.send(PRODUCTS, key(deleted), null).get(); // tombstone theo sau delete — phải được bỏ qua êm
        // Bản update cũ đến muộn sau delete không làm sản phẩm "sống lại".
        product(deleted, "u", "Chuột " + tag, 450_000, true, 1);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mvc.perform(get("/api/search/products").param("q", tag))
                        .andExpect(jsonPath("$.totalElements").value(0)));
    }

    @Test
    void malformedMessageGoesToDeadLetterTopicWithoutBlockingThePartition() throws Exception {
        String id = UUID.randomUUID().toString();
        String tag = tag();
        kafka.send(PRODUCTS, key(id), "{not json").get();
        product(id, "c", "Tai nghe " + tag, 990_000, true, 0);
        awaitSearch(tag, r -> r.andExpect(jsonPath("$.items[0].productId").value(id)));

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-check-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(PRODUCTS + ".DLT"));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (ConsumerRecord<String, String> rec : consumer.poll(Duration.ofMillis(500))) {
                    if (key(id).equals(rec.key())) {
                        return true;
                    }
                }
                return false;
            });
        }
    }

    @Test
    void consumerGroupFollowsIndexVersionAndConsumerNeverCreatesTopics() {
        // Group theo version index → đổi index = group mới = replay từ đầu. (id của @KafkaListener KHÔNG được ghi đè.)
        assertThat(listeners.getListenerContainer("cdc-products").getGroupId())
                .isEqualTo("search-indexer-products-v3");
        // Topic phải do Debezium tạo (compacted, đủ partition), không phải consumer tạo với cấu hình mặc định.
        assertThat(String.valueOf(consumerFactory.getConfigurationProperties()
                .get(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG))).isEqualTo("false");
    }

    @Test
    void variantValuesSkuCodesAndOverlappingPriceRangesAreSearchable() throws Exception {
        String id = UUID.randomUUID().toString();
        String tag = tag();
        String code = "TEE-" + tag.toUpperCase();
        document(id, "r", "Áo thun " + tag, 199_000, 219_000, true, 0, List.of(code), List.of("Đen", "Trắng", "XL"));

        // "áo đen": "áo" ở tên, "đen" ở giá trị option → vẫn khớp (cross_fields), không dấu vẫn khớp.
        awaitSearch("ao den " + tag, r -> r.andExpect(jsonPath("$.items[0].productId").value(id)));
        // Gõ đúng mã SKU (không phân biệt hoa thường).
        awaitSearch(code.toLowerCase(), r -> r.andExpect(jsonPath("$.items[0].productId").value(id)));
        // Khoảng giá SPU [199k, 219k] giao [210k, 300k] → có; [220k, 300k] → không.
        mvc.perform(get("/api/search/products").param("q", tag).param("minPriceVnd", "210000")
                        .param("maxPriceVnd", "300000"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].maxPriceVnd").value(219_000));
        mvc.perform(get("/api/search/products").param("q", tag).param("minPriceVnd", "220000"))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void invalidPagingIsRejected() throws Exception {
        mvc.perform(get("/api/search/products").param("page", "500").param("size", "50"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAGE_TOO_DEEP"));
        mvc.perform(get("/api/search/products").param("size", "51"))
                .andExpect(status().isBadRequest());
    }

    // ----------------------------------------------------------------- helpers: message dạng Debezium

    private void product(String id, String op, String name, long price, boolean active, long version)
            throws Exception {
        document(id, op, name, price, price, active, version, List.of(), List.of());
    }

    /** Dòng catalog_document: cột payload (jsonb) được Debezium gửi dưới dạng CHUỖI JSON. */
    private void document(String id, String op, String name, long minPrice, long maxPrice, boolean active,
                          long version, List<String> skuCodes, List<String> optionValues) throws Exception {
        Map<String, Object> payload = new java.util.HashMap<>(Map.of("productId", id, "slug", "s-" + id,
                "name", name, "description", "", "active", active, "minPriceVnd", minPrice, "maxPriceVnd", maxPrice,
                "skuCodes", skuCodes, "optionValues", optionValues, "ratingCount", 0));
        payload.put("updatedAt", Instant.now().toString());
        Map<String, Object> row = Map.of("product_id", id, "version", version, "schema_version", 1,
                "payload", objectMapper.writeValueAsString(payload), "updated_at", Instant.now().toString());
        kafka.send(PRODUCTS, key(id), envelope(op, objectMapper.writeValueAsString(row))).get();
    }

    private void delete(String id) throws Exception {
        kafka.send(PRODUCTS, key(id), """
                {"before":{"product_id":"%s"},"after":null,"op":"d","ts_ms":%d,"source":{}}"""
                .formatted(id, System.currentTimeMillis())).get();
    }

    private static String envelope(String op, String after) {
        return """
                {"before":null,"after":%s,"op":"%s","ts_ms":%d,"source":{"connector":"postgresql"}}"""
                .formatted(after, op, System.currentTimeMillis());
    }

    private static String key(String id) {
        return "{\"product_id\":\"%s\"}".formatted(id);
    }

    /** Từ khóa duy nhất cho mỗi test → các test dùng chung index không thấy dữ liệu của nhau. */
    private static String tag() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private interface Expectation {
        void check(ResultActions result) throws Exception;
    }

    private void awaitSearch(String q, Expectation expectation) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                expectation.check(mvc.perform(get("/api/search/products").param("q", q))
                        .andExpect(status().isOk())));
    }

    private java.util.Set<String> aliasTargets(String alias) throws Exception {
        return elastic.indices().getAlias(a -> a.name(alias)).result().keySet();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> source(String id) throws Exception {
        var response = elastic.get(g -> g.index("products-v3").id(id), Map.class);
        return response.found() ? (Map<String, Object>) response.source() : null;
    }
}
