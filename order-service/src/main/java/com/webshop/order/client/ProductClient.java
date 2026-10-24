package com.webshop.order.client;

import com.webshop.common.http.ResilientCaller;
import com.webshop.common.http.RestClientFactory;
import com.webshop.order.client.dto.ProductInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Lấy dữ liệu sản phẩm CÓ THẨM QUYỀN (giá, tên, trạng thái) tại thời điểm checkout. */
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

    public List<ProductInfo> findAll(Collection<UUID> ids) {
        return resilience.idempotent(DEPENDENCY, () -> client.get()
                .uri(b -> b.path("/api/products/batch").queryParam("ids", ids.toArray()).build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<ProductInfo>>() {
                }));
    }
}
