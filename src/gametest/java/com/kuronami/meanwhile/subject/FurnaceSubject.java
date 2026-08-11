package com.kuronami.meanwhile.subject;

import com.kuronami.meanwhile.catchup.FurnaceCatchUp;
import com.kuronami.meanwhile.harness.CatchUpSubject;
import com.kuronami.meanwhile.harness.Disturbance;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A furnace smelting a stack of raw iron. Deterministic, so the whole block plus its NBT
 * must come out identical, not merely identically distributed.
 *
 * <p>The load is chosen to exercise every regime change in one window: the furnace lights,
 * consumes fuel, completes several smelts, runs out of input while still lit, and finally
 * goes out. A window that only covered steady-state cooking would pass without touching
 * the transitions, which is where a jump-based catch-up would actually break.
 *
 * <p>{@code skipTransitionCheck} breaks the implementation on purpose, collapsing the
 * window in one arithmetic jump without stopping at the events. It exists so the harness
 * can be shown to reject a wrong answer.
 */
public class FurnaceSubject implements CatchUpSubject {

    private static final BlockPos FURNACE = new BlockPos(4, 1, 4);

    private static final int INPUT_COUNT = 8;
    private static final int FUEL_COUNT = 4;

    private final boolean skipTransitionCheck;
    private final boolean hopperAdjacent;
    private int lastRealTicks;
    /** Taken when the furnace is set aside, checked before reconciling. */
    private long deferredFingerprint;
    private boolean fingerprintTaken;
    private boolean lostTrack;

    public FurnaceSubject() {
        this(false);
    }

    public FurnaceSubject(boolean skipTransitionCheck) {
        this(skipTransitionCheck, false);
    }

    /**
     * @param hopperAdjacent places a hopper against the furnace. The hopper is never ticked
     *                       here, so it moves no items; it is only the marker that makes the
     *                       furnace reachable from outside, which is what the catch-up's
     *                       self-check looks for before agreeing to skip anything.
     */
    public FurnaceSubject(boolean skipTransitionCheck, boolean hopperAdjacent) {
        this.skipTransitionCheck = skipTransitionCheck;
        this.hopperAdjacent = hopperAdjacent;
    }

    @Override
    public String name() {
        if (skipTransitionCheck) {
            return "furnace(no-transition-stop)";
        }
        return hopperAdjacent ? "furnace(hopper adjacent)" : "furnace";
    }

    @Override
    public void setup(GameTestHelper helper) {
        helper.setBlock(FURNACE, Blocks.FURNACE);
        helper.setBlock(FURNACE.above(), hopperAdjacent ? Blocks.HOPPER : Blocks.AIR);
    }

    @Override
    @Nullable
    public String precondition(GameTestHelper helper) {
        if (furnace(helper) == null) {
            return "no furnace block entity at " + FURNACE;
        }
        reset(helper);
        AbstractFurnaceBlockEntity furnace = furnace(helper);
        if (furnace == null) {
            return "no furnace block entity at " + FURNACE + " after reset";
        }
        if (furnace.cookingTotalTime <= 0) {
            return "cook time did not initialise, so the furnace can never complete a smelt"
                    + " and both arms would just burn fuel doing nothing";
        }
        return null;
    }

    @Override
    @Nullable
    public String postcondition(GameTestHelper helper) {
        AbstractFurnaceBlockEntity furnace = furnace(helper);
        int output = furnace == null ? 0 : furnace.getItem(2).getCount();
        // At least the starting load must have been smelted. A window that is disturbed by
        // a hopper legitimately produces more, so this is a floor rather than an equality.
        if (output < INPUT_COUNT) {
            return "the window produced " + output + " ingots against a starting load of "
                    + INPUT_COUNT + ", so it did not exercise smelting and the comparison is vacuous";
        }
        return null;
    }

