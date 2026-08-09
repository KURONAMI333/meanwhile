package com.kuronami.meanwhile.catchup;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

/**
 * Catch-up for {@link AbstractFurnaceBlockEntity}.
 *
 * <p>A furnace is deterministic, so unlike a crop its skipped result must be identical to
 * the ticked one, not merely identically distributed.
 *
 * <p>Rather than deriving a formula for the fuel and cooking interleave, this runs the
 * real vanilla tick at every point where the state machine does something, and jumps the
 * stretches in between where the tick provably only moves two counters by one. Both
 * properties are established by observation, not by reasoning about vanilla's branches:
 * one real tick is taken, the before and after are compared, and a jump is only allowed
 * when that tick did nothing but {@code litTime--} and {@code cookingProgress++}. Exactness
 * therefore holds by construction and survives vanilla changing its conditions.
 *
 * <p>The cost is bounded by how much the furnace actually did, not by how long it was
 * skipped: an idle furnace resolves a million ticks in one, and a working one costs a tick
 * per fuel item and per smelt. That bound, rather than any particular formula, is what
 * makes a tick target safe to stop ticking.
 *
 * <p>Precondition: nothing outside the furnace touches its inventory during the window.
 * A hopper feeding it invalidates the frozen-forever shortcut. Invalidation is the
 * caller's job.
 */
public final class FurnaceCatchUp {

    private FurnaceCatchUp() {
    }

