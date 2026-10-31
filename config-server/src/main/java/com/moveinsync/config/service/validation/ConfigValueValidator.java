package com.moveinsync.config.service.validation;

import com.moveinsync.config.domain.ConfigType;
import com.moveinsync.config.snapshot.ConfigSnapshot;

import java.util.function.Consumer;

/**
 * Strategy contract for one configuration type. Adding a new type means adding one
 * implementation and registering it as a bean - no switch statements to edit
 * (open/closed principle).
 */
public interface ConfigValueValidator {

    ConfigType supportedType();

    /**
     * @param definition the setting being written
     * @param value      the candidate value, already known to be non-blank
     * @param violations sink for human-readable, PII-free violation messages
     */
    void validate(ConfigSnapshot.DefinitionView definition, String value, Consumer<String> violations);

    default String describe() {
        return supportedType().name();
    }
}
