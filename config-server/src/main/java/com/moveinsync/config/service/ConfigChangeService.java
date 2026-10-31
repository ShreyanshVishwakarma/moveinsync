package com.moveinsync.config.service;

import com.moveinsync.config.domain.ChangeAction;
import com.moveinsync.config.domain.ChangeSet;
import com.moveinsync.config.domain.ChangeSetEntry;
import com.moveinsync.config.domain.ConfigLevel;
import com.moveinsync.config.domain.ConfigOverride;
import com.moveinsync.config.domain.OverrideScope;
import com.moveinsync.config.dto.ChangeEntryResponse;
import com.moveinsync.config.dto.ChangeSetResponse;
import com.moveinsync.config.dto.CommitChangeSetRequest;
import com.moveinsync.config.dto.OverrideChange;
import com.moveinsync.config.dto.SnapshotResponse;
import com.moveinsync.config.exception.ValidationException;
import com.moveinsync.config.metrics.ConfigMetrics;
import com.moveinsync.config.repository.ChangeSetEntryRepository;
import com.moveinsync.config.repository.ChangeSetRepository;
import com.moveinsync.config.repository.ConfigOverrideRepository;
import com.moveinsync.config.snapshot.ConfigSnapshot;
import com.moveinsync.config.snapshot.SnapshotStore;
import com.moveinsync.config.service.validation.ConfigValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Write path: validate -> persist atomically -> swap snapshot -> broadcast.
 *
 * <p>The database transaction is the durability boundary; the snapshot swap is the
 * visibility boundary. Because the swap publishes one immutable snapshot, a reader
 * sees either all entries of a change set or none of them - never a mix.
 */
@Service
public class ConfigChangeService {

    private static final Logger log = LoggerFactory.getLogger(ConfigChangeService.class);

    private final ConfigOverrideRepository overrideRepository;
    private final ChangeSetRepository changeSetRepository;
    private final ChangeSetEntryRepository entryRepository;
    private final ConfigValidator validator;
    private final SnapshotLoader snapshotLoader;
    private final SnapshotStore snapshotStore;
    private final CommitGate commitGate;
    private final ConfigMetrics metrics;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactionTemplate;

    public ConfigChangeService(ConfigOverrideRepository overrideRepository,
                               ChangeSetRepository changeSetRepository,
                               ChangeSetEntryRepository entryRepository,
                               ConfigValidator validator,
                               SnapshotLoader snapshotLoader,
                               SnapshotStore snapshotStore,
                               CommitGate commitGate,
                               ConfigMetrics metrics,
                               ApplicationEventPublisher events,
                               TransactionTemplate transactionTemplate) {
        this.overrideRepository = overrideRepository;
        this.changeSetRepository = changeSetRepository;
        this.entryRepository = entryRepository;
        this.validator = validator;
        this.snapshotLoader = snapshotLoader;
        this.snapshotStore = snapshotStore;
        this.commitGate = commitGate;
        this.metrics = metrics;
        this.events = events;
        this.transactionTemplate = transactionTemplate;
    }

    public ChangeSetResponse commit(CommitChangeSetRequest request) {
        return commit(request, null);
    }

    /**
     * @param revertsToVersion non-null when this change set was produced by a rollback
     */
    ChangeSetResponse commit(CommitChangeSetRequest request, Long revertsToVersion) {
        return commitGate.withLock(() -> {
            ConfigSnapshot snapshot = snapshotStore.current();
            List<PreparedChange> prepared = prepare(request, snapshot);

            Committed committed = transactionTemplate.execute(status -> persist(request, prepared, revertsToVersion));
            if (committed == null) {
                throw new IllegalStateException("Change set transaction returned no result");
            }

            // Only after a successful commit do we make the state visible.
            ConfigSnapshot refreshed = snapshotLoader.load(committed.version());
            snapshotStore.publish(refreshed);

            List<ChangeEntryResponse> responses = committed.entries().stream()
                    .map(ChangeSetResponses::toResponse)
                    .toList();

            metrics.recordCommit();
            log.info("Published version={} author={} entries={}",
                    committed.version(), request.author(), responses.size());

            events.publishEvent(new ConfigPublishedEvent(
                    committed.version(),
                    SnapshotResponse.from(refreshed),
                    responses
            ));

            return new ChangeSetResponse(
                    committed.version(),
                    committed.author(),
                    committed.reason(),
                    committed.createdAt(),
                    committed.revertsToVersion(),
                    responses
            );
        });
    }

