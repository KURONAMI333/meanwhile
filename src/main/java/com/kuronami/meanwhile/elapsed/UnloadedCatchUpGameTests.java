package com.kuronami.meanwhile.elapsed;

import com.kuronami.meanwhile.Meanwhile;
import com.kuronami.meanwhile.generic.DeepStateDigest;
import com.kuronami.meanwhile.generic.GenericCatchUp;
import com.kuronami.meanwhile.generic.MillstoneSubject;
import com.kuronami.meanwhile.harness.WorldStateDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Does a machine that was really gone come back where it would have been?
 *
 * <p>The chunk's forced tickets are dropped, the game posts {@code ChunkEvent.Unload} and saves
 * it, it is left out of the world for a chosen number of ticks, and it is asked for again. The
 * clock works out how far behind it is and {@link ChunkCatchUp} spends that window on whatever
 * block entities came back in it. Nothing here is simulated: the unload is the game's, the
 * reload is the game's, and the elapsed count is arithmetic on times that went through disk.
 *
 * <h3>The two arms, and where arm A starts</h3>
 *
 * <blockquote>
 * <b>A</b>: run N real ticks.<br>
 * <b>B</b>: be gone for N ticks and catch up on return.
 * </blockquote>
 *
 * <p>Arm A is started from the state arm B's catch-up started from, recorded by
 * {@link ChunkCatchUp} at the moment it began. Pinning it there is what removes the two or three
 * ticks that separate "the tickets were dropped" from "the chunk actually went", which are real
 * ticks the machine ran and which no arithmetic can make identical between two arms.
 *
 * <p>That pinning would be circular on its own — if the round trip through disk had damaged the
 * machine, arm A would inherit the damage and the two arms would agree about a wrong state. So
 * the round trip is checked separately and first, by {@link RoundTripImages}, which reads the
 * block entities on the unload and load events themselves with no tick in between. Two claims
 * are therefore reported, not one: the trip preserved the state, and the catch-up reproduced the
 * ticking.
 *
 * <h3>Why the millstone, and why only that recipe</h3>
 *
 * <p>The subject is {@link MillstoneSubject}, which picks its own milling recipe by measuring
 * which one reproduces itself over three runs. Create rolls a bonus output per completed recipe
 * off a shared random, so most milling recipes disagree with themselves and would make any
 * bit-for-bit comparison meaningless in a way that looks exactly like a catch-up bug.
 *
 * <h3>Isolation</h3>
 *
 * <p>No {@code @GameTestHolder}: NeoForge finds holders by scanning the classpath, and an
 * annotated class would join the standing suite permanently. Registered from {@link Meanwhile}
 * whenever Create is present, which the build resolves, with an explicit
 * {@code templateNamespace} — without one the namespace falls back to {@code minecraft} and the
 * test is registered and then silently dropped (GAP_LOG G59).
 *
 * <p>One test per batch. Dropping a forced ticket is not scoped to a test, and tests in one batch
 * stand in neighbouring arenas.
 */
public final class UnloadedCatchUpGameTests {

    /** Real ticks the kinetic network is given to form before anything is measured. */
    private static final int SETTLE = 30;
    /** How long the chunk is left out of the world in the measurement. */
    private static final int WINDOW = 900;
    /** The same, in the diagnostic, which only has to reach the sweep. */
    private static final int SHORT_WINDOW = 120;

    /** How long an unload is waited for after the tickets are dropped. 2-8 measured (G56). */
    private static final int UNLOAD_WAIT = 200;
    /** How long the sweep is waited for after the chunk is asked back. */
    private static final int BACK_WAIT = 200;

    /** Completed grinds arm B must contain, so the window crossed a completion boundary. */
    private static final int REQUIRED_GROUND = 2;

    private UnloadedCatchUpGameTests() {
    }

    // ---- the question the supervisor asked first --------------------------------------------

    /**
     * Is the ticker path the same in this window as in the one the catch-up was measured in?
     *
     * <p>Every earlier measurement of {@link GenericCatchUp} was taken on a chunk that had been
     * loaded and ticking all along. Here the block entity is a fresh object deserialised moments
     * ago, and Create's kinetic block entities re-derive their speed and network on their first
     * tick rather than reading them back, so "the same block, reached the same way" is a claim
     * about this window and not something inherited.
     *
     * <p>Asserts only that the round trip happened and that the catch-up was offered the chunk.
     * What the machine did with the window is logged; the comparison is the next test's job.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "unloadedcatchup-diag", timeoutTicks = 1200)
    public static void sweepWindowTickerPathDiagnostic(GameTestHelper helper) {
        if (resurrectProbeRequested()) {
            RoundTripImages.armResurrectProbe();
        }
        onArena(helper, SHORT_WINDOW, ChunkCatchUp.Mode.PRODUCT, null, trip -> {
            ChunkCatchUp.Sweep sweep = trip.sweep;
            if (sweep == null) {
                helper.fail("the chunk came back but the catch-up was never offered it: "
                        + trip.describe());
                return;
            }
            for (ChunkCatchUp.Attempt attempt : sweep.attempts()) {
                Meanwhile.LOGGER.info("[unloaded] diag attempt | {} | type={} before={} after={}",
                        attempt.summary(), attempt.type(), attempt.before(), attempt.after());
            }
            Meanwhile.LOGGER.info("[unloaded] diag millstone | speedNow={} describe={}",
                    speedOf(helper.getLevel().getBlockEntity(helper.absolutePos(MILLSTONE))),
                    trip.subject.describe(helper));
            Meanwhile.LOGGER.info("[unloaded] diag round trip | unloads={} loads={} loadToSweep={}"
                            + " differences={}",
                    RoundTripImages.unloads(), RoundTripImages.loads(),
                    sweep.at() - RoundTripImages.loadAt(),
                    RoundTripImages.roundTripDifferences());
            Meanwhile.LOGGER.info("[unloaded] diag resurrect probe | {}",
                    RoundTripImages.resurrectResult());

            String vacuous = trip.vacuous();
            if (vacuous != null) {
                helper.fail(vacuous);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Is the reconstruction every arm starts from idempotent?
     *
     * <p>The question this answers is which of the two things arm B and arm A differ in is
     * responsible for the kinetic network disagreement both of them and all three real-ticking
     * controls report. They differ in the catch-up, and they also differ in their ordinal
     * position: arm B is reconstructed first, out of block entities that came off disk moments
     * ago and have not ticked since, and arm A and the replay are reconstructed after that.
     * The noise floor is arm A against the replay, so a difference that only appears the first
     * time a reconstruction is applied cannot land in it and lands in the signal instead — for
     * every arm, whether or not anything jumped.
     *
     * <p>So: three reconstructions, back to back, in the tick the chunk came back, with nothing
     * ticked between them and the catch-up not yet reached. If the first disagrees with the
     * second and the second agrees with the third, the disagreement belongs to the arena and
     * neither the catch-up nor ticking is involved in it at all.
     *
     * <p>Reports rather than asserts. It measures the instrument, and what it finds is a fact
     * about the instrument to act on rather than a regression to go red on.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "unloadedcatchup-idempotence", timeoutTicks = 1200)
    public static void reconstructionIdempotenceDiagnostic(GameTestHelper helper) {
        Idempotence probe = new Idempotence();
        onArena(helper, SHORT_WINDOW, ChunkCatchUp.Mode.PRODUCT, probe, trip -> {
            if (trip.sweep == null) {
                helper.fail("the chunk came back but the catch-up was never offered it: "
                        + trip.describe());
                return;
            }
            String vacuous = trip.vacuous();
            if (vacuous != null) {
                helper.fail(vacuous);
                return;
            }
            if (!probe.ran) {
                helper.fail("the sweep never reached the probe, so nothing was reconstructed");
                return;
            }
            probe.report();
            helper.succeed();
        });
    }

    /** Three reconstructions in a row, with nothing ticked between them. */
    private static final class Idempotence implements ArmedObserver {

        private long target = Long.MIN_VALUE;
        private boolean ran;
        private List<String> loadedToFirst = List.of();
        private List<String> firstToSecond = List.of();
        private List<String> secondToThird = List.of();
        private List<String> tickedFirstToSecond = List.of();
        private List<String> tickedSecondToThird = List.of();
        private final List<String> stages = new ArrayList<>();

        @Override
        public void arm(GameTestHelper helper, MillstoneSubject subject, ChunkPos chunk) {
            this.target = chunk.toLong();
        }

        @Override
        public void beforeSweep(ServerLevel level, LevelChunk chunk, int dispatched,
                                List<BlockPos> positions) {
            if (ran || chunk.getPos().toLong() != target) {
                return;
            }
            ran = true;
            HolderLookup.Provider registries = level.registryAccess();

            // The tag every reconstruction restores from, taken once. All three rounds put back
            // exactly these bytes, so anything they end up disagreeing on came from the live
            // objects underneath rather than from what was written down.
            Map<BlockPos, CompoundTag> start = tagsOf(level, positions, registries);
            Map<BlockPos, CompoundTag> loaded = start;
            stages.add("as loaded  : " + networksOf(loaded));

            reconstruct(level, positions, start, registries);
            Map<BlockPos, CompoundTag> first = tagsOf(level, positions, registries);
            stages.add("after 1st  : " + networksOf(first));

            reconstruct(level, positions, start, registries);
            Map<BlockPos, CompoundTag> second = tagsOf(level, positions, registries);
            stages.add("after 2nd  : " + networksOf(second));

            reconstruct(level, positions, start, registries);
            Map<BlockPos, CompoundTag> third = tagsOf(level, positions, registries);
            stages.add("after 3rd  : " + networksOf(third));

            loadedToFirst = differingKeys(loaded, first);
            firstToSecond = differingKeys(first, second);
            secondToThird = differingKeys(second, third);

            // The same three rounds with the window ticked in each of them. This is arm A run
            // three times over, with the catch-up never reached and nothing jumped: whatever
            // these three disagree on is something the arena carries from one pass to the next.
            // The harness builds its noise floor out of the second pass against the third and
            // its signal out of the second against the first, so a key that settles after one
            // pass is a difference the floor cannot contain and the signal must report.
            reconstruct(level, positions, start, registries);
            tickWindow(level, positions, dispatched);
            Map<BlockPos, CompoundTag> pass1 = tagsOf(level, positions, registries);
            stages.add("ticked 1st : " + networksOf(pass1));

            reconstruct(level, positions, start, registries);
            tickWindow(level, positions, dispatched);
            Map<BlockPos, CompoundTag> pass2 = tagsOf(level, positions, registries);
            stages.add("ticked 2nd : " + networksOf(pass2));

            reconstruct(level, positions, start, registries);
            tickWindow(level, positions, dispatched);
            Map<BlockPos, CompoundTag> pass3 = tagsOf(level, positions, registries);
            stages.add("ticked 3rd : " + networksOf(pass3));

            tickedFirstToSecond = differingKeys(pass1, pass2);
            tickedSecondToThird = differingKeys(pass2, pass3);
        }


        @Override
        public void afterSweep(ServerLevel level, LevelChunk chunk, ChunkCatchUp.Sweep result) {
            // Nothing. The whole question is settled before a single tick is spent.
        }

        private void report() {
            for (String stage : stages) {
                Meanwhile.LOGGER.info("[idempotence] {}", stage);
            }
            Meanwhile.LOGGER.info("[idempotence] RESULT restore only | loadedToFirst={}"
                            + " firstToSecond={} secondToThird={} idempotentFromFirst={}",
                    loadedToFirst, firstToSecond, secondToThird,
                    firstToSecond.isEmpty() && secondToThird.isEmpty());
            Meanwhile.LOGGER.info("[idempotence] RESULT restore and tick | firstToSecond={}"
                            + " secondToThird={} settlesAfterOnePass={}",
                    tickedFirstToSecond, tickedSecondToThird,
                    !tickedFirstToSecond.isEmpty() && tickedSecondToThird.isEmpty());
        }
    }

    /** The window, ticked one tick across every position at a time, which is the game's order. */
    private static void tickWindow(ServerLevel level, List<BlockPos> positions, int window) {
        for (int tick = 0; tick < window; tick++) {
            for (BlockPos pos : positions) {
                GenericCatchUp.tickOnce(level, pos);
            }
        }
    }

