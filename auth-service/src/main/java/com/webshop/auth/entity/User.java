package com.webshop.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    /** Chỉ lưu hash BCrypt — không bao giờ lưu hoặc log mật khẩu gốc. */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    /** Danh sách role phân tách bằng dấu phẩy, ví dụ "USER" hoặc "USER,ADMIN". */
    @Column(nullable = false)
    @Getter(AccessLevel.NONE) // tự viết getRoles() bên dưới để trả List thay vì chuỗi thô
    private String roles;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public User(UUID id, String email, String passwordHash, String fullName, List<String> roles, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.roles = String.join(",", roles);
        this.createdAt = createdAt;
    }

    public List<String> getRoles() {
        return Arrays.stream(roles.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
