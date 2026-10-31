package com.moveinsync.config.config;

import com.moveinsync.config.domain.ConfigLevel;
import com.moveinsync.config.dto.CommitChangeSetRequest;
import com.moveinsync.config.dto.CreateDefinitionRequest;
import com.moveinsync.config.dto.OverrideChange;
import com.moveinsync.config.repository.ConfigDefinitionRepository;
import com.moveinsync.config.service.ConfigChangeService;
import com.moveinsync.config.service.ConfigDefinitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Demo dataset modelled on the case study: a Bengaluru office whose booking cut-off is
 * 30 minutes while the client and global defaults stay at 45 and 60.
 *
 * <p>Seeding goes through the regular command path, so even the demo data has a change
 * set, an author, a reason and a version.
 */
@Component
@ConditionalOnProperty(name = "moveinsync.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final String CLIENT_ACME = "acme-corp";
    private static final String OFFICE_BLR = "blr-office-1";
    private static final String OFFICE_HYD = "hyd-office-1";

    private final ConfigDefinitionRepository definitionRepository;
    private final ConfigDefinitionService definitionService;
    private final ConfigChangeService changeService;

    public DemoDataSeeder(ConfigDefinitionRepository definitionRepository,
                          ConfigDefinitionService definitionService,
                          ConfigChangeService changeService) {
        this.definitionRepository = definitionRepository;
        this.definitionService = definitionService;
        this.changeService = changeService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (definitionRepository.count() > 0) {
            return;
        }

        seedDefinitions();
        seedOverrides();
        log.info("Demo configuration seeded");
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
                        new OverrideChange("booking_cutoff_minutes", ConfigLevel.CLIENT, CLIENT_ACME, "45"),
                        new OverrideChange("booking_cutoff_minutes", ConfigLevel.OFFICE, OFFICE_BLR, "30"),
                        new OverrideChange("escort_required", ConfigLevel.CLIENT, CLIENT_ACME, "true"),
                        new OverrideChange("ride_approval_mode", ConfigLevel.OFFICE, OFFICE_HYD, "MANUAL")
                )
        ));
    }
}
