package com.kuronami.meanwhile.scheduler;

import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Which block entity types the scheduler knows how to account for.
 *
 * <p>Everything absent from here is ticked normally, which is the default the whole design
 * falls back to. Adding a type is the only way a target ever gets deferred, so the registry
 * doubles as the list of things anybody has actually measured.
 *
 * <p>Populated once during mod construction and read from the server thread thereafter.
 */
public final class TargetRegistry {

    private static final Map<BlockEntityType<?>, CatchUpTarget> TARGETS = new IdentityHashMap<>();

    private TargetRegistry() {
    }

    /**
     * The three furnace variants, and nothing else yet.
     *
     * <p>All three are {@code AbstractFurnaceBlockEntity}, so they share the catch-up that
     * the suite measured against a plain furnace. A blast furnace and a smoker differ only
     * in which recipe book they consult and how fast they cook, neither of which the jump
     * logic reads: it observes what one real tick did and only collapses the stretches where
     * that tick moved nothing but the two counters.
     */
    public static void bootstrap() {
        TARGETS.put(BlockEntityType.FURNACE, FurnaceTarget.INSTANCE);
        TARGETS.put(BlockEntityType.BLAST_FURNACE, FurnaceTarget.INSTANCE);
        TARGETS.put(BlockEntityType.SMOKER, FurnaceTarget.INSTANCE);
    }

    @Nullable
    public static CatchUpTarget lookup(BlockEntityType<?> type) {
        return TARGETS.get(type);
    }

    public static int size() {
        return TARGETS.size();
    }
}
