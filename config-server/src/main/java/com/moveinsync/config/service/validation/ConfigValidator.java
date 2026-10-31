package com.moveinsync.config.service.validation;

import com.moveinsync.config.domain.ConfigType;
import com.moveinsync.config.exception.ValidationException;
import com.moveinsync.config.snapshot.ConfigSnapshot;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Type -> strategy registry. Validation is deliberately done against the immutable
 * snapshot definition so that it is side-effect free and unit-testable without a
 * database or web server.
 */
@Service
public class ConfigValidator {

    private final Map<ConfigType, ConfigValueValidator> validators = new EnumMap<>(ConfigType.class);

    public ConfigValidator(List<ConfigValueValidator> discovered) {
        for (ConfigValueValidator validator : discovered) {
            ConfigValueValidator previous = validators.put(validator.supportedType(), validator);
            if (previous != null) {
                throw new IllegalStateException("Duplicate validator for " + validator.supportedType());
            }
        }
    }

    /**
     * @throws ValidationException when any rule fails; the message lists every
     *                             violation so an operator can fix them in one round trip.
     */
    public void validate(ConfigSnapshot.DefinitionView definition, String rawValue) {
        List<String> violations = new ArrayList<>();
        if (rawValue == null || rawValue.isBlank()) {
            violations.add(definition.key() + ": value must not be blank");
            throw new ValidationException(violations);
        }

        String value = rawValue.trim();
        ConfigValueValidator validator = validators.get(definition.type());
        if (validator == null) {
            violations.add(definition.key() + ": no validator registered for type " + definition.type());
            throw new ValidationException(violations);
        }
        validator.validate(definition, value, violations::add);

        if (violations.isEmpty() && definition.validationRegex() != null
                && !definition.validationRegex().isBlank()
                && definition.type() != ConfigType.LIST
                && definition.type() != ConfigType.STRING
                && !value.matches(definition.validationRegex())) {
            violations.add(definition.key() + ": value does not match the configured pattern");
        }

        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
    }

    public void validateDefault(ConfigSnapshot.DefinitionView definition) {
        validate(definition, definition.defaultValue());
    }
}
