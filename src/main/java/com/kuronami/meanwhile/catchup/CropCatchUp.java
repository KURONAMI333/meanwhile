package com.kuronami.meanwhile.catchup;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Closed-form catch-up for {@link CropBlock}.
 *
 * <p>Vanilla advances a crop by one age step per random tick with probability
 * {@code p = 1 / ((int)(25 / growthSpeed) + 1)}, gated on brightness >= 9
 * (see {@code CropBlock#randomTick}, 1.21.1). Over N random ticks the number of
 * steps is therefore {@code Binomial(N, p)}, clipped at the crop's max age.
 *
 * <p>Sampling that binomial once reproduces the same distribution as running the
 * N ticks, so a crop nobody is looking at does not need to be ticked at all.
 *
 * <p>Preconditions, both of which the caller must guarantee for the whole window:
 * <ul>
 *   <li>brightness at the crop stays >= 9 (a surface crop crossing dusk does not qualify)</li>
 *   <li>{@code getGrowthSpeed} stays constant, i.e. the farmland below and around
 *       keeps its moisture and nothing is built next to the crop</li>
 * </ul>
 * Violating either makes the result wrong, not merely imprecise. Invalidation is
 * the caller's job.
 */
public final class CropCatchUp {

    private CropCatchUp() {
    }

    /** Brightness vanilla requires before a crop may advance. */
    private static final int MIN_BRIGHTNESS = 9;

    /**
     * The age the crop would have after {@code randomTicks} random ticks.
     *
     * @return the new age, or the current age when the crop cannot grow
     */
    public static int catchUpAge(ServerLevel level, BlockPos pos, BlockState state,
                                 CropBlock crop, int randomTicks, RandomSource random) {
        return catchUpAge(level, pos, state, crop, randomTicks, random, 0);
    }

    /**
     * As above, with the probability denominator shifted by {@code denominatorOffset}.
     *
     * <p>Only a non-zero offset is ever wrong, and that is the point: it is the off-by-one
     * a careless reimplementation of vanilla's formula would produce. The verification
     * suite runs this path deliberately broken to measure that the comparison rejects it,
     * because a comparison never observed to fail is not evidence when it passes.
     */
    public static int catchUpAge(ServerLevel level, BlockPos pos, BlockState state,
                                 CropBlock crop, int randomTicks, RandomSource random,
                                 int denominatorOffset) {
        int age = crop.getAge(state);
        int remaining = crop.getMaxAge() - age;
        if (remaining <= 0 || randomTicks <= 0) {
            return age;
        }
        if (level.getRawBrightness(pos, 0) < MIN_BRIGHTNESS) {
            return age;
        }

        // NeoForge patches getGrowthSpeed to take the BlockState, not the Block, so that
        // modded farmland can participate. Vanilla's decompiled signature differs.
        float growthSpeed = CropBlock.getGrowthSpeed(state, level, pos);
        // Mirrors vanilla exactly, including the int truncation.
        int bound = (int) (25.0F / growthSpeed) + 1 + denominatorOffset;
        double p = 1.0D / bound;

        return age + sampleSteps(randomTicks, p, remaining, random);
    }

    /**
     * Draws {@code min(Binomial(n, p), cap)}.
     *
     * <p>Walks the binomial PMF from k=0 upwards. Because the caller only ever needs
     * up to {@code cap} successes (a crop stops at max age), the walk is bounded by
     * {@code cap} steps regardless of how large {@code n} is. That is what makes this
     * cheaper than ticking.
     */
    static int sampleSteps(int n, double p, int cap, RandomSource random) {
        int limit = Math.min(cap, n);
        if (limit <= 0 || p <= 0.0D) {
            return 0;
        }
        if (p >= 1.0D) {
            return limit;
        }

        // P(X = 0) = (1-p)^n, computed in log space so large n does not underflow silently.
        double logP0 = n * Math.log1p(-p);
        if (logP0 < -700.0D) {
            // P(X = 0) is below double resolution, so P(X >= cap) is indistinguishable from 1.
            return limit;
        }

        double u = random.nextDouble();
        double pk = Math.exp(logP0);
        double cumulative = pk;
        double odds = p / (1.0D - p);

        for (int k = 0; k < limit; k++) {
            if (u < cumulative) {
                return k;
            }
            // PMF recurrence: P(k+1) = P(k) * (n-k)/(k+1) * p/(1-p)
            pk *= ((double) (n - k) / (double) (k + 1)) * odds;
            cumulative += pk;
        }
        return limit;
    }
}
