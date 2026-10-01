package com.eve.diagnostics.entity;

import com.eve.diagnostics.enums.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A registered account.
 *
 * <p>There is deliberately no {@code bookings} collection here. Every booking
 * query starts from the user id, so a collection would only add lazy-loading
 * traps without saving a single query.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    /** BCrypt hash. The plain password never leaves the request DTO. */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // @PrePersist runs while save() is called, so createdAt is already readable
    // straight after save(). @CreationTimestamp would only fill it in at flush
    // time, which is too late to put it in a response body.
    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}