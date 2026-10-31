package com.moveinsync.config.dto;

import com.moveinsync.config.domain.ConfigLevel;

import java.util.List;

public record ExplainResponse(
        String key,
        String clientId,
        String officeId,
        String shiftId,
        String effectiveValue,
        ConfigLevel effectiveLevel,
        long version,
        List<Step> trace
) {
    public record Step(ConfigLevel level, String entityId, String value, boolean applied) {
    }
}
