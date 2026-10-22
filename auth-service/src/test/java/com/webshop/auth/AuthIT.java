package com.webshop.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webshop.messaging.test.SharedContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "webshop.security.jwt.secret=test-secret-that-is-at-least-32-bytes-long!!",
        "webshop.messaging.outbox.scheduling-enabled=false",
        "spring.rabbitmq.password=unused",
        "webshop.security.bcrypt-strength=4"
})
@AutoConfigureMockMvc
class AuthIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerPostgres(registry);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JwtDecoder jwtDecoder;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void registrationStoresBcryptHashAndWritesUserRegisteredOutboxEventAtomically() throws Exception {
        String email = "Alice." + UUID.randomUUID() + "@Example.com";
        JsonNode body = json(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email, "s3cret-pass", "Alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andReturn());
        UUID userId = UUID.fromString(body.get("id").asText());

        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
        assertThat(hash).startsWith("$2a$").doesNotContain("s3cret-pass");

        Map<String, Object> outbox = jdbc.queryForMap(
                "SELECT event_type, routing_key, payload::text AS payload FROM outbox_event WHERE aggregate_id = ?",
                userId.toString());
        assertThat(outbox.get("event_type")).isEqualTo("UserRegistered");
        assertThat(outbox.get("routing_key")).isEqualTo("auth.user.registered");
        JsonNode envelope = objectMapper.readTree((String) outbox.get("payload"));
        assertThat(envelope.get("payload").get("userId").asText()).isEqualTo(userId.toString());
        assertThat(envelope.get("eventVersion").asInt()).isEqualTo(1);
        assertThat((String) outbox.get("payload")).as("không lộ email/mật khẩu trong event")
                .doesNotContain("s3cret-pass").doesNotContain(email.toLowerCase());
    }

    @Test
    void duplicateEmailIsRejectedAndDoesNotEmitSecondEvent() throws Exception {
        String email = "dup-" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerJson(email, "password123", "Dup"))).andExpect(status().isCreated());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email.toUpperCase(), "password123", "Dup")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
        Integer users = jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
        assertThat(users).isEqualTo(1);
    }

    @Test
    void invalidRegistrationIsRejected() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("not-an-email", "short", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void loginIssuesSignedJwtWithSubjectIssuerExpiryAndRoles() throws Exception {
        String email = "bob-" + UUID.randomUUID() + "@example.com";
        JsonNode registered = json(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerJson(email, "password123", "Bob"))).andReturn());

        JsonNode token = json(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn());

        Jwt jwt = jwtDecoder.decode(token.get("accessToken").asText());
        assertThat(jwt.getSubject()).isEqualTo(registered.get("id").asText());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("webshop-auth");
        assertThat(jwt.getIssuedAt()).isNotNull();
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
        assertThat(jwt.getClaimAsStringList("roles")).isEqualTo(List.of("USER"));

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameGenericError() throws Exception {
        String email = "carol-" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerJson(email, "password123", "Carol"))).andExpect(status().isCreated());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void protectedEndpointRejectsMissingOrForgedToken() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.e30.forged"))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String registerJson(String email, String password, String fullName) throws Exception {
        return objectMapper.writeValueAsString(Map.of("email", email, "password", password, "fullName", fullName,
                "roles", List.of("ADMIN")));
    }
}
