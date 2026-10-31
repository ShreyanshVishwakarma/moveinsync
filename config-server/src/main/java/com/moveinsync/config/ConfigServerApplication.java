package com.moveinsync.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Entry point for the MoveInSync hierarchical configuration server.
 *
 * <p>Package layout follows the layering the evaluation asks for:
 * <ul>
 *   <li>{@code controller} - REST and WebSocket edge</li>
 *   <li>{@code service} - resolution, validation, commit, rollback, snapshots</li>
 *   <li>{@code repository} - Spring Data JPA persistence</li>
 *   <li>{@code domain} - entities and immutable value objects</li>
 * </ul>
 */
@SpringBootApplication
@EnableAsync
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
