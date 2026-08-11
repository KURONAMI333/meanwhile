package com.kuronami.meanwhile.gametest;

import com.kuronami.meanwhile.HarnessGameTests;
import com.kuronami.meanwhile.Meanwhile;
import com.kuronami.meanwhile.MenuObservationGameTests;
import com.kuronami.meanwhile.PersistFaceGameTests;
import com.kuronami.meanwhile.ReadFaceGameTests;
import com.kuronami.meanwhile.SchedulerGameTests;
import com.kuronami.meanwhile.WriteFaceGameTests;
import com.kuronami.meanwhile.bench.TickCostBench;
import com.kuronami.meanwhile.chunkprobe.ChunkEventProbe;
import com.kuronami.meanwhile.compat.FurnaceDeferralGameTests;
import com.kuronami.meanwhile.chunkprobe.ChunkRoundTripGameTests;
import com.kuronami.meanwhile.elapsed.CatchUpGuardGameTests;
import com.kuronami.meanwhile.elapsed.ChunkCatchUp;
import com.kuronami.meanwhile.elapsed.CorpusSweepGameTests;
import com.kuronami.meanwhile.elapsed.CrowdedChunkGameTests;
import com.kuronami.meanwhile.elapsed.DimensionKeyGameTests;
import com.kuronami.meanwhile.elapsed.FurnaceSpanGameTests;
import com.kuronami.meanwhile.elapsed.FurnaceWideGameTests;
import com.kuronami.meanwhile.elapsed.ScaffoldGameTests;
import com.kuronami.meanwhile.elapsed.UnloadedCatchUpGameTests;
import com.kuronami.meanwhile.generic.GenericCatchUpGameTests;
import com.kuronami.meanwhile.generic.PeakCorroborationGameTests;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Which measurements a run performs. Exists only on the {@code gametest} source set, so none of
 * it reaches the jar that is distributed.
 *
 * <p>A second class annotated {@code @Mod} with the same id as {@link Meanwhile}: NeoForge
 * constructs every entrypoint it finds for a mod, which is how a mod separates code that must
 * not exist in some runs from code that always must. {@link Meanwhile} is the product and knows
 * nothing about any of this; what decides whether a measurement runs stays here, in one readable
 * place, exactly as it was when it lived next to the product.
 *
 * <p>The registrations are deliberate rather than annotated. NeoForge finds
 * {@code @GameTestHolder} classes by scanning, so an annotation would put the marker-gated
 * classes into the standing suite permanently and there would be no way to ask for the bench or
 * the probe without also paying for them in every run.
 */
@Mod(Meanwhile.MODID)
public class MeanwhileGates {

    /**
     * Written next to the project (and/or in the run directory) to bring the loaded-chunk
     * scheme's tests back into the suite. Same file also has to be paired with uncommenting
     * the {@code [[mixins]]} block in {@code neoforge.mods.toml} for the scheme itself to run;
     * this marker only concerns the five test classes.
     */
    private static final String LOADED_MARKER = "meanwhile-loaded.properties";

