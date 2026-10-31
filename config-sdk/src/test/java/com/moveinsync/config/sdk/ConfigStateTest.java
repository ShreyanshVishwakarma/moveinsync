package com.moveinsync.config.sdk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigStateTest {

    private static final String KEY = "booking_cutoff_minutes";
    private static final String CLIENT = "acme-corp";
    private static final String OFFICE = "blr-office-1";

    private final ConfigState state = ConfigState.fromSnapshot(snapshot());

    @Test
    void shiftWinsOverOfficeClientAndGlobal() {
        ResolvedValue resolved = state.resolve(KEY, new TenantContext(CLIENT, OFFICE, "morning"));

        assertThat(resolved.value()).isEqualTo("15");
        assertThat(resolved.sourceLevel()).isEqualTo(ConfigLevel.SHIFT);
    }

    @Test
    void officeWinsWithoutAShiftOverride() {
        ResolvedValue resolved = state.resolve(KEY, new TenantContext(CLIENT, OFFICE, "evening"));

        assertThat(resolved.value()).isEqualTo("30");
        assertThat(resolved.sourceLevel()).isEqualTo(ConfigLevel.OFFICE);
        assertThat(resolved.sourceEntityId()).isEqualTo(OFFICE);
    }

    @Test
    void clientWinsForAnOfficeWithoutAnOverride() {
        ResolvedValue resolved = state.resolve(KEY, new TenantContext(CLIENT, "hyd-office-1", null));

        assertThat(resolved.value()).isEqualTo("45");
        assertThat(resolved.sourceLevel()).isEqualTo(ConfigLevel.CLIENT);
    }

    @Test
    void globalDefaultAppliesWhenNothingIsOverridden() {
        ResolvedValue resolved = state.resolve(KEY, TenantContext.global());

        assertThat(resolved.value()).isEqualTo("60");
        assertThat(resolved.isDefault()).isTrue();
    }

    @Test
    void explainMarksExactlyOneWinner() {
        List<ResolutionStep> trace = state.explain(KEY, new TenantContext(CLIENT, OFFICE, null));

        assertThat(trace).extracting(ResolutionStep::level)
                .containsExactly(ConfigLevel.SHIFT, ConfigLevel.OFFICE, ConfigLevel.CLIENT, ConfigLevel.GLOBAL);
        assertThat(trace).filteredOn(ResolutionStep::applied)
                .singleElement()
                .extracting(ResolutionStep::level)
                .isEqualTo(ConfigLevel.OFFICE);
    }

    @Test
    void deltaUpdatesAndDeletesOverrides() {
        ConfigState updated = state.applyDelta(new Wire.Delta(8, List.of(
                new Wire.Entry(KEY, "OFFICE", OFFICE, "30", "25")
        )));

        assertThat(updated.resolve(KEY, new TenantContext(CLIENT, OFFICE, null)).value()).isEqualTo("25");
        assertThat(updated.version()).isEqualTo(8);
        // The original state is untouched: snapshots are immutable.
        assertThat(state.resolve(KEY, new TenantContext(CLIENT, OFFICE, null)).value()).isEqualTo("30");

        ConfigState reverted = updated.applyDelta(new Wire.Delta(9, List.of(
                new Wire.Entry(KEY, "OFFICE", OFFICE, "25", null)
        )));

        assertThat(reverted.resolve(KEY, new TenantContext(CLIENT, OFFICE, null)).value()).isEqualTo("45");
    }

    @Test
    void snapshotRoundTripsThroughJson() throws Exception {
        WireCodec codec = new WireCodec();
        Wire.Snapshot wire = state.toSnapshot();

        Wire.Snapshot parsed = codec.readSnapshot(codec.write(wire));
        ConfigState restored = ConfigState.fromSnapshot(parsed);

        assertThat(restored.version()).isEqualTo(state.version());
        assertThat(restored.definitions()).hasSameSizeAs(state.definitions());
        assertThat(restored.resolve(KEY, new TenantContext(CLIENT, OFFICE, "morning")).value())
                .isEqualTo("15");
    }

    @Test
    void unknownKeyFailsLoudly() {
        assertThatThrownBy(() -> state.resolve("not_a_key", TenantContext.global()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownLevelsInWireDataAreIgnoredNotFatal() {
        Wire.Snapshot dirty = new Wire.Snapshot(3,
                List.of(new Wire.Definition(KEY, "DURATION", "60", null, null, null, null)),
                Map.of("REGION", Map.of("apac", Map.of(KEY, "10"))));

        ConfigState tolerant = ConfigState.fromSnapshot(dirty);

        assertThat(tolerant.resolve(KEY, TenantContext.global()).value()).isEqualTo("60");
    }

    static Wire.Snapshot snapshot() {
        return new Wire.Snapshot(
                7,
                List.of(new Wire.Definition(KEY, "DURATION", "60", null, null, 0.0, 1440.0)),
                Map.of(
                        "CLIENT", Map.of(CLIENT, Map.of(KEY, "45")),
                        "OFFICE", Map.of(OFFICE, Map.of(KEY, "30")),
                        "SHIFT", Map.of("morning", Map.of(KEY, "15"))
                )
        );
    }
}