    private List<PreparedChange> prepare(CommitChangeSetRequest request, ConfigSnapshot snapshot) {
        List<PreparedChange> prepared = new ArrayList<>(request.changes().size());
        List<String> violations = new ArrayList<>();
        Set<OverrideScope> seen = new HashSet<>();

        for (OverrideChange change : request.changes()) {
            if (change.level() == ConfigLevel.GLOBAL) {
                violations.add(change.key() + ": GLOBAL is a default, not an override level");
                continue;
            }
            if (!snapshot.hasDefinition(change.key())) {
                violations.add("Unknown configuration key: " + change.key());
                continue;
            }
            OverrideScope scope = OverrideScope.of(change.key(), change.level(), change.entityId());
            if (!seen.add(scope)) {
                violations.add(change.key() + ": duplicate entry for the same level and entity");
                continue;
            }

            String newValue = change.isDelete() ? null : change.value().trim();
            if (newValue != null) {
                ConfigSnapshot.DefinitionView definition = snapshot.definition(change.key()).orElseThrow();
                try {
                    validator.validate(definition, newValue);
                } catch (ValidationException ex) {
                    violations.addAll(ex.getViolations());
                    continue;
                }
            }
            prepared.add(new PreparedChange(scope, newValue));
        }

        if (!violations.isEmpty()) {
            metrics.recordRejection();
            throw new ValidationException(violations);
        }
        return prepared;
    }

    private Committed persist(CommitChangeSetRequest request,
                              List<PreparedChange> prepared,
                              Long revertsToVersion) {

        ChangeSet changeSet = new ChangeSet(request.author(), request.reason());
        if (revertsToVersion != null) {
            changeSet.markRollbackOf(revertsToVersion);
        }
        changeSetRepository.saveAndFlush(changeSet);
        long version = changeSet.getVersion();

        List<ChangeSetEntry> entries = new ArrayList<>(prepared.size());

        for (PreparedChange change : prepared) {
            Optional<ConfigOverride> existing = overrideRepository
                    .findByConfigKeyAndLevelAndEntityId(
                            change.scope().key(), change.scope().level(), change.scope().entityId());

            String oldValue = existing.map(ConfigOverride::getValue).orElse(null);
            ChangeAction action;

            if (change.newValue() == null) {
                if (existing.isEmpty()) {
                    // Deleting something that does not exist is a no-op, not an error.
                    continue;
                }
                overrideRepository.delete(existing.get());
                action = ChangeAction.DELETE;
            } else {
                ConfigOverride override = existing.orElseGet(() -> new ConfigOverride(
                        change.scope().key(), change.scope().level(), change.scope().entityId(),
                        change.newValue()));
                override.setValue(change.newValue());
                override.markUpdatedBy(request.author());
                overrideRepository.save(override);
                action = ChangeAction.UPSERT;
            }

            if (!Objects.equals(oldValue, change.newValue())) {
                entries.add(new ChangeSetEntry(
                        version,
                        change.scope().key(),
                        change.scope().level(),
                        change.scope().entityId(),
                        oldValue,
                        change.newValue(),
                        action
                ));
            }
        }

        entryRepository.saveAll(entries);
        return new Committed(
                version,
                changeSet.getAuthor(),
                changeSet.getReason(),
                changeSet.getCreatedAt(),
                changeSet.getRevertsToVersion(),
                entries
        );
    }

    private record PreparedChange(OverrideScope scope, String newValue) {
    }

    private record Committed(
            long version,
            String author,
            String reason,
            Instant createdAt,
            Long revertsToVersion,
            List<ChangeSetEntry> entries
    ) {
    }
}
