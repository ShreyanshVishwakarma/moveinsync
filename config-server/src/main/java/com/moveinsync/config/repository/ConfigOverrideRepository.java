package com.moveinsync.config.repository;

import com.moveinsync.config.domain.ConfigLevel;
import com.moveinsync.config.domain.ConfigOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConfigOverrideRepository extends JpaRepository<ConfigOverride, Long> {

    Optional<ConfigOverride> findByConfigKeyAndLevelAndEntityId(String configKey,
                                                               ConfigLevel level,
                                                               String entityId);

    List<ConfigOverride> findByLevelAndEntityId(ConfigLevel level, String entityId);

    List<ConfigOverride> findByConfigKey(String configKey);
}
