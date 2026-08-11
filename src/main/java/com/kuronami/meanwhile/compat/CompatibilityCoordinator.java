package com.kuronami.meanwhile.compat;

import com.kuronami.meanwhile.Meanwhile;
import java.util.concurrent.atomic.AtomicBoolean;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/**
 * Which block entities another mod is already catching up, so that this one does not do it twice.
 *
 * <p>Two mods advancing the same machine over the same absence do not produce "caught up once by
 * whichever got there first". They produce the window spent twice: a furnace that was owed 3,000
 * ticks is handed 3,000 by each, and the fuel it burns and the items it smelts come out of an
 * absence that only happened once. There is no arbitration to be had between two mods that each
 * believe they are the only one doing this, so the resolution is to step out of the lane rather
 * than to share it — the same first-writer-wins shape {@code mod-052-free-server-saver}'s
 * {@code CompatibilityCoordinator} uses, and this class follows it deliberately.
 *
 * <h3>Why the whole overlap is furnaces</h3>
 *
 * <p>The version of Unloaded Activity that runs on 1.21.1 is v0.6.7, and it ships no datapack at
 * all: the types it handles are compiled mixins, so there is nothing to enumerate at runtime and
 * nothing to read. Of its 44 mixins exactly one targets a block entity,
 * {@code AbstractFurnaceBlockEntityMixin}; the other subject mixins are block random ticks,
 * precipitation, and passive entity growth. This mod's production walk visits block entities and
 * nothing else, so the overlap is {@link
 * net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity} and its subclasses — furnace,
 * blast furnace, smoker — and nothing besides. The evidence is in GAP_LOG G120, taken from the
 * shipped jar rather than from documentation.
 *
 * <p>That leaves the deferral cheap in the only sense that matters: what this mod is for is modded
 * block entities that no type-aware mod has ever heard of, and on 1.21.1 Unloaded Activity does not
 * touch one of them.
 *
 * <h3>The inference this rests on, and how it would be refuted</h3>
 *
 * <p>Deferring only furnaces assumes Unloaded Activity's 1.21.1 surface stays where it is. The
 * grounds are that 2 of its 65-odd releases ever targeted 1.21.1, the last on 2025-06-16, and that
 * development moved to 1.21.8+ and 26.x on a different architecture. That is an inference and not
 * a fact about the future: if a 1.21.1 release adds a block entity mixin, the added types run
 * twice and this class will not know. So the deferral announces itself at startup instead of being
 * silent — a report of a machine advancing at double rate can be checked against one log line
 * rather than against a reading of both mods' source.
 *
 * <p>The detection is by NeoForge mod id {@code unloaded_activity} with the underscore. Unloaded
 * Activity's Fabric build declares {@code unloadedactivity} without one, which is a different
 * string and would silently never match on the loader this mod ships for.
 */
public final class CompatibilityCoordinator {

    /**
     * Unloaded Activity's NeoForge mod id.
     *
     * <p>Read from the shipped jar's {@code META-INF/neoforge.mods.toml}, not from its Fabric
     * counterpart, which spells it without the underscore.
     */
    public static final String UNLOADED_ACTIVITY = "unloaded_activity";

    /**
     * Whether furnaces are somebody else's job this run.
     *
     * <p>Written once, from the server thread, before any chunk has been swept; read from the
     * drain thereafter. Atomic rather than plain because the write and the reads are not the same
     * thread, and a value published unsafely is a deferral that works on one host and not another.
     */
    private static final AtomicBoolean deferFurnaces = new AtomicBoolean(false);

    private CompatibilityCoordinator() {
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        boolean present = ModList.get().isLoaded(UNLOADED_ACTIVITY);
        deferFurnaces.set(present);
        if (present) {
            // At INFO, and once per server start. This line is the whole of what makes the
            // deferral observable after the fact, so it is not a debug detail.
            Meanwhile.LOGGER.info("[compat] deferring | '{}' is loaded, so furnaces, blast"
                    + " furnaces and smokers are left to it and this mod declines them."
                    + " Everything else in a returning chunk is caught up as usual.",
                    UNLOADED_ACTIVITY);
        }
    }

    /**
     * Whether a furnace should be declined rather than caught up.
     *
     * <p>False in every run where Unloaded Activity is absent, which is the ordinary case and the
     * one the gates measure.
     */
    public static boolean defersFurnaces() {
        return deferFurnaces.get();
    }

    /**
     * Test-only: set the flag without the other mod being installed.
     *
     * <p>What a gate can show is that the flag is the seam — that a furnace is declined when it is
     * set and caught up when it is not. Whether the other mod's presence sets it is
     * {@link #onServerStarting}'s one line, and installing a second mod inside the test run to
     * exercise that line would be measuring NeoForge's mod list rather than this mod.
     *
     * <p>Don't call from anywhere else. This is the eleventh seam of its kind on the product
     * classes (GAP_LOG G133 lists the other ten); it is public for the same reason they are, and
     * it should be closed with them rather than on its own.
     */
    public static void setDeferringFurnaces(boolean defer) {
        deferFurnaces.set(defer);
    }
}