    /**
     * Returns the furnace to a genuinely fresh state without breaking the block.
     *
     * <p>Replacing the block would be the obvious way to get a clean block entity, and is
     * wrong here: vanilla spills a container's contents and pops its stored experience when
     * the block is removed, so both land inside the compared region as entities whose
     * position and motion are randomised and can never match between arms.
     *
     * <p>A furnace also carries more state than its counters and slots. {@code RecipesUsed}
     * tracks completed smelts for experience, and leaving it populated would carry the
     * first arm's eight smelts into the second, which reads as the catch-up inventing work
     * it never did.
     */
    @Override
    public void reset(GameTestHelper helper) {
        AbstractFurnaceBlockEntity furnace = furnace(helper);
        if (furnace == null) {
            return;
        }
        furnace.litTime = 0;
        furnace.litDuration = 0;
        furnace.cookingProgress = 0;
        furnace.cookingTotalTime = 0;
        furnace.recipesUsed.clear();

        furnace.setItem(2, ItemStack.EMPTY);
        furnace.setItem(1, new ItemStack(Items.COAL, FUEL_COUNT));
        // Vanilla only recomputes cookingTotalTime when the input slot receives a *different*
        // item, so refilling with the same raw iron would silently leave it at zero and the
        // furnace would burn its fuel through without ever completing a smelt. Empty first.
        furnace.setItem(0, ItemStack.EMPTY);
        furnace.setItem(0, new ItemStack(Items.RAW_IRON, INPUT_COUNT));

        helper.getLevel().setBlock(helper.absolutePos(FURNACE),
                Blocks.FURNACE.defaultBlockState(), 2);
    }

    /**
     * Takes the furnace out of the world, contents first.
     *
     * <p>For the callers that leave one in a state the game will not resolve. A furnace carried
     * past {@code cookingProgress == cookingTotalTime} never satisfies that equality again, so a
     * lit one left standing counts upward for as long as its fuel lasts, and the fall when it
     * goes out reads as a counter turning over at a value no furnace reaches on its own — which
     * is where the wild {@code CookTime turnsOverAt=9401} sighting came from (GAP_LOG G137 §1).
     *
     * <p>The contents go first because vanilla spills a container when its block is removed, and
     * item entities dropped into an arena outlive the test that made them.
     */
    public void clear(GameTestHelper helper) {
        AbstractFurnaceBlockEntity furnace = furnace(helper);
        if (furnace != null) {
            furnace.clearContent();
        }
        helper.setBlock(FURNACE, Blocks.AIR);
        helper.setBlock(FURNACE.above(), Blocks.AIR);
    }

