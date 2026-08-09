package com.kuronami.meanwhile.elapsed;

import com.kuronami.meanwhile.Meanwhile;
import com.kuronami.meanwhile.generic.GenericCatchUp;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * How much of a burning furnace's window the type-agnostic catch-up can skip.
 *
 * <p>A furnace used to cost a real tick for every tick of the window. Its progress counter goes
 * up, and a rising counter was refused outright because the number it was heading for was taken
 * to be invisible. It is not invisible: the furnace writes {@code CookTimeTotal} beside
 * {@code CookTime} in the same compound, and {@code BurnTime} counts down to a floor of zero.
 * Neither of those facts is about furnaces — one is "a counter going down is heading for zero",
 * the other is "a counter going up may be heading for a number sitting next to it" — and
 * {@link GenericCatchUp} is told neither what a furnace is nor what those tags are called.
 *
 * <p>The measurement is deliberately not "did it jump". A catch-up that jumps once and then
 * ticks the rest is not usefully different from one that never jumps, so what is reported is
 * <b>real ticks spent per 1000 ticks of window</b>, which is the number that decides whether a
 * chunk full of machines can be caught up inside one server tick.
 *
 * <p>Correctness is the same requirement as everywhere else here: the caught-up furnace and one
 * that was ticked the whole way have to be the same, tag for tag. A faster wrong answer is not
 * an improvement.
 *
 * <p>No Create. This is vanilla, and it is registered from {@link Meanwhile} on the catch-up
 * marker alone. No {@code @GameTestHolder}, so the standing suite is unchanged without it.
 */
public final class FurnaceSpanGameTests {

    private static final BlockPos FURNACE = new BlockPos(3, 1, 3);

    /** Long enough to cross several smelts at 200 ticks each. */
    private static final int GAP = 3000;
    /** Real ticks the furnace is given to light before anything is recorded. */
    private static final int SETTLE = 5;

    private static final int INPUT_COUNT = 64;
    private static final int FUEL_COUNT = 64;

    private static final String BATCH = "furnacespan";

    private FurnaceSpanGameTests() {
    }

    /**
     * The window spent by catching up, against the same window ticked in full.
     *
     * <p>Both arms start from one recorded tag. A vanilla furnace reads back everything it
     * writes, so unlike a Create kinetic block entity it can be put back where it was.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = BATCH, timeoutTicks = 1200)
    public static void burningFurnaceIsCaughtUpAndMatchesTicking(GameTestHelper helper) {
        measure(helper, GenericCatchUp.Mode.SAFE, Expect.SKIPS);
    }

    /**
     * A ceiling put far past the real one, so that a jump lands somewhere the machine never was.
     *
     * <p>This is the failure the write-back check cannot see: the tag a jump produces is exactly
     * what the arithmetic intended, self-consistent, and wrong. The only evidence is behavioural,
     * and it arrives on the next tick — a jump is allowed to travel within one regime and the
     * span leaves a tick of that regime over, so a tick that moves different counters means the
     * regime ended earlier than the ceiling said.
     *
     * <p>What is asserted is that the check fires. The state is not asserted to be right: by the
     * time the evidence exists the overshooting jump has already been written, and nothing here
     * puts it back.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = BATCH, timeoutTicks = 1200)
    public static void anInflatedCeilingIsCaughtByTheTickAfterTheJump(GameTestHelper helper) {
        measure(helper, GenericCatchUp.Mode.INFLATED_CEILING, Expect.OVERSHOOTS);
    }

    /** What a run is being asked to show. */
    private enum Expect { SKIPS, RUNS_EVERY_TICK, OVERSHOOTS }

