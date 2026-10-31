package com.moveinsync.config.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Hashed API key. The plaintext key is shown once at creation and never stored, so a
 * database leak does not hand out credentials.
 */
@Entity
@Table(
        name = "api_keys",
        uniqueConstraints = @UniqueConstraint(name = "uk_api_key_hash", columnNames = "key_hash")
)
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    @Column(name = "label", nullable = false, length = 128)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private Role role;

    @Column(name = "client_id", length = 128)
    private String clientId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ApiKey() {
        // JPA
    }

    public ApiKey(String keyHash, String label, Role role, String clientId) {
        this.keyHash = keyHash;
        this.label = label;
        this.role = role;
        this.clientId = clientId;
    }

    public Long getId() {
        return id;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getLabel() {
        return label;
    }

    public Role getRole() {
        return role;
    }

    public String getClientId() {
        return clientId;
    }

    public boolean isActive() {
        return active;
    }

    public void revoke() {
        this.active = false;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Actor toActor() {
        return new Actor(role, clientId);
    }
}
