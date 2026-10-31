package com.moveinsync.config.service.validation;

import com.moveinsync.config.domain.ConfigType;
import com.moveinsync.config.snapshot.ConfigSnapshot;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.Consumer;

/**
 * Validates {@code HH:mm-HH:mm} windows. The start must be strictly before the end;
 * this is the check that stops "new night window start with the old end" from ever
 * being accepted, independently of the atomic change-set guarantee.
 */
@Component
public class TimeWindowValidator implements ConfigValueValidator {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    @Override
    public ConfigType supportedType() {
        return ConfigType.TIME_WINDOW;
    }

    @Override
    public void validate(ConfigSnapshot.DefinitionView definition, String value, Consumer<String> violations) {
        String trimmed = value.trim();
        int separator = trimmed.indexOf('-');
        if (separator < 0 || trimmed.indexOf('-', separator + 1) >= 0) {
            violations.accept(definition.key() + ": window must be formatted HH:mm-HH:mm");
            return;
        }

        String startText = trimmed.substring(0, separator).trim();
        String endText = trimmed.substring(separator + 1).trim();

        LocalTime start;
        LocalTime end;
        try {
            start = LocalTime.parse(startText, FORMAT);
        } catch (DateTimeParseException ex) {
            violations.accept(definition.key() + ": window start is not a valid HH:mm time");
            return;
        }
        try {
            end = LocalTime.parse(endText, FORMAT);
        } catch (DateTimeParseException ex) {
            violations.accept(definition.key() + ": window end is not a valid HH:mm time");
            return;
        }

        if (!start.isBefore(end)) {
            violations.accept(definition.key() + ": window end must be after window start");
        }
    }
}
