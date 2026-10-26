package com.webshop.review.service.impl;

import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.outbox.OutboxEvent;
import com.webshop.messaging.outbox.OutboxWriter;
import com.webshop.review.entity.ProductRating;
import com.webshop.review.messaging.event.ProductRatingChangedEvent;
import com.webshop.review.repository.ProductRatingRepository;
import com.webshop.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Giữ product_rating đúng với bảng review và phát ProductRatingChanged — trong CÙNG transaction với thay đổi review
 * (Outbox): không có lúc review đã đổi mà event bị mất, hoặc event đi mà review rollback.
 *
 * <p>Cách dùng: {@link #lock} TRƯỚC khi sửa review, {@link #recalculate} SAU khi đã flush.</p>
 */
@Component
@RequiredArgsConstructor
public class RatingRecalculator {

    private final ProductRatingRepository productRatingRepository;
    private final ReviewRepository reviewRepository;
    private final OutboxWriter outboxWriter;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public ProductRating lock(UUID productId) {
        productRatingRepository.createIfAbsent(productId, clock.instant());
        return productRatingRepository.lockById(productId).orElseThrow();
    }

    /** Đếm lại từ bảng review (không cộng/trừ dồn) → tự sửa được nếu từng có sai lệch. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recalculate(ProductRating rating) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Object[] row : reviewRepository.countPublishedByStars(rating.getProductId())) {
            counts.put(((Number) row[0]).intValue(), ((Number) row[1]).intValue());
        }
        rating.replace(counts, clock.instant());
        productRatingRepository.flush();
        outboxWriter.append(OutboxEvent.of("ProductRating", rating.getProductId().toString(), "ProductRatingChanged",
                RoutingKeys.REVIEW_RATING_CHANGED,
                new ProductRatingChangedEvent(rating.getProductId(), rating.getRatingCount(), rating.getRatingSum(),
                        rating.getAverage(), rating.getHistogram(), rating.getRevision())));
    }
}
