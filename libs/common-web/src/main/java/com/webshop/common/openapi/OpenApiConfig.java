package com.webshop.common.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Tài liệu OpenAPI (springdoc) cho mọi service Servlet.
 * <ul>
 *   <li>Swagger UI của từng service: {@code http://localhost:<port>/swagger-ui.html}</li>
 *   <li>Swagger UI tổng hợp ở gateway: {@code http://localhost:8080/swagger-ui.html}</li>
 * </ul>
 * Server là đường dẫn TƯƠNG ĐỐI "/": "Try it out" gửi request tới chính origin đang phục vụ trang Swagger
 * (không bị CORS chặn). Mở ở gateway :8080 → đi qua gateway (chỉ /api/**); mở ở port của service → gọi thẳng
 * service (thử được cả /admin/**). Nút "Authorize" nhận access token từ {@code POST /api/auth/login}.
 */
@AutoConfiguration
@ConditionalOnClass(OpenAPI.class)
public class OpenApiConfig {

    public static final String BEARER = "bearerAuth";

    /** Đường dẫn tài liệu cần mở public trong SecurityFilterChain của từng service. */
    public static final String[] DOC_PATHS = {"/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"};

    @Bean
    @ConditionalOnMissingBean
    public OpenAPI webshopOpenApi(@Value("${spring.application.name:service}") String name,
                                  @Value("${server.port:8080}") int port) {
        return new OpenAPI()
                .info(new Info()
                        .title(name + " API")
                        .version("1.0.0")
                        .description("""
                                Webshop microservices (dự án học tập). Lấy token: `POST /api/auth/login` → bấm
                                **Authorize** và dán `accessToken` (không cần chữ `Bearer`). Tiền tệ: số nguyên VND.
                                Endpoint `/admin/**` cần role ADMIN và chỉ thử được từ Swagger UI của chính service
                                (ví dụ http://localhost:8086/swagger-ui.html) vì gateway không route /admin.
                                """))
                .servers(List.of(new Server().url("/")
                        .description("Cùng origin với trang Swagger: gateway :8080 hoặc " + name + " :" + port)))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token từ POST /api/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
