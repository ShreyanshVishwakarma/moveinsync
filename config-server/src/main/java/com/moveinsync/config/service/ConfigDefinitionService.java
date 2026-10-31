package com.moveinsync.config.service;

import com.moveinsync.config.domain.ConfigDefinition;
import com.moveinsync.config.dto.CreateDefinitionRequest;
import com.moveinsync.config.dto.DefinitionResponse;
import com.moveinsync.config.dto.SnapshotResponse;
import com.moveinsync.config.exception.ApiException;
import com.moveinsync.config.repository.ConfigDefinitionRepository;
import com.moveinsync.config.snapshot.ConfigSnapshot;
import com.moveinsync.config.snapshot.SnapshotStore;
import com.moveinsync.config.service.validation.ConfigValidator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Definition management. Default values are validated with the same strategy set as
 * overrides, so a definition can never be created with a default that would fail at
 * read time.
 */
@Service
public class ConfigDefinitionService {

    private final ConfigDefinitionRepository repository;
    private final ConfigValidator validator;
    private final SnapshotLoader snapshotLoader;
    private final SnapshotStore snapshotStore;
    private final ApplicationEventPublisher events;

    public ConfigDefinitionService(ConfigDefinitionRepository repository,
                                   ConfigValidator validator,
                                   SnapshotLoader snapshotLoader,
                                   SnapshotStore snapshotStore,
                                   ApplicationEventPublisher events) {
        this.repository = repository;
        this.validator = validator;
        this.snapshotLoader = snapshotLoader;
        this.snapshotStore = snapshotStore;
        this.events = events;
    }

    @Transactional
    public DefinitionResponse create(CreateDefinitionRequest request) {
        if (repository.existsByKey(request.key())) {
            throw ApiException.conflict("DEFINITION_EXISTS", "A definition with key '" + request.key() + "' already exists");
        }

        ConfigDefinition definition = new ConfigDefinition(request.key(), request.type(), request.defaultValue());
        definition.setValidationRegex(request.validationRegex());
        definition.setAllowedValues(request.allowedValues());
        definition.setMinValue(request.minValue());
        definition.setMaxValue(request.maxValue());
        definition.setDescription(request.description());

        ConfigSnapshot.DefinitionView view = new ConfigSnapshot.DefinitionView(
                definition.getKey(),
                definition.getType(),
                definition.getDefaultValue(),
                definition.getValidationRegex(),
                definition.getAllowedValues(),
                definition.getMinValue(),
                definition.getMaxValue()
        );
        validator.validateDefault(view);

        ConfigDefinition saved = repository.save(definition);
        republish();
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<DefinitionResponse> findAll() {
        return repository.findAll().stream().map(ConfigDefinitionService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public DefinitionResponse find(String key) {
        return repository.findByKey(key)
                .map(ConfigDefinitionService::toResponse)
                .orElseThrow(() -> ApiException.notFound("Definition", key));
    }

    /**
     * Structural changes do not consume a version (versions belong to value change sets),
     * so connected clients are handed a fresh full snapshot instead of a delta.
     */
    private void republish() {
        ConfigSnapshot refreshed = snapshotLoader.load();
        snapshotStore.publish(refreshed);
        events.publishEvent(new ConfigPublishedEvent(
                refreshed.version(),
                SnapshotResponse.from(refreshed),
                List.of()
        ));
    }

    static DefinitionResponse toResponse(ConfigDefinition definition) {
        return new DefinitionResponse(
                definition.getKey(),
                definition.getType(),
                definition.getDefaultValue(),
                definition.getValidationRegex(),
                definition.getAllowedValues(),
                definition.getMinValue(),
                definition.getMaxValue(),
                definition.getDescription()
        );
    }
}
