package com.kuronami.meanwhile.generic;

import com.kuronami.meanwhile.Meanwhile;
import com.kuronami.meanwhile.harness.DifferentialHarness;
import com.kuronami.meanwhile.harness.Verdict;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Does a catch-up that knows nothing about its subject work on a machine nobody wrote a case
 * for?
 *
 * <p>Registered only when Create is present, from {@link Meanwhile}, and carrying no
 * {@code @GameTestHolder} — NeoForge finds holders by scanning the classpath, so an annotated
 * class would join the standing suite permanently and the count in a Create-less run would
 * stop being evidence of anything.
 *
 * <p>Three things have to hold before a match here means anything, and each has its own test:
 * the arena has to be deterministic at all ({@code millstoneArenaIsDeterministic}, where both
 * arms tick and any mismatch is Create's randomness rather than the catch-up's fault), the
 * jump has to actually happen (asserted separately from the comparison, since a catch-up that
 * silently declines matches perfectly while saving nothing), and a comparison that never
 * rejects a wrong answer has to be shown rejecting one.
 */
public final class GenericCatchUpGameTests {

    /** Real server ticks the kinetic network is given to form before anything is measured. */
    private static final int SETTLE = 30;
    /** Long enough to cross several completion boundaries at the millstone's grind rate. */
    private static final int WINDOW = 900;
    /**
     * Real ticks appended to both arms. State a block entity keeps in a field rather than in
     * its NBT — a lazy-tick phase, a cached recipe — is invisible to a diff of the NBT and
     * equally invisible to a digest of it, so a jump can shift it with nothing to show for it.
     * Running on afterwards gives a shifted phase several chances to turn into a visible
     * difference.
     */
    private static final int TAIL = 40;
    private static final long SEED = 20260808L;
    private static final String BATCH = "generic";

    private GenericCatchUpGameTests() {
    }

    // ---- what the machine actually does --------------------------------------------------

