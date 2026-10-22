package com.webshop.search.query;

import com.webshop.common.web.ApiException;

/**
 * Tham số tìm kiếm đã kiểm tra.
 * Giới hạn (page + 1) * size ≤ 10 000 = index.max_result_window mặc định: phân trang from/size sâu hơn
 * bắt Elasticsearch gom và sắp xếp quá nhiều kết quả trên mỗi shard. Cần duyệt sâu → search_after.
 */
public record SearchQuery(String q, Long minPriceVnd, Long maxPriceVnd, Sort sort, int page, int size) {

    static final int MAX_WINDOW = 10_000;

    public enum Sort {
        RELEVANCE, PRICE_ASC, PRICE_DESC, NAME
    }

    public SearchQuery {
        q = q == null ? null : q.strip();
        if (q != null && q.length() > 200) {
            throw ApiException.badRequest("QUERY_TOO_LONG", "q must be at most 200 characters");
        }
        if (minPriceVnd != null && maxPriceVnd != null && minPriceVnd > maxPriceVnd) {
            throw ApiException.badRequest("INVALID_PRICE_RANGE", "minPriceVnd must be <= maxPriceVnd");
        }
        if ((long) (page + 1) * size > MAX_WINDOW) {
            throw ApiException.badRequest("PAGE_TOO_DEEP", "(page + 1) * size must be <= " + MAX_WINDOW);
        }
        sort = sort == null ? Sort.RELEVANCE : sort;
    }

    public boolean hasText() {
        return q != null && !q.isEmpty();
    }
}
