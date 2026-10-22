package com.webshop.payment.config;

import com.webshop.common.openapi.OpenApiConfig;
import com.webshop.common.security.WebshopJwt;
import com.webshop.messaging.MessagingProperties;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.consumer.RetryTopology;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class PaymentConfig {

    @Bean
    public Declarables userRegisteredQueue(MessagingProperties properties) {
        return RetryTopology.consumerQueue(properties, RoutingKeys.Q_PAYMENT_USER_REGISTERED,
                RoutingKeys.USER_REGISTERED);
    }

    @Bean
    public Declarables paymentCommandQueue(MessagingProperties properties) {
        return RetryTopology.consumerQueue(properties, RoutingKeys.Q_PAYMENT_COMMANDS,
                RoutingKeys.PAYMENT_COMMANDS_PATTERN);
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
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(WebshopJwt.authenticationConverter())));
        return http.build();
    }
}
