package com.webshop.cart.config;

import com.webshop.common.openapi.OpenApiConfig;
import com.webshop.common.security.WebshopJwt;
import com.webshop.messaging.MessagingProperties;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.consumer.RetryTopology;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class CartConfig {

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
