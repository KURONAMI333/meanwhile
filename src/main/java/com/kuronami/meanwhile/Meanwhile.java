package com.kuronami.meanwhile;

import com.kuronami.meanwhile.bench.TickCostBench;
import com.kuronami.meanwhile.chunkprobe.ChunkEventProbe;
import com.kuronami.meanwhile.chunkprobe.ChunkRoundTripGameTests;
import com.kuronami.meanwhile.elapsed.CatchUpGuardGameTests;
import com.kuronami.meanwhile.elapsed.ChunkCatchUp;
import com.kuronami.meanwhile.elapsed.ChunkClock;
import com.kuronami.meanwhile.elapsed.ChunkClockAttachments;
import com.kuronami.meanwhile.elapsed.CorpusSweepGameTests;
import com.kuronami.meanwhile.elapsed.FurnaceSpanGameTests;
import com.kuronami.meanwhile.elapsed.FurnaceWideGameTests;
import com.kuronami.meanwhile.elapsed.ScaffoldGameTests;
import com.kuronami.meanwhile.elapsed.UnloadedCatchUpGameTests;
import com.kuronami.meanwhile.generic.GenericCatchUpGameTests;
import com.kuronami.meanwhile.scheduler.TargetRegistry;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Catches a chunk up on the block entity progress it missed while it was away.
 *
 * <p>{@link ChunkClock} writes the game time onto every chunk that is running, and reads it back
 * when the chunk returns; {@link ChunkCatchUp} takes the difference and spends it over whatever
 * block entities the chunk holds, knowing nothing about their types. Both are installed
 * unconditionally. There is no configuration and no marker file deciding whether the mod does its
 * work — installing it is the whole of using it.
 *
 * <p>What marker files still decide is measurement: the tick-cost bench, the chunk round-trip
 * probe and the retired loaded-chunk scheme's tests all change what a standard run measures, so
 * they stay opt-in. The type-agnostic catch-up's own tests need a Create machine as their
 * subject, so those are registered when Create is present and not otherwise.
 */
@Mod(Meanwhile.MODID)
public class Meanwhile {
    public static final String MODID = "meanwhile";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Written next to the project (and/or in the run directory) to bring the loaded-chunk
     * scheme's tests back into the suite. Same file also has to be paired with uncommenting
     * the {@code [[mixins]]} block in {@code neoforge.mods.toml} for the scheme itself to run;
     * this marker only concerns the five test classes.
     */
    private static final String LOADED_MARKER = "meanwhile-loaded.properties";

    public Meanwhile(IEventBus modEventBus, ModContainer modContainer) {
        // Which block entity types the scheduler is allowed to stop ticking. Populated
        // before any world exists, and read from the tick dispatch thereafter.
        TargetRegistry.bootstrap();

        // Where a chunk keeps the game time it was last seen running at, and the two hooks that
        // write it and read it back. Unconditional: this is the mod, not an experiment.
        ChunkClockAttachments.register(modEventBus);
        ChunkClock.install();

        // Spending the difference the clock works out. Unconditional: this is the mod, and a
        // clock with nothing reading it back is a chunk marked unsaved every tick for no result.
        ChunkCatchUp.install();

        // What the generic catch-up can skip on a burning furnace, what a full round trip does
        // to one, and the population survey over whatever types are loaded. All vanilla, so they
        // go into every standard run. No @GameTestHolder on any of them: NeoForge finds holders
        // by scanning the classpath, and registering them here is what keeps the decision in one
        // readable place next to the Create-dependent ones below.
        modEventBus.addListener((RegisterGameTestsEvent event) -> {
            event.register(FurnaceSpanGameTests.class);
            event.register(FurnaceWideGameTests.class);
            event.register(CorpusSweepGameTests.class);
            event.register(CatchUpGuardGameTests.class);
            event.register(ScaffoldGameTests.class);
        });
        LOGGER.info("[catchup] registering the vanilla catch-up gates: furnace span, furnace"
                + " wide, corpus sweep, guard, scaffold");

        // The unloaded catch-up gate is measured against a Create machine, so it exists only
        // when Create does.
        if (createIsPresent()) {
            modEventBus.addListener((RegisterGameTestsEvent event) ->
                    event.register(UnloadedCatchUpGameTests.class));
            LOGGER.info("[catchup] Create is present, registering the unloaded catch-up tests");
        } else {
            LOGGER.info("[catchup] Create is absent, so the unloaded catch-up tests have no"
                    + " subject to measure against and are not registered");
        }

        // The tick-cost bench, and only when a marker file asks for it. Registered here
        // rather than carrying @GameTestHolder, because NeoForge discovers holders by
        // scanning the classpath and an annotated class would join the verification suite
        // permanently. Without the marker the suite is exactly what it was.
        if (TickCostBench.isRequested()) {
            modEventBus.addListener((RegisterGameTestsEvent event) -> event.register(TickCostBench.class));
            LOGGER.info("[bench] marker found, registering the tick-cost bench");
        }

        // The chunk round-trip probe, and only when a marker file asks for it. Same reason as
        // the bench for not carrying @GameTestHolder, and one of its own: with the probe
        // running the server does not finish shutting down, so a classpath-scanned holder would
        // put that in the way of the standing suite.
        if (ChunkRoundTripGameTests.isRequested()) {
            modEventBus.addListener((RegisterGameTestsEvent event) ->
                    event.register(ChunkRoundTripGameTests.class));
            // The chunk event listener goes on with it rather than always. It logs a line per
            // chunk load and unload, and a run without the probe has no use for them.
            ChunkEventProbe.install();
            LOGGER.info("[chunkprobe] marker found, registering the chunk round-trip probe");
        }

        // The type-agnostic catch-up is measured against a Create machine, so its tests only
        // exist when Create does. Same reason as the bench for not carrying @GameTestHolder:
        // an annotated class is found by classpath scan and would change the standing suite.
        if (createIsPresent()) {
            modEventBus.addListener((RegisterGameTestsEvent event) ->
                    event.register(GenericCatchUpGameTests.class));
            LOGGER.info("[generic] Create is present, registering the millstone catch-up tests");
        }

        // The loaded-chunk scheme's own tests (SchedulerGameTests, WriteFaceGameTests,
        // ReadFaceGameTests, PersistFaceGameTests, MenuObservationGameTests). That scheme is
        // retired from the product: its mixins live in meanwhile-loaded.mixins.json, which is
        // not wired into neoforge.mods.toml, so the hooks these tests exercise are not
        // installed in a normal run. Same reason as the bench for not carrying
        // @GameTestHolder: an annotated class is found by classpath scan and would join the
        // standing suite permanently regardless of whether the mixins are active.
        if (loadedSchemeIsRequested()) {
            modEventBus.addListener((RegisterGameTestsEvent event) -> {
                event.register(SchedulerGameTests.class);
                event.register(WriteFaceGameTests.class);
                event.register(ReadFaceGameTests.class);
                event.register(PersistFaceGameTests.class);
                event.register(MenuObservationGameTests.class);
            });
            LOGGER.info("[loaded] marker found, registering the loaded-chunk scheme's tests");
        }
    }

    private static boolean createIsPresent() {
        try {
            return ModList.get().isLoaded("create");
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[generic] could not ask whether Create is loaded | {}", e.toString());
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
