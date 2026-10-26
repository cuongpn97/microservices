package com.webshop.review.mapper;

import com.webshop.review.dto.response.PageResponse;
import com.webshop.review.dto.response.RatingSummaryResponse;
import com.webshop.review.dto.response.ReviewResponse;
import com.webshop.review.entity.ProductRating;
import com.webshop.review.entity.Review;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.springframework.data.domain.Page;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ReviewMapper {

    /** Field trùng tên được map tự động; rating (short → int) và status (enum → String) MapStruct tự đổi kiểu. */
    ReviewResponse toResponse(Review review);

    List<ReviewResponse> toResponses(List<Review> reviews);

    @Mapping(target = "ratingAvg", source = "average")
    RatingSummaryResponse toSummary(ProductRating rating);

    default PageResponse<ReviewResponse> toPage(Page<Review> page) {
        return new PageResponse<>(toResponses(page.getContent()), page.getNumber(), page.getSize(),
                page.getTotalElements());
    }

    /** Sản phẩm chưa có đánh giá nào (chưa có dòng product_rating). */
    default RatingSummaryResponse emptySummary(UUID productId) {
        Map<Integer, Integer> histogram = new LinkedHashMap<>();
        for (int stars = 5; stars >= 1; stars--) {
            histogram.put(stars, 0);
        }
        return new RatingSummaryResponse(productId, 0, null, histogram);
    }
}
