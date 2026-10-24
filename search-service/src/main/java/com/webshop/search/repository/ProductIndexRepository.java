package com.webshop.search.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import co.elastic.clients.json.JsonData;
import com.webshop.search.config.ProductIndexBootstrap;
import com.webshop.search.config.SearchProperties;
import com.webshop.search.entity.ProductDocument;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ghi vào index Elasticsearch (phía GHI của read model). Mỗi thao tác là MỘT update có script chạy trên server
 * Elasticsearch — kiểm tra và ghi trong cùng một bước, tương tự "UPDATE ... WHERE" có điều kiện bên SQL.
 *
 * <p>Chống ghi lùi: bản có {@code version} NHỎ HƠN bản đang có bị bỏ qua (noop) — bảo vệ khi replay đè lên dữ liệu
 * mới hơn. Cho phép version BẰNG: UPDATE bằng SQL thô không tăng version, nhưng trong cùng partition bản đến sau
 * luôn mới hơn.</p>
 */
@Repository
public class ProductIndexRepository {

    private static final String UPSERT_PRODUCT = """
            if (ctx._source.deleted == true) { ctx.op = 'noop'; return; }
            if (ctx._source.productVersion != null && params.version < ctx._source.productVersion) {
              ctx.op = 'noop'; return;
            }
            ctx._source.productId = params.id;
            ctx._source.name = params.name;
            ctx._source.description = params.description;
            ctx._source.priceVnd = params.priceVnd;
            ctx._source.active = params.active;
            ctx._source.productVersion = params.version;
            ctx._source.productUpdatedAt = params.updatedAt;
            """;

    /** id sản phẩm là UUID, không bao giờ dùng lại → đánh dấu xóa là vĩnh viễn, bản cũ đến muộn không "hồi sinh". */
    private static final String DELETE_PRODUCT = """
            ctx._source.productId = params.id;
            ctx._source.deleted = true;
            """;

    private final ElasticsearchClient elasticsearchClient;
    private final SearchProperties searchProperties;

    /**
     * Nhận {@link ProductIndexBootstrap} (không dùng) chỉ để Spring tạo index TRƯỚC bean này — và trước Kafka listener
     * dùng nó. Nếu ghi trước khi index tồn tại, Elasticsearch tự tạo index với dynamic mapping (sai analyzer, sai kiểu).
     */
    public ProductIndexRepository(ElasticsearchClient elasticsearchClient, SearchProperties searchProperties,
                                  ProductIndexBootstrap bootstrap) {
        this.elasticsearchClient = elasticsearchClient;
        this.searchProperties = searchProperties;
    }

    /** @return kết quả Elasticsearch: "created", "updated" hoặc "noop" (bị bỏ qua do bản cũ hơn / đã xóa). */
    public String upsert(ProductDocument document) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", document.id());
        params.put("name", document.name());
        params.put("description", document.description());
        params.put("priceVnd", document.priceVnd());
        params.put("active", document.active());
        params.put("version", document.version());
        params.put("updatedAt", document.updatedAt());
        return apply(document.id(), UPSERT_PRODUCT, params);
    }

    /** @return kết quả Elasticsearch: "created" (xóa trước khi từng index) hoặc "updated". */
    public String markDeleted(String id) {
        return apply(id, DELETE_PRODUCT, Map.of("id", id));
    }

    private String apply(String id, String script, Map<String, Object> params) {
        Map<String, JsonData> jsonParams = new LinkedHashMap<>();
        params.forEach((k, v) -> jsonParams.put(k, JsonData.of(v)));
        try {
            UpdateResponse<Map> response = elasticsearchClient.update(u -> u
                    // Ghi vào index THẬT, không qua alias: khi đang dựng lại index mới, alias vẫn trỏ index cũ.
                    .index(searchProperties.indexName())
                    .id(id)
                    .script(s -> s.source(script).params(jsonParams))
                    // Document chưa tồn tại → chạy script trên document rỗng (thay vì chèn "upsert" nguyên văn).
                    .scriptedUpsert(true)
                    .upsert(Map.of()), Map.class);
            return response.result().jsonValue();
        } catch (IOException e) {
            throw new UncheckedIOException("Elasticsearch write failed for product " + id, e);
        }
    }
}
