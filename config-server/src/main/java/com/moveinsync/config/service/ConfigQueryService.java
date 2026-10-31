package com.moveinsync.config.service;

import com.moveinsync.config.domain.ChangeSet;
import com.moveinsync.config.domain.ChangeSetEntry;
import com.moveinsync.config.domain.ConfigLevel;
import com.moveinsync.config.domain.OverrideScope;
import com.moveinsync.config.domain.ResolutionStep;
import com.moveinsync.config.domain.ResolvedValue;
import com.moveinsync.config.domain.TenantContext;
import com.moveinsync.config.dto.ChangeSetResponse;
import com.moveinsync.config.dto.ExplainResponse;
import com.moveinsync.config.dto.ResolveResponse;
import com.moveinsync.config.dto.SnapshotResponse;
import com.moveinsync.config.dto.SyncResponse;
import com.moveinsync.config.dto.VersionSummary;
import com.moveinsync.config.exception.ApiException;
import com.moveinsync.config.metrics.ConfigMetrics;
import com.moveinsync.config.repository.ChangeSetEntryRepository;
import com.moveinsync.config.repository.ChangeSetRepository;
import com.moveinsync.config.security.Actor;
import com.moveinsync.config.snapshot.ConfigSnapshot;
import com.moveinsync.config.snapshot.SnapshotStore;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read path for the control plane: resolution, explain, snapshots, deltas and history.
 * All hot reads go straight to the in-memory snapshot; only history queries touch the DB.
 *
 * <p>Snapshot and delta responses are tenant-filtered before they leave the process.
 */
@Service
public class ConfigQueryService {

    private final SnapshotStore snapshotStore;
    private final ChangeSetRepository changeSetRepository;
    private final ChangeSetEntryRepository entryRepository;
    private final ConfigMetrics metrics;

    public ConfigQueryService(SnapshotStore snapshotStore,
                              ChangeSetRepository changeSetRepository,
                              ChangeSetEntryRepository entryRepository,
                              ConfigMetrics metrics) {
        this.snapshotStore = snapshotStore;
        this.changeSetRepository = changeSetRepository;
        this.entryRepository = entryRepository;
        this.metrics = metrics;
    }

    public long currentVersion() {
        return snapshotStore.version();
    }

    public ResolveResponse resolve(String key, TenantContext context) {
        ResolvedValue value = resolveWithMeta(key, context);
        return new ResolveResponse(key, value.value(), value.sourceLevel(), value.sourceEntityId(), currentVersion());
    }

    public ResolvedValue resolveWithMeta(String key, TenantContext context) {
        return snapshotStore.current().resolve(key, context);
    }

    public ExplainResponse explain(String key, TenantContext context) {
        ConfigSnapshot snapshot = snapshotStore.current();
        ResolvedValue resolved = snapshot.resolve(key, context);
        List<ExplainResponse.Step> trace = snapshot.explain(key, context).stream()
                .map(step -> new ExplainResponse.Step(step.level(), step.entityId(), step.value(), step.applied()))
                .toList();
        return new ExplainResponse(
                key,
                context.clientId(),
                context.officeId(),
                context.shiftId(),
                resolved.value(),
                resolved.sourceLevel(),
                snapshot.version(),
                trace
        );
    }

    /** Platform-admin snapshot: every tenant. */
    public SnapshotResponse snapshot() {
        return SnapshotResponse.from(snapshotStore.current());
    }

    /** Snapshot scoped to the caller's tenant. */
    public SnapshotResponse snapshot(Actor actor) {
        return SnapshotResponse.from(snapshotStore.current(), tenantFilter(actor));
    }

    public SyncResponse sync(long sinceVersion) {
        return sync(sinceVersion, null);
    }

