package com.webshop.common;

import com.webshop.common.http.HttpRetryProperties;
import com.webshop.common.http.ResilientCaller;
import com.webshop.common.http.RestClientFactory;
import com.webshop.common.security.JwtProperties;
import com.webshop.common.security.WebshopJwt;
import com.webshop.common.web.CorrelationIdFilter;
import com.webshop.common.web.GlobalExceptionHandler;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.client.RestClient;

/**
 * Auto-configuration cho thư viện common-web. Mỗi service vẫn tự khai báo SecurityFilterChain của mình
 * (quy tắc phân quyền là quyết định của từng service), chỉ dùng chung decoder/converter.
 */
@AutoConfiguration
@EnableConfigurationProperties({JwtProperties.class, HttpRetryProperties.class})
@Import(GlobalExceptionHandler.class)
public class CommonWebAutoConfiguration {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "webshop.security.jwt", name = "secret")
    public JwtDecoder jwtDecoder(JwtProperties properties) {
        return WebshopJwt.decoder(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public RestClientFactory restClientFactory(RestClient.Builder builder, HttpRetryProperties properties) {
        return new RestClientFactory(builder, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ResilientCaller resilientCaller(CircuitBreakerRegistry circuitBreakers, RetryRegistry retries,
                                           HttpRetryProperties properties) {
        return new ResilientCaller(circuitBreakers, retries, properties);
    }
}
