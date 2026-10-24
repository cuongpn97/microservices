package com.webshop.search.service;

import com.webshop.search.dto.request.SearchQuery;
import com.webshop.search.dto.response.ProductSearchResponse;

public interface ProductSearchService {

    /** Chỉ trả sản phẩm đang bán và chưa bị xóa. Elasticsearch không kết nối được → 503. */
    ProductSearchResponse search(SearchQuery query);
}
