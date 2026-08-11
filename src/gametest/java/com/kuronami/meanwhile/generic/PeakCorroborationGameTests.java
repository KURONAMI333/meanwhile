package com.kuronami.meanwhile.generic;

import com.kuronami.meanwhile.Meanwhile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * A fall that is not a turnover, and what it is allowed to authorise.
 *
 * <p>{@link GenericCatchUp} learns where a rising counter is heading by watching one turn over.
 * It cannot see a turnover; it sees a fall, and decides. A furnace that has been carried past
 * its {@code cookingTotalTime} produces a fall that is not one: the boundary is written
 * {@code cookingProgress == cookingTotalTime}, so once a jump has stepped over it the counter
 * climbs without limit, and vanilla clamps it back onto that total the instant the fire goes
 * out — {@code Mth.clamp(cookingProgress - 2, 0, cookingTotalTime)}. Read as a turnover, a fall
 * from 9400 to 200 says the counter turns over at 9401.
 *
 * <p>That is not a hypothetical shape. It was measured happening in a standing run, three times
 * in nine, always as {@code from=9400 to=200 rise=1} (G137). What produced it was a furnace one
 * of this suite's own negative controls had carried past its transition and left standing in its
 * arena, which later chunk sweeps then ticked for thousands of ticks until its fuel ran out.
 *
 * <p>Everything below builds that furnace on purpose, so the reading is deterministic rather
 * than three-in-nine, and asks what the table does with it. Vanilla only, so this is registered
 * unconditionally.
 */
public final class PeakCorroborationGameTests {

    private static final BlockPos FURNACE = new BlockPos(3, 1, 3);
    private static final String BATCH = "peakcorroboration";
    /** Real ticks before the body runs, so the placed block has settled. */
    private static final int SETTLE = 5;

    private static final String COOK_TIME = "minecraft:furnace|CookTime";

    /**
     * Where the furnace's counter starts: past {@code CookTimeTotal}, so the {@code ==} boundary
     * is behind it and the counter has nowhere left to turn over. How far past does not matter,
     * and it is set close to where the fall is watched so that the arena is cheap — a test that
     * spends tens of thousands of ticks in one server tick is a load the rest of the suite has
     * to survive.
     */
    private static final int RUNAWAY_FROM = 9000;
    private static final int COOK_TOTAL = 200;
    /**
     * Burn ticks given to the runaway furnace. The counter rises once per tick while the fire is
     * lit and the fire lasts {@code BURN - 1} ticks, so it reaches
     * {@code RUNAWAY_FROM + BURN - 1 = 9400} and is then clamped onto {@code COOK_TOTAL}.
     */
    private static final int BURN = 401;
    /** What the collapse is read as: the last value seen plus the step it was rising by. */
    private static final long BOGUS_PEAK = 9401L;
    /** Ticks of the learning window. Longer than the fall, so the fall is inside it. */
    private static final int LEARN_WINDOW = BURN + 4;

    /**
     * The second window, long enough that a jump aimed at {@link #BOGUS_PEAK} is not cut short
     * by the window running out.
     */
    private static final int SECOND_WINDOW = 600;
    /** Burn ticks in the second window: enough that {@code BurnTime} never bounds the span. */
    private static final int SECOND_BURN = 2000;
    /**
     * How far a jump aimed at {@link #BOGUS_PEAK} travels from the second window's first tick.
     * {@code 9401 - 9001 - 2}: the counter stands at 9001 after that tick, and two ticks of the
     * regime are always held back — one for the boundary, one to check the jump by.
     */
    private static final int SPAN_FROM_BOGUS_PEAK = 398;

    private PeakCorroborationGameTests() {
    }

    // ---- the reading itself ----------------------------------------------------------------

