package com.moveinsync.config.sdk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The version rules from the case study, tested exhaustively. A client that applies
 * v41 after v43 must be impossible by construction, not by convention.
 */
class StateApplierTest {

    private final StateApplier applier = new StateApplier();

    @Test
    void appliesExactlyTheNextVersion() {
        seedAtVersion(5);

        StateApplier.Result result = applier.applyDelta(delta(6, "30", "25"));

        assertThat(result.outcome()).isEqualTo(StateApplier.Outcome.APPLIED);
        assertThat(result.state().version()).isEqualTo(6);
        assertThat(result.state().resolve("booking_cutoff_minutes", new TenantContext("acme-corp", null, null)).value())
                .isEqualTo("25");
    }

    @Test
    void ignoresADuplicateVersion() {
        seedAtVersion(5);
        applier.applyDelta(delta(6, "45", "25"));

        StateApplier.Result duplicate = applier.applyDelta(delta(6, "25", "99"));

        assertThat(duplicate.outcome()).isEqualTo(StateApplier.Outcome.IGNORED_DUPLICATE);
        assertThat(applier.current().version()).isEqualTo(6);
        assertThat(applier.current().resolve("booking_cutoff_minutes", new TenantContext("acme-corp", null, null)).value())
                .isEqualTo("25");
    }

    @Test
    void ignoresAStaleVersion() {
        seedAtVersion(5);
        applier.applyDelta(delta(6, "45", "25"));
        applier.applyDelta(delta(7, "25", "20"));

        StateApplier.Result stale = applier.applyDelta(delta(6, "45", "15"));

        assertThat(stale.outcome()).isEqualTo(StateApplier.Outcome.IGNORED_STALE);
        assertThat(applier.current().version()).isEqualTo(7);
        assertThat(applier.current().resolve("booking_cutoff_minutes", new TenantContext("acme-corp", null, null)).value())
                .isEqualTo("20");
    }

    @Test
    void detectsAGapAndLeavesStateUntouched() {
        seedAtVersion(5);

        StateApplier.Result gap = applier.applyDelta(delta(9, "45", "10"));

        assertThat(gap.outcome()).isEqualTo(StateApplier.Outcome.GAP_DETECTED);
        assertThat(gap.expectedVersion()).isEqualTo(6);
        assertThat(gap.receivedVersion()).isEqualTo(9);
        assertThat(applier.current().version()).isEqualTo(5);
        assertThat(applier.current().resolve("booking_cutoff_minutes", new TenantContext("acme-corp", null, null)).value())
                .isEqualTo("45");
    }

    @Test
    void aSnapshotNeverMovesTheVersionBackwards() {
        seedAtVersion(10);

        StateApplier.Result stale = applier.replaceSnapshot(ConfigStateTest.snapshot());

        assertThat(stale.outcome()).isEqualTo(StateApplier.Outcome.IGNORED_STALE);
        assertThat(applier.current().version()).isEqualTo(10);
    }

    @Test
    void aColdStartAcceptsASnapshotAtTheSameVersion() {
        StateApplier.Result applied = applier.replaceSnapshot(ConfigStateTest.snapshot());

        assertThat(applied.outcome()).isEqualTo(StateApplier.Outcome.APPLIED);
        assertThat(applier.current().version()).isEqualTo(7);
        assertThat(applier.current().definitions()).containsKey("booking_cutoff_minutes");
    }

    @Test
    void aSnapshotAheadReplacesTheWholeState() {
        seedAtVersion(3);

        StateApplier.Result applied = applier.replaceSnapshot(new Wire.Snapshot(
                12,
                List.of(new Wire.Definition("booking_cutoff_minutes", "DURATION", "60", null, null, null, null)),
                java.util.Map.of("OFFICE", java.util.Map.of("blr-office-1",
                        java.util.Map.of("booking_cutoff_minutes", "15")))
        ));

        assertThat(applied.outcome()).isEqualTo(StateApplier.Outcome.APPLIED);
        assertThat(applier.current().version()).isEqualTo(12);
        assertThat(applier.current().resolve("booking_cutoff_minutes",
                new TenantContext(null, "blr-office-1", null)).value()).isEqualTo("15");
    }

    private void seedAtVersion(long version) {
        applier.replaceSnapshot(new Wire.Snapshot(
                version,
                List.of(new Wire.Definition("booking_cutoff_minutes", "DURATION", "60", null, null, null, null)),
                java.util.Map.of("CLIENT", java.util.Map.of("acme-corp",
                        java.util.Map.of("booking_cutoff_minutes", "45")))
        ));
    }

    private Wire.Delta delta(long version, String oldValue, String newValue) {
        return new Wire.Delta(version, List.of(
                new Wire.Entry("booking_cutoff_minutes", "CLIENT", "acme-corp", oldValue, newValue)
        ));
    }
}
