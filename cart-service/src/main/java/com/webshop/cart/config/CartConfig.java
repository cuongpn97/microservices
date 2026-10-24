package com.webshop.cart.config;

import com.webshop.cart.client.ProductApi;
import com.webshop.common.http.RestClientFactory;
import com.webshop.common.openapi.OpenApiConfig;
import com.webshop.common.security.WebshopJwt;
import com.webshop.messaging.MessagingProperties;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.consumer.RetryTopology;
import org.springframework.amqp.core.Declarables;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
public class CartConfig {

    /**
     * Proxy HTTP Interface cho Product Service, chạy trên RestClient từ RestClientFactory
     * (giữ timeout tường minh + X-Correlation-Id như các client viết tay).
     */
    @Bean
    public ProductApi productApi(RestClientFactory factory, @Value("${webshop.services.product-url}") String productUrl) {
        RestClientAdapter adapter = RestClientAdapter.create(factory.create(productUrl));
        return HttpServiceProxyFactory.builderFor(adapter).build().createClient(ProductApi.class);
    }

    @Bean
    public Declarables cartOrderEventsQueue(MessagingProperties properties) {
        return RetryTopology.consumerQueue(properties, RoutingKeys.Q_CART_ORDER_EVENTS, RoutingKeys.ORDER_CONFIRMED);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET, OpenApiConfig.DOC_PATHS).permitAll()
                        .requestMatchers("/admin/**", "/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/cart/**").hasRole("USER")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(WebshopJwt.authenticationConverter())));
        return http.build();
    }
}
