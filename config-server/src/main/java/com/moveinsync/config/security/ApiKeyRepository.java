package com.moveinsync.config.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    Optional<ApiKey> findByKeyHashAndActiveTrue(String keyHash);

    Optional<ApiKey> findByLabel(String label);

    boolean existsByLabel(String label);
}
