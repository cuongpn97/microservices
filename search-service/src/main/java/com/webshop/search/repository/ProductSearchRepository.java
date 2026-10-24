package com.webshop.search.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.webshop.common.http.DependencyUnavailableException;
import com.webshop.search.config.SearchProperties;
import com.webshop.search.dto.request.SearchQuery;
import com.webshop.search.entity.ProductSearchHit;
import com.webshop.search.entity.ProductSearchPage;
import com.webshop.search.enums.SearchSort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Đọc từ index Elasticsearch (phía ĐỌC của read model). Toàn bộ query DSL nằm ở đây.
 * Luôn đọc qua ALIAS (không phải index thật) để việc dựng lại index ở phía sau không ảnh hưởng người dùng.
 */
@Repository
@RequiredArgsConstructor
public class ProductSearchRepository {

    private final ElasticsearchClient elasticsearchClient;
    private final SearchProperties searchProperties;

    public ProductSearchPage search(SearchQuery query) {
        BoolQuery.Builder bool = new BoolQuery.Builder()
                // Document có thể chỉ có cờ deleted (event delete tới khi index chưa có sản phẩm, ví dụ index dựng lại
                // từ topic đã compact một phần) → không có name, không hiển thị.
                .filter(f -> f.exists(e -> e.field("name")))
                .filter(f -> f.term(t -> t.field("active").value(true)))
                .mustNot(m -> m.term(t -> t.field("deleted").value(true)));

        if (query.hasText()) {
            String q = query.q();
            // Hai cách khớp, lấy điểm cao hơn: từ đầy đủ (cho phép gõ sai 1-2 ký tự) và tiền tố khi đang gõ dở.
            bool.must(m -> m.bool(b -> b
                    .should(s -> s.multiMatch(mm -> mm.query(q)
                            .fields("name^3", "description")
                            .type(TextQueryType.BestFields)
                            .operator(Operator.And)
                            .fuzziness("AUTO")))
                    .should(s -> s.match(mt -> mt.field("name.prefix").query(q).operator(Operator.And)))
                    .minimumShouldMatch("1")));
        }
        if (query.minPriceVnd() != null || query.maxPriceVnd() != null) {
            bool.filter(f -> f.range(r -> r.number(n -> {
                n.field("priceVnd");
                if (query.minPriceVnd() != null) {
                    n.gte(query.minPriceVnd().doubleValue());
                }
                if (query.maxPriceVnd() != null) {
                    n.lte(query.maxPriceVnd().doubleValue());
                }
                return n;
            })));
        }

        try {
            SearchResponse<Map> response = elasticsearchClient.search(s -> s
                    .index(searchProperties.indexAlias())
                    .query(q -> q.bool(bool.build()))
                    .sort(sort(query.sort(), query.hasText()))
                    .from(query.page() * query.size())
                    .size(query.size())
                    .trackTotalHits(t -> t.enabled(true)), Map.class);
            List<ProductSearchHit> hits = response.hits().hits().stream().map(ProductSearchRepository::toHit).toList();
            long total = response.hits().total() == null ? hits.size() : response.hits().total().value();
            return new ProductSearchPage(hits, total, response.took());
        } catch (IOException e) {
            // Không kết nối được (ES down, timeout) → 503. Lỗi 4xx từ ES là bug truy vấn → để lọt ra thành 500.
            throw new DependencyUnavailableException("elasticsearch", e.getMessage(), e);
        }
    }

    private static List<SortOptions> sort(SearchSort sort, boolean hasText) {
        SortOptions byName = SortOptions.of(o -> o.field(f -> f.field("name.sort").order(SortOrder.Asc)));
        return switch (sort) {
            case PRICE_ASC -> List.of(SortOptions.of(o -> o.field(f -> f.field("priceVnd").order(SortOrder.Asc))),
                    byName);
            case PRICE_DESC -> List.of(SortOptions.of(o -> o.field(f -> f.field("priceVnd").order(SortOrder.Desc))),
                    byName);
            case NAME -> List.of(byName);
            // Không có từ khóa thì điểm liên quan đều bằng nhau → sắp theo tên cho ổn định giữa các trang.
            case RELEVANCE -> hasText ? List.of(SortOptions.of(o -> o.score(sc -> sc.order(SortOrder.Desc))), byName)
                    : List.of(byName);
        };
    }

    @SuppressWarnings("unchecked")
    private static ProductSearchHit toHit(Hit<Map> hit) {
        Map<String, Object> src = hit.source() == null ? Map.of() : hit.source();
        Number price = (Number) src.get("priceVnd");
        return new ProductSearchHit(hit.id(), (String) src.get("name"), (String) src.get("description"),
                price == null ? 0 : price.longValue(), hit.score());
    }
}
