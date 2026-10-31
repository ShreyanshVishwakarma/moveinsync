package com.moveinsync.config.service;

import com.moveinsync.config.domain.ChangeSet;
import com.moveinsync.config.repository.ChangeSetRepository;
import com.moveinsync.config.repository.ConfigDefinitionRepository;
import com.moveinsync.config.repository.ConfigOverrideRepository;
import com.moveinsync.config.snapshot.ConfigSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rebuilds an immutable snapshot from the database. Called on startup and after each
 * commit; rebuilding is O(definitions + overrides) and is dwarfed by the read volume
 * it enables.
 */
@Service
public class SnapshotLoader {

    private final ConfigDefinitionRepository definitionRepository;
    private final ConfigOverrideRepository overrideRepository;
    private final ChangeSetRepository changeSetRepository;

    public SnapshotLoader(ConfigDefinitionRepository definitionRepository,
                          ConfigOverrideRepository overrideRepository,
                          ChangeSetRepository changeSetRepository) {
        this.definitionRepository = definitionRepository;
        this.overrideRepository = overrideRepository;
        this.changeSetRepository = changeSetRepository;
    }

    @Transactional(readOnly = true)
    public ConfigSnapshot load() {
        long version = changeSetRepository.findFirstByOrderByIdDesc()
                .map(ChangeSet::getVersion)
                .orElse(0L);
        return load(version);
    }

    @Transactional(readOnly = true)
    public ConfigSnapshot load(long version) {
        return ConfigSnapshot.of(
                version,
                definitionRepository.findAll(),
                overrideRepository.findAll()
        );
    }
}
