package com.kuronami.meanwhile.generic;

import com.kuronami.meanwhile.Meanwhile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * What keeps a falling counter safe, asserted rather than assumed.
 *
 * <p>{@link GenericCatchUp} sends a falling counter at zero. Zero is the one number in the design
 * that is not watched — a rising counter is only ever jumped towards a value a turnover has been
 * seen at, twice — and a countdown whose machine acts at some other number would be carried past
 * it. The population was asked how often that shape exists and answered that it does: of the ten
 * types seen moving a falling counter under a powered corpus walk, four stop somewhere other than
 * zero — {@code create:deployer Timer} at -12, {@code create:saw NextTick} at -1,
 * {@code minecraft:hopper TransferCooldown} at 1 and {@code minecraft:blast_furnace BurnTime} at 1
 * (G169).
 *
 * <p>None of the four is reachable, and the reason is not the assumption. It is the margin:
 * {@code span} allows a falling counter {@code after - VERIFY_MARGIN} steps, so a jump lands at or
 * above the margin and every value below it is still reached by a real tick. That is a property of
 * the arithmetic rather than of whichever mods happen to be loaded, which is why it can be
 * asserted at all. "No type has a non-zero floor" cannot be: it is a statement about a population,
 * and it is false besides.
 *
 * <p>A falling counter moves monotonically, so the lowest value a jump travels through is the one
 * it lands on. Bounding the landing bounds the whole jump.
 *
 * <h3>What makes this falsifiable</h3>
 * <p>Give the falling branch of {@code span} one tick less to hold back and a counter standing at
 * 2 becomes jumpable, landing at 1. The sweep below goes red on it (G171).
 */
public final class JumpMarginGameTests {

    private static final String BATCH = "jumpmargin";

    /**
     * Where a falling counter is standing when the jump is computed. Includes the four floors the
     * survey found and the values immediately around the margin, which are the only place the
     * bound can be wrong by one.
     */
    private static final long[] STANDS = {
        -24, -12, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 16, 17,
        24, 32, 33, 64, 100, 199, 200, 1_000, 3_000, 72_000,
    };

    /** How far it moved on the observed tick. A jump repeats this step. */
    private static final long[] FALLS = {-1, -2, -3, -5, -8, -20, -100};

    /** Ticks left in the window, which caps the jump independently of the counter. */
    private static final int[] WINDOWS = {1, 2, 5, 100, 3_000, 72_000};

    /** A rising counter to stand beside the falling one, and the two ceilings it is read against. */
    private static final long RISING_STANDS_AT = 5L;
    private static final long CEILING_FAR = 100_000L;
    private static final long CEILING_NEAR = 10L;

    /**
     * Sweeps that produced a jump, below which the assertion above them would be passing on an
     * empty set. The figure is the measured one rounded well down; it is a floor on emptiness, not
     * a fingerprint of the sweep.
     */
    private static final int JUMPS_EXPECTED_AT_LEAST = 200;

    private JumpMarginGameTests() {
    }

    /**
     * No jump leaves a falling counter below the margin, whatever it was standing at, however far
     * it was moving, and whatever else moved beside it.
     *
     * <p>Three assertions, because the first alone would survive both ways of going wrong. A
     * margin raised until nothing is ever jumped satisfies it vacuously, so the sweep also has to
     * produce jumps; a margin that binds nowhere satisfies it accidentally, so some jump also has
     * to land exactly on it.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = BATCH, timeoutTicks = 200)
    public static void aJumpNeverLandsAFallingCounterBelowTheMargin(GameTestHelper helper) {
        int margin = GenericCatchUp.verifyMargin();
        long noCeiling = GenericCatchUp.noCeiling();

        int cases = 0;
        int jumps = 0;
        int landedOnTheMargin = 0;
        long lowestLanding = Long.MAX_VALUE;
        String worst = null;

        for (long stand : STANDS) {
            for (long fall : FALLS) {
                for (int window : WINDOWS) {
                    // Three shapes of tick: the counter on its own; beside a rising counter
                    // heading somewhere far off, so the fall is what bounds the jump; and beside
                    // one close to its ceiling, so the rise bounds it and the fall lands short of
                    // where it could have gone.
                    long[][] arms = {
                        {},
                        {RISING_STANDS_AT, 1L, CEILING_FAR},
                        {RISING_STANDS_AT, 1L, CEILING_NEAR},
                    };
                    for (long[] beside : arms) {
                        long[] after = beside.length == 0 ? new long[] {stand}
                                : new long[] {stand, beside[0]};
                        long[] movement = beside.length == 0 ? new long[] {fall}
                                : new long[] {fall, beside[1]};
                        long[] ceiling = beside.length == 0 ? new long[] {noCeiling}
                                : new long[] {noCeiling, beside[2]};

                        cases++;
                        int span = GenericCatchUp.spanFor(after, movement, ceiling, window);
                        if (span <= 0) {
                            continue;
                        }
                        // Exactly what the jump does with the answer.
                        int step = Math.min(span, window);
                        long landing = stand + fall * step;

                        jumps++;
                        if (landing == margin) {
                            landedOnTheMargin++;
                        }
                        if (landing < lowestLanding) {
                            lowestLanding = landing;
                            worst = "stand=" + stand + " fall=" + fall + " window=" + window
                                    + " counters=" + after.length + " span=" + span
                                    + " step=" + step + " landing=" + landing;
                        }
                    }
                }
            }
        }

        Meanwhile.LOGGER.info("[margin] SWEEP | verifyMargin={} cases={} jumps={} lowestLanding={}"
                        + " landedExactlyOnTheMargin={} lowest=[{}]",
                margin, cases, jumps, jumps == 0 ? null : lowestLanding, landedOnTheMargin, worst);

        if (jumps < JUMPS_EXPECTED_AT_LEAST) {
            helper.fail("the sweep authorised " + jumps + " jumps of " + cases + " cases, fewer"
                    + " than the " + JUMPS_EXPECTED_AT_LEAST + " it has to produce for the bound"
                    + " below to be about anything");
            return;
        }
        if (lowestLanding < margin) {
            helper.fail("a jump left a falling counter at " + lowestLanding + ", below the margin"
                    + " of " + margin + " that every value under it is reached by a real tick"
                    + " instead: " + worst);
            return;
        }
        if (landedOnTheMargin == 0) {
            helper.fail("no jump landed on the margin of " + margin + ", so nothing here measures"
                    + " the margin: the lowest landing was " + lowestLanding + " (" + worst + ")");
            return;
        }
        helper.succeed();
    }
}
