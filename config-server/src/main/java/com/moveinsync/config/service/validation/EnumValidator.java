package com.moveinsync.config.service.validation;

import com.moveinsync.config.domain.ConfigType;
import com.moveinsync.config.snapshot.ConfigSnapshot;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

@Component
public class EnumValidator implements ConfigValueValidator {

    @Override
    public ConfigType supportedType() {
        return ConfigType.ENUM;
    }

    @Override
    public void validate(ConfigSnapshot.DefinitionView definition, String value, Consumer<String> violations) {
        List<String> allowed = allowedValues(definition);
        if (allowed.isEmpty()) {
            violations.accept(definition.key() + ": setting is an ENUM but has no allowed values configured");
            return;
        }
        if (!allowed.contains(value.trim())) {
            violations.accept(definition.key() + ": must be one of " + allowed);
        }
    }

    static List<String> allowedValues(ConfigSnapshot.DefinitionView definition) {
        if (definition.allowedValues() == null || definition.allowedValues().isBlank()) {
            return List.of();
        }
        return Arrays.stream(definition.allowedValues().split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
