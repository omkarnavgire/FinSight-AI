package com.finsight.auth.domain;

import jakarta.persistence.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * JPA entity representing a registered user.
 *
 * <p>Implements {@link UserDetails} so Spring Security can load it directly from
 * {@code UserRepository.findByEmail()} without a separate adapter.
 *
 * <p>No Jackson annotations — API responses use {@code TokenResponse} and other DTOs,
 * never this entity directly.
 */
@Entity
@Table(name = "users")
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    /** BCrypt-hashed password (strength 12). Never logged or serialised. */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role = Role.USER;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected User() {}

    /** Factory used by {@code AuthService.register()}. */
    public static User create(String email, String passwordHash, String fullName) {
        var user = new User();
        user.email = email;
        user.passwordHash = passwordHash;
        user.fullName = fullName;
        user.role = Role.USER;
        var now = LocalDateTime.now();
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ---- UserDetails ----

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    /** Returns the BCrypt hash — Spring Security compares this against the raw input. */
    @Override
    public String getPassword() {
        return passwordHash;
    }

    /** Username is the email address. */
    @Override
    public String getUsername() {
        return email;
    }

    // ---- Getters ----

    /** @return the surrogate PK; {@code null} before first save. */
    public Long getId() { return id; }

    public String getEmail() { return email; }

    public String getFullName() { return fullName; }

    public Role getRole() { return role; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
