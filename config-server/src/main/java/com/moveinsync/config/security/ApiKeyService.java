package com.moveinsync.config.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Issues and verifies API keys.
 *
 * <p>Only a SHA-256 hash is stored. The raw key is returned once at creation time; the
 * lookup is by hash, so verification does not compare secrets in a way that leaks length
 * or prefix information.
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private final ApiKeyRepository repository;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(ApiKeyRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<Actor> authenticate(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findByKeyHashAndActiveTrue(hash(rawKey))
                .map(ApiKey::toActor);
    }

    /** Creates a random 256-bit key and returns the plaintext once. */
    @Transactional
    public String issue(String label, Role role, String clientId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String rawKey = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repository.save(new ApiKey(hash(rawKey), label, role, clientId));
        log.info("Issued API key label={} role={} client={}", label, role, clientId);
        return rawKey;
    }

    /** Idempotent registration used for the demo dataset. */
    @Transactional
    public void register(String rawKey, String label, Role role, String clientId) {
        if (repository.existsByLabel(label)) {
            return;
        }
        repository.save(new ApiKey(hash(rawKey), label, role, clientId));
    }

    static String hash(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required but unavailable", ex);
        }
    }
}
