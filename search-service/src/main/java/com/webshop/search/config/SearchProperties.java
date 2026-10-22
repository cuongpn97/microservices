package com.webshop.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param indexAlias tên ứng dụng dùng để đọc/ghi (alias), không phải tên index thật
 * @param indexName  index thật đứng sau alias; đổi mapping = tạo index version mới rồi chuyển alias
 */
@ConfigurationProperties(prefix = "webshop.search")
public record SearchProperties(String indexAlias, String indexName, Topics topics) {

    public record Topics(String products) {
    }
}
