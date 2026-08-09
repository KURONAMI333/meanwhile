package com.kuronami.meanwhile.harness;

import com.kuronami.meanwhile.Meanwhile;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Runs a {@link CatchUpSubject} two ways and reports whether skipping ticks changed the
 * outcome.
 *
 * <p>Everything here is one comparison of two arms, differing only in what the arms do.
 * How they are compared depends on the subject, not the question being asked:
 *
 * <ul>
 *   <li>deterministic subjects, such as a furnace, must match bit for bit across the whole
 *       region;</li>
 *   <li>stochastic subjects, such as a crop, can only be compared in distribution.
 *       Per-instance equality is not merely hard there, it is meaningless: two vanilla runs
 *       with different seeds already disagree.</li>
 * </ul>
 *
 * <p>The questions asked of those arms build on each other. {@link #compareExact} and
 * {@link #compareDistributions} ask whether a clean window can be skipped at all.
 * {@link #compareSegmented} asks whether a window can be split, which is what lets a
 * scheduler stop at an interruption. {@link #compareWithDisturbance} asks whether stopping
 * at the interruption actually produces the right answer, and
 * {@link #requireDivergesWithoutNotification} asks whether failing to stop produces a
 * measurably wrong one.
 *
 * <p>All of it is worth only as much as its power to fail. Use {@link #requireDetects} to
 * measure that against a deliberately broken implementation rather than assuming it.
 */
public final class DifferentialHarness {

    private DifferentialHarness() {
    }

    /** What one arm of a comparison does to the subject. */
    @FunctionalInterface
    public interface Arm {
        void run(RandomSource random);
    }

    /**
     * How hard to look. {@code trials} and {@code tolerance} apply to stochastic subjects
     * only; a deterministic subject runs each arm once and demands exact equality.
     */
    public record Effort(int trials, double tolerance, long seedSimulated, long seedCaughtUp) {

        public static Effort exact(long seed) {
            return new Effort(1, 0.0D, seed, seed);
        }
    }

    // ---- questions -------------------------------------------------------------------

    /** Can a clean window be skipped? Deterministic subjects. */
    public static Verdict compareExact(GameTestHelper helper, CatchUpSubject subject,
                                       int ticks, long seed) {
        return compare(helper, subject, "exact ticks=" + ticks,
                random -> subject.simulate(helper, ticks, random),
                random -> catchUpOrTick(subject, helper, ticks, random),
                Effort.exact(seed));
    }

    /** Can a clean window be skipped? Stochastic subjects. */
    public static Verdict compareDistributions(GameTestHelper helper, CatchUpSubject subject,
                                               int ticks, Effort effort) {
        return compare(helper, subject, "dist ticks=" + ticks,
                random -> subject.simulate(helper, ticks, random),
                random -> catchUpOrTick(subject, helper, ticks, random),
                effort);
    }

    /**
     * Can a window be split? Skipping {@code ticks} in one go must be the same as skipping
     * it in two.
     *
     * <p>Everything a scheduler does with an interruption depends on this. If catching up
     * twice does not equal catching up once, there is no way to stop at the moment a hopper
     * touches a furnace, and the only safe answer for anything reachable from outside is to
     * never defer it at all.
     */
    public static Verdict compareSegmented(GameTestHelper helper, CatchUpSubject subject,
                                           int ticks, int splitAt, Effort effort) {
        return compare(helper, subject, "segmented ticks=" + ticks + " split=" + splitAt,
                random -> catchUpOrTick(subject, helper, ticks, random),
                random -> {
                    catchUpOrTick(subject, helper, splitAt, random);
                    catchUpOrTick(subject, helper, ticks - splitAt, random);
                },
                effort);
    }

    /**
     * Does stopping at the interruption give the right answer? The catch-up runs to the
     * disturbance, the disturbance lands, and the catch-up resumes, which is what a
     * scheduler that is told about the interruption would do.
     */
    public static Verdict compareWithDisturbance(GameTestHelper helper, CatchUpSubject subject,
                                                 Disturbance disturbance, int ticks, int disturbAt,
                                                 Effort effort) {
        String label = "disturbed(" + disturbance.name() + ") ticks=" + ticks + " at=" + disturbAt;
        return compare(helper, subject, label,
                random -> {
                    subject.simulate(helper, disturbAt, random);
                    disturbance.apply(helper);
                    subject.simulate(helper, ticks - disturbAt, random);
                },
                random -> {
                    catchUpOrTick(subject, helper, disturbAt, random);
                    disturbance.apply(helper);
                    catchUpOrTick(subject, helper, ticks - disturbAt, random);
                },
                effort);
    }

    /**
     * Does failing to stop give a wrong answer?
     *
     * <p>The caught-up arm models a scheduler that never learned about the interruption: by
     * the time anyone looks, the world already contains the change, and the whole window
     * gets reconciled in one step against that changed state.
     *
     * <p>This is the one comparison that is supposed to fail. It turns "the scheduler must
     * be told about interruptions" from an assumption into a measured fact, and the size of
     * the divergence is how much silent corruption the requirement is holding back.
     */
    public static Verdict requireDivergesWithoutNotification(GameTestHelper helper,
                                                             CatchUpSubject subject,
                                                             Disturbance disturbance,
                                                             int ticks, int disturbAt,
                                                             Effort effort) {
        return requireDetects("unnotified " + disturbance.name() + " on " + subject.name(),
                compareWithoutNotification(helper, subject, disturbance, ticks, disturbAt, effort));
    }

    /**
     * The same arms as above, reported plainly rather than inverted.
     *
     * <p>Used to show a fail-safe working. A subject that can tell it is reachable from
     * outside declines to be skipped, falls back to ticking, and therefore matches — in the
     * very scenario that diverges without the guard.
     */
    public static Verdict compareWithoutNotification(GameTestHelper helper, CatchUpSubject subject,
                                                     Disturbance disturbance, int ticks,
                                                     int disturbAt, Effort effort) {
        String label = "unnotified(" + disturbance.name() + ") ticks=" + ticks + " at=" + disturbAt;
        Verdict comparison = compare(helper, subject, label,
                random -> {
                    subject.simulate(helper, disturbAt, random);
                    disturbance.apply(helper);
                    subject.simulate(helper, ticks - disturbAt, random);
                },
                random -> {
                    if (!subject.canDefer(helper)) {
                        // The scheduler never stopped ticking this one, so the interruption
                        // lands when it really happens and there is no window to misjudge.
                        subject.simulate(helper, disturbAt, random);
                        disturbance.apply(helper);
                        subject.simulate(helper, ticks - disturbAt, random);
                        return;
                    }
                    disturbance.apply(helper);
                    catchUpOrTick(subject, helper, ticks, random);
                },
                effort);
        return comparison;
    }

    /**
     * What does something outside see if it looks partway through a skipped window?
     *
     * <p>Every other comparison here asks what the world looks like once the window is over.
     * That is not enough for anything that reads a deferred subject while it is deferred: a
     * comparator can be driven all the way through a redstone circuit by a number that was
     * never true, and the final state reconciling afterwards does not undo what the circuit
     * did.
     *
     * @param reconcileBeforeRead what the scheduler does when the read arrives. True is the
     *                            correct behaviour, catching up to the moment of the read
     *                            before answering it. False is the naive one, answering from
     *                            whatever stale state the subject was left in, and is
     *                            expected to give a wrong answer.
     */
    public static Verdict compareMidWindowRead(GameTestHelper helper, CatchUpSubject subject,
                                               int ticks, int readAt, boolean reconcileBeforeRead,
                                               Effort effort) {
        String[] labels = subject.externalReadLabels();
        if (labels.length == 0) {
            return Verdict.fail(subject.name() + " has no external reads",
                    "nothing outside can read this subject, so there is no stale-read hazard to test");
        }

        subject.setup(helper);
        String blocked = subject.precondition(helper);
        if (blocked != null) {
            return Verdict.fail(subject.name() + " mid-window read: precondition unmet", blocked);
        }

        double[][] ticked = collectReads(helper, subject, effort.trials(), effort.seedSimulated(),
                (random, sink) -> {
                    subject.simulate(helper, readAt, random);
                    sink.accept(subject.externalReads(helper));
                    subject.simulate(helper, ticks - readAt, random);
                });

        double[][] deferred = collectReads(helper, subject, effort.trials(), effort.seedCaughtUp(),
                (random, sink) -> {
                    if (reconcileBeforeRead) {
                        catchUpOrTick(subject, helper, readAt, random);
                        sink.accept(subject.externalReads(helper));
                        catchUpOrTick(subject, helper, ticks - readAt, random);
                    } else {
                        // The subject was left alone and answers from stale state.
                        sink.accept(subject.externalReads(helper));
                        catchUpOrTick(subject, helper, ticks, random);
                    }
                });

        String label = String.format("read ticks=%d at=%d %s",
                ticks, readAt, reconcileBeforeRead ? "reconciled" : "stale");
        return report(subject, label, labels, ticked, deferred, effort.tolerance());
    }

    /**
     * What gets written to disk if the chunk saves partway through a skipped window?
     *
     * <p>The worst of the three hazards, and the one no mutation hook can catch. Chunk save
     * serialises a block entity's live in-memory fields with no dirty check
     * ({@code LevelChunk#getBlockEntityNbtForSaving}), so a deferred subject that has not
     * reconciled yet has its stale fields written out verbatim. A comparator reading stale
     * state is wrong for a tick; this is wrong forever, and survives the restart that would
     * otherwise hide it.
     *
     * <p>Deterministic subjects only: the whole serialised region is compared, so there is
     * nothing to average.
     *
     * @param reconcileBeforeSave what the scheduler does when the save arrives. True is the
     *                            correct behaviour, flushing the subject before it is
     *                            serialised.
     */
    public static Verdict compareMidWindowPersist(GameTestHelper helper, CatchUpSubject subject,
                                                  int saveAt, boolean reconcileBeforeSave,
                                                  long seed) {
        subject.setup(helper);
        String blocked = subject.precondition(helper);
        if (blocked != null) {
            return Verdict.fail(subject.name() + " mid-window persist: precondition unmet", blocked);
        }
        BoundingBox region = subject.exactRegion(helper);
        if (region == null) {
            return Verdict.fail(subject.name() + " has no exact region",
                    "the persist comparison serialises a region, so it needs one");
        }

        subject.reset(helper);
        subject.simulate(helper, saveAt, RandomSource.create(seed));
        WorldStateDigest ticked = WorldStateDigest.capture(helper.getLevel(), region);

        subject.reset(helper);
        if (reconcileBeforeSave) {
            catchUpOrTick(subject, helper, saveAt, RandomSource.create(seed));
        }
        WorldStateDigest deferred = WorldStateDigest.capture(helper.getLevel(), region);

        String summary = String.format("%s persist at=%d %s | ticked=%s deferred=%s",
                subject.name(), saveAt, reconcileBeforeSave ? "flushed" : "stale",
                ticked.sha256(), deferred.sha256());
        Meanwhile.LOGGER.info("[harness] {}", summary);

        String difference = ticked.firstDifference(deferred);
        return difference == null
                ? Verdict.pass(summary)
                : Verdict.fail(summary, "what would be written to disk differs at " + difference);
    }

    /** Serialising without flushing first must be measurably wrong. */
    public static Verdict requireStalePersistDiverges(GameTestHelper helper, CatchUpSubject subject,
                                                      int saveAt, long seed) {
        return requireDetects("stale mid-window save on " + subject.name(),
                compareMidWindowPersist(helper, subject, saveAt, false, seed));
    }

    /** The stale variant of the above, inverted: answering without catching up must be wrong. */
    public static Verdict requireStaleReadDiverges(GameTestHelper helper, CatchUpSubject subject,
                                                   int ticks, int readAt, Effort effort) {
        return requireDetects("stale mid-window read on " + subject.name(),
                compareMidWindowRead(helper, subject, ticks, readAt, false, effort));
    }

    // ---- machinery -------------------------------------------------------------------

    /**
     * Requires that a comparison rejected something known to be wrong.
     *
     * <p>A comparison never observed to fail is not evidence when it passes.
     */
    public static Verdict requireDetects(String label, Verdict comparison) {
        if (comparison.passed()) {
            return Verdict.fail("not detected: " + label,
                    "a knowingly wrong run passed the comparison, so the comparison cannot be"
                            + " trusted when it passes for a real one. " + comparison.summary());
        }
        return Verdict.pass("detected: " + label + " | " + comparison.detail());
    }

    /** Applies a verdict to the running GameTest. */
    public static void assertVerdict(GameTestHelper helper, Verdict verdict) {
        if (verdict.passed()) {
            helper.succeed();
        } else {
            helper.fail(verdict.summary() + " || " + verdict.detail());
        }
    }

    /**
     * What a scheduler actually does with a deferred subject: ask it to reconcile, and tick
     * it for real if it declines.
     *
     * <p>Routing every arm through this is what makes the safety property a single claim.
     * A catch-up that refuses is not a second kind of pass to reason about separately; it
     * just becomes ticking, which is right by definition. The thing that then needs its own
     * test is the opposite risk, that a subject refuses so often the optimisation is
     * imaginary.
     */
    public static void catchUpOrTick(CatchUpSubject subject, GameTestHelper helper,
                                     int ticks, RandomSource random) {
        if (!subject.catchUp(helper, ticks, random)) {
            subject.simulate(helper, ticks, random);
        }
    }

    private static Verdict compare(GameTestHelper helper, CatchUpSubject subject, String label,
                                   Arm simulated, Arm caughtUp, Effort effort) {
        subject.setup(helper);
        String blocked = subject.precondition(helper);
        if (blocked != null) {
            return Verdict.fail(subject.name() + " " + label + ": precondition unmet", blocked);
        }

        BoundingBox region = subject.exactRegion(helper);
        return region != null
                ? compareExactly(helper, subject, label, simulated, caughtUp, effort, region)
                : compareStatistically(helper, subject, label, simulated, caughtUp, effort);
    }

    private static Verdict compareExactly(GameTestHelper helper, CatchUpSubject subject, String label,
                                          Arm simulated, Arm caughtUp, Effort effort,
                                          BoundingBox region) {
        subject.reset(helper);
        simulated.run(RandomSource.create(effort.seedSimulated()));
        WorldStateDigest a = WorldStateDigest.capture(helper.getLevel(), region);

        String vacuous = subject.postcondition(helper);
        if (vacuous != null) {
            return Verdict.fail(subject.name() + " " + label + ": window did nothing", vacuous);
        }

        subject.reset(helper);
        caughtUp.run(RandomSource.create(effort.seedCaughtUp()));
        WorldStateDigest b = WorldStateDigest.capture(helper.getLevel(), region);

        String summary = String.format("%s %s | simulated=%s catch-up=%s",
                subject.name(), label, a.sha256(), b.sha256());
        Meanwhile.LOGGER.info("[harness] {}", summary);

        String difference = a.firstDifference(b);
        return difference == null
                ? Verdict.pass(summary)
                : Verdict.fail(summary, "world state diverged at " + difference);
    }

    private static Verdict compareStatistically(GameTestHelper helper, CatchUpSubject subject,
                                                String label, Arm simulated, Arm caughtUp,
                                                Effort effort) {
        String[] labels = subject.observationLabels();
        double[][] a = collect(helper, subject, simulated, effort.trials(), effort.seedSimulated());
        double[][] b = collect(helper, subject, caughtUp, effort.trials(), effort.seedCaughtUp());
        return report(subject, label, labels, a, b, effort.tolerance());
    }

    /** One arm of a mid-window read comparison: run, and hand the reading to the sink when it happens. */
    @FunctionalInterface
    public interface ReadArm {
        void run(RandomSource random, java.util.function.Consumer<double[]> sink);
    }

    private static double[][] collectReads(GameTestHelper helper, CatchUpSubject subject,
                                           int trials, long seed, ReadArm arm) {
        int observables = subject.externalReadLabels().length;
        double[][] samples = new double[observables][trials];
        RandomSource random = RandomSource.create(seed);

        for (int trial = 0; trial < trials; trial++) {
            subject.reset(helper);
            int index = trial;
            arm.run(random, reading -> {
                for (int i = 0; i < observables; i++) {
                    samples[i][index] = reading[i];
                }
            });
        }
        return samples;
    }

    private static Verdict report(CatchUpSubject subject, String label, String[] labels,
                                  double[][] a, double[][] b, double tolerance) {
        StringBuilder log = new StringBuilder(
                String.format("%s %s trials=%d", subject.name(), label, a[0].length));
        String failure = null;

        for (int i = 0; i < labels.length; i++) {
            double meanA = mean(a[i]);
            double meanB = mean(b[i]);
            double sdA = standardDeviation(a[i], meanA);
            double sdB = standardDeviation(b[i], meanB);
            double dMean = Math.abs(meanA - meanB);
            double dSd = Math.abs(sdA - sdB);

            log.append(String.format(" | %s: sim %.4f±%.4f cf %.4f±%.4f dMean=%.4f dSd=%.4f",
                    labels[i], meanA, sdA, meanB, sdB, dMean, dSd));

            if (failure == null && dMean > tolerance) {
                failure = String.format("%s mean diverged: simulated=%.4f catch-up=%.4f (tolerance %.4f)",
                        labels[i], meanA, meanB, tolerance);
            }
            if (failure == null && dSd > tolerance) {
                failure = String.format("%s spread diverged: simulated sd=%.4f catch-up sd=%.4f (tolerance %.4f)",
                        labels[i], sdA, sdB, tolerance);
            }
        }

        String summary = log.toString();
        Meanwhile.LOGGER.info("[harness] {}", summary);
        return failure == null ? Verdict.pass(summary) : Verdict.fail(summary, failure);
    }

    private static double[][] collect(GameTestHelper helper, CatchUpSubject subject, Arm arm,
                                      int trials, long seed) {
        int observables = subject.observationLabels().length;
        double[][] samples = new double[observables][trials];
        RandomSource random = RandomSource.create(seed);

        for (int trial = 0; trial < trials; trial++) {
            subject.reset(helper);
            arm.run(random);
            double[] observed = subject.observe(helper);
            for (int i = 0; i < observables; i++) {
                samples[i][trial] = observed[i];
            }
        }
        return samples;
    }

    private static double mean(double[] values) {
        double sum = 0.0D;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    private static double standardDeviation(double[] values, double mean) {
        double sum = 0.0D;
        for (double value : values) {
            double d = value - mean;
            sum += d * d;
        }
        return Math.sqrt(sum / values.length);
    }
}