    @Override
    public void simulate(GameTestHelper helper, int ticks, RandomSource random) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(FURNACE);
        AbstractFurnaceBlockEntity furnace = furnace(helper);
        for (int tick = 0; tick < ticks; tick++) {
            AbstractFurnaceBlockEntity.serverTick(level, pos, level.getBlockState(pos), furnace);
        }
    }

    @Override
    public boolean canDefer(GameTestHelper helper) {
        if (!FurnaceCatchUp.canCatchUp(helper.getLevel(), helper.absolutePos(FURNACE))) {
            return false;
        }
        AbstractFurnaceBlockEntity furnace = furnace(helper);
        if (furnace == null) {
            return false;
        }
        // Deferring is the moment to record what we are leaving behind.
        deferredFingerprint = FurnaceCatchUp.fingerprint(furnace);
        fingerprintTaken = true;
        return true;
    }

    @Override
    public boolean catchUp(GameTestHelper helper, int ticks, RandomSource random) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(FURNACE);
        AbstractFurnaceBlockEntity furnace = furnace(helper);
        if (!FurnaceCatchUp.canCatchUp(level, pos)) {
            lastRealTicks = 0;
            return false;
        }
        if (fingerprintTaken && FurnaceCatchUp.fingerprint(furnace) != deferredFingerprint) {
            // Something reached the furnace while it was set aside. Which route it took does
            // not matter and cannot be recovered; what matters is not inventing a history.
            lastRealTicks = 0;
            lostTrack = true;
            return false;
        }
        if (skipTransitionCheck) {
            // Wrong on purpose: advance the counters across the whole window without ever
            // letting vanilla handle the points where the state machine changes regime.
            furnace.litTime = Math.max(0, furnace.litTime - ticks);
            furnace.cookingProgress += ticks;
            lastRealTicks = 0;
            return true;
        }
        lastRealTicks = FurnaceCatchUp.catchUp(level, pos, furnace, ticks);
        // Reconciling leaves the furnace set aside again, so the fingerprint has to be
        // retaken. Without this, splitting a window into two catch-ups would report the
        // furnace's own progress during the first half as somebody else having touched it.
        if (fingerprintTaken) {
            deferredFingerprint = FurnaceCatchUp.fingerprint(furnace);
        }
        return true;
    }

    /** Whether the last catch-up found the furnace had been changed behind its back. */
    public boolean lostTrack() {
        return lostTrack;
    }

    /** Models the scheduler setting this furnace aside, recording what it is leaving. */
    public void beginDeferral(GameTestHelper helper) {
        lostTrack = false;
        fingerprintTaken = false;
        canDefer(helper);
    }

    /** Ticks the last catch-up actually ran through vanilla, rather than jumped. */
    public int lastRealTicks() {
        return lastRealTicks;
    }

    @Override
    public double[] observe(GameTestHelper helper) {
        AbstractFurnaceBlockEntity furnace = furnace(helper);
        return new double[]{
                furnace.getItem(2).getCount(),
                furnace.getItem(0).getCount(),
                furnace.getItem(1).getCount(),
                furnace.litTime,
                furnace.cookingProgress,
        };
    }

    /**
     * What a comparator against this furnace reads, and whether it looks lit.
     *
     * <p>Both are answered from the block entity's current state, so a furnace that has been
     * left un-ticked answers with whatever it was left holding. The comparator does not care
     * that the state will be reconciled later; it drives its circuit on the number it got.
     */
    @Override
    public double[] externalReads(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(FURNACE);
        BlockState state = level.getBlockState(pos);
        return new double[]{
                state.getAnalogOutputSignal(level, pos),
                state.getValue(AbstractFurnaceBlock.LIT) ? 1.0D : 0.0D,
        };
    }

    @Override
    public String[] externalReadLabels() {
        return new String[]{"comparator", "lit"};
    }

    @Override
    public String[] observationLabels() {
        return new String[]{"output", "input", "fuel", "litTime", "cookingProgress"};
    }

    @Override
    public List<Disturbance> disturbances() {
        return List.of(new HopperRefill());
    }

    /**
     * A hopper tops the furnace back up partway through the window.
     *
     * <p>This is the case that breaks the catch-up's shortcut for a furnace that has gone
     * quiet. Nothing changing in one tick only implies nothing will ever change while the
     * furnace is unreachable from outside, and a hopper is exactly the thing that makes it
     * reachable.
     *
     * <p>Adds to the slots rather than filling them to a fixed level. Setting them would be
     * a no-op when applied at the very start of a window, since that is what a reset leaves
     * behind, and the comparison for an unnotified interruption would degenerate into
     * disturbed against undisturbed. Adding keeps both arms genuinely interrupted so that
     * only the timing differs, which is the thing under test.
     */
    private static final class HopperRefill implements Disturbance {

        @Override
        public String name() {
            return "hopper refill";
        }

        @Override
        public void apply(GameTestHelper helper) {
            AbstractFurnaceBlockEntity furnace = furnace(helper);
            if (furnace == null) {
                return;
            }
            furnace.setItem(1, grownBy(furnace.getItem(1), Items.COAL, FUEL_COUNT));
            furnace.setItem(0, grownBy(furnace.getItem(0), Items.RAW_IRON, INPUT_COUNT));
        }

        private static ItemStack grownBy(ItemStack current, Item item, int amount) {
            return new ItemStack(item, Math.min(64, current.getCount() + amount));
        }
    }

    @Override
    public BoundingBox exactRegion(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(FURNACE);
        return BoundingBox.fromCorners(pos, pos);
    }

    @Nullable
    private static AbstractFurnaceBlockEntity furnace(GameTestHelper helper) {
        return helper.getLevel().getBlockEntity(helper.absolutePos(FURNACE))
                instanceof AbstractFurnaceBlockEntity furnace ? furnace : null;
    }
}