    /** The kinetic bookkeeping of each position, which is what the arms disagree on. */
    private static String networksOf(Map<BlockPos, CompoundTag> tags) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<BlockPos, CompoundTag> entry : tags.entrySet()) {
            CompoundTag tag = entry.getValue();
            out.add(entry.getKey().toShortString() + "="
                    + (tag.contains("Network") ? tag.getCompound("Network").toString() : "<none>"));
        }
        return out.toString();
    }

    // ---- the measurement -----------------------------------------------------------------------

    /**
     * The bit-for-bit comparison, on one machine.
     *
     * <p>Both surfaces are taken. {@link WorldStateDigest} is the serialised one — block states,
     * block entity NBT, entities — and it is the one that has to be identical, because it is what
     * reaches disk and what any other mod or player can observe. {@link DeepStateDigest} adds the
     * live scalar fields, which the catch-up cannot see and therefore cannot carry: a jump
     * advances the counters a machine writes down and leaves the ones it only holds in memory
     * where they were. The assertion on the wider surface is the shape of that divergence rather
     * than its absence — the arms may differ, but only on lines that are live fields. A
     * serialised line joining them is the catch-up being wrong in a way that survives a restart.
     *
     * <p>Restricted to the millstone. The motor is in the region and in the digest, but neither
     * arm ticks it, which is the arrangement the standing Create tests already use. This is the
     * narrow case of {@link #wholeChunkCatchUpMatchesTickingExactly}: one machine offered to the
     * catch-up rather than everything the chunk came back with. Both are asserted, because a
     * difference that only shows when the whole chunk is offered is a different fact from one
     * that shows on a single machine.
     *
     * <p>{@link #roundTripCatchUpMatchesRoundTripTicking} is the stronger statement: it puts the
     * same real unload and reload in front of both arms, so neither is rebuilt from a recorded
     * tag at all. This one is narrower and still binding, and it is where a disagreement is
     * named key by key rather than as a hash.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "unloadedcatchup-exact", timeoutTicks = 2400)
    public static void unloadedCatchUpMatchesTickingExactly(GameTestHelper helper) {
        ChunkCatchUp.Mode mode = ignoreElapsedRequested()
                ? ChunkCatchUp.Mode.IGNORE_ELAPSED
                : ChunkCatchUp.Mode.PRODUCT;
        measure(helper, mode.restrictedTo(helper.absolutePos(MILLSTONE)), true);
    }

    /**
     * The same, with the catch-up offered every block entity in the chunk, which is the product.
     *
     * <p>This and the three controls spent a long time reporting a disagreement on Create's
     * kinetic network that belonged to the arena rather than to the mod, which is why all five
     * were optional. The arena is levelled before arm B runs now, and what it was reporting
     * is measured rather than supposed: see {@link #reconstructionIdempotenceDiagnostic}.
     * The comparison comes out bit-for-bit equal, and is binding for that reason.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "unloadedcatchup-whole", timeoutTicks = 2400)
    public static void wholeChunkCatchUpMatchesTickingExactly(GameTestHelper helper) {
        measure(helper, ignoreElapsedRequested()
                ? ChunkCatchUp.Mode.IGNORE_ELAPSED
                : ChunkCatchUp.Mode.PRODUCT, true);
    }

    /**
     * The instrument, on the arrangement the measurement actually uses.
     *
     * <p>Both arms tick the millstone for every tick of the window; nothing is jumped and nothing
     * else is touched. The only thing left between them is that one of them was rebuilt from a
     * recorded tag and re-attached to its network first.
     *
     * <p>It has to agree, and that is what it is for. Two arms that do the same thing and land
     * anywhere apart mean the instrument is moving under the measurement, and every result
     * taken on this arena would be an artefact that looks exactly like a catch-up bug.
     *
     * <p>It did not agree for a long time. What it was reporting was not a rebuild from NBT
     * failing to reproduce the machine: the restore is idempotent on the bytes, three rounds
     * of it in a row leaving the same tag every time. It was the arena's own first pass of
     * ticking after a reload, which lands on kinetic bookkeeping no later pass lands on.
     * {@link #reconstructionIdempotenceDiagnostic} measures both halves of that.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "unloadedcatchup-control-one", timeoutTicks = 2400)
    public static void controlSingleMachineRealTicksMatch(GameTestHelper helper) {
        measure(helper, ChunkCatchUp.Mode.TICK_INTERLEAVED
                .restrictedTo(helper.absolutePos(MILLSTONE)), false);
    }

    /**
     * The same comparison with the jumping taken out, one machine at a time.
     *
     * <p>This is the real path's order — the catch-up finishes one block entity's whole window
     * before starting the next — with every tick run for real. What it isolates: whether the
     * scaffolding around the comparison is faithful, which nothing else here establishes. Arm A
     * restores each machine from a recorded tag and calls Create's own re-attach; if that
     * reconstruction were not the state arm B was actually in, every result on this arena would
     * be an artefact and would look exactly like a catch-up bug.
     *
     * <p>Binding. It answers a question about the instrument rather than about the mod, and an
     * instrument that stops agreeing with itself invalidates every other result taken on this
     * arena, which is exactly what a standing suite should go red on.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "unloadedcatchup-control-seq", timeoutTicks = 2400)
    public static void controlSequentialRealTicksMatch(GameTestHelper helper) {
        measure(helper, ChunkCatchUp.Mode.TICK_SEQUENTIAL, false);
    }

    /**
     * The same again in the game's own order: every machine one tick, then every machine the next.
     *
     * <p>Paired with the sequential control, this separates the two things the real path does at
     * once. If this one matches and the sequential one does not, then finishing a machine before
     * starting its neighbour is what moved the state, and the jumping is not implicated at all.
     *
     * <p>Binding, for the same reason as its pair.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "unloadedcatchup-control-inter", timeoutTicks = 2400)
    public static void controlInterleavedRealTicksMatch(GameTestHelper helper) {
        measure(helper, ChunkCatchUp.Mode.TICK_INTERLEAVED, false);
    }

    /**
     * One round trip, both arms, every assertion.
     *
     * @param requireJump whether the catch-up must have jumped for the result to mean anything.
     *                    False for the controls, which run every tick for real on purpose.
     */
    private static void measure(GameTestHelper helper, ChunkCatchUp.Mode mode,
                                boolean requireJump) {
        Comparison comparison = new Comparison();

        onArena(helper, WINDOW, mode, comparison, trip -> {
            ChunkCatchUp.Sweep sweep = trip.sweep;
            if (sweep == null) {
                helper.fail("the chunk came back but the catch-up was never offered it: "
                        + trip.describe());
                return;
            }
            Meanwhile.LOGGER.info("[unloaded] round trip | unloads={} loads={} unloadAt={}"
                            + " loadAt={} sweepAt={} loadToSweep={}",
                    RoundTripImages.unloads(), RoundTripImages.loads(),
                    RoundTripImages.unloadAt(), RoundTripImages.loadAt(), sweep.at(),
                    sweep.at() - RoundTripImages.loadAt());
            Meanwhile.LOGGER.info("[unloaded] {}", comparison.summary());

            String vacuous = trip.vacuous();
            if (vacuous != null) {
                helper.fail(vacuous);
                return;
            }

            // 1. The trip itself. Without this, pinning arm A to the observed post-load state
            //    would let a reload that damaged the machine agree with itself.
            RoundTripImages.Report report =
                    RoundTripImages.reportWithin(trip.subject.exactRegion(helper));
            Meanwhile.LOGGER.info("[unloaded] round trip within the machine | {} | added={}",
                    report.summary(), report.added());
            Meanwhile.LOGGER.info("[unloaded] round trip across the chunk | {}",
                    RoundTripImages.roundTripDifferences());
            if (!report.preserved()) {
                helper.fail("the machine did not come back the way it went out, so the state the"
                        + " catch-up started from is not the state that was saved: lost="
                        + report.lost() + " changed=" + report.changed());
                return;
            }

            // 2. The catch-up has to have done something, or the match only says ticking works.
            ChunkCatchUp.Attempt millstone = comparison.attemptAt(helper.absolutePos(MILLSTONE));
            if (millstone == null) {
                helper.fail("the millstone was never offered to the catch-up; attempts="
                        + describeAttempts(sweep));
                return;
            }
            if (millstone.declined()) {
                helper.fail("the catch-up declined the millstone, so the comparison only shows"
                        + " that ticking works: " + millstone.declineReason());
                return;
            }
            if (requireJump && millstone.jumpedTicks() <= 0) {
                helper.fail("the catch-up ticked the whole window on the millstone and jumped"
                        + " nothing, so the match shows nothing: " + millstone.summary());
                return;
            }
            if (comparison.groundB < REQUIRED_GROUND) {
                helper.fail("arm B produced " + comparison.groundB + " ground items, under the "
                        + REQUIRED_GROUND + " needed to be sure the window crossed a completion"
                        + " boundary rather than only moving a counter");
                return;
            }

            // 3. The serialised surface, judged against how far this arena moves on its own.
            //    Bit equality is the claim, and where it holds the noise floor is empty and this
            //    is exactly bit equality. Where it does not, the comparison still has to say
            //    something true: a key the arena wanders on between two identical runs cannot be
            //    evidence about a catch-up, and every key outside that set is.
            Meanwhile.LOGGER.info("[unloaded] serialised keys | noise={} signal={} beyondNoise={}"
                            + " exactDifference={}",
                    comparison.noiseKeys, comparison.signalKeys, comparison.beyondNoise,
                    comparison.exactDifference == null ? "<none>" : "present");
            if (!comparison.beyondNoise.isEmpty()) {
                helper.fail("the caught-up machine differs from the ticked one on state that"
                        + " reaches disk and that two identical ticked runs agree on: "
                        + comparison.beyondNoise + " || " + comparison.exactDifference);
                return;
            }
            if (comparison.groundA != comparison.groundB) {
                helper.fail("the catch-up produced a different amount of work than ticking:"
                        + " ticked=" + comparison.groundA + " caught-up=" + comparison.groundB);
                return;
            }

            // 4. The wider surface, whose divergence must be confined to live fields.
            List<String> serialised = new ArrayList<>();
            Set<String> fields = new LinkedHashSet<>();
            for (String line : comparison.deepDifferences) {
                if (DeepStateDigest.isFieldLine(line)) {
                    fields.add(DeepStateDigest.fieldOf(line));
                } else {
                    serialised.add(line);
                }
            }
            Meanwhile.LOGGER.info("[unloaded] deep divergence | fields={} serialisedLines={}",
                    fields, serialised.size());
            if (!serialised.isEmpty()) {
                helper.fail("the wider comparison diverged in state the machine writes down: "
                        + serialised);
                return;
            }

            // 5. The first guard, asserted on what was actually handed over.
            if (ChunkCatchUp.minDispatchedTicks() < 1) {
                helper.fail("a non-positive tick count reached the catch-up: minDispatched="
                        + ChunkCatchUp.minDispatchedTicks());
                return;
            }
            helper.succeed();
        });
    }

    // ---- is the debt paid in instalments, and exactly once? ----------------------------------------

    /** A gap big enough that paying it in one go is something a person would feel. */
    private static final int BIG_GAP = 120000;

    /**
     * The same debt settled twice: once in a single payment, once in instalments.
     *
     * <p>Three things are being asked at once, and they share an arena because they are the same
     * mechanism seen from three sides.
     *
     * <p><b>Does it cost less at once?</b> The number that matters is not how long the catch-up
     * takes in total — that is the same work either way — but the longest a single level tick is
     * held up by it, because that is the stall a player standing there would feel. Both phases
     * report their worst drain.
     *
     * <p><b>Is it actually split?</b> A budget that is never reached would leave this test passing
     * while measuring one payment under another name, so the number of instalments is asserted,
     * not just logged.
     *
     * <p><b>Is anything paid twice?</b> This is the one that would be silent and severe. Ticks
     * handed over twice are production out of nothing, and nothing downstream would notice. The
     * ledger is kept per chunk over the whole run — what it has been told it is owed, and what has
     * actually been handed over — and the two have to agree exactly, across both phases.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "unloadedcatchup-debt", timeoutTicks = 6000)
    public static void debtIsPaidInInstalmentsAndOnlyOnce(GameTestHelper helper) {
        RoundTripImages.install();
        if (!ChunkCatchUp.isInstalled()) {
            helper.fail("the catch-up is not installed; write meanwhile-catchup.properties");
            return;
        }
        Instalments probe = new Instalments(helper, new int[]{0, ChunkCatchUp.SLICE_TICKS}, true);
        helper.startSequence()
                .thenExecuteFor(5400, probe::step)
                .thenExecute(probe::judge)
                .thenSucceed();
    }

    /**
     * The same debt settled at a range of instalment sizes, to pick the product's default.
     *
     * <p>Nothing is asserted about which size is best; the numbers are what the choice is made
     * from. What is asserted is that every size settles the debt exactly once, because a ledger
     * that only balances at one budget would not be a ledger.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "unloadedcatchup-budget", timeoutTicks = 20000)
    public static void instalmentSizeSweep(GameTestHelper helper) {
        RoundTripImages.install();
        // Five readings per size. One reading per size cannot tell a difference between sizes
        // from the spread within a size, and the first pass at this produced a non-monotonic
        // table that was read as a floor.
        int[] repeats = new int[]{250, 500, 1000, 2000, 8000};
        int[] slices = new int[repeats.length * 5];
        for (int i = 0; i < slices.length; i++) {
            slices[i] = repeats[i % repeats.length];
        }
        Instalments probe = new Instalments(helper, slices, false);
        helper.startSequence()
                .thenExecuteFor(19000, probe::step)
                .thenExecute(probe::judge)
                .thenSucceed();
    }

    private static final class Instalments {

        private enum Step { PLACING, SETTLING, RESET, RELEASED, GONE, BACK, PAYING, DONE }

        private static final int WAIT = 60;

        private final GameTestHelper helper;
        private final ServerLevel level;
        private final ChunkPos target;
        private final List<ChunkPos> arena;
        private final MillstoneSubject subject = new MillstoneSubject();

        private Step step = Step.PLACING;
        private int countdown = SETTLE;
        private int phase;
        private long releasedAt = -1L;
        private long unloadAt = -1L;
        private long askedAt = -1L;
        private long payingSince = -1L;

        /** Instalment size per phase; 0 means settle it all at once. */
        private final int[] slices;
        private final boolean gate;
        private final long[] worstMicros;
        private final int[] drainsUsed;
        private final long[] ticksToPay;
        private final int[] instalments;
        private final long[] owed;
        private final long[] paid;
        @Nullable
        private String failure;

        private Instalments(GameTestHelper helper, int[] slices, boolean gate) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.target = new ChunkPos(helper.absolutePos(MILLSTONE));
            this.arena = arenaChunks(helper);
            this.slices = slices;
            this.gate = gate;
            this.worstMicros = new long[slices.length];
            this.drainsUsed = new int[slices.length];
            this.ticksToPay = new long[slices.length];
            this.instalments = new int[slices.length];
            this.owed = new long[slices.length];
            this.paid = new long[slices.length];
        }

        private void step() {
            if (step == Step.DONE) {
                return;
            }
            long now = level.getGameTime();
            switch (step) {
                case PLACING -> {
                    if (!subject.place(helper)) {
                        fail("create:millstone or create:creative_motor is not registered");
                        return;
                    }
                    step = Step.SETTLING;
                }
                case SETTLING -> {
                    if (--countdown > 0) {
                        return;
                    }
                    String blocked = subject.arm(helper);
                    if (blocked != null) {
                        fail("the arena is not usable: " + blocked);
                        return;
                    }
                    step = Step.RESET;
                }
                case RESET -> {
                    subject.reset(helper);
                    ChunkCatchUp.resetCounters();
                    // Phase 0 settles the whole thing in one payment; phase 1 uses the product's
                    // own budget, so the comparison is against what would actually ship.
                    if (slices[phase] == 0) {
                        ChunkCatchUp.setBudget(Integer.MAX_VALUE, Integer.MAX_VALUE);
                    } else {
                        ChunkCatchUp.setBudget(slices[phase], Integer.MAX_VALUE);
                    }
                    ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT
                            .restrictedTo(helper.absolutePos(MILLSTONE))
                            .withFixedWindow(BIG_GAP));
                    RoundTripImages.watch(target);
                    for (ChunkPos chunk : arena) {
                        level.setChunkForced(chunk.x, chunk.z, false);
                    }
                    releasedAt = now;
                    step = Step.RELEASED;
                }
                case RELEASED -> {
                    if (RoundTripImages.unloads() > 0) {
                        unloadAt = RoundTripImages.unloadAt();
                        step = Step.GONE;
                        return;
                    }
                    if (now - releasedAt > UNLOAD_WAIT) {
                        fail("phase " + phase + ": no ChunkEvent.Unload in " + UNLOAD_WAIT);
                    }
                }
                case GONE -> {
                    if (now - unloadAt < WAIT) {
                        return;
                    }
                    for (ChunkPos chunk : arena) {
                        level.setChunkForced(chunk.x, chunk.z, true);
                    }
                    askedAt = now;
                    payingSince = -1L;
                    step = Step.BACK;
                }
                case BACK -> {
                    // Watched through the ledger rather than the outstanding balance: the sweep
                    // and the drain both run inside one level tick, so a debt settled in a single
                    // payment is never observable as a non-zero balance from out here.
                    if (ChunkCatchUp.owedFor(target) > 0) {
                        payingSince = now;
                        step = Step.PAYING;
                        return;
                    }
                    if (now - askedAt > BACK_WAIT) {
                        fail("phase " + phase + ": the chunk came back at " + askedAt
                                + " and was never told it owed anything");
                    }
                }
                case PAYING -> {
                    if (ChunkCatchUp.paidFor(target) < ChunkCatchUp.owedFor(target)) {
                        if (now - payingSince > 2000) {
                            fail("phase " + phase + ": still owing "
                                    + ChunkCatchUp.debtFor(level, target) + " after 2000 ticks");
                        }
                        return;
                    }
                    worstMicros[phase] = ChunkCatchUp.worstDrainMicros();
                    drainsUsed[phase] = ChunkCatchUp.drains();
                    ticksToPay[phase] = now - payingSince + 1;
                    instalments[phase] = ChunkCatchUp.slicesFor(target);
                    owed[phase] = ChunkCatchUp.owedFor(target);
                    paid[phase] = ChunkCatchUp.paidFor(target);
                    // One placeholder per value, checked against the argument list. The earlier
                    // form carried an extra argument and every field after it printed the wrong
                    // quantity under the right name.
                    Meanwhile.LOGGER.info("[debt] phase {} | slice={} gap={} paidOver={} ticks"
                                    + " drains={} worstDrain={}us owed={} paid={} queue={}"
                                    + " reentryRefused={} instalments={}",
                            phase, slices[phase] == 0 ? "unlimited" : String.valueOf(slices[phase]),
                            BIG_GAP, ticksToPay[phase], drainsUsed[phase], worstMicros[phase],
                            owed[phase], paid[phase], ChunkCatchUp.queueLength(),
                            ChunkCatchUp.reentryRefused(), instalments[phase]);
                    if (++phase >= slices.length) {
                        step = Step.DONE;
                        return;
                    }
                    step = Step.RESET;
                }
                default -> {
                }
            }
        }

        private void fail(String message) {
            if (failure == null) {
                failure = message;
            }
            step = Step.DONE;
        }

        private void judge() {
            for (ChunkPos chunk : arena) {
                level.setChunkForced(chunk.x, chunk.z, true);
            }
            ChunkCatchUp.restoreBudget();
            ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT);
            ChunkCatchUp.forget(level);
            RoundTripImages.stopWatching();

            for (int i = 0; i < slices.length; i++) {
                Meanwhile.LOGGER.info("[debt] RESULT slice={} | worstDrain={}us instalments={}"
                                + " paidOver={} ticks owed={} paid={}",
                        slices[i] == 0 ? "unlimited" : String.valueOf(slices[i]), worstMicros[i],
                        instalments[i], ticksToPay[i], owed[i], paid[i]);
            }

            if (failure != null) {
                helper.fail(failure);
                return;
            }
            for (int i = 0; i < slices.length; i++) {
                if (owed[i] != BIG_GAP) {
                    helper.fail("phase " + i + " was told it owed " + owed[i] + " rather than "
                            + BIG_GAP + ", so the ledger is not measuring the window");
                    return;
                }
                if (paid[i] != owed[i]) {
                    helper.fail("phase " + i + " was owed " + owed[i] + " and handed over "
                            + paid[i] + "; ticks given twice are production out of nothing and"
                            + " nothing downstream would notice");
                    return;
                }
            }
            if (ChunkCatchUp.reentryRefused() > 0) {
                Meanwhile.LOGGER.info("[debt] reentry refused {} time(s); the guard turned those"
                        + " calls away before they touched anything",
                        ChunkCatchUp.reentryRefused());
            }
            if (!gate) {
                helper.succeed();
                return;
            }
            if (instalments[0] != 1) {
                helper.fail("the unbudgeted phase took " + instalments[0] + " instalments rather"
                        + " than one, so it is not the single payment it is meant to be");
                return;
            }
            if (instalments[1] <= 1) {
                helper.fail("the budgeted phase settled in " + instalments[1] + " instalment(s),"
                        + " so nothing was split and this compares one payment with one payment");
                return;
            }
            if (worstMicros[1] >= worstMicros[0]) {
                helper.fail("the longest single drain did not fall when the debt was split: one"
                        + " payment " + worstMicros[0] + "us, instalments " + worstMicros[1]
                        + "us");
                return;
            }
            helper.succeed();
        }
    }

    // ---- interrupted repayment ---------------------------------------------------------------------

    /**
     * A chunk that goes away again before it has been paid off, and a save while it is being paid.
     *
     * <p>Paying a debt over sixty ticks means sixty ticks in which the chunk can be dropped, the
     * server can save, or the process can stop. Losing the outstanding part would quietly undo
     * the absence; paying it twice would be production out of nothing. The ledger is kept per
     * chunk across the whole run, so the assertion is the same one either way and it now has to
     * hold across an interruption rather than only inside one uninterrupted repayment.
     *
     * <p>Three interruptions, in order: a forced save while the balance is outstanding, then a
     * full unload and reload with the balance still outstanding, then the repayment allowed to
     * finish.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "unloadedcatchup-interrupt", timeoutTicks = 8000)
    public static void interruptedRepaymentLosesNothingAndPaysOnce(GameTestHelper helper) {
        RoundTripImages.install();
        Interrupted probe = new Interrupted(helper);
        helper.startSequence()
                .thenExecuteFor(7200, probe::step)
                .thenExecute(probe::judge)
                .thenSucceed();
    }

    private static final class Interrupted {

        private enum Step { PLACING, SETTLING, RESET, RELEASED, GONE, BACK, PAYING_SAVE,
            PAYING_DROP, DROPPED, DROPPED_WAIT, RETURNED, FINISHING, DONE }

        /** Big enough that the balance is still outstanding when the interruptions land. */
        private static final int GAP = 60000;
        private static final int SLICE = 500;
        private static final int WAIT = 40;

        private final GameTestHelper helper;
        private final ServerLevel level;
        private final ChunkPos target;
        private final List<ChunkPos> arena;
        private final MillstoneSubject subject = new MillstoneSubject();

        private Step step = Step.PLACING;
        private int countdown = SETTLE;
        private long releasedAt = -1L;
        private long unloadAt = -1L;
        private long markAt = -1L;
        private long debtAtSave = -1L;
        private long debtAfterSave = -1L;
        private long debtBeforeDrop = -1L;
        private long debtAfterReturn = -1L;
        private long paidBeforeDrop = -1L;
        @Nullable
        private String failure;

        private Interrupted(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.target = new ChunkPos(helper.absolutePos(MILLSTONE));
            this.arena = arenaChunks(helper);
        }

        private void step() {
            if (step == Step.DONE) {
                return;
            }
            long now = level.getGameTime();
            switch (step) {
                case PLACING -> {
                    if (!subject.place(helper)) {
                        fail("create:millstone or create:creative_motor is not registered");
                        return;
                    }
                    step = Step.SETTLING;
                }
                case SETTLING -> {
                    if (--countdown > 0) {
                        return;
                    }
                    String blocked = subject.arm(helper);
                    if (blocked != null) {
                        fail("the arena is not usable: " + blocked);
                        return;
                    }
                    ChunkCatchUp.resetCounters();
                    ChunkCatchUp.setBudget(SLICE, Integer.MAX_VALUE);
                    ChunkCatchUp.setCarryDebtAcrossReload(!brokenPersistenceRequested());
                    ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT
                            .restrictedTo(helper.absolutePos(MILLSTONE)).withFixedWindow(GAP));
                    step = Step.RESET;
                }
                case RESET -> {
                    subject.reset(helper);
                    RoundTripImages.watch(target);
                    for (ChunkPos chunk : arena) {
                        level.setChunkForced(chunk.x, chunk.z, false);
                    }
                    releasedAt = now;
                    step = Step.RELEASED;
                }
                case RELEASED -> {
                    if (RoundTripImages.unloads() > 0) {
                        unloadAt = RoundTripImages.unloadAt();
                        step = Step.GONE;
                        return;
                    }
                    if (now - releasedAt > UNLOAD_WAIT) {
                        fail("no ChunkEvent.Unload in " + UNLOAD_WAIT);
                    }
                }
                case GONE -> {
                    if (now - unloadAt < WAIT) {
                        return;
                    }
                    for (ChunkPos chunk : arena) {
                        level.setChunkForced(chunk.x, chunk.z, true);
                    }
                    markAt = now;
                    step = Step.BACK;
                }
                case BACK -> {
                    if (ChunkCatchUp.debtFor(level, target) > 0) {
                        step = Step.PAYING_SAVE;
                        return;
                    }
                    if (now - markAt > BACK_WAIT) {
                        fail("the chunk came back and never carried a balance; owed="
                                + ChunkCatchUp.owedFor(target)
                                + " paid=" + ChunkCatchUp.paidFor(target));
                    }
                }
                // Interruption 1: a save while the balance is outstanding.
                case PAYING_SAVE -> {
                    debtAtSave = ChunkCatchUp.debtFor(level, target);
                    level.getChunkSource().save(false);
                    debtAfterSave = ChunkCatchUp.debtFor(level, target);
                    Meanwhile.LOGGER.info("[interrupt] saved mid-repayment | debtBefore={}"
                            + " debtAfter={}", debtAtSave, debtAfterSave);
                    step = Step.PAYING_DROP;
                }
                // Interruption 2: drop it entirely, with the balance still outstanding.
                case PAYING_DROP -> {
                    debtBeforeDrop = ChunkCatchUp.debtFor(level, target);
                    paidBeforeDrop = ChunkCatchUp.paidFor(target);
                    if (debtBeforeDrop <= 0) {
                        fail("the balance was settled before it could be interrupted; raise the"
                                + " gap or lower the slice");
                        return;
                    }
                    RoundTripImages.watch(target);
                    for (ChunkPos chunk : arena) {
                        level.setChunkForced(chunk.x, chunk.z, false);
                    }
                    releasedAt = now;
                    Meanwhile.LOGGER.info("[interrupt] dropping mid-repayment | debt={} paid={}",
                            debtBeforeDrop, paidBeforeDrop);
                    step = Step.DROPPED;
                }
                case DROPPED -> {
                    if (RoundTripImages.unloads() > 0) {
                        unloadAt = RoundTripImages.unloadAt();
                        step = Step.DROPPED_WAIT;
                        return;
                    }
                    if (now - releasedAt > UNLOAD_WAIT) {
                        fail("the chunk did not unload while it still owed " + debtBeforeDrop);
                    }
                }
                case DROPPED_WAIT -> {
                    if (now - unloadAt < WAIT) {
                        return;
                    }
                    for (ChunkPos chunk : arena) {
                        level.setChunkForced(chunk.x, chunk.z, true);
                    }
                    markAt = now;
                    step = Step.RETURNED;
                }
                case RETURNED -> {
                    if (level.getChunkSource().getChunkNow(target.x, target.z) != null) {
                        debtAfterReturn = ChunkCatchUp.debtFor(level, target);
                        Meanwhile.LOGGER.info("[interrupt] back | debtBeforeDrop={}"
                                        + " debtAfterReturn={} owed={} paid={}",
                                debtBeforeDrop, debtAfterReturn, ChunkCatchUp.owedFor(target),
                                ChunkCatchUp.paidFor(target));
                        markAt = now;
                        step = Step.FINISHING;
                        return;
                    }
                    if (now - markAt > BACK_WAIT) {
                        fail("the chunk did not come back");
                    }
                }
                case FINISHING -> {
                    if (ChunkCatchUp.debtFor(level, target) <= 0
                            && ChunkCatchUp.paidFor(target) >= ChunkCatchUp.owedFor(target)) {
                        step = Step.DONE;
                        return;
                    }
                    if (now - markAt > 4000) {
                        fail("the balance was still " + ChunkCatchUp.debtFor(level, target)
                                + " after 4000 ticks; owed=" + ChunkCatchUp.owedFor(target)
                                + " paid=" + ChunkCatchUp.paidFor(target));
                    }
                }
                default -> {
                }
            }
        }

        private void fail(String message) {
            if (failure == null) {
                failure = message;
            }
            step = Step.DONE;
        }

        private void judge() {
            for (ChunkPos chunk : arena) {
                level.setChunkForced(chunk.x, chunk.z, true);
            }
            long owed = ChunkCatchUp.owedFor(target);
            long paid = ChunkCatchUp.paidFor(target);
            Meanwhile.LOGGER.info("[interrupt] RESULT | persistenceBroken={} debtAtSave={}"
                            + " debtAfterSave={} debtBeforeDrop={} paidBeforeDrop={}"
                            + " debtAfterReturn={} owed={} paid={} difference={}",
                    brokenPersistenceRequested(), debtAtSave, debtAfterSave, debtBeforeDrop,
                    paidBeforeDrop, debtAfterReturn, owed, paid, owed - paid);
            ChunkCatchUp.restoreBudget();
            ChunkCatchUp.setCarryDebtAcrossReload(true);
            ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT);
            ChunkCatchUp.forget(level);
            RoundTripImages.stopWatching();

            if (failure != null) {
                helper.fail(failure);
                return;
            }
            if (debtAtSave != debtAfterSave) {
                helper.fail("a save changed what was outstanding: before=" + debtAtSave
                        + " after=" + debtAfterSave);
                return;
            }
            if (paid != owed) {
                helper.fail("across a save, an unload and a reload the ledger does not balance:"
                        + " owed=" + owed + " paid=" + paid + " (difference " + (owed - paid)
                        + "); ticks lost are an absence undone, ticks repeated are production"
                        + " out of nothing");
                return;
            }
            helper.succeed();
        }
    }

    /**
     * Ten short absences instead of one long one.
     *
     * <p>An error of one tick per round trip is invisible once and obvious ten times. The same
     * total gap is taken in ten pieces, and the ledger has to balance over the lot.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "unloadedcatchup-repeat", timeoutTicks = 8000)
    public static void tenShortRoundTripsBalanceLikeOneLongOne(GameTestHelper helper) {
        RoundTripImages.install();
        Repeated probe = new Repeated(helper);
        helper.startSequence()
                .thenExecuteFor(7200, probe::step)
                .thenExecute(probe::judge)
                .thenSucceed();
    }

    private static final class Repeated {

        private enum Step { PLACING, SETTLING, RESET, RELEASED, GONE, BACK, DONE }

        private static final int TRIPS = 10;
        private static final int PER_TRIP = 90;
        private static final int WAIT = 30;

        private final GameTestHelper helper;
        private final ServerLevel level;
        private final ChunkPos target;
        private final List<ChunkPos> arena;
        private final MillstoneSubject subject = new MillstoneSubject();

        private Step step = Step.PLACING;
        private int countdown = SETTLE;
        private int trip;
        private long releasedAt = -1L;
        private long unloadAt = -1L;
        private long markAt = -1L;
        private final List<String> perTrip = new ArrayList<>();
        @Nullable
        private String failure;

        private Repeated(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.target = new ChunkPos(helper.absolutePos(MILLSTONE));
            this.arena = arenaChunks(helper);
        }

        private void step() {
            if (step == Step.DONE) {
                return;
            }
            long now = level.getGameTime();
            switch (step) {
                case PLACING -> {
                    if (!subject.place(helper)) {
                        fail("create:millstone or create:creative_motor is not registered");
                        return;
                    }
                    step = Step.SETTLING;
                }
                case SETTLING -> {
                    if (--countdown > 0) {
                        return;
                    }
                    String blocked = subject.arm(helper);
                    if (blocked != null) {
                        fail("the arena is not usable: " + blocked);
                        return;
                    }
                    ChunkCatchUp.resetCounters();
                    ChunkCatchUp.restoreBudget();
                    ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT
                            .restrictedTo(helper.absolutePos(MILLSTONE))
                            .withFixedWindow(PER_TRIP));
                    subject.reset(helper);
                    step = Step.RESET;
                }
                case RESET -> {
                    RoundTripImages.watch(target);
                    for (ChunkPos chunk : arena) {
                        level.setChunkForced(chunk.x, chunk.z, false);
                    }
                    releasedAt = now;
                    step = Step.RELEASED;
                }
                case RELEASED -> {
                    if (RoundTripImages.unloads() > 0) {
                        unloadAt = RoundTripImages.unloadAt();
                        step = Step.GONE;
                        return;
                    }
                    if (now - releasedAt > UNLOAD_WAIT) {
                        fail("trip " + trip + ": no unload");
                    }
                }
                case GONE -> {
                    if (now - unloadAt < WAIT) {
                        return;
                    }
                    for (ChunkPos chunk : arena) {
                        level.setChunkForced(chunk.x, chunk.z, true);
                    }
                    markAt = now;
                    step = Step.BACK;
                }
                case BACK -> {
                    long owed = ChunkCatchUp.owedFor(target);
                    long paid = ChunkCatchUp.paidFor(target);
                    if (owed >= (long) (trip + 1) * PER_TRIP && paid >= owed
                            && ChunkCatchUp.debtFor(level, target) <= 0) {
                        perTrip.add("trip" + trip + ":owed=" + owed + ",paid=" + paid);
                        if (++trip >= TRIPS) {
                            step = Step.DONE;
                            return;
                        }
                        step = Step.RESET;
                        return;
                    }
                    if (now - markAt > BACK_WAIT) {
                        fail("trip " + trip + ": owed=" + owed + " paid=" + paid + " debt="
                                + ChunkCatchUp.debtFor(level, target));
                    }
                }
                default -> {
                }
            }
        }

        private void fail(String message) {
            if (failure == null) {
                failure = message;
            }
            step = Step.DONE;
        }

        private void judge() {
            for (ChunkPos chunk : arena) {
                level.setChunkForced(chunk.x, chunk.z, true);
            }
            long owed = ChunkCatchUp.owedFor(target);
            long paid = ChunkCatchUp.paidFor(target);
            Meanwhile.LOGGER.info("[repeat] RESULT | trips={}/{} perTrip={} totalOwed={}"
                            + " totalPaid={} expected={} difference={}",
                    trip, TRIPS, PER_TRIP, owed, paid, (long) TRIPS * PER_TRIP, owed - paid);
            for (String line : perTrip) {
                Meanwhile.LOGGER.info("[repeat] {}", line);
            }
            ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT);
            ChunkCatchUp.forget(level);
            RoundTripImages.stopWatching();

            if (failure != null) {
                helper.fail(failure);
                return;
            }
            if (trip < TRIPS) {
                helper.fail("only " + trip + " of " + TRIPS + " round trips finished");
                return;
            }
            if (owed != (long) TRIPS * PER_TRIP) {
                helper.fail("ten trips of " + PER_TRIP + " came to " + owed
                        + " rather than " + (long) TRIPS * PER_TRIP
                        + "; the count drifts across round trips");
                return;
            }
            if (paid != owed) {
                helper.fail("over ten round trips the ledger does not balance: owed=" + owed
                        + " paid=" + paid);
                return;
            }
            helper.succeed();
        }
    }

    /** Drop what is still outstanding when a chunk goes away, instead of carrying it. */
    private static boolean brokenPersistenceRequested() {
        return marker("meanwhile-nc-lose-debt.properties");
    }

    // ---- does a machine that came back still get ticked? -------------------------------------------

    /**
     * Whether the game keeps ticking a block entity after its chunk has been away.
     *
     * <p>Nothing is caught up here — the catch-up is set to look at the chunk and spend nothing —
     * because the question is about the round trip alone. A machine that comes back and is never
     * ticked again would be a fault of the round trip that no amount of correct catching up could
     * make good, and it would also mean the comparison's settling window is measuring two states
     * that cannot move.
     *
     * <p>The same window is run twice on the same machine: once with the chunk left alone, and
     * once after it has been dropped and asked back. Both are counted in real server ticks, so
     * "the game ticked it" is the thing measured rather than assumed. The chunk's own ticker
     * registry ({@code LevelChunk.tickersInLevel}) is read at each sample, so that a machine that
     * is not advancing can be told apart from one that is not registered to advance.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "unloadedcatchup-natural", timeoutTicks = 3000)
    public static void roundTripBlockEntityKeepsTickingNaturally(GameTestHelper helper) {
        RoundTripImages.install();
        Natural probe = new Natural(helper);
        helper.startSequence()
                .thenExecuteFor(2400, probe::step)
                .thenExecute(probe::judge)
                .thenSucceed();
    }

    /** Real server ticks each half of the natural-ticking probe is watched for. */
    private static final int NATURAL_WINDOW = 100;

    private static final class Natural {

        private enum Step { PLACING, SETTLING, LOADED_RUN, RESET, RELEASED, GONE, BACK,
            RETURNED_RUN, DONE }

        private static final int WAIT = 300;

        private final GameTestHelper helper;
        private final ServerLevel level;
        private final ChunkPos target;
        private final BlockPos pos;
        private final List<ChunkPos> arena;
        private final MillstoneSubject subject = new MillstoneSubject();

        private Step step = Step.PLACING;
        private int countdown = SETTLE;
        private int ran;
        private long releasedAt = -1L;
        private long unloadAt = -1L;
        private long askedAt = -1L;
        private long backAt = -1L;
        private int loadedStartTimer = Integer.MIN_VALUE;
        private int loadedEndTimer = Integer.MIN_VALUE;
        private double loadedStartGround;
        private double loadedEndGround;
        private int returnedStartTimer = Integer.MIN_VALUE;
        private int returnedEndTimer = Integer.MIN_VALUE;
        private double returnedStartGround;
        private double returnedEndGround;
        @Nullable
        private String failure;

        private Natural(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.pos = helper.absolutePos(MILLSTONE);
            this.target = new ChunkPos(pos);
            this.arena = arenaChunks(helper);
        }

        private void step() {
            if (step == Step.DONE) {
                return;
            }
            long now = level.getGameTime();
            switch (step) {
                case PLACING -> {
                    if (!subject.place(helper)) {
                        fail("create:millstone or create:creative_motor is not registered");
                        return;
                    }
                    step = Step.SETTLING;
                }
                case SETTLING -> {
                    if (--countdown > 0) {
                        return;
                    }
                    String blocked = subject.arm(helper);
                    if (blocked != null) {
                        fail("the arena is not usable: " + blocked);
                        return;
                    }
                    ChunkCatchUp.setMode(ChunkCatchUp.Mode.NO_CATCH_UP);
                    subject.reset(helper);
                    loadedStartTimer = timerOf(level, pos);
                    loadedStartGround = subject.observe(helper)[0];
                    sample("loaded/start");
                    ran = NATURAL_WINDOW;
                    step = Step.LOADED_RUN;
                }
                case LOADED_RUN -> {
                    if (--ran > 0) {
                        if (ran % 25 == 0) {
                            sample("loaded/+" + (NATURAL_WINDOW - ran));
                        }
                        return;
                    }
                    loadedEndTimer = timerOf(level, pos);
                    loadedEndGround = subject.observe(helper)[0];
                    sample("loaded/end");
                    step = Step.RESET;
                }
                case RESET -> {
                    subject.reset(helper);
                    RoundTripImages.watch(target);
                    for (ChunkPos chunk : arena) {
                        level.setChunkForced(chunk.x, chunk.z, false);
                    }
                    releasedAt = now;
                    step = Step.RELEASED;
                }
                case RELEASED -> {
                    if (RoundTripImages.unloads() > 0) {
                        unloadAt = RoundTripImages.unloadAt();
                        step = Step.GONE;
                        return;
                    }
                    if (now - releasedAt > UNLOAD_WAIT) {
                        fail("no ChunkEvent.Unload in " + UNLOAD_WAIT + " ticks");
                    }
                }
                case GONE -> {
                    if (now - unloadAt < WAIT) {
                        return;
                    }
                    for (ChunkPos chunk : arena) {
                        level.setChunkForced(chunk.x, chunk.z, true);
                    }
                    askedAt = now;
                    step = Step.BACK;
                }
                case BACK -> {
                    if (RoundTripImages.loads() > 0 && RoundTripImages.loadAt() >= askedAt) {
                        backAt = now;
                        returnedStartTimer = timerOf(level, pos);
                        returnedStartGround = subject.observe(helper)[0];
                        sample("returned/start");
                        ran = NATURAL_WINDOW;
                        step = Step.RETURNED_RUN;
                        return;
                    }
                    if (now - askedAt > BACK_WAIT) {
                        fail("no ChunkEvent.Load in " + BACK_WAIT + " ticks after asking");
                    }
                }
                case RETURNED_RUN -> {
                    if (--ran > 0) {
                        if (ran % 25 == 0) {
                            sample("returned/+" + (NATURAL_WINDOW - ran));
                        }
                        return;
                    }
                    returnedEndTimer = timerOf(level, pos);
                    returnedEndGround = subject.observe(helper)[0];
                    sample("returned/end");
                    step = Step.DONE;
                }
                default -> {
                }
            }
        }

        /** One reading of everything that could explain a machine standing still. */
        private void sample(String label) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(target.x, target.z);
            Meanwhile.LOGGER.info("[natural] {} | t={} timer={} ground={} speed={}"
                            + " shouldTickBlocks={} chunkLoaded={} ticker={}",
                    label, level.getGameTime(), timerOf(level, pos),
                    subject.observe(helper)[0], speedOf(level.getBlockEntity(pos)),
                    level.shouldTickBlocksAt(target.toLong()), chunk != null,
                    tickerRegistration(chunk, pos));
        }

        private void fail(String message) {
            if (failure == null) {
                failure = message;
            }
            step = Step.DONE;
        }

        private void judge() {
            for (ChunkPos chunk : arena) {
                level.setChunkForced(chunk.x, chunk.z, true);
            }
            ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT);
            RoundTripImages.stopWatching();

            int loadedDelta = loadedEndTimer - loadedStartTimer;
            int returnedDelta = returnedEndTimer - returnedStartTimer;
            Meanwhile.LOGGER.info("[natural] RESULT | window={} ticks || never-left: timer {}->{}"
                            + " delta={} ground {}->{} || after-round-trip: timer {}->{} delta={}"
                            + " ground {}->{} || unloadAt={} loadAt={} backAt={}",
                    NATURAL_WINDOW, loadedStartTimer, loadedEndTimer, loadedDelta,
                    loadedStartGround, loadedEndGround, returnedStartTimer, returnedEndTimer,
                    returnedDelta, returnedStartGround, returnedEndGround,
                    unloadAt, RoundTripImages.loadAt(), backAt);

            if (failure != null) {
                helper.fail(failure);
                return;
            }
            if (step != Step.DONE) {
                helper.fail("the probe did not finish: step=" + step);
                return;
            }
            boolean neverLeftMoved = loadedDelta != 0 || loadedEndGround != loadedStartGround;
            boolean returnedMoved = returnedDelta != 0 || returnedEndGround != returnedStartGround;
            if (!neverLeftMoved) {
                helper.fail("the machine did not advance in " + NATURAL_WINDOW + " server ticks"
                        + " even without leaving, so this probe cannot tell whether a round trip"
                        + " costs anything: timer " + loadedStartTimer + "->" + loadedEndTimer);
                return;
            }
            if (!returnedMoved) {
                helper.fail("the machine advanced " + NATURAL_WINDOW + " ticks' worth before it"
                        + " left and nothing at all after it came back, so the game is no longer"
                        + " ticking it: timer " + returnedStartTimer + "->" + returnedEndTimer
                        + " ground " + returnedStartGround + "->" + returnedEndGround);
                return;
            }
            helper.succeed();
        }
    }

    /** The millstone's countdown, straight off its serialised form. */
    private static int timerOf(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity == null
                ? Integer.MIN_VALUE
                : blockEntity.saveWithoutMetadata(level.registryAccess()).getInt("Timer");
    }

    /**
     * What the chunk's own ticker registry holds for this position.
     *
     * <p>{@code LevelChunk} keeps a map from position to a rebindable wrapper, and clearing a
     * chunk rebinds every one of them to a ticker that does nothing. Reading it separates "the
     * machine has nothing to do" from "nothing is going to ask it".
     */
    private static String tickerRegistration(@Nullable LevelChunk chunk, BlockPos pos) {
        if (chunk == null) {
            return "<chunk not loaded>";
        }
        try {
            java.lang.reflect.Field field =
                    net.minecraft.world.level.chunk.LevelChunk.class
                            .getDeclaredField("tickersInLevel");
            field.setAccessible(true);
            Map<?, ?> tickers = (Map<?, ?>) field.get(chunk);
            Object wrapper = tickers.get(pos);
            if (wrapper == null) {
                return "<no ticker registered> registered=" + tickers.size();
            }
            if (wrapper instanceof net.minecraft.world.level.block.entity.TickingBlockEntity live) {
                return "type=" + live.getType() + " removed=" + live.isRemoved()
                        + " registered=" + tickers.size();
            }
            return wrapper.getClass().getSimpleName();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "<unreadable: " + e + ">";
        }
    }

    // ---- the main gate: two round trips, one variable ---------------------------------------------

    /**
     * The window spent two ways, on two machines that both really went away and really came back.
     *
     * <blockquote>
     * <b>B1</b>: unload, N ticks gone, load, then spend the window as N real ticks.<br>
     * <b>B2</b>: unload, N ticks gone, load, then spend the window by catching up.
     * </blockquote>
     *
     * <p>One variable. The round trip is in both arms and cancels, so whatever a save and a load
     * cost is charged to both and neither is penalised for it. That is the whole reason this
     * exists rather than a comparison against a machine that never left: a millstone that never
     * left does not agree with one that did even when the window is ticked in full, so requiring
     * agreement with it would be requiring a save and load more faithful than the game's own.
     *
     * <p>Both trips start from the same recorded state and spend the same fixed number of ticks,
     * because the delay between dropping a forced ticket and the game letting the chunk go is not
     * the same twice and would otherwise put a different window in each arm.
     *
     * <p><b>C</b> — the same window on a machine that is never unloaded — runs first, in the same
     * arena, so that the kinetic network it is compared against is the same object. Nothing is
     * asserted against it. It is there to produce {@code diff(B1, C)}: the keys a round trip
     * costs on its own, generated every run rather than written down once, because a list of
     * exceptions maintained by hand goes stale and starts absorbing real faults.
     *
     * <p>Both comparisons are made twice: <b>strict</b>, in the tick the window finished, and
     * <b>settled</b>, after a few more real ticks in both arms. A field recomputed on the next
     * tick shows up in the first and not the second. Neither is dropped — settled alone would
     * hide a fault that heals inside the window, strict alone reports transients as faults.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "unloadedcatchup-b1b2", timeoutTicks = 6000)
    public static void roundTripCatchUpMatchesRoundTripTicking(GameTestHelper helper) {
        RoundTripImages.install();
        if (!ChunkCatchUp.isInstalled()) {
            helper.fail("the catch-up is not installed; write meanwhile-catchup.properties");
            return;
        }
        Duel duel = new Duel(helper);
        helper.startSequence()
                .thenExecuteFor(5400, duel::step)
                .thenExecute(() -> {
                    duel.restoreArena();
                    duel.judge();
                })
                .thenSucceed();
    }

    /**
     * Ticks added to both arms before the second of the two comparisons.
     *
     * <p>Longer than it looks like it needs to be, and measured rather than guessed. A millstone
     * whose chunk has just come back does not resume immediately: its countdown sat at zero for
     * between 25 and 50 server ticks before it started running again
     * ({@link #roundTripBlockEntityKeepsTickingNaturally} — {@code returned/+25 timer=0},
     * {@code returned/+50 timer=191}). A settling window shorter than that dormancy compares two
     * machines that have both not moved, which is the strict comparison over again wearing a
     * different name.
     */
    private static final int SETTLE_COMPARE = 100;

    /** One arm's readings. */
    private record Arm(String name, String strict, String settled,
                       Map<BlockPos, CompoundTag> strictTags, Map<BlockPos, CompoundTag> settledTags,
                       long elapsed, int dispatched, int realTicks, int jumpedTicks, int jumps,
                       int attempted, int declined, double ground, int resumedAfter,
                       List<String> wideStrict, List<String> wideSettled, String wideStrictHash,
                       String wideSettledHash, String wideShape) {
    }

    /** C, then B1, then B2, in one arena. */
    private static final class Duel implements ChunkCatchUp.Observer {

        private enum Step { PLACING, SETTLING, CONTINUOUS, CONTINUOUS_SETTLE,
            TRIP_RESET, TRIP_RELEASED, TRIP_GONE, TRIP_BACK, TRIP_SETTLE, DONE }

        private static final int WAIT = 300;

        private final GameTestHelper helper;
        private final ServerLevel level;
        private final ChunkPos target;
        private final List<ChunkPos> arena;
        private final MillstoneSubject subject = new MillstoneSubject();

        private Step step = Step.PLACING;
        private int countdown = SETTLE;
        private int trip;
        private long releasedAt = -1L;
        private long unloadAt = -1L;
        private long askedAt = -1L;
        @Nullable
        private ChunkCatchUp.Sweep sweep;
        private long sweptAt = -1L;
        @Nullable
        private String strictAtSweep;
        @Nullable
        private Map<BlockPos, CompoundTag> tagsAtSweep;
        private double groundAtSweep;
        private List<String> wideAtSweep = List.of();
        private String wideHashAtSweep = "<none>";
        private String wideShape = "<none>";
        private BoundingBox wideBox = BoundingBox.fromCorners(BlockPos.ZERO, BlockPos.ZERO);
        private int timerAtSweep = Integer.MIN_VALUE;
        private int resumeAfter = -1;
        private final List<BlockPos> touched = new ArrayList<>();

        @Nullable
        private Arm armC;
        /** B1a, B1b, B2. The first two differ from each other in nothing at all. */
        private final Arm[] arms = new Arm[3];
        @Nullable
        private String failure;
        private BoundingBox region = BoundingBox.fromCorners(BlockPos.ZERO, BlockPos.ZERO);

        private Duel(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.target = new ChunkPos(helper.absolutePos(MILLSTONE));
            this.arena = arenaChunks(helper);
        }

        private void step() {
            if (step == Step.DONE) {
                return;
            }
            long now = level.getGameTime();
            switch (step) {
                case PLACING -> {
                    if (!subject.place(helper)) {
                        fail("create:millstone or create:creative_motor is not registered");
                        return;
                    }
                    step = Step.SETTLING;
                }
                case SETTLING -> {
                    if (--countdown > 0) {
                        return;
                    }
                    String blocked = subject.arm(helper);
                    if (blocked != null) {
                        fail("the arena is not usable: " + blocked);
                        return;
                    }
                    region = subject.exactRegion(helper);
                    wideBox = arenaBox(helper);
                    touched.add(helper.absolutePos(MILLSTONE));
                    // C starts here: put the machine back to the state every arm starts from and
                    // then simply leave it running. No tickets are dropped.
                    subject.reset(helper);
                    countdown = WINDOW;
                    step = Step.CONTINUOUS;
                }
                case CONTINUOUS -> {
                    if (--countdown > 0) {
                        return;
                    }
                    strictAtSweep = digest();
                    tagsAtSweep = tagsOf(level, touched, level.registryAccess());
                    groundAtSweep = subject.observe(helper)[0];
                    WideStateDigest wideC = WideStateDigest.capture(level, wideBox);
                    wideAtSweep = wideC.lines();
                    wideHashAtSweep = wideC.sha256();
                    wideShape = wideC.shape();
                    countdown = SETTLE_COMPARE;
                    step = Step.CONTINUOUS_SETTLE;
                }
                case CONTINUOUS_SETTLE -> {
                    if (--countdown > 0) {
                        return;
                    }
                    armC = new Arm("C(never unloaded)", strictAtSweep, digest(), tagsAtSweep,
                            tagsOf(level, touched, level.registryAccess()),
                            0L, WINDOW, WINDOW, 0, 0, 1, 0, groundAtSweep, -1,
                            wideAtSweep, wideNow(), wideHashAtSweep, wideHashNow(), wideShape);
                    Meanwhile.LOGGER.info("[duel] {} | strict={} settled={}",
                            armC.name(), armC.strict(), armC.settled());
                    step = Step.TRIP_RESET;
                }
                case TRIP_RESET -> {
                    subject.reset(helper);
                    sweep = null;
                    sweptAt = -1L;
                    strictAtSweep = null;
                    tagsAtSweep = null;
                    RoundTripImages.watch(target);
                    ChunkCatchUp.setMode((trip < 2
                            ? ChunkCatchUp.Mode.TICK_INTERLEAVED
                            : ChunkCatchUp.Mode.PRODUCT)
                            .restrictedTo(helper.absolutePos(MILLSTONE))
                            .withFixedWindow(WINDOW));
                    ChunkCatchUp.setObserver(this);
                    for (ChunkPos pos : arena) {
                        level.setChunkForced(pos.x, pos.z, false);
                    }
                    releasedAt = now;
                    Meanwhile.LOGGER.info("[duel] trip {} released | chunk={} at={} mode={}",
                            trip, target, now, ChunkCatchUp.mode().label());
                    step = Step.TRIP_RELEASED;
                }
                case TRIP_RELEASED -> {
                    if (RoundTripImages.unloads() > 0) {
                        unloadAt = RoundTripImages.unloadAt();
                        step = Step.TRIP_GONE;
                        return;
                    }
                    if (now - releasedAt > UNLOAD_WAIT) {
                        fail("trip " + trip + ": no ChunkEvent.Unload in " + UNLOAD_WAIT
                                + " ticks after the forced tickets were dropped");
                    }
                }
                case TRIP_GONE -> {
                    if (now - unloadAt < WAIT) {
                        return;
                    }
                    for (ChunkPos pos : arena) {
                        level.setChunkForced(pos.x, pos.z, true);
                    }
                    askedAt = now;
                    step = Step.TRIP_BACK;
                }
                case TRIP_BACK -> {
                    if (sweep != null) {
                        countdown = SETTLE_COMPARE;
                        step = Step.TRIP_SETTLE;
                        return;
                    }
                    if (now - askedAt > BACK_WAIT) {
                        fail("trip " + trip + ": the chunk came back at " + askedAt
                                + " but nothing spent its window " + BACK_WAIT + " ticks later");
                    }
                }
                case TRIP_SETTLE -> {
                    // When the machine starts moving again, counted from the tick its window
                    // finished. A block entity whose chunk has just come back sits still for a
                    // while first, and how long is the thing the two arms can disagree about
                    // without disagreeing about any state that was written down.
                    if (resumeAfter < 0 && timerOf(level, helper.absolutePos(MILLSTONE))
                            != timerAtSweep) {
                        resumeAfter = SETTLE_COMPARE - countdown;
                    }
                    if (--countdown > 0) {
                        return;
                    }
                    finishTrip();
                }
                default -> {
                }
            }
        }

        private void finishTrip() {
            ChunkCatchUp.Sweep result = sweep;
            if (result == null || strictAtSweep == null || tagsAtSweep == null) {
                fail("trip " + trip + ": nothing was captured at the sweep");
                return;
            }
            int jumps = 0;
            int real = 0;
            for (ChunkCatchUp.Attempt attempt : result.attempts()) {
                jumps += attempt.jumps();
                real += attempt.realTicks();
            }
            Arm arm = new Arm(switch (trip) {
                        case 0 -> "B1a(round trip + real ticks)";
                        case 1 -> "B1b(round trip + real ticks, again)";
                        default -> "B2(round trip + catch-up)";
                    },
                    strictAtSweep, digest(), tagsAtSweep,
                    tagsOf(level, touched, level.registryAccess()),
                    result.elapsed(), result.dispatched(), real, result.jumpedTicks(), jumps,
                    result.attempted(), result.declined(), groundAtSweep, resumeAfter,
                    wideAtSweep, wideNow(), wideHashAtSweep, wideHashNow(), wideShape);
            Meanwhile.LOGGER.info("[duel] {} | strict={} settled={} elapsed={} dispatched={}"
                            + " realTicks={} jumps={} jumpedTicks={} attempted={} declined={}"
                            + " unloadAt={} loadAt={} sweptAt={}",
                    arm.name(), arm.strict(), arm.settled(), arm.elapsed(), arm.dispatched(),
                    arm.realTicks(), arm.jumps(), arm.jumpedTicks(), arm.attempted(),
                    arm.declined(), unloadAt, RoundTripImages.loadAt(), sweptAt);
            Meanwhile.LOGGER.info("[duel] {} ground={} timerAtSweep={} resumedAfter={} ticks",
                    arm.name(), arm.ground(), timerAtSweep, resumeAfter);

            arms[trip] = arm;
            ChunkCatchUp.setObserver(null);
            if (trip < 2) {
                trip++;
                step = Step.TRIP_RESET;
                return;
            }
            step = Step.DONE;
        }

        @Override
        public void afterSweep(ServerLevel level, LevelChunk chunk, ChunkCatchUp.Sweep result) {
            if (sweep != null || chunk.getPos().toLong() != target.toLong()) {
                return;
            }
            sweep = result;
            sweptAt = result.at();
            // In the tick the window finished, before the machine runs on and blurs it.
            strictAtSweep = digest();
            tagsAtSweep = tagsOf(level, touched, level.registryAccess());
            groundAtSweep = subject.observe(helper)[0];
            WideStateDigest wide = WideStateDigest.capture(level, wideBox);
            wideAtSweep = wide.lines();
            wideHashAtSweep = wide.sha256();
            wideShape = wide.shape();
            timerAtSweep = timerOf(level, helper.absolutePos(MILLSTONE));
            resumeAfter = -1;
        }

        private String digest() {
            return WorldStateDigest.capture(level, region).sha256();
        }

        private List<String> wideNow() {
            return WideStateDigest.capture(level, wideBox).lines();
        }

        private String wideHashNow() {
            return WideStateDigest.capture(level, wideBox).sha256();
        }

        private void fail(String message) {
            if (failure == null) {
                failure = message;
            }
            step = Step.DONE;
        }

        private void restoreArena() {
            for (ChunkPos pos : arena) {
                level.setChunkForced(pos.x, pos.z, true);
            }
            ChunkCatchUp.setObserver(null);
            ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT);
            RoundTripImages.stopWatching();
        }

        private void judge() {
            if (failure != null) {
                helper.fail(failure);
                return;
            }
            if (armC == null || arms[0] == null || arms[1] == null || arms[2] == null) {
                helper.fail("not every arm finished: C=" + (armC != null) + " B1a="
                        + (arms[0] != null) + " B1b=" + (arms[1] != null) + " B2="
                        + (arms[2] != null) + " step=" + step);
                return;
            }
            Arm armB1 = arms[0];
            Arm armB1b = arms[1];
            Arm armB2 = arms[2];

            List<String> roundTripCost = differingKeys(armB1.strictTags(), armC.strictTags());
            List<String> roundTripCostSettled =
                    differingKeys(armB1.settledTags(), armC.settledTags());
            Meanwhile.LOGGER.info("[duel] CATALOGUE diff(B1,C) | strict={} settled={}",
                    roundTripCost, roundTripCostSettled);

            // Whether the settling window moved anything at all, per arm. A settled comparison
            // between two states that did not move is the strict comparison again under another
            // name, and would be worth nothing while looking like a second check.
            Meanwhile.LOGGER.info("[duel] SETTLE moved | C={} B1a={} B1b={} B2={} ticks={}",
                    differingKeys(armC.strictTags(), armC.settledTags()),
                    differingKeys(armB1.strictTags(), armB1.settledTags()),
                    differingKeys(armB1b.strictTags(), armB1b.settledTags()),
                    differingKeys(armB2.strictTags(), armB2.settledTags()), SETTLE_COMPARE);
            for (Arm arm : List.of(armC, armB1, armB1b, armB2)) {
                Meanwhile.LOGGER.info("[duel] TAGS {} | strict={} settled={}",
                        arm.name(), arm.strictTags().values(), arm.settledTags().values());
            }

            // Two arms that did exactly the same thing. Whatever they disagree on is what this
            // arena carries between round trips on its own, and a disagreement with B2 on those
            // same keys is not evidence about catching up.
            List<String> noiseStrict = differingKeys(armB1.strictTags(), armB1b.strictTags());
            List<String> noiseSettled = differingKeys(armB1.settledTags(), armB1b.settledTags());
            List<String> strict = differingKeys(armB1.strictTags(), armB2.strictTags());
            List<String> settled = differingKeys(armB1.settledTags(), armB2.settledTags());
            List<String> strictBeyond = new ArrayList<>(strict);
            strictBeyond.removeAll(noiseStrict);
            List<String> settledBeyond = new ArrayList<>(settled);
            settledBeyond.removeAll(noiseSettled);
            Meanwhile.LOGGER.info("[duel] NOISE diff(B1a,B1b) | strict={} settled={}"
                            + " || beyond: strict={} settled={}",
                    noiseStrict, noiseSettled, strictBeyond, settledBeyond);
            Meanwhile.LOGGER.info("[duel] GATE diff(B1a,B2) | strictHashes={}/{}/{} match={}"
                            + " strictKeys={} || settledHashes={}/{}/{} match={} settledKeys={}",
                    armB1.strict(), armB1b.strict(), armB2.strict(),
                    armB1.strict().equals(armB2.strict()), strict,
                    armB1.settled(), armB1b.settled(), armB2.settled(),
                    armB1.settled().equals(armB2.settled()), settled);

            if (armB1.ground() < REQUIRED_GROUND || armB2.ground() < REQUIRED_GROUND) {
                helper.fail("a window that grinds nothing makes both arms agree for free:"
                        + " B1 ground=" + armB1.ground() + " B2 ground=" + armB2.ground()
                        + " (C ground=" + armC.ground() + ")");
                return;
            }
            if (armB1.ground() != armB2.ground()) {
                helper.fail("the two arms produced different amounts of work: B1="
                        + armB1.ground() + " B2=" + armB2.ground());
                return;
            }
            if (armB2.jumps() == 0 || armB2.jumpedTicks() <= 0) {
                helper.fail("B2 never jumped, so the comparison only shows that ticking works: "
                        + "jumps=" + armB2.jumps() + " jumpedTicks=" + armB2.jumpedTicks());
                return;
            }
            if (armB1.realTicks() < armB1.dispatched()) {
                helper.fail("B1 did not tick its whole window for real, so it is not the arm it"
                        + " claims to be: realTicks=" + armB1.realTicks()
                        + " dispatched=" + armB1.dispatched());
                return;
            }
            if (armB1.dispatched() != armB2.dispatched()) {
                helper.fail("the two arms spent different windows: B1=" + armB1.dispatched()
                        + " B2=" + armB2.dispatched());
                return;
            }
            if (!strictBeyond.isEmpty()) {
                helper.fail("the caught-up machine and the ticked one differ after the same round"
                        + " trip and the same window, on state two identical ticked trips agree"
                        + " on: " + strictBeyond + " (all strict differences: " + strict
                        + ", noise: " + noiseStrict + ")");
                return;
            }
            // The settled comparison is reported, not asserted on, and the reason is measured.
            // A machine whose chunk has just come back sits still for a while first, and how long
            // varies: 22, 28, 31, 32 and 33 ticks have all been seen, between arms that did the
            // same thing. Two identical trips are not enough samples to bound that, so the floor
            // they give comes out empty on some runs and holds a Timer on others. Asserting on
            // the difference makes the gate report the resume delay's variance as a fault in the
            // catch-up. The bit-for-bit claim lives in the strict comparison above, which has
            // held on every run.
            Meanwhile.LOGGER.info("[duel] SETTLED (reported, not asserted) | beyond={}"
                            + " resumedAfter: B1a={} B1b={} B2={}",
                    settledBeyond, armB1.resumedAfter(), armB1b.resumedAfter(),
                    armB2.resumedAfter());

            // The wide surface: nine chunks, block states, entities, scheduled ticks and every
            // block entity in them. Judged the same way as the narrow one, against two arms that
            // did the same thing — a wider surface carries more that moves on its own, so a
            // difference means nothing until the floor under it is known.
            List<String> wideNoise = lineDifferences(armB1.wideStrict(), armB1b.wideStrict());
            List<String> wideSignal = lineDifferences(armB1.wideStrict(), armB2.wideStrict());
            List<String> wideBeyond = new ArrayList<>(wideSignal);
            wideBeyond.removeAll(wideNoise);
            List<String> wideNoiseSettled =
                    lineDifferences(armB1.wideSettled(), armB1b.wideSettled());
            List<String> wideSignalSettled =
                    lineDifferences(armB1.wideSettled(), armB2.wideSettled());
            List<String> wideBeyondSettled = new ArrayList<>(wideSignalSettled);
            wideBeyondSettled.removeAll(wideNoiseSettled);

            Meanwhile.LOGGER.info("[duel] WIDE shape | {}", armB1.wideShape());
            Meanwhile.LOGGER.info("[duel] WIDE strict | hashes={}/{}/{} match={} noise={}"
                            + " signal={} beyond={}",
                    armB1.wideStrictHash(), armB1b.wideStrictHash(), armB2.wideStrictHash(),
                    armB1.wideStrictHash().equals(armB2.wideStrictHash()),
                    wideNoise.size(), wideSignal.size(), wideBeyond.size());
            Meanwhile.LOGGER.info("[duel] WIDE settled | hashes={}/{}/{} noise={} signal={}"
                            + " beyond={}",
                    armB1.wideSettledHash(), armB1b.wideSettledHash(), armB2.wideSettledHash(),
                    wideNoiseSettled.size(), wideSignalSettled.size(), wideBeyondSettled.size());
            for (String line : wideBeyond.subList(0, Math.min(12, wideBeyond.size()))) {
                Meanwhile.LOGGER.info("[duel] WIDE beyond-noise | {}", line);
            }
            for (String line : wideNoise.subList(0, Math.min(6, wideNoise.size()))) {
                Meanwhile.LOGGER.info("[duel] WIDE noise sample | {}", line);
            }
            // The settled side of the wide surface is not asserted on, for the same reason the
            // narrow settled comparison is not. Counting it and never naming it is a different
            // thing though: a number with nothing behind it cannot be read later, and a baseline
            // taken over it would freeze a line nobody has seen. Printed for that reason.
            for (String line : wideBeyondSettled.subList(0,
                    Math.min(12, wideBeyondSettled.size()))) {
                Meanwhile.LOGGER.info("[duel] WIDE settled beyond-noise | {}", line);
            }
            if (!wideBeyond.isEmpty()) {
                helper.fail("over nine chunks, block states, entities and scheduled ticks, the"
                        + " caught-up world differs from the ticked one on " + wideBeyond.size()
                        + " line(s) that two identical ticked trips agree on: "
                        + wideBeyond.subList(0, Math.min(6, wideBeyond.size()))
                        + " (noise floor " + wideNoise.size() + " lines)");
                return;
            }
            helper.succeed();
        }
    }

    // ---- what a machine that never left looks like ------------------------------------------------

    /**
     * The third arm: a millstone that is never unloaded and never rebuilt.
     *
     * <p>Both arms of every comparison in this class have been through something. One came back
     * from disk; the other was put back together from a recorded tag. Neither can say what the
     * value in dispute — {@code lastStressApplied}, and the {@code Network.AddedStress} it is
     * written as — is on a machine that simply ran, which is the only thing that says which of
     * the two is the odd one out.
     *
     * <p>So nothing here restores anything. The blocks are placed, the network is given real
     * server ticks to form, the input goes in through the item-handler capability, and the
     * machine is left alone for the same window the comparison uses, ticked by the server rather
     * than by anything in this mod. The value is read at intervals, because "it is 4 and always
     * was" and "it reaches 4 after a while" are different answers.
     *
     * <p>Then, on that same machine, the reconstruction the comparison uses is applied and the
     * window run again — so the effect of the reconstruction is measured on a machine whose
     * before-value is known, rather than inferred from two runs that differ in other ways too.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "unloadedcatchup-continuous", timeoutTicks = 2400)
    public static void continuouslyLoadedMillstoneStressBookkeeping(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(MILLSTONE);
        MillstoneSubject subject = new MillstoneSubject();
        if (!subject.place(helper)) {
            helper.fail("create:millstone or create:creative_motor is not registered");
            return;
        }

        int[] inserted = {0};
        helper.startSequence()
                .thenExecuteAfter(SETTLE, () -> {
                    inserted[0] = insertAndesite(level, pos);
                    report(level, pos, "a/after-insert");
                })
                .thenExecuteAfter(1, () -> report(level, pos, "a/t+1"))
                .thenExecuteAfter(4, () -> report(level, pos, "a/t+5"))
                .thenExecuteAfter(45, () -> report(level, pos, "a/t+50"))
                .thenExecuteAfter(400, () -> report(level, pos, "a/t+450"))
                .thenExecuteAfter(453, () -> report(level, pos, "a/t+903 CONTINUOUS"))
                .thenExecute(() -> {
                    // The reconstruction the comparison uses, applied to a machine whose value
                    // going in has just been read.
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity == null) {
                        helper.fail("the millstone vanished");
                        return;
                    }
                    CompoundTag live = blockEntity.saveWithoutMetadata(level.registryAccess());
                    Meanwhile.LOGGER.info("[stress] b/tag-being-restored | carriesAddedStress={}"
                                    + " tag={}",
                            live.getCompound("Network").contains("AddedStress"), live);

                    restore(level, pos, live, level.registryAccess());
                    report(level, pos, "b/after-load-before-reattach");
                    reattach(level.getBlockEntity(pos));
                    report(level, pos, "b/after-reattach");

                    GenericCatchUp.tick(level, pos, WINDOW);
                    report(level, pos, "b/after-" + WINDOW + "-tickOnce RECONSTRUCTED");
                })
                .thenExecute(() -> {
                    if (inserted[0] == 0) {
                        helper.fail("nothing was inserted into the millstone, so it never ran");
                        return;
                    }
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity == null) {
                        helper.fail("the millstone vanished");
                        return;
                    }
                    helper.succeed();
                })
                .thenSucceed();
    }

    /** One reading of the value in dispute, plus enough context to place it. */
    private static void report(ServerLevel level, BlockPos pos, String label) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            Meanwhile.LOGGER.info("[stress] {} | <no block entity>", label);
            return;
        }
        CompoundTag tag = blockEntity.saveWithoutMetadata(level.registryAccess());
        CompoundTag network = tag.getCompound("Network");
        Float field = floatField(blockEntity, "lastStressApplied");
        // wasMoved decides which half of KineticBlockEntity#read runs: set, the method clears
        // every kinetic field, hands the tag to SmartBlockEntity and returns without restoring
        // Speed, Source or Network at all.
        Meanwhile.LOGGER.info("[stress] {} | t={} lastStressApplied={} rawBits={}"
                        + " hasAddedStress={} AddedStress={} speed={} speedField={}"
                        + " tagHasSpeed={} tagHasNetwork={} wasMoved={} networkObj={} tag={}",
                label, level.getGameTime(),
                field == null ? "<unreadable>" : field,
                field == null ? "<unreadable>" : Float.floatToRawIntBits(field),
                network.contains("AddedStress"),
                network.contains("AddedStress") ? network.getFloat("AddedStress") : "<absent>",
                speedOf(blockEntity), floatField(blockEntity, "speed"),
                tag.contains("Speed"), tag.contains("Network"),
                objectField(blockEntity, "wasMoved"), objectField(blockEntity, "network"), tag);
    }

    /** A named field off a block entity as text, for the ones that are not floats. */
    private static String objectField(BlockEntity blockEntity, String name) {
        for (Class<?> type = blockEntity.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(blockEntity);
                return value == null ? "null" : String.valueOf(value);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Declared further up, or not readable here.
            }
        }
        return "<absent>";
    }

    /** A named float off a block entity, wherever in its hierarchy it is declared. */
    @Nullable
    private static Float floatField(BlockEntity blockEntity, String name) {
        for (Class<?> type = blockEntity.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.getFloat(blockEntity);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Declared further up, or not readable here.
            }
        }
        return null;
    }

    /**
     * Fills the millstone through the capability, which is how a hopper would.
     *
     * <p>Written here rather than taken from {@code MillstoneSubject}, whose own arming restores
     * the block entity from NBT several times while it probes for a reproducible recipe — the one
     * thing this arm may not have done to it. {@code create:milling/andesite} is named directly
     * because that is the recipe the subject's probe settles on, and the point of this arm is not
     * to re-derive it.
     */
    private static int insertAndesite(ServerLevel level, BlockPos pos) {
        net.neoforged.neoforge.items.IItemHandler handler = level.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null) {
            return 0;
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            net.minecraft.world.item.ItemStack remainder = handler.insertItem(slot,
                    new net.minecraft.world.item.ItemStack(
                            net.minecraft.world.level.block.Blocks.ANDESITE.asItem(), 64), false);
            int put = 64 - remainder.getCount();
            if (put > 0) {
                return put;
            }
        }
        return 0;
    }

    // ---- the first guard ------------------------------------------------------------------------

    /**
     * A stored time in the future must not be spent.
     *
     * <p>{@code lastSeen=9451 at=1} was measured on this suite, and a real server produces the
     * same shape when a crash rolls {@code level.dat} back further than the chunk files went. The
     * chunk here is made to write a time far ahead of itself before it leaves, so it comes back
     * with a negative difference.
     *
     * <p>What is asserted is that no non-positive count was handed to {@link GenericCatchUp}.
     * That is deliberately a claim about the dispatch rather than about the world:
     * {@code GenericCatchUp.catchUp} loops {@code while (remaining > 0)} and is inert for a
     * negative count today, so removing the guard corrupts nothing — it only lets a negative
     * through. The negative control flips the guard and this assertion is what goes red.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "unloadedcatchup-negative", timeoutTicks = 1200)
    public static void nonPositiveElapsedIsNotDispatched(GameTestHelper helper) {
        ChunkCatchUp.Mode mode = nonPositiveRequested()
                ? ChunkCatchUp.Mode.ALLOW_NON_POSITIVE
                : ChunkCatchUp.Mode.PRODUCT;
        ServerLevel level = helper.getLevel();
        ChunkPos target = new ChunkPos(helper.absolutePos(MILLSTONE));
        int skippedBefore = ChunkCatchUp.skippedNonPositive();
        // Far enough ahead that the chunk cannot tick its way back to a positive difference
        // inside the test, and negative by a margin no rounding could explain.
        ChunkClock.setStampOffset(target, 100000L);

        onArena(helper, SHORT_WINDOW, mode, null, trip -> {
            ChunkClock.setStampOffset(target, 0L);
            ChunkClock.Reconciliation reconciliation = ChunkClock.lastReconciliation(level, target);
            Meanwhile.LOGGER.info("[unloaded] non-positive | mode={} reconciliation={} sweep={}"
                            + " skippedNonPositive={}->{} minDispatched={}",
                    ChunkCatchUp.mode().label(), reconciliation,
                    trip.sweep == null ? "<none>" : "at=" + trip.sweep.at()
                            + " elapsed=" + trip.sweep.elapsed()
                            + " dispatched=" + trip.sweep.dispatched(),
                    skippedBefore, ChunkCatchUp.skippedNonPositive(),
                    ChunkCatchUp.minDispatchedTicks());

            if (RoundTripImages.unloads() == 0 || RoundTripImages.loads() == 0) {
                helper.fail("the chunk never made the round trip, so no stored time was carried"
                        + " through disk: " + trip.describe());
                return;
            }
            if (reconciliation == null || !reconciliation.priorPresent()) {
                helper.fail("the chunk came back with no stored time, so there is no difference"
                        + " to be negative: " + reconciliation);
                return;
            }
            if (reconciliation.elapsed() >= 0) {
                helper.fail("the stored time was not carried into the future as intended, so this"
                        + " is not measuring what it claims to: elapsed="
                        + reconciliation.elapsed() + " lastSeen=" + reconciliation.lastSeen()
                        + " at=" + reconciliation.at());
                return;
            }
            if (ChunkCatchUp.minDispatchedTicks() < 1) {
                helper.fail("a non-positive tick count reached the catch-up: minDispatched="
                        + ChunkCatchUp.minDispatchedTicks()
                        + " (elapsed=" + reconciliation.elapsed() + ")");
                return;
            }
            if (ChunkCatchUp.skippedNonPositive() <= skippedBefore) {
                helper.fail("no chunk was skipped for a non-positive difference, so the guard was"
                        + " never exercised even though the difference was "
                        + reconciliation.elapsed());
                return;
            }
            helper.succeed();
        });
    }

    // ---- the second guard -------------------------------------------------------------------------

    /**
     * A chunk offered from outside the level sweep is refused before anything is read off it.
     *
     * <p>The unload path is where this matters: a {@code getBlockEntity} inside it pulls the chunk
     * back to FULL and re-posts {@code Load}, and the server stops being able to finish shutting
     * down (GAP_LOG G58, measured at 4462 and 8586 laps before the process was killed). The
     * catch-up registers no unload listener at all, so the guard is a second line rather than the
     * only one; what this measures is that the second line holds.
     *
     * <p>No round trip, no tickets dropped. The chunk is the arena's own, fully loaded, and the
     * only thing wrong with the call is where it came from.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "unloadedcatchup-outside", timeoutTicks = 600)
    public static void catchUpIsRefusedOutsideTheSweep(GameTestHelper helper) {
        RoundTripImages.install();
        ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT);
        ServerLevel level = helper.getLevel();
        ChunkPos target = new ChunkPos(helper.absolutePos(MILLSTONE));
        LevelChunk chunk = level.getChunkSource().getChunkNow(target.x, target.z);
        if (chunk == null) {
            helper.fail("the arena chunk " + target + " is not loaded, so there is nothing to"
                    + " offer from the wrong place");
            return;
        }

        int refusedBefore = ChunkCatchUp.refusedOutsideSweep();
        int dispatchesBefore = ChunkCatchUp.dispatches();
        boolean inSweep = ChunkClock.inSweep();
        ChunkCatchUp.offerFromOutsideTheSweep(level, chunk, 5000L);
        int refusedAfter = ChunkCatchUp.refusedOutsideSweep();
        int dispatchesAfter = ChunkCatchUp.dispatches();

        Meanwhile.LOGGER.info("[unloaded] outside sweep | chunk={} inSweepAtCall={} refused={}->{}"
                        + " dispatches={}->{}",
                target, inSweep, refusedBefore, refusedAfter, dispatchesBefore, dispatchesAfter);

        if (inSweep) {
            helper.fail("a GameTest step ran inside the clock's sweep, so this call was not from"
                    + " outside it and the refusal would mean nothing");
            return;
        }
        if (refusedAfter != refusedBefore + 1) {
            helper.fail("offering a chunk from outside the sweep was not refused: refused "
                    + refusedBefore + " -> " + refusedAfter);
            return;
        }
        if (dispatchesAfter != dispatchesBefore) {
            helper.fail("a refused offer still dispatched a catch-up: dispatches "
                    + dispatchesBefore + " -> " + dispatchesAfter);
            return;
        }
        helper.succeed();
    }

    // ---- the comparison, run in the tick the catch-up finished -------------------------------------

    /**
     * Captures arm B and runs arm A, both inside the sweep that just caught the chunk up.
     *
     * <p>Everything happens in one server tick on purpose. The machine keeps running after the
     * sweep, so a snapshot taken from a later GameTest step would be of a state several ticks
     * past the one the catch-up produced, and the difference would be blamed on the catch-up.
     *
     * <p>Nothing here calls {@code helper.fail}. An assertion thrown from a level tick handler
     * unwinds through the server's tick rather than through the test, so the results are recorded
     * and judged by the step that follows.
     */
    /**
     * An observer the round trip hands its subject to before releasing the chunk.
     *
     * <p>The trip has to tell an observer which chunk is the one being measured before anything
     * is dropped, and the observers that want that differ in what they do with the sweep.
     */
    private interface ArmedObserver extends ChunkCatchUp.Observer {
        void arm(GameTestHelper helper, MillstoneSubject subject, ChunkPos chunk);
    }

    private static final class Comparison implements ArmedObserver {

        @Nullable
        private ChunkCatchUp.Sweep sweep;
        @Nullable
        private String exactDifference;
        private List<String> deepDifferences = List.of();
        private String exactB = "<none>";
        private String exactA = "<none>";
        private String deepB = "<none>";
        private String deepA = "<none>";
        private String exactReplay = "<none>";
        private double groundB;
        private double groundA;
        private double groundReplay;
        private List<String> noiseKeys = List.of();
        private List<String> signalKeys = List.of();
        private List<String> beyondNoise = new ArrayList<>();
        private boolean ran;
        private boolean normalised;
        /** The tag each machine carried when the sweep reached it. Both arms start from these. */
        private final Map<BlockPos, CompoundTag> start = new LinkedHashMap<>();

        @Nullable
        private GameTestHelper helper;
        @Nullable
        private MillstoneSubject subject;
        private long target = Long.MIN_VALUE;

        @Nullable
        private ChunkCatchUp.Attempt attemptAt(BlockPos pos) {
            if (sweep == null) {
                return null;
            }
            for (ChunkCatchUp.Attempt attempt : sweep.attempts()) {
                if (attempt.pos().equals(pos)) {
                    return attempt;
                }
            }
            return null;
        }

        @Override
        public void arm(GameTestHelper helper, MillstoneSubject subject, ChunkPos chunk) {
            this.helper = helper;
            this.subject = subject;
            this.target = chunk.toLong();
        }

        /**
         * Puts every machine about to be spent through the reconstruction both arms will use.
         *
         * <p>Without this the two arms are not comparable on a Create machine, and the reason is
         * measured rather than supposed: with arm B left on the live reloaded object and arm A
         * rebuilt from its tag, the two disagree on the millstone's {@code AddedStress} and on
         * the {@code lastStressApplied} field behind it — state the block entity writes out but
         * does not read back, so no amount of care makes a rebuild reproduce it. Normalising here
         * costs the measurement something and it is worth saying what: the catch-up is then
         * measured on a machine restored from the tag that came back through disk, rather than on
         * the live object the game reloaded. The tag is the same one either way; the live fields
         * behind it start from a known place instead of two different ones.
         */
        @Override
        public void beforeSweep(ServerLevel level, LevelChunk chunk, int dispatched,
                                List<BlockPos> positions) {
            if (ran || normalised || helper == null || chunk.getPos().toLong() != target) {
                return;
            }
            normalised = true;
            HolderLookup.Provider registries = level.registryAccess();
            for (BlockPos pos : positions) {
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity != null) {
                    start.put(pos, blockEntity.saveWithoutMetadata(registries));
                }
            }
            reconstruct(level, positions, registries);

            // Then the same window ticked once and thrown away, and the reconstruction applied
            // again. This costs a window of ticking and it buys the only thing that made the
            // three arms unlike each other besides the catch-up: their ordinal position.
            //
            // Measured, not supposed. reconstructionIdempotenceDiagnostic runs the restore three
            // times with nothing ticked and gets the same bytes every time, and runs
            // restore-and-tick three times and gets a kinetic network that disagrees with itself
            // between the first pass and the second and agrees from the second on. Arm B is the
            // first pass, arm A the second, the replay the third — so the floor arm A and the
            // replay define cannot contain a difference that settles after one pass, and the
            // signal arm A and arm B define is bound to report it, for every arm, whether or not
            // anything jumped. That is the whole of the Network disagreement all five of these
            // comparisons have carried since the arena was built.
            //
            // What it does not cost: both arms still start from the tag recorded above, which is
            // the one that came back through disk, and the restore is idempotent on the bytes.
            tickWindow(level, positions, dispatched);
            reconstruct(level, positions, registries);

            Meanwhile.LOGGER.info("[unloaded] normalised | chunk={} positions={} window={}",
                    chunk.getPos(), positions, dispatched);
        }

        /** One arm: back to the common start, then the window run a tick at a time for real. */
        private void runWindow(ServerLevel level, List<BlockPos> order,
                               HolderLookup.Provider registries, int window) {
            reconstruct(level, order, registries);
            tickWindow(level, order, window);
        }

        /** Both arms start here: every recorded tag put back, then every machine re-attached. */
        private void reconstruct(ServerLevel level, List<BlockPos> positions,
                                 HolderLookup.Provider registries) {
            UnloadedCatchUpGameTests.reconstruct(level, positions, start, registries);
        }

        @Override
        public void afterSweep(ServerLevel level, LevelChunk chunk, ChunkCatchUp.Sweep result) {
            if (ran || helper == null || subject == null
                    || chunk.getPos().toLong() != target) {
                return;
            }
            ran = true;
            sweep = result;

            BoundingBox region = subject.exactRegion(helper);
            HolderLookup.Provider registries = level.registryAccess();

            WorldStateDigest exactCaught = WorldStateDigest.capture(level, region);
            DeepStateDigest deepCaught = DeepStateDigest.capture(level, region);
            groundB = subject.observe(helper)[0];

            // Arm A. The same reconstruction arm B started from, then the window run for real.
            // Interleaved one tick at a time across the positions, which is the order the game
            // ticks them in; the catch-up instead finishes one machine before starting the next,
            // and the two coincide only because nothing in this arena feeds anything else over
            // time (D8).
            List<BlockPos> order = new ArrayList<>();
            for (ChunkCatchUp.Attempt attempt : result.attempts()) {
                order.add(attempt.pos());
            }
            // The window arm A runs is how long the chunk was gone, not how much of it the mod
            // decided to spend. Following the dispatch would make an arm that spends nothing
            // agree with a reference that also does nothing, which is the one thing a control
            // for "ignore the elapsed time" has to be unable to do.
            int window = (int) Math.max(0L, Math.min(result.elapsed(), Integer.MAX_VALUE));
            Map<BlockPos, CompoundTag> caught = tagsOf(level, order, registries);

            runWindow(level, order, registries, window);
            WorldStateDigest exactTicked = WorldStateDigest.capture(level, region);
            DeepStateDigest deepTicked = DeepStateDigest.capture(level, region);
            groundA = subject.observe(helper)[0];
            Map<BlockPos, CompoundTag> ticked = tagsOf(level, order, registries);

            // Arm A again, identically. Two runs of the same operations on the same subject are
            // the floor this instrument can resolve: whatever they disagree on is something the
            // arena carries between arms rather than anything a catch-up did, and a difference
            // against arm B on those same keys says nothing. Anything outside that floor does.
            runWindow(level, order, registries, window);
            groundReplay = subject.observe(helper)[0];
            Map<BlockPos, CompoundTag> replay = tagsOf(level, order, registries);
            exactReplay = WorldStateDigest.capture(level, region).sha256();

            noiseKeys = differingKeys(ticked, replay);
            signalKeys = differingKeys(ticked, caught);
            beyondNoise = new ArrayList<>(signalKeys);
            beyondNoise.removeAll(noiseKeys);

            exactB = exactCaught.sha256();
            exactA = exactTicked.sha256();
            deepB = deepCaught.sha256();
            deepA = deepTicked.sha256();
            exactDifference = exactTicked.firstDifference(exactCaught);
            deepDifferences = deepTicked.differences(deepCaught, 24);
            for (String line : deepDifferences) {
                Meanwhile.LOGGER.info("[unloaded] deep diff | {}", line);
            }
        }

        private String summary() {
            return "comparison | window=" + (sweep == null ? -1 : sweep.dispatched())
                    + " exact: ticked=" + exactA + " caught-up=" + exactB
                    + " replay=" + exactReplay
                    + " match=" + (exactDifference == null)
                    + " selfMatch=" + exactA.equals(exactReplay)
                    + " | deep: ticked=" + deepA + " caught-up=" + deepB
                    + " lines=" + deepDifferences.size()
                    + " | ground: ticked=" + groundA + " caught-up=" + groundB
                    + " replay=" + groundReplay
                    + " | keys: noise=" + noiseKeys + " signal=" + signalKeys
                    + " beyondNoise=" + beyondNoise;
        }
    }

    /** Every recorded tag put back, then every machine re-attached to its kinetic network. */
    private static void reconstruct(ServerLevel level, List<BlockPos> positions,
                                    Map<BlockPos, CompoundTag> start,
                                    HolderLookup.Provider registries) {
        for (BlockPos pos : positions) {
            CompoundTag tag = start.get(pos);
            if (tag != null) {
                restore(level, pos, tag, registries);
            }
        }
        for (BlockPos pos : positions) {
            reattach(level.getBlockEntity(pos));
        }
    }

    /** Lines one list has and the other does not, both directions. */
    private static List<String> lineDifferences(List<String> left, List<String> right) {
        List<String> out = new ArrayList<>();
        Set<String> mine = new LinkedHashSet<>(left);
        Set<String> theirs = new LinkedHashSet<>(right);
        for (String line : left) {
            if (!theirs.contains(line)) {
                out.add("ticked  : " + line);
            }
        }
        for (String line : right) {
            if (!mine.contains(line)) {
                out.add("caughtup: " + line);
            }
        }
        return out;
    }

    /** The serialised form of each position, for a key-by-key comparison between arms. */
    private static Map<BlockPos, CompoundTag> tagsOf(ServerLevel level,
                                                               List<BlockPos> positions,
                                                               HolderLookup.Provider registries) {
        Map<BlockPos, CompoundTag> tags = new LinkedHashMap<>();
        for (BlockPos pos : positions) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity != null) {
                tags.put(pos, blockEntity.saveWithoutMetadata(registries));
            }
        }
        return tags;
    }

    /**
     * Which serialised keys two states disagree on, named by position and key.
     *
     * <p>Named rather than diffed as text, because the question a noise floor has to answer is
     * "the same keys?" and not "the same values?" — two runs that both wander on
     * {@code AddedStress} are the same phenomenon whatever numbers they land on.
     */
    private static List<String> differingKeys(Map<BlockPos, CompoundTag> left,
                                              Map<BlockPos, CompoundTag> right) {
        Set<String> keys = new LinkedHashSet<>();
        Set<BlockPos> positions = new LinkedHashSet<>(left.keySet());
        positions.addAll(right.keySet());
        for (BlockPos pos : positions) {
            CompoundTag a = left.get(pos);
            CompoundTag b = right.get(pos);
            if (a == null || b == null) {
                keys.add(pos.toShortString() + ".<whole block entity>");
                continue;
            }
            Set<String> names = new LinkedHashSet<>(a.getAllKeys());
            names.addAll(b.getAllKeys());
            for (String name : names) {
                if (!Objects.equals(a.get(name), b.get(name))) {
                    keys.add(pos.toShortString() + "." + name);
                }
            }
        }
        return new ArrayList<>(keys);
    }

    // ---- the round trip ----------------------------------------------------------------------------

    private static final BlockPos MILLSTONE = new BlockPos(4, 2, 4);

    private enum Phase { PLACING, SETTLING, ARMED, RELEASED, GONE, BACK, DONE }

    /** One drop-and-return, driven a tick at a time. */
    private static final class Trip {

        private final GameTestHelper helper;
        private final ServerLevel level;
        private final ChunkPos target;
        private final List<ChunkPos> arena;
        private final MillstoneSubject subject = new MillstoneSubject();
        private final int wait;
        @Nullable
        private final ArmedObserver comparison;

        private Phase phase = Phase.PLACING;
        private int settleLeft = SETTLE;
        private long releasedAt = -1L;
        private long unloadAt = -1L;
        private long askedAt = -1L;
        @Nullable
        private ChunkCatchUp.Sweep sweep;
        @Nullable
        private String failure;

        private Trip(GameTestHelper helper, int wait, @Nullable ArmedObserver comparison) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.target = new ChunkPos(helper.absolutePos(MILLSTONE));
            this.arena = arenaChunks(helper);
            this.wait = wait;
            this.comparison = comparison;
        }

        private void step() {
            if (phase == Phase.DONE) {
                return;
            }
            long now = level.getGameTime();
            switch (phase) {
                case PLACING -> place();
                case SETTLING -> settle();
                case ARMED -> release(now);
                case RELEASED -> awaitUnload(now);
                case GONE -> awaitReturn(now);
                case BACK -> awaitSweep(now);
                default -> {
                }
            }
        }

        private void place() {
            if (!subject.place(helper)) {
                fail("create:millstone or create:creative_motor is not registered");
                return;
            }
            phase = Phase.SETTLING;
        }

        /** The kinetic network needs real server ticks to form; a comparison cannot wait inside one. */
        private void settle() {
            if (--settleLeft > 0) {
                return;
            }
            String blocked = subject.arm(helper);
            if (blocked != null) {
                fail("the arena is not usable: " + blocked);
                return;
            }
            phase = Phase.ARMED;
        }

        private void release(long now) {
            subject.reset(helper);
            RoundTripImages.watch(target);
            if (comparison != null) {
                comparison.arm(helper, subject, target);
                ChunkCatchUp.setObserver(comparison);
            }
            Meanwhile.LOGGER.info("[unloaded] releasing | chunk={} arena={} recipe={} at={} {}",
                    target, arena, subject.recipeId(), now, subject.describe(helper));
            for (ChunkPos pos : arena) {
                level.setChunkForced(pos.x, pos.z, false);
            }
            releasedAt = now;
            phase = Phase.RELEASED;
        }

        private void awaitUnload(long now) {
            if (RoundTripImages.unloads() > 0) {
                unloadAt = RoundTripImages.unloadAt();
                Meanwhile.LOGGER.info("[unloaded] gone | chunk={} releasedAt={} unloadAt={}"
                        + " delta={}", target, releasedAt, unloadAt, unloadAt - releasedAt);
                phase = Phase.GONE;
                return;
            }
            if (now - releasedAt > UNLOAD_WAIT) {
                fail("chunk " + target + " posted no ChunkEvent.Unload in " + UNLOAD_WAIT
                        + " ticks after its forced tickets were dropped");
            }
        }

        private void awaitReturn(long now) {
            if (now - unloadAt < wait) {
                return;
            }
            for (ChunkPos pos : arena) {
                level.setChunkForced(pos.x, pos.z, true);
            }
            askedAt = now;
            Meanwhile.LOGGER.info("[unloaded] asked back | chunk={} askedAt={} goneFor={}",
                    target, askedAt, now - unloadAt);
            phase = Phase.BACK;
        }

        private void awaitSweep(long now) {
            ChunkCatchUp.Sweep result = ChunkCatchUp.lastSweep(level, target);
            if (result != null && result.at() >= askedAt) {
                sweep = result;
                phase = Phase.DONE;
                return;
            }
            ChunkClock.Reconciliation reconciliation =
                    ChunkClock.lastReconciliation(level, target);
            // Given a margin rather than taken the same tick: the clock works the difference out
            // and hands it over inside one sweep, but a run in which nothing is handed over must
            // be distinguishable from one where the handover has simply not happened yet.
            if (reconciliation != null && reconciliation.at() >= askedAt
                    && now - reconciliation.at() > 15L) {
                // The clock has worked the difference out and the catch-up did not run on it,
                // which is a result rather than a timeout: the guards are allowed to skip.
                Meanwhile.LOGGER.info("[unloaded] reconciled without a catch-up | {}",
                        reconciliation);
                phase = Phase.DONE;
                return;
            }
            if (now - askedAt > BACK_WAIT) {
                fail("chunk " + target + " came back at " + askedAt + " but neither the clock nor"
                        + " the catch-up had run on it " + BACK_WAIT + " ticks later");
            }
        }

        /** The states in which nothing was actually measured, whatever else looks fine. */
        @Nullable
        private String vacuous() {
            if (failure != null) {
                return failure;
            }
            if (phase != Phase.DONE) {
                return "the round trip did not finish: " + describe();
            }
            if (RoundTripImages.unloads() == 0) {
                return "the chunk never unloaded, so nothing was caught up from being away";
            }
            if (RoundTripImages.loads() == 0) {
                return "the chunk never came back";
            }
            return null;
        }

        private String describe() {
            return "phase=" + phase + " releasedAt=" + releasedAt + " unloadAt=" + unloadAt
                    + " askedAt=" + askedAt + " unloads=" + RoundTripImages.unloads()
                    + " loads=" + RoundTripImages.loads()
                    + " sweep=" + (sweep == null ? "<none>" : "at=" + sweep.at())
                    + (failure == null ? "" : " failure=" + failure);
        }

        private void fail(String message) {
            if (failure == null) {
                failure = message;
            }
            phase = Phase.DONE;
        }

        /** Whatever happened, the framework must get the arena back the way it left it. */
        private void restoreArena() {
            for (ChunkPos pos : arena) {
                level.setChunkForced(pos.x, pos.z, true);
            }
            ChunkCatchUp.setObserver(null);
            RoundTripImages.stopWatching();
        }
    }

    /**
     * Drives one round trip and hands the result to {@code body}.
     *
     * <p>The arena is put back and the observer taken off before {@code body} runs, so that a
     * failing assertion cannot leave a forced ticket behind for the batch teardown to double
     * count or an observer wired into the next test.
     */
    private static void onArena(GameTestHelper helper, int wait, ChunkCatchUp.Mode mode,
                                @Nullable ArmedObserver comparison, Consumer<Trip> body) {
        RoundTripImages.install();
        ChunkCatchUp.setMode(mode);
        if (!ChunkCatchUp.isInstalled()) {
            helper.fail("the catch-up is not installed, so this run measures nothing; write "
                    + "meanwhile-catchup.properties next to the project or in run/");
            return;
        }
        Trip trip = new Trip(helper, wait, comparison);
        int runTicks = SETTLE + UNLOAD_WAIT + wait + BACK_WAIT + 60;

        helper.startSequence()
                .thenExecuteFor(runTicks, trip::step)
                .thenExecute(() -> {
                    trip.restoreArena();
                    Meanwhile.LOGGER.info("[unloaded] RESULT {}", trip.describe());
                    try {
                        body.accept(trip);
                    } finally {
                        ChunkCatchUp.setMode(ChunkCatchUp.Mode.PRODUCT);
                    }
                })
                .thenSucceed();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static void restore(ServerLevel level, BlockPos pos, CompoundTag tag,
                                HolderLookup.Provider registries) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return;
        }
        blockEntity.loadWithComponents(tag.copy(), registries);
        blockEntity.setChanged();
    }

    /**
     * Create's {@code KineticBlockEntity#attachKinetics}, reached without compiling against it.
     *
     * <p>A kinetic block entity writes its speed and network but does not read them back: on load
     * it clears them and re-derives them by attaching to its neighbours, which normally happens on
     * its first tick. Without this, arm A would start from a stationary machine and sit still.
     */
    private static void reattach(@Nullable BlockEntity blockEntity) {
        if (blockEntity == null) {
            return;
        }
        try {
            blockEntity.getClass().getMethod("attachKinetics").invoke(blockEntity);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Not a kinetic block entity, which is the ordinary case for most of a chunk.
        }
    }

    /** Create's {@code KineticBlockEntity#getSpeed}, for a log line, without compiling against it. */
    private static float speedOf(@Nullable BlockEntity blockEntity) {
        if (blockEntity == null) {
            return Float.NaN;
        }
        try {
            return ((Number) blockEntity.getClass().getMethod("getSpeed").invoke(blockEntity))
                    .floatValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Float.NaN;
        }
    }

    private static String describeAttempts(ChunkCatchUp.Sweep sweep) {
        List<String> out = new ArrayList<>();
        for (ChunkCatchUp.Attempt attempt : sweep.attempts()) {
            out.add(attempt.summary());
        }
        return out.toString();
    }

    /**
     * The arena's own bounds, which is as wide as a comparison can be made here.
     *
     * <p>Not the chunks around it. Nine chunks would contain whatever the framework put in the
     * neighbouring arenas, and those run for however long each arm takes; the reason is set out
     * on {@link WideStateDigest}.
     */
    static BoundingBox arenaBox(GameTestHelper helper) {
        AABB bounds = helper.getBounds();
        return BoundingBox.fromCorners(
                BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ),
                BlockPos.containing(bounds.maxX - 1.0, bounds.maxY - 1.0, bounds.maxZ - 1.0));
    }

    /**
     * The chunks the framework force-loaded for this arena, from the bounding box
     * {@code StructureUtils.forceLoadChunks} was handed, plus the structure block's own chunk in
     * case it sits outside. A forced ticket propagates outwards, so an arena with one chunk still
     * held stays loaded through its neighbour.
     */
    static List<ChunkPos> arenaChunks(GameTestHelper helper) {
        AABB bounds = helper.getBounds();
        BoundingBox box = BoundingBox.fromCorners(
                BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ),
                BlockPos.containing(bounds.maxX - 1.0, bounds.maxY - 1.0, bounds.maxZ - 1.0));
        List<ChunkPos> chunks = new ArrayList<>();
        box.intersectingChunks().forEach(chunks::add);
        ChunkPos structureBlock = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        if (!chunks.contains(structureBlock)) {
            chunks.add(structureBlock);
        }
        return chunks;
    }

    // ---- which negative control this run is ---------------------------------------------------------

    private static boolean marker(String name) {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path candidate : List.of(
                cwd.resolve(name),
                cwd.resolve("run").resolve(name),
                cwd.getParent() == null ? cwd.resolve(name) : cwd.getParent().resolve(name))) {
            if (Files.isRegularFile(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** Spend nothing however long the chunk was gone. The comparison has to notice. */
    private static boolean ignoreElapsedRequested() {
        return marker("meanwhile-nc-ignore-elapsed.properties");
    }

    /** Hand the negative difference over instead of skipping it. */
    private static boolean nonPositiveRequested() {
        return marker("meanwhile-nc-non-positive.properties");
    }

    /** Ask the level for a block entity from inside an unload, once, and see what happens. */
    private static boolean resurrectProbeRequested() {
        return marker("meanwhile-nc-resurrect.properties");
    }
}
