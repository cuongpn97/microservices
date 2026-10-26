package com.webshop.review.entity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/** Khóa chính ghép của {@link Purchase}. JPA @IdClass cần: constructor rỗng, Serializable, equals/hashCode theo mọi field. */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EqualsAndHashCode
public class PurchaseId implements Serializable {

    private UUID userId;
    private UUID productId;
    private UUID orderId;
}
