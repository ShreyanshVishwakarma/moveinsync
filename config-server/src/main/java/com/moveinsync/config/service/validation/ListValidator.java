package com.moveinsync.config.service.validation;

import com.moveinsync.config.domain.ConfigType;
import com.moveinsync.config.snapshot.ConfigSnapshot;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
public class ListValidator implements ConfigValueValidator {

    @Override
    public ConfigType supportedType() {
        return ConfigType.LIST;
    }

    @Override
    public void validate(ConfigSnapshot.DefinitionView definition, String value, Consumer<String> violations) {
        String[] elements = value.split(",");
        if (elements.length == 0) {
            violations.accept(definition.key() + ": list must contain at least one element");
            return;
        }
        for (String element : elements) {
            if (element.isBlank()) {
                violations.accept(definition.key() + ": list contains an empty element");
                return;
            }
            if (definition.validationRegex() != null
                    && !definition.validationRegex().isBlank()
                    && !element.trim().matches(definition.validationRegex())) {
                violations.accept(definition.key() + ": list element does not match the allowed pattern");
                return;
            }
        }
    }
}
