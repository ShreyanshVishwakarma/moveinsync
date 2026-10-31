package com.moveinsync.config.service.validation;

import com.moveinsync.config.domain.ConfigType;
import com.moveinsync.config.snapshot.ConfigSnapshot;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Durations are expressed in whole minutes and can never be negative
 * (a booking cut-off of -30 minutes is rejected here).
 */
@Component
public class DurationValidator implements ConfigValueValidator {

    @Override
    public ConfigType supportedType() {
        return ConfigType.DURATION;
    }

    @Override
    public void validate(ConfigSnapshot.DefinitionView definition, String value, Consumer<String> violations) {
        long minutes;
        try {
            minutes = Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            violations.accept(definition.key() + ": duration must be a whole number of minutes");
            return;
        }
        if (minutes < 0) {
            violations.accept(definition.key() + ": duration must be >= 0 minutes");
            return;
        }
        if (definition.minValue() != null && minutes < definition.minValue()) {
            violations.accept(definition.key() + ": duration must be >= " + definition.minValue().longValue() + " minutes");
        }
        if (definition.maxValue() != null && minutes > definition.maxValue()) {
            violations.accept(definition.key() + ": duration must be <= " + definition.maxValue().longValue() + " minutes");
        }
    }
}