    /**
     * Whether this furnace can be skipped at all.
     *
     * <p>The shortcut that makes skipping cheap is the assumption that a furnace which did
     * nothing this tick will keep doing nothing. That holds only while nothing outside can
     * reach its inventory, and a neighbouring hopper is exactly the thing that can. Feeding
     * a skipped furnace without the scheduler noticing produces a world where the ore was
     * never smelted, which is measured and confirmed in the verification suite.
     *
     * <p>Declining here is the fail-safe: the caller ticks the furnace normally instead.
     * A hopper is a conservative proxy for reachable in general, and a real implementation
     * would widen it rather than narrow it.
     */
    public static boolean canCatchUp(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (level.getBlockState(pos.relative(direction)).is(Blocks.HOPPER)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A fingerprint of everything about this furnace that something outside could change.
     *
     * <p>Taken when the furnace is set aside and checked again before reconciling. If it
     * still matches, nothing touched the furnace in between, whatever route it might have
     * taken — which turns an unbounded problem into a bounded one. Enumerating every way to
     * reach a container cannot be finished: {@code Container} and {@code IItemHandler} both
     * hand out the live {@code ItemStack} objects, so anything holding one can grow or
     * shrink it in place with no notification reaching anybody, and NeoForge's own
     * {@code ItemStackHandler.onContentsChanged} is an empty method by default. Comparing
     * state on the way back needs no such list.
     *
     * <p>What it cannot do is undo the damage. A mismatch means the furnace changed at an
     * unknown moment, and the window is not reconstructable from what is left. The value is
     * only that the catch-up then declines instead of inventing a plausible history.
     */
    public static long fingerprint(AbstractFurnaceBlockEntity furnace) {
        long hash = 17L;
        hash = hash * 31L + furnace.litTime;
        hash = hash * 31L + furnace.litDuration;
        hash = hash * 31L + furnace.cookingProgress;
        hash = hash * 31L + furnace.cookingTotalTime;
        for (int slot = 0; slot < 3; slot++) {
            ItemStack stack = furnace.getItem(slot);
            hash = hash * 31L + stack.getCount();
            hash = hash * 31L + (stack.isEmpty() ? 0 : stack.getItem().hashCode());
            hash = hash * 31L + (stack.getComponents() == null ? 0 : stack.getComponents().hashCode());
        }
        return hash;
    }

    /**
     * @return how many ticks were actually run through vanilla. The gap between this and
     *         {@code ticks} is the saving, and it is what the verification suite measures.
     */
    public static int catchUp(ServerLevel level, BlockPos pos,
                              AbstractFurnaceBlockEntity furnace, int ticks) {
        int remaining = ticks;
        int realTicks = 0;

        while (remaining > 0) {
            Snapshot before = Snapshot.of(furnace);
            AbstractFurnaceBlockEntity.serverTick(level, pos, level.getBlockState(pos), furnace);
            remaining--;
            realTicks++;
            if (remaining <= 0) {
                break;
            }

            Snapshot after = Snapshot.of(furnace);
            if (after.equals(before)) {
                // A tick that changed nothing, in a furnace nothing else can reach, will keep
                // changing nothing. The rest of the window is a no-op.
                break;
            }

            int span = linearSpan(after, after.litTime - before.litTime,
                    after.cookingProgress - before.cookingProgress, sameExceptCounters(before, after));
            if (span > 0) {
                int step = Math.min(span, remaining);
                furnace.litTime += (after.litTime - before.litTime) * step;
                furnace.cookingProgress += (after.cookingProgress - before.cookingProgress) * step;
                remaining -= step;
            }
            // span == 0 means the machine just changed regime (relit, finished a smelt,
            // emptied). Those ticks are taken one at a time, which is correct, and there are
            // only as many of them as there was work to do.
        }
        return realTicks;
    }

    /** True when the tick moved nothing except the two counters. */
    private static boolean sameExceptCounters(Snapshot before, Snapshot after) {
        return after.litDuration == before.litDuration
                && after.cookingTotalTime == before.cookingTotalTime
                && after.sameItemsAs(before);
    }

    /**
     * How many further ticks may be collapsed into arithmetic, given that the tick just
     * taken moved the counters by {@code dLit} and {@code dCook} and changed nothing else.
     *
     * <p>Only the three steady regimes vanilla actually has are recognised, and each is
     * cut short one tick before the counter it advances would reach a boundary, so the
     * boundary itself is always handled by a real tick:
     *
     * <ul>
     *   <li>lit and cooking ({@code -1, +1}) until either the fuel runs out or the smelt
     *       completes</li>
     *   <li>lit with nothing to cook ({@code -1, 0}) until the fuel runs out</li>
     *   <li>cold and cooling off ({@code 0, -2}) until the progress reaches zero</li>
     * </ul>
     *
     * @return the safe span, or 0 when the last tick was anything else
     */
    private static int linearSpan(Snapshot after, int dLit, int dCook, boolean onlyCounters) {
        if (!onlyCounters) {
            return 0;
        }

        int span = Integer.MAX_VALUE;
        if (dLit == -1) {
            span = Math.min(span, after.litTime - 1);
        } else if (dLit != 0) {
            return 0;
        }

        if (dCook == 1) {
            span = Math.min(span, after.cookingTotalTime - after.cookingProgress - 1);
        } else if (dCook == -2) {
            // Only the cooling regime decrements by two, and it only runs while unlit.
            // A lit furnace losing progress is vanilla resetting it, not a steady decline.
            if (after.litTime != 0) {
                return 0;
            }
            span = Math.min(span, (after.cookingProgress - 1) / 2);
        } else if (dCook != 0) {
            return 0;
        }

        if (span == Integer.MAX_VALUE) {
            // Nothing bounds the jump, so there is no safe distance to jump.
            return 0;
        }
        return Math.max(0, span);
    }

    private record Snapshot(int litTime, int litDuration, int cookingProgress, int cookingTotalTime,
                            ItemStack input, ItemStack fuel, ItemStack output) {

        static Snapshot of(AbstractFurnaceBlockEntity furnace) {
            return new Snapshot(
                    furnace.litTime,
                    furnace.litDuration,
                    furnace.cookingProgress,
                    furnace.cookingTotalTime,
                    furnace.getItem(0).copy(),
                    furnace.getItem(1).copy(),
                    furnace.getItem(2).copy());
        }

        boolean sameItemsAs(Snapshot other) {
            return ItemStack.matches(this.input, other.input)
                    && ItemStack.matches(this.fuel, other.fuel)
                    && ItemStack.matches(this.output, other.output);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Snapshot other)) {
                return false;
            }
            return this.litTime == other.litTime
                    && this.litDuration == other.litDuration
                    && this.cookingProgress == other.cookingProgress
                    && this.cookingTotalTime == other.cookingTotalTime
                    && sameItemsAs(other);
        }

        @Override
        public int hashCode() {
            return litTime * 31 + cookingProgress;
        }
    }
}
