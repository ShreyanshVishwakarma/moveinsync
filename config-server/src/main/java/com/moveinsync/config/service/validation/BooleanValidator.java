package com.moveinsync.config.service.validation;

import com.moveinsync.config.domain.ConfigType;
import com.moveinsync.config.snapshot.ConfigSnapshot;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
public class BooleanValidator implements ConfigValueValidator {

    @Override
    public ConfigType supportedType() {
        return ConfigType.BOOLEAN;
    }

    @Override
    public void validate(ConfigSnapshot.DefinitionView definition, String value, Consumer<String> violations) {
        String normalized = value.trim();
        if (!normalized.equals("true") && !normalized.equals("false")) {
            violations.accept(definition.key() + ": must be 'true' or 'false'");
        }
    }
}
