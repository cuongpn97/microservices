package com.webshop.cart.config;

import com.webshop.cart.client.CatalogApi;
import com.webshop.common.http.RestClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
public class HttpClientConfig {

    /**
     * Proxy HTTP Interface cho Product Service, chạy trên RestClient từ RestClientFactory
     * (giữ timeout tường minh + X-Correlation-Id như các client viết tay).
     */
    @Bean
    public CatalogApi catalogApi(RestClientFactory factory, @Value("${webshop.services.product-url}") String productUrl) {
        RestClientAdapter adapter = RestClientAdapter.create(factory.create(productUrl));
        return HttpServiceProxyFactory.builderFor(adapter).build().createClient(CatalogApi.class);
    }
}
