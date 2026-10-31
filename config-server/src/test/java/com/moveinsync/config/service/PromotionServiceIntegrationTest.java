package com.moveinsync.config.service;

import com.moveinsync.config.domain.ConfigLevel;
import com.moveinsync.config.domain.ConfigType;
import com.moveinsync.config.domain.TenantContext;
import com.moveinsync.config.dto.ChangeSetResponse;
import com.moveinsync.config.dto.CommitChangeSetRequest;
import com.moveinsync.config.dto.CreateDefinitionRequest;
import com.moveinsync.config.dto.OverrideChange;
import com.moveinsync.config.security.Actor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Staged rollout: a canary lands on two offices, every other office keeps the old value,
 * then promotion widens it to the whole client as one new version.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PromotionServiceIntegrationTest {

    private static final String KEY = "booking_cutoff_minutes";
    private static final String CLIENT = "acme-corp";

    @Autowired
    private ConfigDefinitionService definitionService;

    @Autowired
    private ConfigChangeService changeService;

    @Autowired
    private ConfigQueryService queryService;

    @Autowired
    private PromotionService promotionService;

    @Test
    void aCanaryOnTwoOfficesIsPromotedToEveryOffice() {
        definitionService.create(new CreateDefinitionRequest(
                KEY, ConfigType.DURATION, "60", null, null, 0.0, 1440.0, "Cut-off"));

        ChangeSetResponse staged = changeService.commit(new CommitChangeSetRequest(
                "operator-1",
                "Canary: two Bengaluru offices first",
                List.of(
                        new OverrideChange(KEY, ConfigLevel.OFFICE, "blr-office-1", "25", CLIENT),
                        new OverrideChange(KEY, ConfigLevel.OFFICE, "blr-office-2", "25", CLIENT)
                )
        ));

        assertThat(queryService.resolve(KEY, new TenantContext(CLIENT, "blr-office-1", null)).value())
                .isEqualTo("25");
        assertThat(queryService.resolve(KEY, new TenantContext(CLIENT, "del-office-1", null)).value())
                .isEqualTo("60");
        // Another tenant is untouched by the canary.
        assertThat(queryService.resolve(KEY, new TenantContext("globex-corp", "blr-office-1", null)).value())
                .isEqualTo("60");

        ChangeSetResponse promoted = promotionService.promote(staged.version(), true, "operator-1", Actor.system());

        assertThat(promoted.promotedFromVersion()).isEqualTo(staged.version());
        assertThat(promoted.version()).isEqualTo(staged.version() + 1);

        // Every office now inherits the client-level value, including ones never targeted.
        assertThat(queryService.resolve(KEY, new TenantContext(CLIENT, "del-office-1", null)).value())
                .isEqualTo("25");
        assertThat(queryService.resolve(KEY, new TenantContext(CLIENT, "blr-office-1", null)).sourceLevel())
                .isEqualTo(ConfigLevel.CLIENT);
        assertThat(queryService.resolve(KEY, new TenantContext("globex-corp", "blr-office-1", null)).value())
                .isEqualTo("60");
    }

    @Test
    void aClientLevelChangeSetCannotBePromoted() {
        definitionService.create(new CreateDefinitionRequest(
                KEY, ConfigType.DURATION, "60", null, null, 0.0, 1440.0, "Cut-off"));

        ChangeSetResponse clientLevel = changeService.commit(new CommitChangeSetRequest(
                "operator-1",
                "Already client wide",
                List.of(new OverrideChange(KEY, ConfigLevel.CLIENT, CLIENT, "40", CLIENT))
        ));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        promotionService.promote(clientLevel.version(), false, "operator-1", Actor.system()))
                .hasMessageContaining("client-level");
    }
}
