package com.moveinsync.config.repository;

import com.moveinsync.config.domain.ChangeSetEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChangeSetEntryRepository extends JpaRepository<ChangeSetEntry, Long> {

    List<ChangeSetEntry> findByChangeSetIdOrderByIdAsc(Long changeSetId);

    /** Entries strictly newer than a version, used to heal clients that missed pushes. */
    List<ChangeSetEntry> findByChangeSetIdGreaterThanOrderByChangeSetIdAscIdAsc(Long version);

    /** Full mutation history up to a version, used to reconstruct state for rollback. */
    List<ChangeSetEntry> findByChangeSetIdLessThanEqualOrderByChangeSetIdAscIdAsc(Long version);

    long countByChangeSetIdGreaterThan(Long version);
}
