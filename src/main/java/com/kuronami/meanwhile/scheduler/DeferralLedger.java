package com.kuronami.meanwhile.scheduler;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which subjects are currently set aside, since when, and what they looked like at the time.
 *
 * <p>Deliberately free of any Minecraft type. Everything the verification work established
 * about when it is safe to stop ticking something is an invariant about bookkeeping, not
 * about furnaces, so it belongs somewhere it can be reasoned about and tested on its own.
 *
 * <p>Three of those invariants are load-bearing:
 *
 * <ul>
 *   <li><b>The fingerprint is verified, not trusted.</b> Enumerating every route by which
 *       something can reach a deferred subject cannot be finished — containers hand out
 *       live item references and the notification hook mods build on is empty by default —
 *       so the ledger compares state on the way back instead of trying to be told.</li>
 *   <li><b>A clean reconcile re-arms.</b> The subject is still deferred afterwards, and its
 *       fingerprint has moved because it just advanced. Forgetting this makes a window split
 *       in two report the subject's own progress as interference.</li>
 *   <li><b>Losing track is remembered.</b> A mismatch does not say which route was taken,
 *       but it does say this subject is reachable by something the guard did not predict.
 *       That is worth more than the route: the subject stops being deferred, and a gap in
 *       the guard costs one incident per subject instead of one per occurrence.</li>
 * </ul>
 *
 * <p>What none of it does is recover the lost window. The change happened at an unknown
 * moment and the history is gone. The ledger's job is to make sure nothing downstream
 * invents one.
 *
 * @param <K> however the caller identifies a subject, typically a position
 */
public final class DeferralLedger<K> {

    /** What happened when a caller asked to reconcile. */
    public enum Outcome {
        /** Was not deferred; nothing to reconcile and nothing was skipped. */
        NOT_DEFERRED,
        /** Deferred and untouched. The elapsed tick count is usable. */
        CLEAN,
        /**
         * Deferred, and its state no longer matches what was left behind. The elapsed time
         * is not usable: something changed it at an unknown moment. The subject is dropped
         * from the ledger and refused from now on.
         */
        LOST_TRACK
    }

    /** The result of a reconcile request. */
    public record Reconciliation(Outcome outcome, long elapsedTicks) {

        public boolean usable() {
            return outcome == Outcome.CLEAN;
        }
    }

    private record Entry(long sinceTick, long fingerprint) {
    }

    private final Map<K, Entry> deferred = new HashMap<>();
    private final Set<K> distrusted = new HashSet<>();

    /**
     * Whether this subject may be set aside at all.
     *
     * <p>False forever once the ledger has caught it changing behind its back. A subject
     * that has demonstrated it is reachable is reachable, whatever the guard believes.
     */
    public boolean mayDefer(K key) {
        return !distrusted.contains(key);
    }

    /**
     * Stop ticking this subject, recording what it looks like now.
     *
     * @return false when the subject is distrusted and must keep being ticked
     */
    public boolean defer(K key, long now, long fingerprint) {
        if (!mayDefer(key)) {
            return false;
        }
        deferred.put(key, new Entry(now, fingerprint));
        return true;
    }

    public boolean isDeferred(K key) {
        return deferred.containsKey(key);
    }

    /**
     * Bring this subject back up to date, if it can be.
     *
     * <p>Call before anything observes the subject — a tick, a comparator, a chunk save.
     * On {@link Outcome#CLEAN} the caller applies {@code elapsedTicks} of catch-up and the
     * subject stays deferred with a refreshed fingerprint. On {@link Outcome#LOST_TRACK}
     * the caller must not apply anything: the elapsed time cannot be accounted for.
     *
     * @param currentFingerprint the subject's fingerprint right now
     */
    public Reconciliation reconcile(K key, long now, long currentFingerprint) {
        Entry entry = deferred.get(key);
        if (entry == null) {
            return new Reconciliation(Outcome.NOT_DEFERRED, 0L);
        }
        if (entry.fingerprint() != currentFingerprint) {
            deferred.remove(key);
            distrusted.add(key);
            return new Reconciliation(Outcome.LOST_TRACK, 0L);
        }
        return new Reconciliation(Outcome.CLEAN, now - entry.sinceTick());
    }

    /**
     * Records the state a clean reconcile left behind, re-arming the subject.
     *
     * <p>Separate from {@link #reconcile} because the caller only knows the new fingerprint
     * after it has applied the catch-up.
     */
    public void rearm(K key, long now, long fingerprintAfterCatchUp) {
        if (deferred.containsKey(key)) {
            deferred.put(key, new Entry(now, fingerprintAfterCatchUp));
        }
    }

    /** Stop deferring this subject, without holding it against it. */
    public void release(K key) {
        deferred.remove(key);
    }

    public boolean isDistrusted(K key) {
        return distrusted.contains(key);
    }

    public int deferredCount() {
        return deferred.size();
    }

    public int distrustedCount() {
        return distrusted.size();
    }
}