    /**
     * Measurement, not a claim: what the millstone moves each tick, how often it completes,
     * and what the generic catch-up makes of it.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = BATCH, timeoutTicks = 600)
    public static void millstoneArenaDiagnostic(GameTestHelper helper) {
        onArmedArena(helper, new MillstoneSubject(), subject -> {
            ServerLevel level = helper.getLevel();
            BlockPos pos = helper.absolutePos(MillstoneSubject.millstonePos());
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) {
                helper.fail("the millstone block entity disappeared");
                return;
            }

            Meanwhile.LOGGER.info("[generic] diag recipe={} feed={}",
                    subject.recipeId(), subject.feed().getItem());
            Meanwhile.LOGGER.info("[generic] diag ticker={} be={}",
                    tickerName(level, pos), blockEntity.getClass().getName());

            // Before any reset, so that a machine which stops working after its NBT is put
            // back cannot be confused with one that was never being ticked in the first place.
            Meanwhile.LOGGER.info("[generic] diag armed  | {}", subject.describe(helper));
            GenericCatchUp.tick(level, pos, 300);
            Meanwhile.LOGGER.info("[generic] diag after 300 manual ticks, no reset | {}",
                    subject.describe(helper));

            subject.reset(helper);
            Meanwhile.LOGGER.info("[generic] diag after reset | {} | precondition={}",
                    subject.describe(helper), subject.precondition(helper));

            CompoundTag previous = blockEntity.saveWithoutMetadata(level.registryAccess());
            String steady = null;
            int firstOutputTick = -1;
            int regimes = 0;
            for (int tick = 1; tick <= WINDOW; tick++) {
                GenericCatchUp.tickOnce(level, pos);
                CompoundTag now = blockEntity.saveWithoutMetadata(level.registryAccess());
                List<String> changed = GenericCatchUp.changedPaths(previous, now);
                String shape = shapeOf(changed);
                if (!shape.equals(steady)) {
                    steady = shape;
                    regimes++;
                    if (regimes <= 24) {
                        Meanwhile.LOGGER.info("[generic] diag tick {} | {}", tick, changed);
                    }
                }
                if (firstOutputTick < 0 && subject.observe(helper)[0] > 0) {
                    firstOutputTick = tick;
                }
                previous = now;
            }
            double[] ticked = subject.observe(helper);
            Meanwhile.LOGGER.info("[generic] diag simulated {} ticks | ground={} input={} "
                            + "firstOutputTick={} regimeChanges={}",
                    WINDOW, ticked[0], ticked[1], firstOutputTick, regimes);

            subject.reset(helper);
            GenericCatchUp.Result result =
                    GenericCatchUp.catchUp(level, pos, WINDOW, GenericCatchUp.Mode.SAFE);
            double[] caught = subject.observe(helper);
            Meanwhile.LOGGER.info("[generic] diag catch-up {} ticks | {} | ground={} input={}",
                    WINDOW, result, caught[0], caught[1]);

            if (ticked[0] < 2) {
                helper.fail("the arena ground " + ticked[0] + " items in " + WINDOW
                        + " ticks, so the window never crosses a completion boundary");
                return;
            }
            helper.succeed();
        });
    }

    // ---- is the arena even deterministic? -------------------------------------------------

    /**
     * Both arms tick. Nothing is skipped, so a mismatch here belongs to Create — a recipe
     * rolling its outputs off a shared random would put every other exact result on this
     * machine beyond interpretation.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = BATCH, timeoutTicks = 600)
    public static void millstoneArenaIsDeterministic(GameTestHelper helper) {
        onArmedArena(helper, new MillstoneSubject(GenericCatchUp.Mode.SAFE, true, 0), subject -> {
            Verdict verdict = DifferentialHarness.compareExact(helper, subject, WINDOW, SEED);
            DifferentialHarness.assertVerdict(helper, verdict);
        });
    }

    // ---- the measurement -------------------------------------------------------------------

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = BATCH, timeoutTicks = 600)
    public static void millstoneGenericCatchUpMatchesExactly(GameTestHelper helper) {
        onArmedArena(helper, new MillstoneSubject(), subject -> {
            Verdict verdict = DifferentialHarness.compareExact(helper, subject, WINDOW, SEED);
            requireJumped(helper, subject, verdict, WINDOW);
        });
    }

    /**
     * The same window, with both arms run on for a while afterwards.
     *
     * <p>The digest and the jump look through the same hole. Whatever the block entity does
     * not serialise cannot appear in the diff that authorised the jump, and cannot appear in
     * the comparison that blesses it either. Ticking on afterwards is the cheapest way to give
     * a hidden counter that the jump left out of step a chance to turn into something visible.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = BATCH, timeoutTicks = 600)
    public static void millstoneGenericCatchUpSurvivesTailTicks(GameTestHelper helper) {
        onArmedArena(helper, new MillstoneSubject(GenericCatchUp.Mode.SAFE, false, TAIL), subject -> {
            Verdict verdict = DifferentialHarness.compareExact(helper, subject, WINDOW, SEED);
            requireJumped(helper, subject, verdict, WINDOW);
        });
    }

    // ---- the same window, looked at through a wider instrument -------------------------------

    /**
     * The catch-up does leave something behind, and this pins down exactly what.
     *
     * <p>Widening the verdict past the serialised surface makes the millstone's two arms stop
     * agreeing. That is the measurement, not a failure: a jump advances the counters a block
     * entity writes down and cannot advance the ones it only keeps in fields, so Create's
     * periodic counters come out of the window at a different point in their cycle than they
     * would have. The serialised state is still identical, which is why nothing narrower could
     * have said so.
     *
     * <p>What is asserted is the shape of that divergence rather than its absence: the arms
     * must differ, and every line they differ on must be a live field. The moment a serialised
     * line joins them, the catch-up is wrong in a way that reaches disk, and this goes red.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = BATCH, timeoutTicks = 600)
    public static void millstoneDeepDivergenceIsConfinedToUnserialisedState(GameTestHelper helper) {
        onArmedArena(helper, new MillstoneSubject(), subject -> {
            Outcome outcome = compareDeep(helper, subject, WINDOW);
            if (outcome.blocked() != null) {
                helper.fail(outcome.blocked());
                return;
            }
            GenericCatchUp.Result result = subject.last();
            if (result == null || result.declined() || result.jumps() == 0) {
                helper.fail("the catch-up never jumped, so there was nothing to leave behind: "
                        + result);
                return;
            }

            List<String> serialised = new ArrayList<>();
            Set<String> fields = new LinkedHashSet<>();
            for (String difference : outcome.differences()) {
                if (DeepStateDigest.isFieldLine(difference)) {
                    fields.add(DeepStateDigest.fieldOf(difference));
                } else {
                    serialised.add(difference);
                }
            }
            Meanwhile.LOGGER.info("[generic] deep divergence | fields={} serialisedLines={}",
                    fields, serialised.size());

            if (!serialised.isEmpty()) {
                helper.fail("the catch-up diverged in state the machine writes down, which"
                        + " reaches disk: " + serialised);
                return;
            }
            if (fields.isEmpty()) {
                helper.fail("the wider comparison found nothing at all, so either the blind"
                        + " spot is empty here or it is not being looked at; " + outcome.summary());
                return;
            }
            helper.succeed();
        });
    }

    /**
     * The wider comparison is a new instrument, so it needs its own demonstration that it can
     * reject something. Inheriting the narrow one's credibility would not transfer.
     *
     * <p>Stronger than "it noticed a difference", because it notices one on the correct
     * implementation too. What has to happen here is that the <em>serialised</em> state
     * diverges — the failure that survives a restart, rather than a counter out of phase.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = BATCH, timeoutTicks = 600)
    public static void millstoneDeepComparisonDetectsIgnoredBoundary(GameTestHelper helper) {
        onArmedArena(helper, new MillstoneSubject(GenericCatchUp.Mode.IGNORE_BOUNDARY), subject -> {
            Outcome outcome = compareDeep(helper, subject, WINDOW);
            if (outcome.blocked() != null) {
                helper.fail(outcome.blocked());
                return;
            }
            for (String difference : outcome.differences()) {
                if (!DeepStateDigest.isFieldLine(difference)) {
                    helper.succeed();
                    return;
                }
            }
            helper.fail("ignoring the boundary did not disturb the serialised state, so this"
                    + " comparison has not been shown to reject a wrong answer: "
                    + outcome.summary());
        });
    }

    /** One deep comparison: why it could not run, or how the two arms differed. */
    private record Outcome(@Nullable String blocked, String summary, List<String> differences) {
    }

