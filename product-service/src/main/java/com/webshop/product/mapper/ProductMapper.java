package com.webshop.product.mapper;

import com.webshop.product.dto.response.PageResponse;
import com.webshop.product.dto.response.ProductResponse;
import com.webshop.product.entity.Product;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * MapStruct sinh class ProductMapperImpl lúc compile (xem target/generated-sources/annotations).
 * Field trùng tên giữa Product và ProductResponse được map tự động; field lệch tên mới cần @Mapping.
 *
 * Chỉ map entity → response. Chiều request → entity KHÔNG dùng mapper vì cần logic nghiệp vụ
 * (sinh id, giá trị mặc định, thời gian) — để trong service cho dễ thấy.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ProductMapper {

    ProductResponse toResponse(Product product);

    List<ProductResponse> toResponses(List<Product> products);

    default PageResponse<ProductResponse> toPageResponse(Page<Product> page) {
        return new PageResponse<>(toResponses(page.getContent()), page.getNumber(), page.getSize(),
                page.getTotalElements());
    }
}
