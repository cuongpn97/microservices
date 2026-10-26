package com.webshop.cart.client;

import com.webshop.cart.client.dto.SkuInfo;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * HTTP Interface (Spring Framework 6.1+) mô tả API tra cứu SKU của Product Service — Spring sinh proxy chạy trên RestClient.
 * Chỉ khai báo endpoint; timeout/correlation-id đến từ RestClient bên dưới, retry/circuit breaker do ProductClient bọc ngoài.
 * Lỗi 4xx/5xx được ném ra như RestClient thường (vd. HttpClientErrorException.NotFound).
 */
@HttpExchange("/api/products/skus")
public interface CatalogApi {

    @GetExchange("/{id}")
    SkuInfo findSku(@PathVariable UUID id);

    // Collection được render thành ?ids=a&ids=b — khớp @RequestParam List<UUID> bên product-service
    @GetExchange("/batch")
    List<SkuInfo> findSkus(@RequestParam Collection<UUID> ids);
}
