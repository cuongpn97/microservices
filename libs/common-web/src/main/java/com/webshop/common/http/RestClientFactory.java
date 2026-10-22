package com.webshop.common.http;

import com.webshop.common.web.CorrelationIds;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.web.client.RestClient;

/**
 * Tạo RestClient với connect/read timeout tường minh và tự động truyền X-Correlation-Id.
 * Không có timeout = một dependency chậm có thể giữ hết thread của service gọi (cascading failure).
 */
public class RestClientFactory {

    private final RestClient.Builder builder;
    private final HttpRetryProperties properties;

    public RestClientFactory(RestClient.Builder builder, HttpRetryProperties properties) {
        this.builder = builder;
        this.properties = properties;
    }

    public RestClient create(String baseUrl) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings))
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = CorrelationIds.current();
                    if (correlationId != null) {
                        request.getHeaders().set(CorrelationIds.HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}