    /**
     * The two arms of {@code compareExact}, judged by {@link DeepStateDigest} instead.
     *
     * <p>Written here rather than added to {@code DifferentialHarness}, which is frozen: forty
     * standing tests are calibrated against its output, and a new question is not worth
     * disturbing them.
     */
    private static Outcome compareDeep(GameTestHelper helper, MillstoneSubject subject,
                                       int ticks) {
        subject.setup(helper);
        String blocked = subject.precondition(helper);
        if (blocked != null) {
            return new Outcome("precondition unmet: " + blocked, "", List.of());
        }
        BoundingBox region = subject.exactRegion(helper);

        subject.reset(helper);
        subject.simulate(helper, ticks, RandomSource.create(SEED));
        DeepStateDigest ticked = DeepStateDigest.capture(helper.getLevel(), region);

        String vacuous = subject.postcondition(helper);
        if (vacuous != null) {
            return new Outcome("the window did nothing: " + vacuous, "", List.of());
        }

        subject.reset(helper);
        DifferentialHarness.catchUpOrTick(subject, helper, ticks, RandomSource.create(SEED));
        DeepStateDigest deferred = DeepStateDigest.capture(helper.getLevel(), region);

        String summary = String.format("%s deep ticks=%d lines=%d | simulated=%s catch-up=%s",
                subject.name(), ticks, ticked.size(), ticked.sha256(), deferred.sha256());
        Meanwhile.LOGGER.info("[harness] {}", summary);

        List<String> differences = ticked.differences(deferred, 24);
        for (String difference : differences) {
            Meanwhile.LOGGER.info("[generic] deep diff | {}", difference);
        }
        return new Outcome(null, summary, differences);
    }