    /**
     * The same, with the ceiling rule taken away.
     *
     * <p>This is the behaviour the furnace had before: every rise refused, every tick of the
     * window run for real. It has to be measurably worse, or the rule it removes is doing
     * nothing and the improvement reported by its pair belongs to something else.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = BATCH, timeoutTicks = 1200)
    public static void withoutTheCeilingRuleTheFurnaceCannotBeSkipped(GameTestHelper helper) {
        measure(helper, GenericCatchUp.Mode.NO_CEILING, Expect.RUNS_EVERY_TICK);
    }

    private static void measure(GameTestHelper helper, GenericCatchUp.Mode mode, Expect expect) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(FURNACE);
        helper.setBlock(FURNACE, Blocks.FURNACE);
        load(helper);

        helper.startSequence()
                .thenExecuteAfter(SETTLE, () -> {
                    HolderLookup.Provider registries = level.registryAccess();
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity == null) {
                        helper.fail("no furnace block entity at " + FURNACE);
                        return;
                    }
                    CompoundTag start = blockEntity.saveWithoutMetadata(registries);
                    BlockState startState = level.getBlockState(pos);
                    Meanwhile.LOGGER.info("[furnace] start | mode={} gap={} state={} tag={}",
                            mode.label(), GAP, startState, start);
                    if (start.getInt("BurnTime") <= 0) {
                        helper.fail("the furnace never lit, so there is no window to skip: "
                                + start);
                        return;
                    }

                    // Ticked in full. Through the same restore the other arm gets, not from the
                    // live block entity: an arm that was rebuilt and one that was not are two
                    // different machines, and the difference would be charged to the catch-up.
                    restore(level, pos, start, startState, registries);
                    int ranTicked = GenericCatchUp.tick(level, pos, GAP);
                    CompoundTag ticked = level.getBlockEntity(pos)
                            .saveWithoutMetadata(registries);
                    BlockState tickedState = level.getBlockState(pos);

                    // Caught up, from the same place, knowing nothing about this type yet: the
                    // first furnace in a world has to watch its counter turn over before it can
                    // jump over one.
                    GenericCatchUp.forgetPeaks();
                    restore(level, pos, start, startState, registries);
                    GenericCatchUp.Result result = GenericCatchUp.catchUp(level, pos, GAP, mode);
                    CompoundTag caught = level.getBlockEntity(pos)
                            .saveWithoutMetadata(registries);
                    BlockState caughtState = level.getBlockState(pos);

                    // And again with the table warm, which is every furnace after the first.
                    restore(level, pos, start, startState, registries);
                    GenericCatchUp.Result warm = GenericCatchUp.catchUp(level, pos, GAP, mode);
                    CompoundTag warmTag = level.getBlockEntity(pos)
                            .saveWithoutMetadata(registries);
                    Meanwhile.LOGGER.info("[furnace] WARM mode={} gap={} | realTicks={}"
                                    + " realTicksPer1000={} jumps={} jumpedTicks={} overshot={}"
                                    + " matchesTicked={} peaks={}",
                            mode.label(), GAP, warm.realTicks(),
                            String.format("%.1f", warm.realTicks() * 1000.0 / GAP), warm.jumps(),
                            warm.jumpedTicks(), warm.overshot(), warmTag.equals(ticked),
                            GenericCatchUp.peaks());

                    double perThousand = result.realTicks() * 1000.0 / GAP;
                    Meanwhile.LOGGER.info("[furnace] RESULT mode={} gap={} | realTicks={}"
                                    + " realTicksPer1000={} jumps={} jumpedTicks={}"
                                    + " overshot={} declined={} refusals={} first=[{}]",
                            mode.label(), GAP, result.realTicks(),
                            String.format("%.1f", perThousand), result.jumps(),
                            result.jumpedTicks(), result.overshot(), result.declined(),
                            result.refusals(), result.firstRefusal());
                    Meanwhile.LOGGER.info("[furnace] STATE mode={} | ticked({} ran)={} {}",
                            mode.label(), ranTicked, tickedState, ticked);
                    Meanwhile.LOGGER.info("[furnace] STATE mode={} | caught-up={} {}",
                            mode.label(), caughtState, caught);

                    if (expect == Expect.OVERSHOOTS) {
                        if (!result.overshot()) {
                            helper.fail("a ceiling put far past the real one was not caught by"
                                    + " the tick after the jump: " + result);
                            return;
                        }
                        Meanwhile.LOGGER.info("[furnace] OVERSHOOT DETECTED | {} | ticked={}"
                                        + " caught-up={}",
                                result.firstRefusal(), ticked, caught);
                        helper.succeed();
                        return;
                    }
                    if (!tickedState.equals(caughtState)) {
                        helper.fail("the block state differs: ticked=" + tickedState
                                + " caught-up=" + caughtState);
                        return;
                    }
                    if (!ticked.equals(caught)) {
                        helper.fail("the caught-up furnace is not the ticked one: ticked="
                                + ticked + " caught-up=" + caught);
                        return;
                    }
                    if (result.overshot()) {
                        helper.fail("a jump was found to have left its regime: "
                                + result.firstRefusal());
                        return;
                    }
                    if (ticked.getInt("BurnTime") <= 0) {
                        helper.fail("the fuel ran out inside the window, so the two arms agree"
                                + " about a furnace that spent most of it switched off");
                        return;
                    }
                    if (expect == Expect.SKIPS) {
                        if (result.jumps() == 0) {
                            helper.fail("nothing was skipped: " + result);
                            return;
                        }
                        if (result.realTicks() >= GAP) {
                            helper.fail("every tick of the window was run for real, so the"
                                    + " window was not skipped at all: realTicks="
                                    + result.realTicks());
                            return;
                        }
                    } else if (expect == Expect.RUNS_EVERY_TICK && result.realTicks() < GAP) {
                        helper.fail("the window was skipped even with the ceiling rule removed,"
                                + " so the saving cannot be attributed to it: realTicks="
                                + result.realTicks() + " of " + GAP);
                        return;
                    }
                    helper.succeed();
                })
                .thenSucceed();
    }

    /**
     * Both arms start here. A vanilla furnace reads back everything it writes.
     *
     * <p>The block is only replaced when its state has actually moved. Replacing it regardless
     * destroys the block entity and builds a new one, which spills the furnace's contents into
     * the world as items and hands back a machine that is not the one that was recorded.
     */
    private static void restore(ServerLevel level, BlockPos pos, CompoundTag tag, BlockState state,
                                HolderLookup.Provider registries) {
        if (!level.getBlockState(pos).equals(state)) {
            level.setBlock(pos, state, 3);
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return;
        }
        // Emptied by hand first. AbstractFurnaceBlockEntity#loadAdditional puts the recipe tally
        // it reads on top of whatever is already there rather than replacing it, so restoring a
        // tag whose RecipesUsed is empty leaves the previous arm's tally in place and the second
        // arm finishes with both. Nothing in the catch-up is involved; this is the scaffolding.
        if (blockEntity instanceof AbstractFurnaceBlockEntity furnace) {
            furnace.recipesUsed.clear();
        }
        blockEntity.loadWithComponents(tag.copy(), registries);
        blockEntity.setChanged();
    }

    private static void load(GameTestHelper helper) {
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
        furnace.setItem(0, new ItemStack(Items.RAW_IRON, INPUT_COUNT));
    }

    @Nullable
    private static AbstractFurnaceBlockEntity furnace(GameTestHelper helper) {
        return helper.getLevel().getBlockEntity(helper.absolutePos(FURNACE))
                instanceof AbstractFurnaceBlockEntity found ? found : null;
    }
}
