package com.webshop.search.enums;

/** Cách sắp xếp kết quả tìm kiếm (query param ?sort=...). */
public enum SearchSort {
    /** Theo điểm liên quan; không có từ khóa thì theo tên. */
    RELEVANCE,
    PRICE_ASC,
    PRICE_DESC,
    NAME
}
