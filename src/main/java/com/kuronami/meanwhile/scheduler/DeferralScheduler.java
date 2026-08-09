package com.kuronami.meanwhile.scheduler;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides, at the point the game is about to tick a block entity, whether to let it.
 *
 * <p>One per {@link ServerLevel}, holding one {@link DeferralLedger}. Deliberately not
 * persisted: on restart the ledger is empty, every target is ticked normally, and nothing
 * has to be trusted across a session boundary. Losing a deferral costs the ticks it would
 * have saved and nothing else, so the safe direction is also the cheap one.
 *
 * <p>The policy at this stage is as small as it can be: a registered target that says it is
 * unreachable stops being ticked. No time budget, no distance, no heuristics about how busy
 * the server is. Those are decisions that need measurements this stage does not have yet,
 * and adding them before the measurements would make the first numbers uninterpretable.
 */
public final class DeferralScheduler {

    /** What a reconcile request did. */
    public enum Result {
        /** The position was not deferred; nothing was skipped and nothing was applied. */
        NOT_DEFERRED,
        /** The window was clean and has been applied. The target is still deferred. */
        CAUGHT_UP,
        /**
         * Something reached the target while it was set aside. The elapsed time cannot be
         * accounted for, so nothing was applied. The target will not be deferred again.
         */
        LOST_TRACK,
        /** The target refused to account for the window and goes back to being ticked. */
        DECLINED
    }

    /**
     * @param elapsedTicks how many ticks the target had been left alone for, which for a
     *                     {@link Result#CAUGHT_UP} is the number of ticker calls skipped
     * @param realTicks    how many ticks the catch-up actually ran, which is what it cost
     */
    public record Reconcile(Result result, long elapsedTicks, int realTicks) {
    }

    /**
     * Off by default. The loaded-chunk scheme this flag gates — deferring a target's tick
     * while its chunk stays loaded, reconciling it at one of three surfaces
     * ({@code LevelChunk#setBlockState}, {@code BlockBehaviour$BlockStateBase#getAnalogOutputSignal},
     * {@code LevelChunk#getBlockEntityNbtForSaving}) — is not wired into the running mod;
     * {@code meanwhile-loaded.mixins.json} carries the hooks that would act on it and is not
     * referenced from {@code neoforge.mods.toml}. A batch that wants to exercise the scheme
     * (its own GameTests, {@link com.kuronami.meanwhile.CatchUpPrimitiveBatch}) turns this on
     * for its own duration and restores it afterwards.
     */
    private static volatile boolean enabled = false;

    /**
     * Whether a catch-up is running right now, on the thread that runs them.
     *
     * <p>A catch-up ticks its subject for real, and a real tick writes to the world: a
     * furnace crossing the lit boundary calls {@code Level#setBlock} on its own position
     * ({@code AbstractFurnaceBlockEntity#serverTick}, the {@code flag != isLit()} branch).
     * A hook that reconciles before a write therefore re-enters itself while its own
     * catch-up is halfway through, and at that moment the ledger still holds the fingerprint
     * from before the catch-up started. The nested reconcile compares the subject's own
     * progress against it, calls that interference, and distrusts the position for the rest
     * of the session. Nothing else in the world is different afterwards, so the damage is
     * invisible to any test that only checks the subject came out right.
     *
     * <p>Set by {@link #reconcileIfDeferred} around the catch-up rather than by each hook
     * around its own call, because a hook has to stand aside for catch-ups it did not start:
     * the write hook was tripped by a catch-up a test kicked off directly, and the read and
     * save hooks will be in the same position with respect to each other.
     *
     * <p>Plain rather than volatile because writes to a level happen on its server thread,
     * which is the same thread the catch-up runs on. Read through {@link #isReconciling()}.
     */
    private static boolean reconciling = false;

    private final DeferralLedger<BlockPos> ledger = new DeferralLedger<>();

    /**
     * Positions somebody has a screen open on, and who has it open.
     *
     * <p>Held here rather than asked of the target, because whether something is being
     * watched has nothing to do with what kind of machine it is. A target that had to
     * remember to check would be one enumeration away from the failure this project keeps
     * finding: the next target somebody adds is the one that forgets.
     *
     * <p>Watchers are kept rather than a count so a stale one can be dropped on the way past
     * — {@link #isObserved} prunes players who have gone or closed their screen by some route
     * nobody hooked. That costs a position its optimisation for as long as the stale entry
     * lasts and costs correctness nothing, which is the direction to be wrong in.
     *
     * <p>Empty in almost every world at almost every moment, which is what makes the check on
     * the dispatch path a reference comparison.
     */
    private final Map<BlockPos, List<ServerPlayer>> observers = new HashMap<>();

