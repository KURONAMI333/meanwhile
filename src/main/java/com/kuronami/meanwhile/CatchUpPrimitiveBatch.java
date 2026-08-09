package com.kuronami.meanwhile;

import com.kuronami.meanwhile.scheduler.DeferralScheduler;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * The batch for measurements about the catch-up itself rather than about the scheduler.
 *
 * <p>One test lives here: {@code HarnessGameTests#catchUpLeavesTheFurnaceTickableByTheGame},
 * which asks whether a furnace the catch-up jumped forward is still something the game's
 * per-chunk dispatch will carry on ticking. That question is about the primitive, and the
 * answer has to hold whether or not anything is deferring furnaces at the time.
 *
 * <p>The scheduler is off for the batch because with it on the question changes meaning. Its
 * furnace has nothing against it, so the scheduler stops ticking it — correctly, that being
 * the entire mechanism — and the test then reads as the mod failing. The assertion is
 * untouched; what changed is the condition it is asked under.
 *
 * <p>The batch is the isolation rather than a flag flipped inside the test, because tests in
 * a batch run beside each other in the same level and a static flipped mid-batch would land
 * on all of them. The framework restores it here even when the test times out.
 *
 * <p>The property this leaves uncovered on the scheduler's side — that a subject the ledger
 * has stopped trusting goes back to being ticked for real — is measured by
 * {@code SchedulerGameTests#distrustedFurnaceGoesBackToBeingTickedByTheGame}.
 */
@GameTestHolder(Meanwhile.MODID)
public final class CatchUpPrimitiveBatch {

    static final String BATCH = "catchupprimitive";

    @BeforeBatch(batch = BATCH)
    public static void beginBatch(ServerLevel level) {
        DeferralScheduler.setEnabled(false);
        Meanwhile.LOGGER.info("[scheduler] primitive batch begin | enabled={}",
                DeferralScheduler.isEnabled());
    }

    @AfterBatch(batch = BATCH)
    public static void endBatch(ServerLevel level) {
        DeferralScheduler.setEnabled(false);
        Meanwhile.LOGGER.info("[scheduler] primitive batch end | enabled={}",
                DeferralScheduler.isEnabled());
    }

    private CatchUpPrimitiveBatch() {
    }
}
