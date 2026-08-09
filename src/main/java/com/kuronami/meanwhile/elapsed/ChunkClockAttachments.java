package com.kuronami.meanwhile.elapsed;

import com.kuronami.meanwhile.Meanwhile;
import com.mojang.serialization.Codec;
import java.util.function.Supplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Where a chunk keeps the game time it was last seen running at.
 *
 * <p>A NeoForge data attachment rather than a mixin on the chunk serializer: {@code ChunkAccess}
 * implements {@code IAttachmentHolder} directly ({@code ChunkAccess.java:60}), the chunk
 * serializer already writes and reads the attachment block
 * ({@code ChunkSerializer.java:400} and {@code :214}), and a serializable attachment is copied
 * from a {@code ProtoChunk} to a {@code LevelChunk} on promotion. Nothing here needs a mixin.
 *
 * <p>The stored value is a bare game tick. {@code Codec.LONG} is the whole serializer.
 *
 * <p>The default value is a sentinel that is never meant to be read. Every read in this package
 * goes through {@code getExistingDataOrNull}, because {@code getData} would store the default
 * into the holder and hand it back, which makes "the attachment did not survive the round trip"
 * indistinguishable from "the chunk was last seen at that tick".
 */
public final class ChunkClockAttachments {

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Meanwhile.MODID);

    /** The game time of the last tick this chunk was seen ticking blocks. */
    public static final Supplier<AttachmentType<Long>> LAST_SEEN_GAME_TIME =
            ATTACHMENT_TYPES.register("last_seen_game_time",
                    () -> AttachmentType.builder(() -> Long.MIN_VALUE)
                            .serialize(Codec.LONG)
                            .build());

    /**
     * Ticks this chunk is owed and has not been given yet.
     *
     * <p>Kept apart from {@link #LAST_SEEN_GAME_TIME} rather than expressed by holding that value
     * back, and the difference matters. If the stored time were frozen while a debt was
     * outstanding, then a chunk that unloaded halfway through being paid would come back and
     * work out its difference against the frozen time — counting the stretch it had already been
     * paid for a second time. With the debt written down separately, the stored time advances
     * every tick the chunk runs, a fresh absence adds to the debt rather than replacing it, and
     * nothing is either lost or paid twice.
     *
     * <p>Serialised for the same reason the stored time is: a chunk can go away mid-repayment,
     * and what it is still owed has to survive that.
     */
    public static final Supplier<AttachmentType<Long>> CATCH_UP_DEBT =
            ATTACHMENT_TYPES.register("catch_up_debt",
                    () -> AttachmentType.builder(() -> 0L)
                            .serialize(Codec.LONG)
                            .build());

    public static void register(IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }

    private ChunkClockAttachments() {
    }
}
