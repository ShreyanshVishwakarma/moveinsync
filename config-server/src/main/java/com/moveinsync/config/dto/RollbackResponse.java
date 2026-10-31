package com.moveinsync.config.dto;

import java.util.List;

/**
 * Result of a rollback: the diff that was (or would be) applied. A rollback is never
 * an in-place history edit - it publishes a brand new change set so versions keep
 * moving forward and every connected client learns about it through the normal path.
 */
public record RollbackResponse(
        long targetVersion,
        Long publishedVersion,
        boolean dryRun,
        List<ChangeEntryResponse> diff
) {
}
