package com.moveinsync.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * A value set at CLIENT, OFFICE or SHIFT level. The unique constraint guarantees
 * at most one override per (key, level, entity).
 */
@Entity
@Table(
        name = "config_overrides",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_override_scope",
                columnNames = {"client_id", "config_key", "level", "entity_id"}
        ),
        indexes = {
                @Index(name = "idx_override_level_entity", columnList = "level, entity_id"),
                @Index(name = "idx_override_key", columnList = "config_key"),
                @Index(name = "idx_override_client", columnList = "client_id")
        }
)
public class ConfigOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, length = 128)
    private String configKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 16)
    private ConfigLevel level;

    @Column(name = "entity_id", nullable = false, length = 128)
    private String entityId;

    /**
     * Tenant that owns this override. CLIENT-level rows use the client id as the entity
     * id as well; OFFICE/SHIFT rows need the owning client because entity ids are only
     * unique inside a tenant.
     */
    @Column(name = "client_id", length = 128)
    private String clientId;

    @Column(name = "config_value", nullable = false, length = 512)
    private String value;

    @Column(name = "updated_by", length = 128)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ConfigOverride() {
        // JPA
    }

    public ConfigOverride(String configKey, ConfigLevel level, String entityId, String value) {
        this(configKey, level, entityId, value, null);
    }

    public ConfigOverride(String configKey, ConfigLevel level, String entityId, String value, String clientId) {
        this.configKey = configKey;
        this.level = level;
        this.entityId = entityId;
        this.value = value;
        this.clientId = clientId;
    }

    public Long getId() {
        return id;
    }

    public String getConfigKey() {
        return configKey;
    }

    public ConfigLevel getLevel() {
        return level;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
        this.updatedAt = Instant.now();
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void markUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