    /**
     * Delta sync. A client that is already current gets an empty delta; a client whose
     * version is unknown or too old gets a full snapshot (bounded staleness, never a gap).
     */
    public SyncResponse sync(long sinceVersion, Actor actor) {
        metrics.recordResync();
        String tenant = tenantFilter(actor);
        ConfigSnapshot snapshot = snapshotStore.current();
        long current = snapshot.version();

        if (sinceVersion <= 0 || sinceVersion > current) {
            return SyncResponse.full(SnapshotResponse.from(snapshot, tenant), sinceVersion);
        }
        if (sinceVersion == current) {
            return SyncResponse.delta(current, sinceVersion, List.of());
        }

        List<ChangeSetEntry> entries = entryRepository
                .findByChangeSetIdGreaterThanOrderByChangeSetIdAscIdAsc(sinceVersion);

        Map<Long, List<ChangeSetEntry>> byVersion = entries.stream()
                .filter(entry -> tenant == null || entry.getClientId() == null || tenant.equals(entry.getClientId()))
                .collect(Collectors.groupingBy(ChangeSetEntry::getChangeSetId, TreeMap::new, Collectors.toList()));

        Map<Long, ChangeSet> changeSets = changeSetRepository.findAllById(byVersion.keySet()).stream()
                .collect(Collectors.toMap(ChangeSet::getId, Function.identity()));

        List<ChangeSetResponse> responses = byVersion.entrySet().stream()
                .map(entry -> ChangeSetResponses.toResponse(changeSets.get(entry.getKey()), entry.getValue()))
                .toList();

        return SyncResponse.delta(current, sinceVersion, responses);
    }

    public List<VersionSummary> listVersions(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return changeSetRepository.findAllByOrderByIdDesc(PageRequest.of(0, safeLimit)).stream()
                .map(ChangeSetResponses::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public ChangeSetResponse getChangeSet(long version) {
        ChangeSet changeSet = changeSetRepository.findById(version)
                .orElseThrow(() -> ApiException.notFound("Version", String.valueOf(version)));
        List<ChangeSetEntry> entries = entryRepository.findByChangeSetIdOrderByIdAsc(version);
        return ChangeSetResponses.toResponse(changeSet, entries);
    }

    /**
     * Reconstructs the override state as it stood immediately after {@code version}.
     * Used by rollback to compute a forward-moving change set.
     */
    @Transactional(readOnly = true)
    public Map<OverrideScope, String> stateAt(long version) {
        if (version < 0 || version > currentVersion()) {
            throw ApiException.notFound("Version", String.valueOf(version));
        }
        if (version == 0) {
            return Map.of();
        }
        List<ChangeSetEntry> history = entryRepository
                .findByChangeSetIdLessThanEqualOrderByChangeSetIdAscIdAsc(version);

        Map<OverrideScope, String> state = new LinkedHashMap<>();
        for (ChangeSetEntry entry : history) {
            OverrideScope scope = OverrideScope.of(
                    entry.getConfigKey(), entry.getLevel(), entry.getEntityId(), entry.getClientId());
            if (entry.getNewValue() == null) {
                state.remove(scope);
            } else {
                state.put(scope, entry.getNewValue());
            }
        }
        return state;
    }

    /** Current override state derived from the live snapshot (authoritative, no replay). */
    public Map<OverrideScope, String> currentState() {
        Map<OverrideScope, String> state = new HashMap<>();
        for (ConfigSnapshot.ScopedOverride entry : snapshotStore.current().scopedEntries()) {
            state.put(
                    OverrideScope.of(entry.key(), entry.level(), entry.entityId(), entry.clientId()),
                    entry.value());
        }
        return state;
    }

    public List<ResolutionStep> explainSteps(String key, TenantContext context) {
        return snapshotStore.current().explain(key, context);
    }

    /** Visible for tests that need the raw snapshot. */
    public ConfigSnapshot snapshotView() {
        return snapshotStore.current();
    }

    public List<ChangeSetEntry> historyUpTo(long version) {
        List<ChangeSetEntry> entries = new ArrayList<>();
        entries.addAll(entryRepository.findByChangeSetIdLessThanEqualOrderByChangeSetIdAscIdAsc(version));
        return entries;
    }

    private String tenantFilter(Actor actor) {
        if (actor == null || actor.isAdmin()) {
            return null;
        }
        return actor.clientId();
    }
}