    /**
     * How many windows this level has actually folded up, and how big they were.
     *
     * <p>Only counted when a catch-up really ran, so a reconcile asked for at the moment the
     * target was already up to date does not register. Both halves of what the hooks have to
     * prove need this. That a hook fired at all is otherwise invisible from a test — the
     * subject coming out right is equally consistent with the hook never having been reached,
     * which for a hook placed by hand on a geometry the test also builds is the likely way to
     * be wrong. And a hook that reconciles everything on every read passes every safety
     * comparison while giving back all the ticks it saved; measuring that requires knowing
     * which windows stayed open.
     *
     * <p>Read as a delta bracketing the observation, never as an absolute: tests within a
     * GameTest batch run against one level at the same time.
     */
    private int caughtUpWindows;
    private long caughtUpElapsedTicks;
    private int caughtUpRealTicks;

    private DeferralScheduler() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Whether a catch-up is already running, in which case a hook must not start another. */
    public static boolean isReconciling() {
        return reconciling;
    }

    /**
     * Claim the right to run a catch-up.
     *
     * <p>Private: the claim is taken by {@link #reconcileIfDeferred} around the only thing
     * that runs one. A hook that took its own claim would be standing aside for catch-ups it
     * started and walking into catch-ups it did not, which is the failure this exists for.
     *
     * @return false when one is already running, in which case the caller must not reconcile
     *         and must not call {@link #endReconcile()}
     */
    private static boolean beginReconcile() {
        if (reconciling) {
            return false;
        }
        reconciling = true;
        return true;
    }

