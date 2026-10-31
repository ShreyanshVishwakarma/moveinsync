package com.moveinsync.config.service;

import com.moveinsync.config.domain.ChangeSet;
import com.moveinsync.config.domain.ChangeSetEntry;
import com.moveinsync.config.dto.ChangeEntryResponse;
import com.moveinsync.config.dto.ChangeSetResponse;
import com.moveinsync.config.dto.VersionSummary;

import java.util.List;

/**
 * Mapping helpers kept out of the services so the domain stays free of transport types.
 */
public final class ChangeSetResponses {

    private ChangeSetResponses() {
    }

    public static ChangeEntryResponse toResponse(ChangeSetEntry entry) {
        return new ChangeEntryResponse(
                entry.getConfigKey(),
                entry.getLevel(),
                entry.getEntityId(),
                entry.getClientId(),
                entry.getOldValue(),
                entry.getNewValue(),
                entry.getAction()
        );
    }

    public static ChangeSetResponse toResponse(ChangeSet changeSet, List<ChangeSetEntry> entries) {
        return new ChangeSetResponse(
                changeSet.getVersion(),
                changeSet.getAuthor(),
                changeSet.getReason(),
                changeSet.getCreatedAt(),
                changeSet.getRevertsToVersion(),
                changeSet.getPromotedFromVersion(),
                entries.stream().map(ChangeSetResponses::toResponse).toList()
        );
    }

    public static VersionSummary toSummary(ChangeSet changeSet) {
        return new VersionSummary(
                changeSet.getVersion(),
                changeSet.getAuthor(),
                changeSet.getReason(),
                changeSet.getCreatedAt(),
                changeSet.getRevertsToVersion()
        );
    }
}
