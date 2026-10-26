package com.webshop.order.client;

import com.webshop.common.http.ResilientCaller;
import com.webshop.common.http.RestClientFactory;
import com.webshop.order.client.dto.SkuInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Lấy dữ liệu SKU CÓ THẨM QUYỀN (giá, tên, trạng thái) tại thời điểm checkout. */
@Component
public class ProductClient {

    private static final String DEPENDENCY = "product-service";

    private final RestClient client;
    private final ResilientCaller resilience;

    public ProductClient(RestClientFactory factory, ResilientCaller resilience,
                         @Value("${webshop.services.product-url}") String productUrl) {
        this.client = factory.create(productUrl);
        this.resilience = resilience;
    }

    public List<SkuInfo> findSkus(Collection<UUID> skuIds) {
        return resilience.idempotent(DEPENDENCY, () -> client.get()
                .uri(b -> b.path("/api/products/skus/batch").queryParam("ids", skuIds.toArray()).build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<SkuInfo>>() {
                }));
    }
}