    // ---- the run that has to fail ------------------------------------------------------------

    /**
     * Stopping short of the boundary is the whole safety argument, so take it away.
     *
     * <p>This arm collapses the window in one arithmetic step from the first counter movement
     * it sees, never letting the machine handle the tick where the counter would reach zero.
     * A comparison that cannot reject this says nothing when it accepts the real one.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = BATCH, timeoutTicks = 600)
    public static void millstoneComparisonDetectsIgnoredBoundary(GameTestHelper helper) {
        onArmedArena(helper, new MillstoneSubject(GenericCatchUp.Mode.IGNORE_BOUNDARY), subject -> {
            Verdict comparison = DifferentialHarness.compareExact(helper, subject, WINDOW, SEED);
            Meanwhile.LOGGER.info("[generic] negative control ignore-boundary | {}",
                    subject.last());
            DifferentialHarness.assertVerdict(helper,
                    DifferentialHarness.requireDetects("boundary ignored on " + subject.name(),
                            comparison));
        });
    }

    // ---- machinery ----------------------------------------------------------------------------

    /**
     * A catch-up that declines matches by definition, because the caller ticks instead. The
     * comparison therefore cannot tell "reproduced the window" from "did the window", and the
     * jump has to be asserted separately from the match.
     */
    private static void requireJumped(GameTestHelper helper, MillstoneSubject subject,
                                      Verdict verdict, int window) {
        GenericCatchUp.Result result = subject.last();
        Meanwhile.LOGGER.info("[generic] {} window={} | {} | {} || {}",
                subject.name(), window, result == null ? "no catch-up ran" : result.toString(),
                verdict.summary(), verdict.detail());
        if (!verdict.passed()) {
            helper.fail(verdict.summary() + " || " + verdict.detail());
            return;
        }
        if (result == null || result.declined()) {
            helper.fail("the catch-up declined, so the match only shows that ticking works: "
                    + (result == null ? "no result" : result.declineReason()));
            return;
        }
        if (result.jumps() == 0) {
            helper.fail("the catch-up never jumped, so the window was ticked in full and the"
                    + " match shows nothing. refusals=" + result.refusals()
                    + " first=" + result.firstRefusal());
            return;
        }
        helper.succeed();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String tickerName(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        var blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null
                || !(state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock block)) {
            return "<none>";
        }
        Object ticker = block.getTicker(level, state,
                (net.minecraft.world.level.block.entity.BlockEntityType) blockEntity.getType());
        return ticker == null ? "<null>" : ticker.getClass().getName();
    }

    /** The change set with the values stripped, so a steady regime reads as one shape. */
    private static String shapeOf(List<String> changed) {
        StringBuilder shape = new StringBuilder();
        for (String entry : changed) {
            int bracket = entry.indexOf('[');
            shape.append(bracket < 0 ? entry : entry.substring(0, bracket)).append(',');
        }
        return shape.toString();
    }

    /**
     * Places the machine, gives the kinetic network real server ticks to form, loads it, and
     * only then runs the body.
     *
     * <p>The delay is not politeness. A millstone that has just been placed has no speed, and
     * a comparison runs entirely inside a single server tick, so there is nowhere inside one
     * for a network to form.
     */
    private static void onArmedArena(GameTestHelper helper, MillstoneSubject subject,
                                     Consumer<MillstoneSubject> body) {
        if (!subject.place(helper)) {
            helper.fail("create:millstone or create:creative_motor is not registered");
            return;
        }
        helper.runAfterDelay(SETTLE, () -> {
            String blocked = subject.arm(helper);
            if (blocked != null) {
                helper.fail("the arena is not usable: " + blocked);
                return;
            }
            body.accept(subject);
        });
    }
}
