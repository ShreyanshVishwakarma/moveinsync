package com.moveinsync.config.service;

import com.moveinsync.config.domain.ChangeAction;
import com.moveinsync.config.domain.ConfigLevel;
import com.moveinsync.config.dto.ChangeEntryResponse;
import com.moveinsync.config.dto.ChangeSetResponse;
import com.moveinsync.config.dto.CommitChangeSetRequest;
import com.moveinsync.config.dto.OverrideChange;
import com.moveinsync.config.exception.ApiException;
import com.moveinsync.config.security.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Staged rollout promotion.
 *
 * <p>A canary is just a normal change set whose overrides sit at OFFICE level for a few
 * chosen offices. Promotion widens those values to CLIENT level for the owning tenant, so
 * every office inherits them, and - by default - drops the now-redundant office overrides.
 * The promotion is published as a new version, so it reaches clients through the same
 * delta path as any other change.
 */
@Service
public class PromotionService {

    private static final Logger log = LoggerFactory.getLogger(PromotionService.class);

    private final ConfigQueryService queryService;
    private final ConfigChangeService changeService;

    public PromotionService(ConfigQueryService queryService, ConfigChangeService changeService) {
        this.queryService = queryService;
        this.changeService = changeService;
    }

    public ChangeSetResponse promote(long stagedVersion, boolean removeSourceOverrides, String author, Actor actor) {
        if (stagedVersion <= 0) {
            throw ApiException.badRequest("INVALID_VERSION", "stagedVersion must be a published version");
        }
        ChangeSetResponse staged = queryService.getChangeSet(stagedVersion);
        List<ChangeEntryResponse> entries = staged.entries();

        if (entries.isEmpty()) {
            throw ApiException.badRequest("NOT_PROMOTABLE", "That version contains no overrides to promote");
        }

        // One CLIENT-level override per (key, tenant) already covered by the canary.
        Map<String, OverrideChange> promoted = new LinkedHashMap<>();
        List<OverrideChange> changes = new ArrayList<>();

        for (ChangeEntryResponse entry : entries) {
            if (entry.action() != ChangeAction.UPSERT || entry.level() == ConfigLevel.CLIENT) {
                continue;
            }
            String tenant = entry.clientId();
            if (tenant == null) {
                throw ApiException.badRequest("NOT_PROMOTABLE",
                        "Staged entries must carry a clientId before they can be promoted");
            }
            String promotionKey = tenant + "\u0001" + entry.key();
            promoted.putIfAbsent(promotionKey, new OverrideChange(
                    entry.key(), ConfigLevel.CLIENT, tenant, entry.newValue(), tenant));
        }

        if (promoted.isEmpty()) {
            throw ApiException.badRequest("NOT_PROMOTABLE",
                    "That version only contains client-level or deleted overrides");
        }

        changes.addAll(promoted.values());

        if (removeSourceOverrides) {
            for (ChangeEntryResponse entry : entries) {
                if (entry.action() == ChangeAction.UPSERT && entry.level() != ConfigLevel.CLIENT) {
                    changes.add(new OverrideChange(
                            entry.key(), entry.level(), entry.entityId(), null, entry.clientId()));
                }
            }
        }

        CommitChangeSetRequest request = new CommitChangeSetRequest(
                author,
                "Promote canary v" + stagedVersion + " to every office",
                changes
        );
        ChangeSetResponse published = changeService.commitPromotion(request, actor, stagedVersion);
        log.info("Promoted staged version={} into version={} entries={}",
                stagedVersion, published.version(), changes.size());
        return published;
    }
}
