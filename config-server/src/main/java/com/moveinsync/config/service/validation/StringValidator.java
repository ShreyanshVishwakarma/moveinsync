package com.moveinsync.config.service.validation;

import com.moveinsync.config.domain.ConfigType;
import com.moveinsync.config.snapshot.ConfigSnapshot;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
public class StringValidator implements ConfigValueValidator {

    @Override
    public ConfigType supportedType() {
        return ConfigType.STRING;
    }

    @Override
    public void validate(ConfigSnapshot.DefinitionView definition, String value, Consumer<String> violations) {
        if (value.length() > 512) {
            violations.accept(definition.key() + ": value exceeds the maximum length of 512 characters");
        }
        if (definition.validationRegex() != null
                && !definition.validationRegex().isBlank()
                && !value.matches(definition.validationRegex())) {
            violations.accept(definition.key() + ": value does not match the configured pattern");
        }
    }
}
