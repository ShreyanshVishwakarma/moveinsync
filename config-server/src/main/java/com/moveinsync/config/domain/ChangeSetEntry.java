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

/**
 * Audit row for one override mutation. Storing old and new values makes
 * "what changed between version N and N+1" (and rollback diffs) a pure query.
 */
@Entity
@Table(
        name = "change_set_entries",
        indexes = {
                @Index(name = "idx_entry_change_set", columnList = "change_set_id"),
                @Index(name = "idx_entry_key", columnList = "config_key")
        }
)
public class ChangeSetEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "change_set_id", nullable = false)
    private Long changeSetId;

    @Column(name = "config_key", nullable = false, length = 128)
    private String configKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 16)
    private ConfigLevel level;

    @Column(name = "entity_id", nullable = false, length = 128)
    private String entityId;

    /** Tenant that owned the override at the time of the change. */
    @Column(name = "client_id", length = 128)
    private String clientId;

    @Column(name = "old_value", length = 512)
    private String oldValue;

    @Column(name = "new_value", length = 512)
    private String newValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16)
    private ChangeAction action;

    protected ChangeSetEntry() {
        // JPA
    }

    public ChangeSetEntry(Long changeSetId,
                          String configKey,
                          ConfigLevel level,
                          String entityId,
                          String clientId,
                          String oldValue,
                          String newValue,
                          ChangeAction action) {
        this.changeSetId = changeSetId;
        this.configKey = configKey;
        this.level = level;
        this.entityId = entityId;
        this.clientId = clientId;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.action = action;
    }

    public Long getId() {
        return id;
    }

    public Long getChangeSetId() {
        return changeSetId;
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

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public ChangeAction getAction() {
        return action;
    }
}
