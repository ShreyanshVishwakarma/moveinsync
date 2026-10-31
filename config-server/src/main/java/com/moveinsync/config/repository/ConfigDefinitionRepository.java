package com.moveinsync.config.repository;

import com.moveinsync.config.domain.ConfigDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConfigDefinitionRepository extends JpaRepository<ConfigDefinition, Long> {

    Optional<ConfigDefinition> findByKey(String key);

    boolean existsByKey(String key);
}
