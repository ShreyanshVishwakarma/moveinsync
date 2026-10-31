package com.moveinsync.config.dto;

import com.moveinsync.config.domain.ConfigType;

public record DefinitionResponse(
        String key,
        ConfigType type,
        String defaultValue,
        String validationRegex,
        String allowedValues,
        Double minValue,
        Double maxValue,
        String description
) {
}