    /**
     * The collapse is recorded, and recorded as what it is: one sighting of a number the counter
     * never reached.
     *
     * <p>Asserting that it is <em>not</em> recorded would be the wrong fix. Nothing here can tell
     * this fall from a turnover at the moment it happens, and refusing to write it down would
     * also refuse the first sighting of every real turnover. What can be said is that one
     * sighting is not evidence.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = BATCH, timeoutTicks = 1200)
    public static void aRunawayCounterIsReadAsATurnoverItNeverReached(GameTestHelper helper) {
        helper.startSequence().thenExecuteAfter(SETTLE, () -> {
            ServerLevel level = helper.getLevel();
            BlockPos pos = helper.absolutePos(FURNACE);

            String blocked = learnTheBogusPeak(helper, level, pos);
            if (blocked != null) {
                helper.fail(blocked);
                return;
            }

            Map<Long, Integer> seen = GenericCatchUp.observations().get(COOK_TIME);
            Long authorising = GenericCatchUp.peaks().get(COOK_TIME);
            Meanwhile.LOGGER.info("[peaks] LEARN | observations={} authorises={}",
                    seen, authorising);

            if (seen == null || !Integer.valueOf(1).equals(seen.get(BOGUS_PEAK))) {
                helper.fail("the runaway collapse was not read as a turnover at " + BOGUS_PEAK
                        + ", so this test is no longer measuring the thing it was written for:"
                        + " observations=" + seen);
                return;
            }
            if (authorising != null) {
                helper.fail("one sighting of " + BOGUS_PEAK + " is authorising a jump at "
                        + authorising + ", which is the hole this closes");
                return;
            }
            clear(level, pos);
            helper.succeed();
        }).thenSucceed();
    }

    /**
     * With the sighting standing alone, no jump is aimed at it. The counter costs real ticks,
     * which is what a counter nothing is known about already costs.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = BATCH, timeoutTicks = 1200)
    public static void aSingleSightingAuthorisesNoJump(GameTestHelper helper) {
        helper.startSequence().thenExecuteAfter(SETTLE, () -> {
            ServerLevel level = helper.getLevel();
            BlockPos pos = helper.absolutePos(FURNACE);

            String blocked = learnTheBogusPeak(helper, level, pos);
            if (blocked != null) {
                helper.fail(blocked);
                return;
            }

            GenericCatchUp.Result result = secondWindow(helper, level, pos);
            Meanwhile.LOGGER.info("[peaks] GUARDED | {} | cookTime={} observations={}",
                    result, cookTime(level, pos), GenericCatchUp.observations().get(COOK_TIME));

            if (result.declined()) {
                helper.fail("the catch-up declined, so nothing was measured: "
                        + result.declineReason());
                return;
            }
            if (result.jumps() != 0) {
                helper.fail("a jump was authorised by a single sighting of " + BOGUS_PEAK + ": "
                        + result);
                return;
            }
            if (result.realTicks() != SECOND_WINDOW) {
                helper.fail("the window was not run in full: realTicks=" + result.realTicks()
                        + " of " + SECOND_WINDOW);
                return;
            }
            if (!result.refusals().containsKey("peak-not-seen")) {
                helper.fail("the window was run in full for some other reason than the counter"
                        + " having nothing established to aim at: " + result.refusals());
                return;
            }
            clear(level, pos);
            helper.succeed();
        }).thenSucceed();
    }

    /**
     * The negative control: the same single sighting, with the corroboration requirement taken
     * away. A jump has to happen, and it has to be {@link #BOGUS_PEAK} that sets how far it goes.
     *
     * <p>Asserting the distance rather than merely {@code jumps > 0} is what makes this a control
     * of this rule and not of jumping in general: {@code 6593} is arithmetic on 9401 and nothing
     * else in the arena produces it.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = BATCH, timeoutTicks = 1200)
    public static void withoutCorroborationASingleSightingAuthorisesAJump(GameTestHelper helper) {
        helper.startSequence().thenExecuteAfter(SETTLE, () -> {
            ServerLevel level = helper.getLevel();
            BlockPos pos = helper.absolutePos(FURNACE);

            GenericCatchUp.Result result;
            String blocked;
            // Global, and every other test in this run shares it. Both the flip and the restore
            // are inside this one body, which runs inside a single server tick, so no other
            // test can be between them.
            GenericCatchUp.setPeakCorroboration(false);
            try {
                blocked = learnTheBogusPeak(helper, level, pos);
                result = blocked == null ? secondWindow(helper, level, pos) : null;
            } finally {
                GenericCatchUp.setPeakCorroboration(true);
            }

            if (blocked != null) {
                helper.fail(blocked);
                return;
            }
            Meanwhile.LOGGER.info("[peaks] NEGATIVE CONTROL | {} | cookTime={}",
                    result, cookTime(level, pos));

            if (result.jumps() == 0) {
                helper.fail("with the corroboration requirement removed the single sighting of "
                        + BOGUS_PEAK + " still authorised nothing, so the guarded arm is not"
                        + " evidence of anything: " + result);
                return;
            }
            if (result.jumpedTicks() < SPAN_FROM_BOGUS_PEAK) {
                helper.fail("a jump happened but did not travel the distance " + BOGUS_PEAK
                        + " allows (" + SPAN_FROM_BOGUS_PEAK + "), so it was aimed at something"
                        + " else and this does not control the rule under test: " + result);
                return;
            }
            clear(level, pos);
            helper.succeed();
        }).thenSucceed();
    }

    // ---- the arena ---------------------------------------------------------------------------

    /**
     * Builds the runaway furnace and spends a window on it, so that the collapse is watched by a
     * catch-up rather than by the game.
     *
     * @return why it could not be measured, or null
     */
    @Nullable
    private static String learnTheBogusPeak(GameTestHelper helper, ServerLevel level,
                                            BlockPos pos) {
        GenericCatchUp.forgetPeaks();
        if (!arm(helper, level, pos, RUNAWAY_FROM, BURN)) {
            return "no furnace block entity at " + FURNACE;
        }
        GenericCatchUp.Result learning =
                GenericCatchUp.catchUp(level, pos, LEARN_WINDOW, GenericCatchUp.Mode.SAFE);
        if (learning.declined()) {
            return "the learning window declined: " + learning.declineReason();
        }
        if (cookTime(level, pos) > RUNAWAY_FROM) {
            return "the fire did not go out inside the learning window, so no fall was watched:"
                    + " cookTime=" + cookTime(level, pos) + " " + learning;
        }
        return null;
    }

