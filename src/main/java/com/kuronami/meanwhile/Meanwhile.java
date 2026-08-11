package com.kuronami.meanwhile;

import com.kuronami.meanwhile.compat.CompatibilityCoordinator;
import com.kuronami.meanwhile.elapsed.ChunkCatchUp;
import com.kuronami.meanwhile.elapsed.ChunkClock;
import com.kuronami.meanwhile.elapsed.ChunkClockAttachments;
import com.kuronami.meanwhile.scheduler.TargetRegistry;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Catches a chunk up on the block entity progress it missed while it was away.
 *
 * <p>{@link ChunkClock} writes the game time onto every chunk that is running, and reads it back
 * when the chunk returns; {@link ChunkCatchUp} takes the difference and spends it over whatever
 * block entities the chunk holds, knowing nothing about their types. Both are installed
 * unconditionally. There is no configuration and no marker file deciding whether the mod does its
 * work — installing it is the whole of using it.
 *
 * <p>This class is the whole of what is distributed and it measures nothing. What a run measures
 * lives on the {@code gametest} source set, which the jar does not carry — see
 * {@code MeanwhileGates} there, and {@code verifyNoTestScaffoldingInJar} in {@code build.gradle}
 * for the check that it stays out.
 */
@Mod(Meanwhile.MODID)
public class Meanwhile {
    public static final String MODID = "meanwhile";
    public static final Logger LOGGER = LogUtils.getLogger();

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

        // Which block entities another mod is already catching up. Registered before any world
        // exists and decided once the server starts, so the drain never has to ask.
        NeoForge.EVENT_BUS.register(CompatibilityCoordinator.class);
    }
}
