package com.moveinsync.config.service;

import com.moveinsync.config.domain.ChangeAction;
import com.moveinsync.config.domain.OverrideScope;
import com.moveinsync.config.dto.ChangeEntryResponse;
import com.moveinsync.config.dto.ChangeSetResponse;
import com.moveinsync.config.dto.CommitChangeSetRequest;
import com.moveinsync.config.dto.OverrideChange;
import com.moveinsync.config.dto.RollbackRequest;
import com.moveinsync.config.dto.RollbackResponse;
import com.moveinsync.config.exception.ApiException;
import com.moveinsync.config.metrics.ConfigMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Instant rollback implemented as a new forward change set.
 *
 * <p>Rewriting history in place would break every client that already applied newer
 * versions, so a rollback computes the delta back to the target state and publishes it
 * with the next version number. Clients converge through the normal delta path.
 */
@Service
public class RollbackService {

    private static final Logger log = LoggerFactory.getLogger(RollbackService.class);

    private final ConfigQueryService queryService;
    private final ConfigChangeService changeService;
    private final CommitGate commitGate;
    private final ConfigMetrics metrics;

    public RollbackService(ConfigQueryService queryService,
                           ConfigChangeService changeService,
                           CommitGate commitGate,
                           ConfigMetrics metrics) {
        this.queryService = queryService;
        this.changeService = changeService;
        this.commitGate = commitGate;
        this.metrics = metrics;
    }

    public RollbackResponse rollback(RollbackRequest request) {
        return commitGate.withLock(() -> {
            long target = request.targetVersion();
            long currentVersion = queryService.currentVersion();
            if (target < 0 || target > currentVersion) {
                throw ApiException.notFound("Version", String.valueOf(target));
            }

            Map<OverrideScope, String> targetState = queryService.stateAt(target);
            Map<OverrideScope, String> currentState = queryService.currentState();

            Set<OverrideScope> scopes = new LinkedHashSet<>();
            scopes.addAll(currentState.keySet());
            scopes.addAll(targetState.keySet());

            List<ChangeEntryResponse> diff = new ArrayList<>();
            List<OverrideChange> changes = new ArrayList<>();

            for (OverrideScope scope : scopes) {
                String currentValue = currentState.get(scope);
                String targetValue = targetState.get(scope);
                if (java.util.Objects.equals(currentValue, targetValue)) {
                    continue;
                }
                diff.add(new ChangeEntryResponse(
                        scope.key(),
                        scope.level(),
                        scope.entityId(),
                        currentValue,
                        targetValue,
                        targetValue == null ? ChangeAction.DELETE : ChangeAction.UPSERT
                ));
                changes.add(new OverrideChange(scope.key(), scope.level(), scope.entityId(), targetValue));
            }

            if (diff.isEmpty()) {
                return new RollbackResponse(target, null, request.dryRun(), List.of());
            }
            if (request.dryRun()) {
                return new RollbackResponse(target, null, true, diff);
            }

            CommitChangeSetRequest commitRequest = new CommitChangeSetRequest(
                    request.author(),
                    "Rollback to v" + target + ": " + request.reason(),
                    changes
            );
            ChangeSetResponse published = changeService.commit(commitRequest, target);
            metrics.recordRollback();
            log.info("Rollback published target={} newVersion={} changes={}", target, published.version(), changes.size());

            return new RollbackResponse(target, published.version(), false, diff);
        });
    }

    /** Diff preview without publishing; used by the API for confirmation screens. */
    public List<ChangeEntryResponse> preview(long targetVersion) {
        Map<OverrideScope, String> targetState = queryService.stateAt(targetVersion);
        Map<OverrideScope, String> currentState = queryService.currentState();
        Map<OverrideScope, String> scopes = new LinkedHashMap<>(currentState);
        targetState.forEach(scopes::putIfAbsent);

        List<ChangeEntryResponse> diff = new ArrayList<>();
        for (OverrideScope scope : scopes.keySet()) {
            String currentValue = currentState.get(scope);
            String targetValue = targetState.get(scope);
            if (!java.util.Objects.equals(currentValue, targetValue)) {
                diff.add(new ChangeEntryResponse(
                        scope.key(),
                        scope.level(),
                        scope.entityId(),
                        currentValue,
                        targetValue,
                        targetValue == null ? ChangeAction.DELETE : ChangeAction.UPSERT
                ));
            }
        }
        return diff;
    }
}
