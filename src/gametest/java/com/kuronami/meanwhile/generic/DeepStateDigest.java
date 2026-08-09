package com.kuronami.meanwhile.generic;

import com.kuronami.meanwhile.harness.WorldStateDigest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The state of a region as {@link WorldStateDigest} sees it, plus the scalar fields the block
 * entities actually hold.
 *
 * <h3>Why a second digest exists</h3>
 *
 * <p>{@link GenericCatchUp} decides whether to jump by diffing {@code saveWithoutMetadata},
 * and {@link WorldStateDigest} decides whether the jump was right by hashing the same thing.
 * The two look through one hole. Anything a block entity keeps in a field and does not
 * serialise cannot appear in the diff that authorises a jump, and cannot appear in the
 * comparison that blesses it either — Create's {@code lazyTickCounter} is exactly such a
 * field, and a jump necessarily leaves it behind. A comparison built out of the same surface
 * as the decision cannot report that.
 *
 * <p>So the verdict is widened rather than the decision. The decision stays on the serialised
 * surface, because that is genuinely all a production path can see; widening it would be
 * deciding on information that is not there. The verdict is allowed to cheat, because its job
 * is to catch the decision being wrong.
 *
 * <p>This is the same failure the furnace suite already hit one layer down: comparing the
 * quantities somebody thought to observe missed an experience ledger that had leaked between
 * arms, and the fix was to compare the whole serialised form instead of a chosen list. The
 * serialised form is itself a chosen list, one layer up.
 */
public final class DeepStateDigest {

    /**
     * How far into object fields to descend. One level is the block entity's own scalars,
     * which is where the non-serialised counters live; deeper costs a widening blast radius
     * for less and less that belongs to the machine.
     */
    public static final int DEFAULT_DEPTH = 2;

    /** A ceiling, so that a bad descent produces a bounded mess rather than an unbounded one. */
    private static final int MAX_LINES = 4000;

    private final List<String> lines;

    private DeepStateDigest(List<String> lines) {
        this.lines = lines;
    }

    public List<String> lines() {
        return Collections.unmodifiableList(lines);
    }

    public int size() {
        return lines.size();
    }

    public static DeepStateDigest capture(ServerLevel level, BoundingBox box) {
        return capture(level, box, DEFAULT_DEPTH);
    }

    public static DeepStateDigest capture(ServerLevel level, BoundingBox box, int maxDepth) {
        List<String> lines = new ArrayList<>(WorldStateDigest.capture(level, box).lines());

        List<String> fields = new ArrayList<>();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity == null) {
                        continue;
                    }
                    // Positions are relative to the box, because a GameTest arena lands
                    // somewhere new every run and absolute coordinates would make two arms of
                    // the same comparison differ from run to run for no reason.
                    String label = "be@" + (x - box.minX()) + "," + (y - box.minY())
                            + "," + (z - box.minZ());
                    collect(blockEntity, label, maxDepth, new IdentityHashMap<>(), fields);
                }
            }
        }
        // Declared field order is not promised to be stable, and nothing here depends on it.
        Collections.sort(fields);
        lines.addAll(fields);
        return new DeepStateDigest(lines);
    }

    private static void collect(Object owner, String path, int depth,
                                IdentityHashMap<Object, Boolean> seen, List<String> out) {
        if (owner == null || depth < 0 || out.size() >= MAX_LINES
                || seen.put(owner, Boolean.TRUE) != null) {
            return;
        }
        for (Class<?> type = owner.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(owner);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // Recorded rather than dropped: a field that cannot be read on one arm and
                    // can on the other must not quietly become an agreement.
                    out.add(path + "." + type.getSimpleName() + "#" + field.getName()
                            + " = <unreadable:" + e.getClass().getSimpleName() + ">");
                    continue;
                }
                String name = path + "." + type.getSimpleName() + "#" + field.getName();
                String scalar = scalarOf(value);
                if (scalar != null) {
                    out.add(name + " = " + scalar);
                    continue;
                }
                if (value != null && depth > 0 && worthDescending(value)) {
                    collect(value, name, depth - 1, seen, out);
                }
            }
        }
    }

    /**
     * The value as a line, or null when it is not a scalar.
     *
     * <p>Floating point goes in as raw bits. A jump that leaves a speed one ulp off is exactly
     * the kind of thing this is here to catch, and printing it would round it away.
     */
    @Nullable
    private static String scalarOf(@Nullable Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Float number) {
            return "f:" + Float.floatToRawIntBits(number);
        }
        if (value instanceof Double number) {
            return "d:" + Double.doubleToRawLongBits(number);
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character
                || value instanceof CharSequence || value instanceof Enum<?>
                || value instanceof BlockPos) {
            return value.getClass().getSimpleName() + ":" + value;
        }
        return null;
    }

    /**
     * Whether to look inside an object.
     *
     * <p>A block entity holds a reference to its level, and through it to the whole server, so
     * an unfiltered descent walks the world. Only things that belong to the machine are
     * followed: its own mod's objects, and the inventory wrappers NeoForge hands it.
     */
    private static boolean worthDescending(Object value) {
        if (value instanceof BlockEntity || value instanceof Class<?>) {
            return false;
        }
        String name = value.getClass().getName();
        return name.startsWith("com.simibubi.create")
                || name.startsWith("mekanism.")
                || name.startsWith("net.neoforged.neoforge.items")
                || name.startsWith("net.neoforged.neoforge.fluids");
    }

    /** Marks which arm a differing line came from. Both are the same length on purpose. */
    private static final String FROM_SIMULATED = "simulated: ";
    private static final String FROM_CAUGHT_UP = "catch-up : ";
    private static final int PREFIX_LENGTH = FROM_SIMULATED.length();

    /** Every line that differs, both sides, capped so a total mismatch stays readable. */
    public List<String> differences(DeepStateDigest other, int limit) {
        List<String> out = new ArrayList<>();
        Set<String> mine = new java.util.LinkedHashSet<>(lines);
        Set<String> theirs = new java.util.LinkedHashSet<>(other.lines);
        for (String line : lines) {
            if (!theirs.contains(line) && out.size() < limit) {
                out.add(FROM_SIMULATED + line);
            }
        }
        for (String line : other.lines) {
            if (!mine.contains(line) && out.size() < limit) {
                out.add(FROM_CAUGHT_UP + line);
            }
        }
        return out;
    }

    /**
     * Whether a differing line is one of the live fields rather than part of the serialised
     * state — which is to say, whether it is something the catch-up could have seen.
     */
    public static boolean isFieldLine(String difference) {
        return difference.length() > PREFIX_LENGTH
                && difference.startsWith("be@", PREFIX_LENGTH);
    }

    /** The field name out of a differing line, for saying which counters moved. */
    public static String fieldOf(String difference) {
        String line = difference.substring(Math.min(PREFIX_LENGTH, difference.length()));
        int dot = line.indexOf('.');
        int equals = line.indexOf(" = ");
        if (dot < 0 || equals < 0 || equals < dot) {
            return line;
        }
        return line.substring(dot + 1, equals);
    }

    public String sha256() {
        try {
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                sha.update(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                sha.update((byte) '\n');
            }
            byte[] hash = sha.digest();
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                text.append(String.format("%02x", hash[i]));
            }
            return text.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}
