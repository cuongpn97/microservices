package com.webshop.auth.web;

import com.webshop.auth.token.TokenService;
import com.webshop.auth.user.LoginService;
import com.webshop.auth.user.RegistrationService;
import com.webshop.auth.user.User;
import com.webshop.auth.user.UserRepository;
import com.webshop.common.security.WebshopJwt;
import com.webshop.common.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final RegistrationService registrationService;
    private final LoginService loginService;
    private final TokenService tokenService;
    private final UserRepository users;

    public AuthController(RegistrationService registrationService, LoginService loginService,
                          TokenService tokenService, UserRepository users) {
        this.registrationService = registrationService;
        this.loginService = loginService;
        this.tokenService = tokenService;
        this.users = users;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        // Role luôn do server quyết định (USER) — client không thể tự đăng ký làm ADMIN.
        User user = registrationService.register(request.email(), request.password(), request.fullName(),
                List.of("USER"));
        return UserResponse.from(user);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        User user = loginService.authenticate(request.email(), request.password());
        TokenService.IssuedToken token = tokenService.issue(user);
        return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds(), token.expiresAt(),
                user.getId(), user.getRoles());
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = WebshopJwt.userId(jwt);
        return users.findById(userId).map(UserResponse::from)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "User not found"));
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 8, max = 72, message = "password must be 8-72 characters") String password,
            @NotBlank @Size(max = 200) String fullName) {

        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + ", password=***]";
        }
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank @Size(max = 72) String password) {

        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=***]";
        }
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn, Instant expiresAt,
                                UUID userId, List<String> roles) {
    }

    public record UserResponse(UUID id, String email, String fullName, List<String> roles, Instant createdAt) {
        static UserResponse from(User user) {
            return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRoles(),
                    user.getCreatedAt());
        }
    }
}
