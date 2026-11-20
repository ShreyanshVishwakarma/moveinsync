package com.moveinsync.config.config;

import com.moveinsync.config.domain.ConfigLevel;
import com.moveinsync.config.dto.CommitChangeSetRequest;
import com.moveinsync.config.dto.CreateDefinitionRequest;
import com.moveinsync.config.dto.OverrideChange;
import com.moveinsync.config.repository.ConfigDefinitionRepository;
import com.moveinsync.config.security.ApiKeyService;
import com.moveinsync.config.security.Role;
import com.moveinsync.config.security.User;
import com.moveinsync.config.security.UserRepository;
import com.moveinsync.config.service.ConfigChangeService;
import com.moveinsync.config.service.ConfigDefinitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Demo dataset modelled on the case study, plus three API keys that show the role model.
 *
 * <p>Seeding goes through the regular command path, so even the demo data has a change
 * set, an author, a reason and a version.
 */
@Component
@ConditionalOnProperty(name = "moveinsync.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final String CLIENT_ACME = "acme-corp";
    private static final String CLIENT_GLOBEX = "globex-corp";
    private static final String OFFICE_BLR = "blr-office-1";
    private static final String OFFICE_HYD = "hyd-office-1";

    /** Demo-only credentials. Production users are created by an admin and hashed with BCrypt. */
    public static final String ADMIN_KEY = "demo-admin-key";
    public static final String OPERATOR_KEY = "demo-operator-key";
    public static final String EMPLOYEE_KEY = "demo-employee-key";
    public static final String GLOBEX_KEY = "demo-globex-key";

    public static final String ADMIN_USER = "admin";
    public static final String OPERATOR_USER = "operator";
    public static final String EMPLOYEE_USER = "employee";
    public static final String GLOBEX_USER = "globex";
    public static final String DEMO_PASSWORD = "password123";

    private final ConfigDefinitionRepository definitionRepository;
    private final ConfigDefinitionService definitionService;
    private final ConfigChangeService changeService;
    private final ApiKeyService apiKeyService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DemoDataSeeder(ConfigDefinitionRepository definitionRepository,
                          ConfigDefinitionService definitionService,
                          ConfigChangeService changeService,
                          ApiKeyService apiKeyService,
                          UserRepository userRepository,
                          PasswordEncoder passwordEncoder) {
        this.definitionRepository = definitionRepository;
        this.definitionService = definitionService;
        this.changeService = changeService;
        this.apiKeyService = apiKeyService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedApiKeys();
        seedUsers();
        if (definitionRepository.count() > 0) {
            return;
        }

        seedDefinitions();
        seedOverrides();
        log.info("Demo configuration seeded; demo users: {} / {} / {} / {} (password {})",
                ADMIN_USER, OPERATOR_USER, EMPLOYEE_USER, GLOBEX_USER, DEMO_PASSWORD);
    }

    private void seedUsers() {
        registerUser(ADMIN_USER, Role.ADMIN, null);
        registerUser(OPERATOR_USER, Role.OPERATOR, CLIENT_ACME);
        registerUser(EMPLOYEE_USER, Role.EMPLOYEE, CLIENT_ACME);
        registerUser(GLOBEX_USER, Role.OPERATOR, CLIENT_GLOBEX);
    }

    private void registerUser(String username, Role role, String clientId) {
        if (userRepository.existsByUsername(username)) {
            return;
        }
        userRepository.save(new User(username, passwordEncoder.encode(DEMO_PASSWORD), role, clientId));
    }

    private void seedApiKeys() {
        apiKeyService.register(ADMIN_KEY, "demo-admin", Role.ADMIN, null);
        apiKeyService.register(OPERATOR_KEY, "demo-operator", Role.OPERATOR, CLIENT_ACME);
        apiKeyService.register(EMPLOYEE_KEY, "demo-employee", Role.EMPLOYEE, CLIENT_ACME);
        apiKeyService.register(GLOBEX_KEY, "demo-globex", Role.OPERATOR, CLIENT_GLOBEX);
    }

    private void seedDefinitions() {
        definitionService.create(new CreateDefinitionRequest(
                "booking_cutoff_minutes",
                com.moveinsync.config.domain.ConfigType.DURATION,
                "60",
                null, null, 0.0, 1440.0,
                "How many minutes before departure bookings close"
        ));
        definitionService.create(new CreateDefinitionRequest(
                "max_ride_minutes",
                com.moveinsync.config.domain.ConfigType.DURATION,
                "90",
                null, null, 1.0, 600.0,
                "Maximum allowed ride duration"
        ));
        definitionService.create(new CreateDefinitionRequest(
                "night_safety_window",
                com.moveinsync.config.domain.ConfigType.TIME_WINDOW,
                "22:00-23:59",
                null, null, null, null,
                "Window in which night-safety rules apply (same-day window)"
        ));
        definitionService.create(new CreateDefinitionRequest(
                "allowed_cab_types",
                com.moveinsync.config.domain.ConfigType.LIST,
                "SEDAN,SUV",
                "[A-Z_]+", null, null, null,
                "Cab categories a client may book"
        ));
        definitionService.create(new CreateDefinitionRequest(
                "escort_required",
                com.moveinsync.config.domain.ConfigType.BOOLEAN,
                "false",
                null, null, null, null,
                "Whether a security escort must accompany night rides"
        ));
        definitionService.create(new CreateDefinitionRequest(
                "ride_approval_mode",
                com.moveinsync.config.domain.ConfigType.ENUM,
                "AUTO",
                null, "AUTO,MANUAL,ESCALATE", null, null,
                "Who approves a ride request"
        ));
    }

    private void seedOverrides() {
        changeService.commit(new CommitChangeSetRequest(
                "seed",
                "Initial tenant configuration",
                List.of(
                        new OverrideChange("booking_cutoff_minutes", ConfigLevel.CLIENT, CLIENT_ACME, "45", CLIENT_ACME),
                        new OverrideChange("booking_cutoff_minutes", ConfigLevel.OFFICE, OFFICE_BLR, "30", CLIENT_ACME),
                        new OverrideChange("escort_required", ConfigLevel.CLIENT, CLIENT_ACME, "true", CLIENT_ACME),
                        new OverrideChange("ride_approval_mode", ConfigLevel.OFFICE, OFFICE_HYD, "MANUAL", CLIENT_ACME),
                        new OverrideChange("booking_cutoff_minutes", ConfigLevel.CLIENT, CLIENT_GLOBEX, "50", CLIENT_GLOBEX)
                )
        ));
    }
}
