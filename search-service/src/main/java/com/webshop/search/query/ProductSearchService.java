package com.webshop.search.query;

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
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
public class ProductSearchService {

    private final ElasticsearchClient client;
    private final SearchProperties props;

    public ProductSearchService(ElasticsearchClient client, SearchProperties props) {
        this.client = client;
        this.props = props;
    }

    public SearchResult search(SearchQuery query) {
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
            SearchResponse<Map> response = client.search(s -> s
                    .index(props.indexAlias())
                    .query(q -> q.bool(bool.build()))
                    .sort(sort(query.sort(), query.hasText()))
                    .from(query.page() * query.size())
                    .size(query.size())
                    .trackTotalHits(t -> t.enabled(true)), Map.class);
            List<ProductHit> items = response.hits().hits().stream().map(ProductSearchService::toHit).toList();
            long total = response.hits().total() == null ? items.size() : response.hits().total().value();
            return new SearchResult(items, query.page(), query.size(), total, response.took());
        } catch (IOException e) {
            // Không kết nối được (ES down, timeout) → 503. Lỗi 4xx từ ES là bug truy vấn → để lọt ra thành 500.
            throw new DependencyUnavailableException("elasticsearch", e.getMessage(), e);
        }
    }

    private static List<SortOptions> sort(SearchQuery.Sort sort, boolean hasText) {
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
    private static ProductHit toHit(Hit<Map> hit) {
        Map<String, Object> src = hit.source() == null ? Map.of() : hit.source();
        Number price = (Number) src.get("priceVnd");
        return new ProductHit(hit.id(), (String) src.get("name"), (String) src.get("description"),
                price == null ? 0 : price.longValue(), hit.score());
    }

    /** Không có tồn kho: lấy số thật từ GET /api/inventory/batch cho các sản phẩm đang hiển thị. */
    public record ProductHit(String productId, String name, String description, long priceVnd, Double score) {
    }

    public record SearchResult(List<ProductHit> items, int page, int size, long totalElements, long tookMs) {
    }
}
