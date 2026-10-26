package com.webshop.review.config;

import com.webshop.common.openapi.OpenApiConfig;
import com.webshop.common.security.WebshopJwt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Rule nào khớp TRƯỚC thắng → các rule cụ thể (moderation, mine) phải đứng trước rule GET công khai.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET, OpenApiConfig.DOC_PATHS).permitAll()
                        .requestMatchers("/admin/**", "/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/reviews/moderation/**").hasRole("ADMIN")
                        .requestMatchers("/api/reviews/products/*/mine").hasRole("USER")
                        .requestMatchers(HttpMethod.GET, "/api/reviews/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(WebshopJwt.authenticationConverter())));
        return http.build();
    }
}
