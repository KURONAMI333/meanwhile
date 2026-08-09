package com.kuronami.meanwhile.elapsed;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/**
 * Everything in nine chunks that a caught-up window could have changed.
 *
 * <p>The comparisons in this package have been made over one machine's serialised form, which is
 * where the machine keeps its work. It is not where all of the machine's effects land. A hopper
 * pushes into the block beside it and that block can be in the next chunk; a belt carries items
 * across a boundary; a furnace that finishes drops nothing but a furnace that is broken does; and
 * a block entity's counters are only half of a machine — the other half is the block state it
 * sits in.
 *
 * <p><b>Block state is the one that matters most here</b>, because it is where a catch-up can go
 * wrong in a way nothing else would show. Carrying {@code BurnTime} down to zero by arithmetic
 * leaves a furnace whose {@code lit} property is still true: the tag says the fire is out, the
 * world says it is burning, and every comparison made on the tag alone agrees with itself. The
 * only witness is the state.
 *
 * <p>What is collected, all of it position-normalised against the box so that two arenas in
 * different places compare equal:
 *
 * <ul>
 *   <li>every non-air block state in the box;</li>
 *   <li>every block entity's serialised form, which carries container contents with it;</li>
 *   <li>every entity in the box, item entities included, with the identity and exact position
 *       dropped — those differ between two runs of the same thing and mean nothing;</li>
 *   <li>every pending block and fluid tick, which is scheduled work a jump can lose.</li>
 * </ul>
 *
 * <h3>What this does not see, and why</h3>
 *
 * <p><b>It does not see anything pushed across a chunk boundary.</b> A hopper feeding the block
 * beside it, a belt carrying an item into the next chunk — the effects that land outside the box
 * are outside this. That is a real gap and it is not closed here.
 *
 * <p>It was tried. Widening the box to the nine chunks around the subject does contain those
 * effects, and it also contains other tests: GameTest lays its arenas out fourteen blocks apart,
 * nine chunks is forty-eight blocks across, so a box that reaches the neighbouring chunks always
 * reaches five or six other arenas as well. Their machines run for however long each arm of a
 * comparison takes, which is not the same length, so they disagree between arms for reasons that
 * have nothing to do with any catch-up — measured at five such lines surviving a two-sample noise
 * floor. The box is therefore the arena's own bounds, and cross-chunk effects are not covered by
 * any test in this repository.
 *
 * <h3>How much of this has been shown to detect anything</h3>
 *
 * <p>Not all of it. Two of the four surfaces have been made to report a difference on purpose and
 * did; one has not, and the reason is known:
 *
 * <ul>
 *   <li><b>block states — demonstrated.</b> Putting a furnace's {@code lit} back to true while
 *       leaving its block entity exactly as the catch-up left it produces two differing lines,
 *       which is the failure this surface exists for.</li>
 *   <li><b>scheduled ticks — demonstrated.</b> Booking one block tick on one arm produces one
 *       differing line.</li>
 *   <li><b>block entities — demonstrated</b> by every comparison in this package.</li>
 *   <li><b>entities — not demonstrated, and cannot be here.</b> An item entity does not survive a
 *       chunk round trip in this harness: dropped into an arena, counted (1), the chunk released,
 *       unloaded, reloaded, counted again (0). Every comparison in this package puts a real
 *       unload and reload in front of both arms, so there is never an entity left by the time
 *       anything is compared. The collection code runs and reports {@code entities=0}; whether it
 *       would report a difference if there were one is untested. Treat this surface as unproven.
 *       </li>
 * </ul>
 */
public final class WideStateDigest {

    /** Blocks above and below the subject that are looked at. */
    public static final int HEIGHT_BAND = 8;

    private final List<String> lines;
    private final int blocks;
    private final int blockEntities;
    private final int entities;
    private final int scheduled;

    private WideStateDigest(List<String> lines, int blocks, int blockEntities, int entities,
                            int scheduled) {
        this.lines = lines;
        this.blocks = blocks;
        this.blockEntities = blockEntities;
        this.entities = entities;
        this.scheduled = scheduled;
    }

    /** The nine chunks around {@code centre}, in a band of height around it. */
    public static BoundingBox nineChunksAround(BlockPos centre) {
        ChunkPos chunk = new ChunkPos(centre);
        return new BoundingBox(
                (chunk.x - 1) << 4, centre.getY() - HEIGHT_BAND, (chunk.z - 1) << 4,
                ((chunk.x + 2) << 4) - 1, centre.getY() + HEIGHT_BAND, ((chunk.z + 2) << 4) - 1);
    }

    public static WideStateDigest capture(ServerLevel level, BoundingBox box) {
        HolderLookup.Provider registries = level.registryAccess();
        List<String> lines = new ArrayList<>();
        int blocks = 0;
        int blockEntities = 0;
        int scheduled = 0;

        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    String local = (x - box.minX()) + "," + (y - box.minY())
                            + "," + (z - box.minZ());
                    lines.add("block " + local + " " + state);
                    blocks++;

                    if (level.getBlockTicks().hasScheduledTick(pos, state.getBlock())) {
                        lines.add("blocktick " + local + " " + state.getBlock());
                        scheduled++;
                    }
                    if (level.getFluidTicks().hasScheduledTick(pos, state.getFluidState()
                            .getType())) {
                        lines.add("fluidtick " + local + " " + state.getFluidState().getType());
                        scheduled++;
                    }

                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity != null) {
                        CompoundTag tag = blockEntity.saveWithoutMetadata(registries);
                        lines.add("blockentity " + local + " " + tag);
                        blockEntities++;
                    }
                }
            }
        }

        List<String> entityLines = new ArrayList<>();
        for (Entity entity : level.getEntities((Entity) null, AABB.of(box), e -> true)) {
            CompoundTag tag = new CompoundTag();
            entity.saveWithoutId(tag);
            // Identity and exact placement differ between two runs of the same thing without
            // meaning anything; what an item entity is and how many of it are what matter.
            tag.remove("UUID");
            tag.remove("Pos");
            tag.remove("Motion");
            tag.remove("Rotation");
            tag.remove("FallDistance");
            tag.remove("Air");
            entityLines.add("entity " + entity.getType() + " " + tag);
        }
        // Iteration order is not stable between runs.
        Collections.sort(entityLines);
        lines.addAll(entityLines);

        return new WideStateDigest(lines, blocks, blockEntities, entityLines.size(), scheduled);
    }

    public List<String> lines() {
        return Collections.unmodifiableList(lines);
    }

    public String shape() {
        return "blocks=" + blocks + " blockEntities=" + blockEntities + " entities=" + entities
                + " scheduledTicks=" + scheduled + " lines=" + lines.size();
    }

    /** Every line one has and the other does not, both directions, capped. */
    public List<String> differences(WideStateDigest other, int limit) {
        List<String> out = new ArrayList<>();
        Set<String> mine = new LinkedHashSet<>(lines);
        Set<String> theirs = new LinkedHashSet<>(other.lines);
        for (String line : lines) {
            if (!theirs.contains(line) && out.size() < limit) {
                out.add("ticked  : " + line);
            }
        }
        for (String line : other.lines) {
            if (!mine.contains(line) && out.size() < limit) {
                out.add("caughtup: " + line);
            }
        }
        return out;
    }

    public String sha256() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                digest.update(line.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}