    /** A fresh runaway furnace, and a window long enough for a jump to show. */
    private static GenericCatchUp.Result secondWindow(GameTestHelper helper, ServerLevel level,
                                                      BlockPos pos) {
        arm(helper, level, pos, RUNAWAY_FROM, SECOND_BURN);
        return GenericCatchUp.catchUp(level, pos, SECOND_WINDOW, GenericCatchUp.Mode.SAFE);
    }

    /**
     * A lit furnace whose progress counter is already past the total it is compared against.
     *
     * <p>The block is emptied before it is replaced: taking a container out of the world spills
     * its contents as entities, and this arena is walked by chunk sweeps belonging to other
     * tests.
     *
     * <p>No fuel, so the fire goes out after exactly {@code burn - 1} counting ticks rather than
     * relighting. The counter is set after the items, because vanilla recomputes
     * {@code cookingTotalTime} whenever the input slot receives something different.
     */
    private static boolean arm(GameTestHelper helper, ServerLevel level, BlockPos pos, int from,
                               int burn) {
        clear(level, pos);
        BlockState lit = Blocks.FURNACE.defaultBlockState()
                .setValue(AbstractFurnaceBlock.LIT, true);
        level.setBlock(pos, lit, 3);
        if (!(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
            return false;
        }
        furnace.setItem(0, new ItemStack(Items.RAW_IRON, 64));
        furnace.setItem(1, ItemStack.EMPTY);
        furnace.setItem(2, ItemStack.EMPTY);
        furnace.recipesUsed.clear();
        furnace.litTime = burn;
        furnace.litDuration = burn;
        furnace.cookingTotalTime = COOK_TOTAL;
        furnace.cookingProgress = from;
        furnace.setChanged();
        return true;
    }

    /** Takes the furnace out without spilling it. */
    private static void clear(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace) {
            furnace.clearContent();
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }

    private static int cookTime(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace
                ? furnace.cookingProgress : -1;
    }
}
