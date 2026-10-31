package com.moveinsync.config.service.validation;

import com.moveinsync.config.domain.ConfigType;
import com.moveinsync.config.exception.ValidationException;
import com.moveinsync.config.snapshot.ConfigSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigValidatorTest {

    private final ConfigValidator validator = new ConfigValidator(List.of(
            new NumberValidator(),
            new DurationValidator(),
            new TimeWindowValidator(),
            new EnumValidator(),
            new ListValidator(),
            new BooleanValidator(),
            new StringValidator()
    ));

    @Test
    void negativeDurationIsRejected() {
        var definition = definition("booking_cutoff_minutes", ConfigType.DURATION, "60", 0.0, 1440.0, null, null);

        assertThatThrownBy(() -> validator.validate(definition, "-30"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid configuration value");
    }

    @Test
    void zeroDurationIsAllowed() {
        var definition = definition("booking_cutoff_minutes", ConfigType.DURATION, "60", 0.0, 1440.0, null, null);

        assertThatCode(() -> validator.validate(definition, "0")).doesNotThrowAnyException();
    }

    @Test
    void windowEndingBeforeItStartsIsRejected() {
        var definition = definition("night_safety_window", ConfigType.TIME_WINDOW, "22:00-23:59", null, null, null, null);

        assertThatThrownBy(() -> validator.validate(definition, "23:00-22:00"))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getViolations())
                        .anyMatch(violation -> violation.contains("must be after window start")));
    }

    @Test
    void equalStartAndEndIsRejected() {
        var definition = definition("night_safety_window", ConfigType.TIME_WINDOW, "22:00-23:59", null, null, null, null);

        assertThatThrownBy(() -> validator.validate(definition, "22:00-22:00"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void malformedWindowIsRejected() {
        var definition = definition("night_safety_window", ConfigType.TIME_WINDOW, "22:00-23:59", null, null, null, null);

        assertThatThrownBy(() -> validator.validate(definition, "22:00"))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getViolations())
                        .anyMatch(violation -> violation.contains("HH:mm-HH:mm")));
    }

    @Test
    void valueOutsideAllowedEnumIsRejected() {
        var definition = definition("ride_approval_mode", ConfigType.ENUM, "AUTO", null, null,
                "AUTO,MANUAL,ESCALATE", null);

        assertThatThrownBy(() -> validator.validate(definition, "ROBOT"))
                .isInstanceOf(ValidationException.class);
        assertThatCode(() -> validator.validate(definition, "MANUAL")).doesNotThrowAnyException();
    }

    @Test
    void numberOutsideRangeIsRejected() {
        var definition = definition("max_ride_km", ConfigType.NUMBER, "40", 0.0, 500.0, null, null);

        assertThatThrownBy(() -> validator.validate(definition, "501"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> validator.validate(definition, "not-a-number"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void listElementsMustMatchThePattern() {
        var definition = definition("allowed_cab_types", ConfigType.LIST, "SEDAN,SUV", null, null, null, "[A-Z_]+");

        assertThatCode(() -> validator.validate(definition, "SEDAN,SUV")).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(definition, "sedan,SUV"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void blankValueIsRejected() {
        var definition = definition("max_ride_km", ConfigType.NUMBER, "40", 0.0, 500.0, null, null);

        assertThatThrownBy(() -> validator.validate(definition, "  "))
                .isInstanceOf(ValidationException.class);
    }

    private ConfigSnapshot.DefinitionView definition(String key,
                                                     ConfigType type,
                                                     String defaultValue,
                                                     Double min,
                                                     Double max,
                                                     String allowedValues,
                                                     String regex) {
        return new ConfigSnapshot.DefinitionView(key, type, defaultValue, regex, allowedValues, min, max);
    }
}
