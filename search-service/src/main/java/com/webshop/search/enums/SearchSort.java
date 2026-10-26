package com.webshop.search.enums;

/** Cách sắp xếp kết quả tìm kiếm (query param ?sort=...). */
public enum SearchSort {
    /** Theo điểm liên quan; không có từ khóa thì theo tên. */
    RELEVANCE,
    /** Theo giá thấp nhất của SPU ("Từ ..."). */
    PRICE_ASC,
    /** Theo giá cao nhất của SPU. */
    PRICE_DESC,
    /** Điểm đánh giá trung bình, rồi số lượt đánh giá. */
    RATING,
    NAME
}
