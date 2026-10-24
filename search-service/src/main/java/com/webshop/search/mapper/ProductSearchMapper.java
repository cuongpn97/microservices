package com.webshop.search.mapper;

import com.webshop.search.dto.request.SearchQuery;
import com.webshop.search.dto.response.ProductHitResponse;
import com.webshop.search.dto.response.ProductSearchResponse;
import com.webshop.search.entity.ProductSearchHit;
import com.webshop.search.entity.ProductSearchPage;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ProductSearchMapper {

    /** page/size lấy từ query (trang client yêu cầu), phần còn lại từ kết quả Elasticsearch. */
    @Mapping(target = "items", source = "result.hits")
    @Mapping(target = "page", source = "query.page")
    @Mapping(target = "size", source = "query.size")
    @Mapping(target = "totalElements", source = "result.totalHits")
    @Mapping(target = "tookMs", source = "result.tookMs")
    ProductSearchResponse toResponse(ProductSearchPage result, SearchQuery query);

    ProductHitResponse toHitResponse(ProductSearchHit hit);
}