    /** Release the claim taken by {@link #beginReconcile()}. Belongs in a finally block. */
    private static void endReconcile() {
        reconciling = false;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /**
     * For the {@code ServerLevel} mixin that holds one of these per level, and for nothing
     * else. Every other caller goes through {@link #of}.
     */
    public static DeferralScheduler create() {
        return new DeferralScheduler();
    }

    /**
     * The scheduler this level carries.
     *
     * <p>A field read through the interface the level's mixin implements. The dispatch calls
     * this for every ticking block entity on every tick, before it knows whether that block
     * entity is one the scheduler manages, so this is the cost every machine in the world
     * pays for the mod being installed at all.
     */
    public static DeferralScheduler of(ServerLevel level) {
        return ((SchedulerHolder) level).meanwhile$scheduler();
    }

    /**
     * Whether the game should skip this block entity's tick.
     *
     * <p>Called from the dispatch itself, so it runs for every ticking block entity in the
     * world and has to stay cheap for the ones it does not manage: an unregistered type
     * costs one identity map lookup and nothing else.
     *
     * <p>An already-deferred target is skipped without re-asking whether it is still
     * unreachable. Re-asking every tick would cost six block lookups per target per tick,
     * which is most of what deferring was meant to save. Something becoming reachable
     * partway through a window is the job of the write hook.
     */
    public boolean shouldSkipTick(ServerLevel level, BlockPos pos, BlockEntity blockEntity) {
        if (!enabled || level.isClientSide) {
            return false;
        }
        CatchUpTarget target = TargetRegistry.lookup(blockEntity.getType());
        if (target == null) {
            return false;
        }

        BlockPos key = pos.immutable();
        // Before anything else about this position, because a window that opens under a
        // watcher is a screen that stops updating rather than a read that comes back stale.
        if (isObserved(key)) {
            return false;
        }
        if (ledger.isDeferred(key)) {
            return true;
        }
        if (!ledger.mayDefer(key) || !target.canDefer(level, key)) {
            return false;
        }
        return ledger.defer(key, level.getGameTime(), target.fingerprint(level, key));
    }

    /**
     * Bring a deferred target up to date, if it is deferred and if it can be.
     *
     * <p>The entry point for everything that observes a target from outside the tick loop:
     * the write, comparator read and chunk save hooks all arrive here, as does the test
     * suite.
     */
    public Reconcile reconcileIfDeferred(ServerLevel level, BlockPos pos) {
        BlockPos key = pos.immutable();
        if (!ledger.isDeferred(key)) {
            return new Reconcile(Result.NOT_DEFERRED, 0L, 0);
        }

        BlockEntity blockEntity = level.getBlockEntity(key);
        CatchUpTarget target = blockEntity == null ? null : TargetRegistry.lookup(blockEntity.getType());
        if (target == null) {
            // Whatever was here is gone or is no longer something we know how to account for.
            // The window belongs to a subject that no longer exists, so there is nothing to
            // apply it to and nothing to hold against the position.
            ledger.release(key);
            return new Reconcile(Result.NOT_DEFERRED, 0L, 0);
        }

        long now = level.getGameTime();
        DeferralLedger.Reconciliation reconciliation =
                ledger.reconcile(key, now, target.fingerprint(level, key));

        switch (reconciliation.outcome()) {
            case NOT_DEFERRED -> {
                return new Reconcile(Result.NOT_DEFERRED, 0L, 0);
            }
            case LOST_TRACK -> {
                return new Reconcile(Result.LOST_TRACK, 0L, 0);
            }
            default -> {
            }
        }

        long elapsed = reconciliation.elapsedTicks();
        if (elapsed <= 0L) {
            return new Reconcile(Result.CAUGHT_UP, 0L, 0);
        }

        int ticks = (int) Math.min(Integer.MAX_VALUE, elapsed);
        // Marked for the duration of the catch-up, because a catch-up writes to the world and
        // the hooks that watch for writes must not treat those as somebody reaching the
        // target. See the note on the flag. Nothing about a non-reentrant call changes: the
        // flag is false on entry and false again on exit.
        int realTicks;
        boolean claimed = beginReconcile();
        try {
            realTicks = target.catchUp(level, key, ticks);
        } finally {
            if (claimed) {
                endReconcile();
            }
        }
        if (realTicks < 0) {
            ledger.release(key);
            return new Reconcile(Result.DECLINED, elapsed, 0);
        }

        // The target is still set aside, and it has moved under its own power, so the
        // fingerprint has to be retaken. Without this a window split in two reports the
        // target's own progress during the first half as somebody having touched it.
        ledger.rearm(key, now, target.fingerprint(level, key));
        caughtUpWindows++;
        caughtUpElapsedTicks += elapsed;
        caughtUpRealTicks += realTicks;
        return new Reconcile(Result.CAUGHT_UP, elapsed, realTicks);
    }

    // ---- observation --------------------------------------------------------------------

    /**
     * Somebody has opened a screen on this position.
     *
     * <p>Settles the window on the spot and stops deferring, in that order. Settling first is
     * what makes the first frame right; stopping is what makes every frame after it right,
     * and only the second is something a hook on the read could not have done.
     *
     * <p>Releasing rather than leaving it deferred, because the dispatch consults
     * {@link #isObserved} before it consults the ledger and would otherwise hold a window
     * open that nothing is going to close. Released without prejudice: nothing has been
     * caught reaching the subject, so it may be deferred again once the screen goes.
     */
    public void beginObserving(ServerLevel level, BlockPos pos, ServerPlayer watcher) {
        BlockPos key = pos.immutable();
        observers.computeIfAbsent(key, ignored -> new ArrayList<>(1)).add(watcher);
        if (ledger.isDeferred(key)) {
            reconcileIfDeferred(level, key);
            ledger.release(key);
        }
    }

    /** That screen has gone. Nothing else changes: the next dispatch decides afresh. */
    public void endObserving(BlockPos pos, ServerPlayer watcher) {
        BlockPos key = pos.immutable();
        List<ServerPlayer> watchers = observers.get(key);
        if (watchers == null) {
            return;
        }
        watchers.remove(watcher);
        if (watchers.isEmpty()) {
            observers.remove(key);
        }
    }

    /**
     * Whether anything is watching this position right now.
     *
     * <p>Prunes as it goes, so a watcher that left by a route nothing hooked stops counting
     * the first time the position is asked about. {@code hasContainerOpen} is false once the
     * server has closed the screen for any reason, including the player walking out of the
     * four blocks a container stays valid within.
     */
    public boolean isObserved(BlockPos pos) {
        if (observers.isEmpty()) {
            return false;
        }
        BlockPos key = pos.immutable();
        List<ServerPlayer> watchers = observers.get(key);
        if (watchers == null) {
            return false;
        }
        watchers.removeIf(watcher -> watcher.isRemoved() || !watcher.hasContainerOpen());
        if (watchers.isEmpty()) {
            observers.remove(key);
            return false;
        }
        return true;
    }

    /** Windows folded up on this level since it was created. See the field. */
    public int caughtUpWindows() {
        return caughtUpWindows;
    }

    /** Ticks those windows spanned, which is the saving that had to be accounted for. */
    public long caughtUpElapsedTicks() {
        return caughtUpElapsedTicks;
    }

    /** Ticks actually run to account for them, which is what the saving cost. */
    public int caughtUpRealTicks() {
        return caughtUpRealTicks;
    }

    public boolean isDeferred(BlockPos pos) {
        return ledger.isDeferred(pos.immutable());
    }

    public boolean isDistrusted(BlockPos pos) {
        return ledger.isDistrusted(pos.immutable());
    }

    public int deferredCount() {
        return ledger.deferredCount();
    }
}
