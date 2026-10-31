package com.moveinsync.config.dto;

import com.moveinsync.config.domain.ConfigType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateDefinitionRequest(
        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "[a-z0-9_.\\-]+", message = "must be lower_snake or dotted lower-case")
        String key,

        @NotNull
        ConfigType type,

        @NotBlank
        @Size(max = 512)
        String defaultValue,

        @Size(max = 256)
        String validationRegex,

        @Size(max = 1024)
        String allowedValues,

        Double minValue,

        Double maxValue,

        @Size(max = 512)
        String description
) {
}
