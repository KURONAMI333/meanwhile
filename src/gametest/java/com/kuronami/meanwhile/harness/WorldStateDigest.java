package com.kuronami.meanwhile.harness;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * A canonical, comparable snapshot of everything inside a region: block states, block
 * entity NBT, and entity NBT.
 *
 * <p>Deliberately a list of text lines rather than only a hash. A hash can tell you that
 * two runs differ; it cannot tell you where, and a differential harness whose failure
 * message is "the hashes differ" costs more time than it saves. The hash is derived from
 * the lines for logging.
 */
public final class WorldStateDigest {

    private final List<String> lines;

    private WorldStateDigest(List<String> lines) {
        this.lines = lines;
    }

    public static WorldStateDigest capture(ServerLevel level, BoundingBox box) {
        HolderLookup.Provider registries = level.registryAccess();
        List<String> lines = new ArrayList<>();

        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    String local = (x - box.minX()) + "," + (y - box.minY()) + "," + (z - box.minZ());
                    lines.add("block " + local + " " + state);

                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity != null) {
                        CompoundTag tag = blockEntity.saveWithoutMetadata(registries);
                        lines.add("blockentity " + local + " " + tag);
                    }
                }
            }
        }

        AABB aabb = AABB.of(box);
        List<Entity> entities = new ArrayList<>(level.getEntities((Entity) null, aabb, e -> true));
        // Entity iteration order is not stable between runs, so sort before comparing.
        entities.sort(Comparator.comparing(e -> e.getUUID().toString()));
        for (Entity entity : entities) {
            CompoundTag tag = new CompoundTag();
            entity.saveWithoutId(tag);
            // The UUID and exact position differ run to run without meaning anything.
            tag.remove("UUID");
            lines.add("entity " + entity.getType() + " " + tag);
        }

        return new WorldStateDigest(lines);
    }

    public List<String> lines() {
        return lines;
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
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * The first place two snapshots disagree, or null when they are identical.
     */
    public String firstDifference(WorldStateDigest other) {
        int shared = Math.min(this.lines.size(), other.lines.size());
        for (int i = 0; i < shared; i++) {
            String mine = this.lines.get(i);
            String theirs = other.lines.get(i);
            if (!mine.equals(theirs)) {
                return "line " + i + "\n  simulated: " + mine + "\n  catch-up:  " + theirs;
            }
        }
        if (this.lines.size() != other.lines.size()) {
            List<String> longer = this.lines.size() > other.lines.size() ? this.lines : other.lines;
            String which = this.lines.size() > other.lines.size() ? "simulated" : "catch-up";
            return "extra content in " + which + " at line " + shared + ": " + longer.get(shared);
        }
        return null;
    }
}
