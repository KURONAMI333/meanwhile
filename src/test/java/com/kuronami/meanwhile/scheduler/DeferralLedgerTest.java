package com.kuronami.meanwhile.scheduler;

import com.kuronami.meanwhile.scheduler.DeferralLedger.Outcome;
import com.kuronami.meanwhile.scheduler.DeferralLedger.Reconciliation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bookkeeping invariants that decide whether it is safe to stop ticking something.
 *
 * <p>Each of these exists because getting it wrong produces a specific, already-observed
 * failure rather than a hypothetical one. The re-arm test in particular: a ledger that does
 * not refresh the fingerprint after a clean reconcile reports a subject's own progress as
 * somebody else having touched it, which showed up the moment a window was split in two.
 */
class DeferralLedgerTest {

    private static final String KEY = "furnace@0,64,0";
    private static final long CLEAN = 0xABCDEFL;
    private static final long CHANGED = 0x123456L;

    @Test
    void reconcilingSomethingNeverDeferredSkipsNothing() {
        DeferralLedger<String> ledger = new DeferralLedger<>();
        Reconciliation result = ledger.reconcile(KEY, 100L, CLEAN);
        assertEquals(Outcome.NOT_DEFERRED, result.outcome());
        assertFalse(result.usable());
        assertEquals(0L, result.elapsedTicks());
    }

    @Test
    void anUntouchedSubjectReportsTheTimeItWasSetAsideFor() {
        DeferralLedger<String> ledger = new DeferralLedger<>();
        assertTrue(ledger.defer(KEY, 100L, CLEAN));

        Reconciliation result = ledger.reconcile(KEY, 3100L, CLEAN);
        assertEquals(Outcome.CLEAN, result.outcome());
        assertEquals(3000L, result.elapsedTicks());
    }

    @Test
    void aChangedFingerprintIsNotAccountedFor() {
        DeferralLedger<String> ledger = new DeferralLedger<>();
        ledger.defer(KEY, 100L, CLEAN);

        Reconciliation result = ledger.reconcile(KEY, 3100L, CHANGED);
        assertEquals(Outcome.LOST_TRACK, result.outcome());
        assertFalse(result.usable());
        // Not merely flagged: the elapsed time must not be offered, because applying it is
        // exactly how a plausible but invented history gets written into the world.
        assertEquals(0L, result.elapsedTicks());
    }

    @Test
    void losingTrackOnceStopsTheSubjectBeingDeferredAgain() {
        DeferralLedger<String> ledger = new DeferralLedger<>();
        ledger.defer(KEY, 100L, CLEAN);
        ledger.reconcile(KEY, 3100L, CHANGED);

        assertTrue(ledger.isDistrusted(KEY));
        assertFalse(ledger.mayDefer(KEY));
        assertFalse(ledger.defer(KEY, 4000L, CLEAN),
                "a subject that has demonstrated it is reachable must keep being ticked");
        assertFalse(ledger.isDeferred(KEY));
    }

    @Test
    void distrustIsPerSubjectRatherThanGlobal() {
        DeferralLedger<String> ledger = new DeferralLedger<>();
        ledger.defer(KEY, 100L, CLEAN);
        ledger.reconcile(KEY, 3100L, CHANGED);

        String other = "furnace@0,64,9";
        assertTrue(ledger.mayDefer(other));
        assertTrue(ledger.defer(other, 3100L, CLEAN));
        assertEquals(1, ledger.distrustedCount());
    }

    /**
     * The failure that prompted this class to exist.
     *
     * <p>Catching up advances the subject, so its fingerprint after a clean reconcile is
     * nothing like the one recorded when it was set aside. A ledger that does not re-arm
     * reports the second half of a split window as interference.
     */
    @Test
    void aCleanReconcileRearmsWithTheNewState() {
        DeferralLedger<String> ledger = new DeferralLedger<>();
        ledger.defer(KEY, 100L, CLEAN);

        Reconciliation first = ledger.reconcile(KEY, 1600L, CLEAN);
        assertEquals(Outcome.CLEAN, first.outcome());
        assertEquals(1500L, first.elapsedTicks());

        // The catch-up ran, so the subject now looks different through no fault of anyone's.
        long afterCatchUp = 0x999999L;
        ledger.rearm(KEY, 1600L, afterCatchUp);

        Reconciliation second = ledger.reconcile(KEY, 3100L, afterCatchUp);
        assertEquals(Outcome.CLEAN, second.outcome(),
                "the subject's own progress was reported as somebody else touching it");
        assertEquals(1500L, second.elapsedTicks(),
                "elapsed time must be measured from the last reconcile, not the first deferral");
    }

    @Test
    void rearmingSomethingNotDeferredDoesNotSecretlyDeferIt() {
        DeferralLedger<String> ledger = new DeferralLedger<>();
        ledger.rearm(KEY, 100L, CLEAN);
        assertFalse(ledger.isDeferred(KEY));
        assertEquals(Outcome.NOT_DEFERRED, ledger.reconcile(KEY, 200L, CLEAN).outcome());
    }

    @Test
    void releasingIsNotHeldAgainstTheSubject() {
        DeferralLedger<String> ledger = new DeferralLedger<>();
        ledger.defer(KEY, 100L, CLEAN);
        ledger.release(KEY);

        assertFalse(ledger.isDeferred(KEY));
        assertFalse(ledger.isDistrusted(KEY));
        assertTrue(ledger.defer(KEY, 200L, CLEAN),
                "releasing is the scheduler's own decision, not evidence of interference");
    }
}
