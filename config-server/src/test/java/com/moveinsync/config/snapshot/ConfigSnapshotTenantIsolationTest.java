package com.moveinsync.config.snapshot;

import com.moveinsync.config.domain.ConfigDefinition;
import com.moveinsync.config.domain.ConfigLevel;
import com.moveinsync.config.domain.ConfigOverride;
import com.moveinsync.config.domain.ConfigType;
import com.moveinsync.config.domain.TenantContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant isolation is a property of resolution, not just of the API layer: even if two
 * clients share an office id, one tenant can never resolve the other's override.
 */
class ConfigSnapshotTenantIsolationTest {

    private static final String KEY = "booking_cutoff_minutes";
    private static final String SHARED_OFFICE = "shared-office";

    private final ConfigSnapshot snapshot = ConfigSnapshot.of(
            9,
            List.of(new ConfigDefinition(KEY, ConfigType.DURATION, "60")),
            List.of(
                    new ConfigOverride(KEY, ConfigLevel.OFFICE, SHARED_OFFICE, "10", "tenant-a"),
                    new ConfigOverride(KEY, ConfigLevel.OFFICE, SHARED_OFFICE, "20", "tenant-b"),
                    new ConfigOverride(KEY, ConfigLevel.CLIENT, "tenant-a", "5", "tenant-a")
            )
    );

    @Test
    void eachTenantSeesItsOwnOverrideForTheSameOfficeId() {
        assertThat(snapshot.resolve(KEY, new TenantContext("tenant-a", SHARED_OFFICE, null)).value())
                .isEqualTo("10");
        assertThat(snapshot.resolve(KEY, new TenantContext("tenant-b", SHARED_OFFICE, null)).value())
                .isEqualTo("20");
    }

    @Test
    void aTenantWithNoOverrideFallsBackToTheGlobalDefault() {
        assertThat(snapshot.resolve(KEY, new TenantContext("tenant-c", SHARED_OFFICE, null)).value())
                .isEqualTo("60");
    }

    @Test
    void aContextWithoutATenantCannotProbeOfficeOverrides() {
        assertThat(snapshot.resolve(KEY, new TenantContext(null, SHARED_OFFICE, null)).value())
                .isEqualTo("60");
    }

    @Test
    void snapshotProjectionIsFilteredPerTenant() {
        Map<ConfigLevel, Map<String, Map<String, String>>> visible = snapshot.overridesFor("tenant-a");

        assertThat(visible.get(ConfigLevel.OFFICE)).containsOnlyKeys(SHARED_OFFICE);
        assertThat(visible.get(ConfigLevel.OFFICE).get(SHARED_OFFICE)).containsEntry(KEY, "10");
        assertThat(visible.get(ConfigLevel.CLIENT)).containsKey("tenant-a");
        assertThat(visible.get(ConfigLevel.CLIENT)).doesNotContainKey("tenant-b");
    }

    @Test
    void adminProjectionKeepsBothTenantsDistinct() {
        Map<ConfigLevel, Map<String, Map<String, String>>> visible = snapshot.overridesFor(null);

        assertThat(visible.get(ConfigLevel.OFFICE)).hasSize(2);
    }
}
