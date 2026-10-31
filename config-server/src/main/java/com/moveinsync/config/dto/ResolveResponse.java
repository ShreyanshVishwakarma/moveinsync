package com.moveinsync.config.dto;

import com.moveinsync.config.domain.ConfigLevel;

public record ResolveResponse(
        String key,
        String value,
        ConfigLevel sourceLevel,
        String sourceEntityId,
        long version
) {
}
