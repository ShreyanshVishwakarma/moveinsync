package com.moveinsync.config.dto;

import com.moveinsync.config.domain.ChangeAction;
import com.moveinsync.config.domain.ConfigLevel;

public record ChangeEntryResponse(
        String key,
        ConfigLevel level,
        String entityId,
        String clientId,
        String oldValue,
        String newValue,
        ChangeAction action
) {
}
