package com.moveinsync.config.snapshot;

import com.moveinsync.config.domain.ConfigDefinition;
import com.moveinsync.config.domain.ConfigLevel;
import com.moveinsync.config.domain.ConfigOverride;
import com.moveinsync.config.domain.ConfigType;
import com.moveinsync.config.domain.ResolutionStep;
import com.moveinsync.config.domain.ResolvedValue;
import com.moveinsync.config.domain.TenantContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigSnapshotResolutionTest {

    private static final String KEY = "booking_cutoff_minutes";

    private final ConfigSnapshot snapshot = ConfigSnapshot.of(
            7L,
            List.of(definition(KEY, "60")),
            List.of(
                    override(KEY, ConfigLevel.CLIENT, "acme-corp", "45"),
                    override(KEY, ConfigLevel.OFFICE, "blr-office-1", "30"),
                    override(KEY, ConfigLevel.SHIFT, "morning", "15")
            )
    );

    @Test
    void shiftWinsOverOfficeClientAndGlobal() {
        ResolvedValue resolved = snapshot.resolve(KEY, new TenantContext("acme-corp", "blr-office-1", "morning"));

        assertThat(resolved.value()).isEqualTo("15");
        assertThat(resolved.sourceLevel()).isEqualTo(ConfigLevel.SHIFT);
        assertThat(resolved.sourceEntityId()).isEqualTo("morning");
    }

    @Test
    void officeWinsWhenNoShiftOverrideExists() {
        ResolvedValue resolved = snapshot.resolve(KEY, new TenantContext("acme-corp", "blr-office-1", "evening"));

        assertThat(resolved.value()).isEqualTo("30");
        assertThat(resolved.sourceLevel()).isEqualTo(ConfigLevel.OFFICE);
    }

    @Test
    void clientWinsForAnOfficeWithoutAnOverride() {
        ResolvedValue resolved = snapshot.resolve(KEY, new TenantContext("acme-corp", "hyd-office-1", null));

        assertThat(resolved.value()).isEqualTo("45");
        assertThat(resolved.sourceLevel()).isEqualTo(ConfigLevel.CLIENT);
    }

    @Test
    void globalDefaultAppliesWhenNothingIsOverridden() {
        ResolvedValue resolved = snapshot.resolve(KEY, TenantContext.global());

        assertThat(resolved.value()).isEqualTo("60");
        assertThat(resolved.sourceLevel()).isEqualTo(ConfigLevel.GLOBAL);
        assertThat(resolved.isDefault()).isTrue();
    }

    @Test
    void explainShowsEveryLevelAndMarksTheWinner() {
        List<ResolutionStep> trace = snapshot.explain(KEY, new TenantContext("acme-corp", "blr-office-1", "morning"));

        assertThat(trace).extracting(ResolutionStep::level)
                .containsExactly(ConfigLevel.SHIFT, ConfigLevel.OFFICE, ConfigLevel.CLIENT, ConfigLevel.GLOBAL);
        assertThat(trace).filteredOn(ResolutionStep::applied)
                .singleElement()
                .extracting(ResolutionStep::level)
                .isEqualTo(ConfigLevel.SHIFT);
        assertThat(trace).filteredOn(step -> step.level() == ConfigLevel.OFFICE)
                .singleElement()
                .satisfies(step -> assertThat(step.value()).isEqualTo("30"));
    }

    @Test
    void unknownKeyIsRejected() {
        assertThatThrownBy(() -> snapshot.resolve("does_not_exist", TenantContext.global()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown configuration key");
    }

    private static ConfigDefinition definition(String key, String defaultValue) {
        return new ConfigDefinition(key, ConfigType.DURATION, defaultValue);
    }

    private static ConfigOverride override(String key, ConfigLevel level, String entityId, String value) {
        return new ConfigOverride(key, level, entityId, value);
    }
}
