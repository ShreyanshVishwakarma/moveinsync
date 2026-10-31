package com.moveinsync.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An immutable record of one atomic publication. The generated id doubles as the
 * configuration version, which is why versions can only ever move forward.
 */
@Entity
@Table(name = "change_sets")
public class ChangeSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "author", nullable = false, length = 128)
    private String author;

    @Column(name = "reason", nullable = false, length = 512)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** Set when this change set was produced by a rollback. */
    @Column(name = "reverts_to_version")
    private Long revertsToVersion;

    /** Set when this change set widened a staged canary to the whole client. */
    @Column(name = "promoted_from_version")
    private Long promotedFromVersion;

    protected ChangeSet() {
        // JPA
    }

    public ChangeSet(String author, String reason) {
        this.author = author;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public long getVersion() {
        return id;
    }

    public String getAuthor() {
        return author;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getRevertsToVersion() {
        return revertsToVersion;
    }

    public Long getPromotedFromVersion() {
        return promotedFromVersion;
    }

    public void markRollbackOf(long targetVersion) {
        this.revertsToVersion = targetVersion;
    }

    public void markPromotionOf(long stagedVersion) {
        this.promotedFromVersion = stagedVersion;
    }
}
