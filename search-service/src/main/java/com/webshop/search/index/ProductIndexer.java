package com.webshop.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import co.elastic.clients.json.JsonData;
import com.webshop.search.config.SearchProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ghi read model: một document = một sản phẩm, chỉ từ luồng CDC của bảng products.
 *
 * <p>Không có tồn kho: search không lọc/sắp xếp theo tồn kho, nên index nó chỉ tốn một lần index lại document cho
 * MỖI reserve/commit/release (Elasticsearch không cập nhật tại chỗ) — đúng lúc flash sale. Số tồn kho hiển thị được
 * UI đọc thẳng từ Inventory Service cho các sản phẩm đang hiển thị.</p>
 *
 * <p>Chống ghi lùi: bản có {@code version} (cột @Version của JPA) NHỎ HƠN bản đang có bị bỏ qua — bảo vệ khi
 * replay đè lên dữ liệu mới hơn. Cho phép version BẰNG: UPDATE bằng SQL thô không tăng version, nhưng trong cùng
 * partition bản đến sau luôn mới hơn.</p>
 */
@Component
public class ProductIndexer {

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

    private final ElasticsearchClient client;
    private final SearchProperties props;
    private final MeterRegistry meters;

    /** Nhận {@link ProductIndexBootstrap} để Spring tạo index TRƯỚC khi bean này (và listener dùng nó) sẵn sàng. */
    public ProductIndexer(ElasticsearchClient client, SearchProperties props, MeterRegistry meters,
                          ProductIndexBootstrap bootstrap) {
        this.client = client;
        this.props = props;
        this.meters = meters;
    }

    public void upsertProduct(ProductRow row) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", row.id());
        params.put("name", row.name());
        params.put("description", row.description());
        params.put("priceVnd", row.priceVnd());
        params.put("active", row.active());
        params.put("version", row.version());
        params.put("updatedAt", row.updatedAt());
        apply(row.id(), UPSERT_PRODUCT, params);
    }

    public void deleteProduct(String id) {
        apply(id, DELETE_PRODUCT, Map.of("id", id));
    }

    private void apply(String id, String script, Map<String, Object> params) {
        Map<String, JsonData> jsonParams = new LinkedHashMap<>();
        params.forEach((k, v) -> jsonParams.put(k, JsonData.of(v)));
        try {
            UpdateResponse<Map> response = client.update(u -> u
                    // Ghi vào index THẬT, không qua alias: khi đang dựng lại index mới, alias vẫn trỏ index cũ.
                    .index(props.indexName())
                    .id(id)
                    .script(s -> s.source(script).params(jsonParams))
                    // Document chưa tồn tại → chạy script trên document rỗng (thay vì chèn "upsert" nguyên văn).
                    .scriptedUpsert(true)
                    .upsert(Map.of()), Map.class);
            meters.counter("webshop.search.cdc.applied", "result", response.result().jsonValue()).increment();
        } catch (IOException e) {
            throw new UncheckedIOException("Elasticsearch write failed for product " + id, e);
        }
    }

    public record ProductRow(String id, String name, String description, long priceVnd, boolean active,
                             long version, String updatedAt) {
    }
}