    public MeanwhileGates(IEventBus modEventBus, ModContainer modContainer) {
        // The per-chunk running totals the gates read back. Off in the product, because they
        // are one entry per chunk ever swept that nothing evicts and nothing in the product
        // ever reads (GAP_LOG G142). Asked for here rather than by the tests that read them:
        // this class is constructed before any gate runs, and a test that had to remember to
        // switch them on would read 0 and call it a balanced ledger.
        ChunkCatchUp.setRecordRunningTotals(true);

        // What the generic catch-up can skip on a burning furnace, what a full round trip does
        // to one, and the population survey over whatever types are loaded. All vanilla, so they
        // go into every standard run.
        modEventBus.addListener((RegisterGameTestsEvent event) -> {
            event.register(FurnaceSpanGameTests.class);
            event.register(FurnaceWideGameTests.class);
            event.register(CorpusSweepGameTests.class);
            event.register(CatchUpGuardGameTests.class);
            event.register(ScaffoldGameTests.class);
            event.register(PeakCorroborationGameTests.class);
            event.register(DimensionKeyGameTests.class);
            event.register(CrowdedChunkGameTests.class);
            event.register(FurnaceDeferralGameTests.class);
        });
        Meanwhile.LOGGER.info("[catchup] registering the vanilla catch-up gates: furnace span, furnace"
                + " wide, corpus sweep, guard, scaffold, peak corroboration, dimension key,"
                + " crowded chunk");

        // The unloaded catch-up gate is measured against a Create machine, so it exists only
        // when Create does.
        if (createIsPresent()) {
            modEventBus.addListener((RegisterGameTestsEvent event) ->
                    event.register(UnloadedCatchUpGameTests.class));
            Meanwhile.LOGGER.info("[catchup] Create is present, registering the unloaded catch-up tests");
        } else {
            Meanwhile.LOGGER.info("[catchup] Create is absent, so the unloaded catch-up tests have no"
                    + " subject to measure against and are not registered");
        }

        // The tick-cost bench, and only when a marker file asks for it.
        if (TickCostBench.isRequested()) {
            modEventBus.addListener((RegisterGameTestsEvent event) -> event.register(TickCostBench.class));
            Meanwhile.LOGGER.info("[bench] marker found, registering the tick-cost bench");
        }

        // The chunk round-trip probe, and only when a marker file asks for it. With the probe
        // running the server does not finish shutting down, so it stays out of the standing
        // suite for a reason of its own.
        if (ChunkRoundTripGameTests.isRequested()) {
            modEventBus.addListener((RegisterGameTestsEvent event) ->
                    event.register(ChunkRoundTripGameTests.class));
            // The chunk event listener goes on with it rather than always. It logs a line per
            // chunk load and unload, and a run without the probe has no use for them.
            ChunkEventProbe.install();
            Meanwhile.LOGGER.info("[chunkprobe] marker found, registering the chunk round-trip probe");
        }

        // The type-agnostic catch-up is measured against a Create machine, so its tests only
        // exist when Create does.
        if (createIsPresent()) {
            modEventBus.addListener((RegisterGameTestsEvent event) ->
                    event.register(GenericCatchUpGameTests.class));
            Meanwhile.LOGGER.info("[generic] Create is present, registering the millstone catch-up tests");
        }

        // The loaded-chunk scheme's own tests (SchedulerGameTests, WriteFaceGameTests,
        // ReadFaceGameTests, PersistFaceGameTests, MenuObservationGameTests). That scheme is
        // retired from the product: its mixins live in meanwhile-loaded.mixins.json, which is
        // not wired into neoforge.mods.toml, so the hooks these tests exercise are not
        // installed in a normal run.
        if (loadedSchemeIsRequested()) {
            modEventBus.addListener((RegisterGameTestsEvent event) -> {
                event.register(SchedulerGameTests.class);
                event.register(WriteFaceGameTests.class);
                event.register(ReadFaceGameTests.class);
                event.register(PersistFaceGameTests.class);
                event.register(MenuObservationGameTests.class);
            });
            Meanwhile.LOGGER.info("[loaded] marker found, registering the loaded-chunk scheme's tests");
        }

        // HarnessGameTests, ChunkClockGameTests and CatchUpPrimitiveBatch carry
        // @GameTestHolder and are found by the classpath scan instead. They are on this source
        // set, so the scan reaches them in a run and never in the jar.
    }

    private static boolean createIsPresent() {
        try {
            return ModList.get().isLoaded("create");
        } catch (RuntimeException | LinkageError e) {
            Meanwhile.LOGGER.warn("[generic] could not ask whether Create is loaded | {}", e.toString());
            return false;
        }
    }

    /**
     * Whether a marker file asks for the loaded-chunk scheme's tests.
     *
     * <p>A file rather than a system property, same reason as {@code TickCostBench}: the run
     * task's JVM arguments are set in {@code build.gradle}, which this is not allowed to touch.
     */
    private static boolean loadedSchemeIsRequested() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path candidate : List.of(
                cwd.resolve(LOADED_MARKER),
                cwd.resolve("run").resolve(LOADED_MARKER),
                cwd.getParent() == null ? cwd.resolve(LOADED_MARKER) : cwd.getParent().resolve(LOADED_MARKER))) {
            if (Files.isRegularFile(candidate)) {
                return true;
            }
        }
        return false;
    }
}
