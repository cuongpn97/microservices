package com.webshop.product.dto.response;

import java.util.UUID;

/** url là URL công khai (object storage/CDN) ghép từ object key lúc trả response. */
public record ImageResponse(
        UUID id,
        String url,
        String altText,
        int position,
        String contentType,
        long sizeBytes) {
}
