package com.moveinsync.config.service.validation;

import com.moveinsync.config.domain.ConfigType;
import com.moveinsync.config.snapshot.ConfigSnapshot;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
public class NumberValidator implements ConfigValueValidator {

    @Override
    public ConfigType supportedType() {
        return ConfigType.NUMBER;
    }

    @Override
    public void validate(ConfigSnapshot.DefinitionView definition, String value, Consumer<String> violations) {
        double parsed;
        try {
            parsed = Double.parseDouble(value.trim());
        } catch (NumberFormatException ex) {
            violations.accept(definition.key() + ": expected a number but got a non-numeric value");
            return;
        }
        if (definition.minValue() != null && parsed < definition.minValue()) {
            violations.accept(definition.key() + ": must be >= " + format(definition.minValue()));
        }
        if (definition.maxValue() != null && parsed > definition.maxValue()) {
            violations.accept(definition.key() + ": must be <= " + format(definition.maxValue()));
        }
    }

    private String format(Double value) {
        return value % 1 == 0 ? String.valueOf(value.longValue()) : String.valueOf(value);
    }
}
