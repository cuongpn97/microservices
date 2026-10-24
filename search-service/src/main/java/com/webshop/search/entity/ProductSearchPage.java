package com.webshop.search.entity;

import java.util.List;

/**
 * Một trang kết quả từ Elasticsearch.
 *
 * @param tookMs thời gian Elasticsearch xử lý truy vấn (không gồm network) — trả cho client để dễ quan sát.
 */
public record ProductSearchPage(List<ProductSearchHit> hits, long totalHits, long tookMs) {
}
