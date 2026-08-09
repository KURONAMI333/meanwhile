package com.kuronami.meanwhile.harness;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One thing whose catch-up is under test: how to build it, how to run it forward the slow
 * way, how to run it forward the fast way, and how to look at the result.
 *
 * <p>{@link DifferentialHarness} drives both arms through this interface, so adding a new
 * tick target to the verification suite means writing one of these and nothing else.
 */
public interface CatchUpSubject {

    String name();

    /** Build the scene. Called once, before any trial. */
    void setup(GameTestHelper helper);

    /**
     * Why the arena cannot support this test, or null when it is fine.
     *
     * <p>Not optional politeness. A crop test run in the dark passes trivially because
     * both arms report zero growth, so a comparison without a precondition check can
     * report success while proving nothing.
     */
    @Nullable
    String precondition(GameTestHelper helper);

    /** Restore the starting state. Called before every arm of every trial. */
    void reset(GameTestHelper helper);

    /** Arm A: actually run {@code ticks} ticks of vanilla logic. */
    void simulate(GameTestHelper helper, int ticks, RandomSource random);

    /**
     * May this subject be left un-ticked at all right now?
     *
     * <p>Asked before the scheduler stops ticking, which is a different moment from asking
     * whether a window can be reconciled afterwards. A furnace with a hopper against it can
     * be reconciled perfectly well; what it cannot do is be left alone, because the hopper
     * will change it and nothing will say so. Answering false means the subject keeps being
     * ticked every tick, so interruptions land at their real time and there is no window to
     * get wrong.
     */
    default boolean canDefer(GameTestHelper helper) {
        return true;
    }

    /**
     * Arm B: skip {@code ticks} ticks and reconcile in one step.
     *
     * @return false when the catch-up declined, because it could not establish that its
     *         preconditions held for the whole window. Declining is a correct outcome, not
     *         an error: the caller falls back to ticking normally. It is what stops a
     *         subject that cannot prove it is safe from guessing, and it is why the safety
     *         property has one shape rather than two — every window either reconciles to
     *         the right answer or is ticked for real.
     */
    boolean catchUp(GameTestHelper helper, int ticks, RandomSource random);

    /**
     * Why the window that just ran did not exercise what this subject claims to test, or
     * null when it did. Checked against the simulated arm.
     *
     * <p>The counterpart to {@link #precondition}: that one catches an arena that cannot
     * work, this one catches a scenario that ran without doing anything. A furnace whose
     * cook time failed to initialise burns fuel for the whole window and completes no
     * smelt, and both arms agree on that perfectly.
     */
    @Nullable
    default String postcondition(GameTestHelper helper) {
        return null;
    }

    /**
     * What something outside sees when it looks at this subject right now, without changing
     * it: a comparator reading a container, a client rendering a lit furnace.
     *
     * <p>A different hazard from every other one here, and one the state comparisons cannot
     * express. Those all ask what the world looks like once the window is over, and a
     * deferred subject can reconcile perfectly at the end while having answered wrongly
     * throughout. A comparator that reads a stale furnace drives its circuit on a number
     * that was never true, and the final state agreeing afterwards does not undo it.
     *
     * <p>Empty when nothing outside can read this subject.
     */
    default double[] externalReads(GameTestHelper helper) {
        return new double[0];
    }

    /** Names for {@link #externalReads}. */
    default String[] externalReadLabels() {
        return new String[0];
    }

    /** Numbers describing the resulting state. Compared across arms in the stochastic mode. */
    double[] observe(GameTestHelper helper);

    /** Names for {@link #observe}, so failures say what diverged. */
    String[] observationLabels();

    /**
     * The ways the outside world can reach in and invalidate this subject's preconditions
     * partway through a window.
     *
     * <p>Declared by the subject rather than by the tests, so that adding a tick target to
     * the suite means stating how it can be interfered with, and the interference cases get
     * exercised without anyone remembering to write them.
     */
    default List<Disturbance> disturbances() {
        return List.of();
    }

    /**
     * The region whose entire state must match bit for bit, or null when the subject is
     * stochastic and only its distribution can be compared.
     */
    @Nullable
    default BoundingBox exactRegion(GameTestHelper helper) {
        return null;
    }
}
