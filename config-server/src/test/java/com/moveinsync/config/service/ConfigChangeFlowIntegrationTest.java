package com.moveinsync.config.service;

import com.moveinsync.config.domain.ConfigLevel;
import com.moveinsync.config.domain.TenantContext;
import com.moveinsync.config.dto.ChangeSetResponse;
import com.moveinsync.config.dto.CommitChangeSetRequest;
import com.moveinsync.config.dto.CreateDefinitionRequest;
import com.moveinsync.config.dto.OverrideChange;
import com.moveinsync.config.dto.RollbackRequest;
import com.moveinsync.config.dto.RollbackResponse;
import com.moveinsync.config.dto.SyncResponse;
import com.moveinsync.config.exception.ValidationException;
import com.moveinsync.config.domain.ConfigType;
import com.moveinsync.config.metrics.ConfigMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end write path against H2: commit, reject, sync and rollback.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ConfigChangeFlowIntegrationTest {

    private static final String KEY = "booking_cutoff_minutes";
    private static final String CLIENT = "acme-corp";
    private static final String OFFICE = "blr-office-1";

    @Autowired
    private ConfigDefinitionService definitionService;

    @Autowired
    private ConfigChangeService changeService;

    @Autowired
    private ConfigQueryService queryService;

    @Autowired
    private RollbackService rollbackService;

    @Autowired
    private ConfigMetrics metrics;

    @Test
    void commitIsAtomicVisibleImmediatelyAndVersioned() {
        createDurationDefinition();

        ChangeSetResponse published = changeService.commit(new CommitChangeSetRequest(
                "operator-1",
                "Reduce cut-off for the Bengaluru office",
                List.of(new OverrideChange(KEY, ConfigLevel.OFFICE, OFFICE, "30"))
        ));

        assertThat(published.version()).isEqualTo(1L);
        assertThat(published.entries()).hasSize(1);
        assertThat(published.entries().get(0).oldValue()).isNull();
        assertThat(published.entries().get(0).newValue()).isEqualTo("30");

        assertThat(queryService.currentVersion()).isEqualTo(1L);
        assertThat(queryService.resolve(KEY, new TenantContext(CLIENT, OFFICE, null)).value()).isEqualTo("30");
        // Every other office still resolves the global default.
        assertThat(queryService.resolve(KEY, new TenantContext(CLIENT, "hyd-office-1", null)).value()).isEqualTo("60");
    }

    @Test
    void invalidValueIsRejectedBeforePublicationAndChangesNothing() {
        createDurationDefinition();

        assertThatThrownBy(() -> changeService.commit(new CommitChangeSetRequest(
                "operator-1",
                "Cut-off of minus thirty minutes",
                List.of(new OverrideChange(KEY, ConfigLevel.OFFICE, OFFICE, "-30"))
        )))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getViolations())
                        .anyMatch(violation -> violation.contains("must be >= 0")));

        assertThat(queryService.currentVersion()).isZero();
        assertThat(queryService.resolve(KEY, new TenantContext(CLIENT, OFFICE, null)).value()).isEqualTo("60");
        assertThat(metrics.rejections().count()).isGreaterThan(0);
    }

    @Test
    void aMultiKeyChangeSetBecomesVisibleUnderOneVersion() {
        createDurationDefinition();
        definitionService.create(new CreateDefinitionRequest(
                "night_safety_window", ConfigType.TIME_WINDOW, "22:00-23:59",
                null, null, null, null, "Night window"));

        ChangeSetResponse published = changeService.commit(new CommitChangeSetRequest(
                "operator-1",
                "Move the night window for this office",
                List.of(
                        new OverrideChange("night_safety_window", ConfigLevel.OFFICE, OFFICE, "21:00-23:30"),
                        new OverrideChange(KEY, ConfigLevel.OFFICE, OFFICE, "30")
                )
        ));

        assertThat(published.version()).isEqualTo(1L);
        assertThat(published.entries()).hasSize(2);
        assertThat(queryService.currentVersion()).isEqualTo(1L);
    }

    @Test
    void syncReturnsOnlyChangeSetsNewerThanTheClientVersion() {
        createDurationDefinition();

        changeService.commit(new CommitChangeSetRequest("operator-1", "First",
                List.of(new OverrideChange(KEY, ConfigLevel.CLIENT, CLIENT, "45"))));
        changeService.commit(new CommitChangeSetRequest("operator-1", "Second",
                List.of(new OverrideChange(KEY, ConfigLevel.OFFICE, OFFICE, "30"))));

        SyncResponse delta = queryService.sync(1L);

        assertThat(delta.full()).isFalse();
        assertThat(delta.version()).isEqualTo(2L);
        assertThat(delta.changeSets()).hasSize(1);
        assertThat(delta.changeSets().get(0).version()).isEqualTo(2L);

        // A cold client gets everything.
        assertThat(queryService.sync(0L).full()).isTrue();
        // A client that is already current gets an empty delta.
        assertThat(queryService.sync(2L).changeSets()).isEmpty();
    }

    @Test
    void rollbackPublishesANewVersionWithTheOldValues() {
        createDurationDefinition();

        ChangeSetResponse first = changeService.commit(new CommitChangeSetRequest("operator-1", "Set to 30",
                List.of(new OverrideChange(KEY, ConfigLevel.OFFICE, OFFICE, "30"))));
        changeService.commit(new CommitChangeSetRequest("operator-1", "Set to 20",
                List.of(new OverrideChange(KEY, ConfigLevel.OFFICE, OFFICE, "20"))));

        RollbackResponse preview = rollbackService.rollback(
                new RollbackRequest(first.version(), "operator-1", "Bad change", true));
        assertThat(preview.publishedVersion()).isNull();
        assertThat(preview.diff()).hasSize(1);
        assertThat(preview.diff().get(0).newValue()).isEqualTo("30");

        RollbackResponse applied = rollbackService.rollback(
                new RollbackRequest(first.version(), "operator-1", "Bad change", false));

        assertThat(applied.publishedVersion()).isEqualTo(3L);
        assertThat(queryService.resolve(KEY, new TenantContext(CLIENT, OFFICE, null)).value()).isEqualTo("30");
        assertThat(queryService.currentVersion()).isEqualTo(3L);
        assertThat(queryService.getChangeSet(3L).revertsToVersion()).isEqualTo(first.version());
    }

    private void createDurationDefinition() {
        definitionService.create(new CreateDefinitionRequest(
                KEY, ConfigType.DURATION, "60",
                null, null, 0.0, 1440.0,
                "Minutes before departure that bookings close"));
    }
}
