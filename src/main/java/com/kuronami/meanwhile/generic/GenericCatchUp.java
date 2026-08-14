package com.kuronami.meanwhile.generic;

import com.kuronami.meanwhile.Meanwhile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catch-up for a block entity whose type is unknown.
 *
 * <p>{@code FurnaceCatchUp} jumps the stretches between a furnace's regime changes by
 * observing that a tick moved nothing but two named counters, and it knows which boundaries
 * those counters are heading for. This one is the same method with the type knowledge
 * removed: the observation is a diff of the whole serialised block entity, and the boundary
 * is whatever a decreasing counter reaching zero is.
 *
 * <p>The window is walked as follows. One real tick is taken through the block's own
 * {@link BlockEntityTicker}, and the block state plus {@code saveWithoutMetadata} are
 * compared before and after. A jump is allowed only when that tick changed nothing but the
 * values of integral numeric tags at identical paths — no key appearing or disappearing, no
 * list, string or float moving, no block state change. The jump distance is bounded so that
 * every decreasing counter stays at one or above, which puts the tick where it would have
 * reached zero back in vanilla's hands. Anything else costs a real tick.
 *
 * <p>Two limits are structural rather than incidental, and neither is fixed by any amount of
 * care here:
 *
 * <ul>
 *   <li>An <em>increasing</em> counter is never jumped. Its boundary is a number the machine
 *       compares against, and that number is only sometimes in the NBT: a furnace keeps its
 *       {@code cookingTotalTime} there, but a machine comparing against a constant in its
 *       own code keeps nothing there at all, and the difference is invisible from here. The
 *       furnace's cooking stretch is therefore out of reach for this generic form, which is
 *       strictly weaker than the typed one rather than a replacement for it.</li>
 *   <li>State a block entity does not serialise is invisible to the diff, and equally
 *       invisible to any comparison made with the same instrument. A counter that lives only
 *       in a field — a lazy-tick phase, a cached recipe — is silently phase-shifted by a
 *       jump. What a matching digest establishes is that the NBT-visible state was
 *       reproduced, not that the jump was sound.</li>
 * </ul>
 *
 * <p>Nothing here mentions any particular block, and nothing here is on a production path.
 */
public final class GenericCatchUp {

    /**
     * The values each rising counter has been seen turning over at, by block entity type, by the
     * path of the tag inside it, and by how often each value has been seen.
     *
     * <h3>Why a table of observations and not a tag standing next to the counter</h3>
     *
     * <p>The tag beside it was a guess, and a guess that is too high is not merely a wasted jump.
     * A machine whose boundary is written {@code progress == total} — which is how
     * {@code AbstractFurnaceBlockEntity} writes it — stops arriving at that boundary the moment a
     * jump steps over it, and it never arrives again: the furnace counts upward forever and
     * finishes nothing. Detecting that afterwards does not undo it. So there is no guess left in
     * here. A counter is jumped only after this has watched it turn over and written down where.
     *
     * <p>The key names a type, not a machine, so the cost is paid once per kind rather than once
     * per block: the first furnace in a world spends real ticks learning that its counter turns
     * over at 200, and every other furnace jumps immediately. No type knowledge is involved in
     * that — the type is the key of a table, not something this understands.
     *
     * <h3>Why one observation of it is not enough</h3>
     *
     * <p>A fall is a turnover only if the machine put the counter back to where it starts, and
     * nothing here can tell that from a counter being collapsed onto some other number. A furnace
     * that has been carried past its {@code cookingTotalTime} climbs without limit — the boundary
     * is written {@code ==} and is now behind it — and vanilla clamps it back onto that total the
     * moment the fire goes out. The fall from 9400 to 200 is indistinguishable, here, from a
     * counter that turns over at 9401. Taking the smallest value seen is no protection while the
     * wrong one is the only one there.
     *
     * <p>So a value has to be seen {@link #CORROBORATION} times before a jump may be aimed at it.
     * Until then the counter is worth one real tick each, which is exactly what already happens
     * for a counter nothing has been seen about at all. The price is paid once per type: the
     * first furnace in a world watches two cycles instead of one.
     *
     * <p>Not persisted. A restart re-learns, at the price of a few cycles per type.
     */
    private static final Map<String, TreeMap<Long, Integer>> OBSERVED = new ConcurrentHashMap<>();

    /**
     * How many times one value has to be seen turning over before a jump may be aimed at it.
     */
    private static final int CORROBORATION = 2;

    /**
     * Distinct turnover values kept for one counter.
     *
     * <p>A counter that lands somewhere different every cycle would otherwise grow this table
     * without limit. When it is full the largest value is dropped: what authorises a jump is the
     * smallest corroborated value, so losing the largest can only ever shorten a jump.
     */
    private static final int DISTINCT_KEPT = 8;

    /**
     * Test-only: let a single observation authorise a jump, which is what this used to do.
     *
     * <p>Kept only to be measured doing the wrong thing. With this off, one fall that was not a
     * turnover is enough to aim a jump at where it appeared to happen.
     */
    private static volatile boolean peakCorroboration = true;

    public static void setPeakCorroboration(boolean on) {
        peakCorroboration = on;
        Meanwhile.LOGGER.info("[generic] peak corroboration | {}", on);
    }

    private GenericCatchUp() {
    }

    /** How many sightings of one value make it usable. */
    private static int required() {
        return peakCorroboration ? CORROBORATION : 1;
    }

    /** What may authorise a jump so far, for a report. */
    public static Map<String, Long> peaks() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, TreeMap<Long, Integer>> entry : OBSERVED.entrySet()) {
            Long authorising = authorisingIn(entry.getValue());
            if (authorising != null) {
                out.put(entry.getKey(), authorising);
            }
        }
        return out;
    }

    /** Every value seen and how often, including the ones that authorise nothing yet. */
    public static Map<String, Map<Long, Integer>> observations() {
        Map<String, Map<Long, Integer>> out = new LinkedHashMap<>();
        OBSERVED.forEach((key, counts) -> {
            synchronized (counts) {
                out.put(key, new LinkedHashMap<>(counts));
            }
        });
        return out;
    }

    /**
     * The smallest value seen often enough to be aimed at, or null.
     *
     * <p>Smallest rather than first: which observation arrives first depends on run order, and a
     * table whose contents depend on run order is not an observation. Small is also the safe
     * direction — a value below the real boundary shortens a jump, one above it steps over.
     */
    @Nullable
    private static Long authorisingIn(TreeMap<Long, Integer> counts) {
        int needed = required();
        synchronized (counts) {
            for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
                if (entry.getValue() >= needed) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /** Test-only: start again knowing nothing, so the first-of-a-kind cost can be measured. */
    static void forgetPeaks() {
        OBSERVED.clear();
    }

    private static String peakKey(String typeKey, String path) {
        return typeKey + "|" + path;
    }

    /**
     * Writes down that a counter turned over.
     *
     * <p>The turning point is not a value this ever sees in a tag. A furnace at 199 is stepped to
     * 200 and reset to 0 inside the same tick, so what is observed is 199 followed by 0. The value
     * the machine acts on is therefore the last value seen plus the step it was taking.
     */
    private static void recordPeak(String typeKey, String path, long peak, BlockPos pos,
                                   long from, long to, long rise) {
        TreeMap<Long, Integer> counts =
                OBSERVED.computeIfAbsent(peakKey(typeKey, path), ignored -> new TreeMap<>());
        Long before = authorisingIn(counts);
        int seen;
        synchronized (counts) {
            seen = counts.merge(peak, 1, Integer::sum);
            while (counts.size() > DISTINCT_KEPT) {
                counts.pollLastEntry();
            }
        }
        Long after = authorisingIn(counts);

        // Every sighting up to the one that makes the value usable, and nothing after that: a
        // counter jumping every cycle would otherwise write a line per cycle for the rest of
        // the run, and the sightings that decide anything are the first two.
        if (seen <= CORROBORATION) {
            Meanwhile.LOGGER.info("[generic] peak seen | type={} path={} turnsOverAt={} seen={}"
                            + " from={} to={} rise={} pos={}",
                    typeKey, path, peak, seen, from, to, rise, pos.toShortString());
        }
        if (!Objects.equals(before, after)) {
            Meanwhile.LOGGER.info("[generic] peak authorises | type={} path={} turnsOverAt={}"
                            + " was={}", typeKey, path, after, before);
        }
    }

    /**
     * How much bigger than one step a fall has to be before it is a counter turning over.
     *
     * <p>Scale-free on purpose: the test is against the counter's own step, not against any
     * number belonging to any machine.
     */
    private static final int REWIND_RATIO = 4;

    /** Test-only: count every fall as a turnover, which is what this used to do. */
    private static volatile boolean rewindDistinction = true;

    static void setRewindDistinction(boolean on) {
        rewindDistinction = on;
        Meanwhile.LOGGER.info("[generic] rewind distinction | {}", on);
    }

    /**
     * Whether a fall is a counter going back to the start rather than merely counting down.
     *
     * <p>Both happen, and only one of them says where the counter was heading. A furnace's
     * cooking progress climbs to 199 and is put back to 0 in the same tick it completes — a fall
     * of 199 against a step of 1. The same counter, once the fire is out, is walked back down two
     * at a time by {@code clamp(progress - 2, 0, total)} — a fall of 2 against a step of 1. Read
     * as a turnover, that second one teaches that the counter turns over at around 100, which is
     * wrong and, once written down, is wrong for every furnace afterwards.
     *
     * <p>Two conditions, neither of which names anything: the fall is several times the step it
     * had been rising by, and it lands near the bottom relative to how far it fell. A counter
     * that rewinds to somewhere well above zero is not recognised and is simply never jumped,
     * which is the safe way to be wrong about it.
     */
    private static boolean isRewind(long from, long to, long rise) {
        if (!rewindDistinction) {
            return true;
        }
        long fall = from - to;
        return fall >= rise * REWIND_RATIO && to * (long) REWIND_RATIO < fall;
    }

    @Nullable
    private static Long peakOf(String typeKey, String path) {
        TreeMap<Long, Integer> counts = OBSERVED.get(peakKey(typeKey, path));
        return counts == null ? null : authorisingIn(counts);
    }

    // ---- where falling counters were seen to stop: observation only ------------------------

    /**
     * What falling counters have been seen doing, by block entity type and tag path.
     *
     * <p>Nothing reads this to decide anything, and {@link #span} is unchanged by its existence.
     * A falling counter is still sent towards zero, which is the one number this class assumes
     * rather than watches. Whether that assumption holds is a question about a population, so
     * this writes down what the population does and answers nothing itself.
     *
     * <p>A trough is only seen when the fall and the rise that ends it both happen inside one
     * window, which is the same reach the peak table has.
     */
    private static final Map<String, Falling> FALLING = new ConcurrentHashMap<>();

    /** How many distinct trough values are kept for one counter. */
    private static final int DISTINCT_TROUGHS_KEPT = 8;

    /**
     * One counter's falls.
     *
     * <p>Both ends of the range are kept as scalars, because the cap on {@code troughs} drops the
     * smallest value when the table is full. The small end is what the current assumption expects
     * to see and the large end is the evidence against it, so a table that could silently lose
     * either would be answering a different question than the one it was built for.
     */
    private static final class Falling {
        private long falls;
        private long turnarounds;
        private long lowest = Long.MAX_VALUE;
        private long highest = Long.MIN_VALUE;
        private final TreeMap<Long, Integer> troughs = new TreeMap<>();
    }

    /**
     * One counter's falls, as a reading. The two extremes are null when no fall was ever seen to
     * turn back up, which is a counter that says nothing about where it was heading.
     */
    record FloorReading(long falls, long turnarounds, @Nullable Long lowest,
                        @Nullable Long highest, Map<Long, Integer> troughs) {
    }

    /** Measurement only: every falling counter seen so far. */
    static Map<String, FloorReading> floors() {
        Map<String, FloorReading> out = new LinkedHashMap<>();
        FALLING.forEach((key, falling) -> {
            synchronized (falling) {
                boolean turned = falling.turnarounds > 0;
                out.put(key, new FloorReading(falling.falls, falling.turnarounds,
                        turned ? falling.lowest : null, turned ? falling.highest : null,
                        new LinkedHashMap<>(falling.troughs)));
            }
        });
        return out;
    }

    /** Measurement only: how much of a falling counter every jump already leaves to the machine. */
    static int verifyMargin() {
        return VERIFY_MARGIN;
    }

    private static Falling fallingFor(String typeKey, String path) {
        return FALLING.computeIfAbsent(peakKey(typeKey, path), ignored -> new Falling());
    }

    /** A counter went down. Counted whether or not it is ever seen coming back up. */
    private static void countFall(String typeKey, String path) {
        Falling falling = fallingFor(typeKey, path);
        synchronized (falling) {
            falling.falls++;
        }
    }

    /**
     * A counter that had been falling went back up, which is the only thing that says where it
     * was heading.
     *
     * <p>What is written down is the value it was seen at, not the value one further step would
     * have reached. The two differ by exactly one step and which of them a machine acts on is a
     * judgement about that machine: a vanilla furnace's {@code BurnTime} is seen at 1 and its
     * fuel is taken when it would reach 0, while a millstone's {@code Timer} is seen at 0. The
     * step is logged beside the value so that both readings stay available.
     */
    private static void recordTrough(String typeKey, String path, long low, long fall, long to,
                                     BlockPos pos) {
        Falling falling = fallingFor(typeKey, path);
        int seen;
        synchronized (falling) {
            falling.turnarounds++;
            falling.lowest = Math.min(falling.lowest, low);
            falling.highest = Math.max(falling.highest, low);
            seen = falling.troughs.merge(low, 1, Integer::sum);
            while (falling.troughs.size() > DISTINCT_TROUGHS_KEPT) {
                falling.troughs.pollFirstEntry();
            }
        }
        // Same cap as the peak table's, for the same reason: a counter turning round every cycle
        // would otherwise write a line per cycle for the rest of the run.
        if (seen <= CORROBORATION) {
            Meanwhile.LOGGER.info("[generic] trough seen | type={} path={} turnsUpAt={} seen={}"
                            + " to={} fall={} pos={}",
                    typeKey, path, low, seen, to, fall, pos.toShortString());
        }
    }

    /** Every integral scalar in a tag, by dotted path, for watching values turn over. */
    private static void flatten(CompoundTag tag, List<String> prefix, Map<String, Long> out) {
        for (String key : tag.getAllKeys()) {
            Tag child = tag.get(key);
            if (child == null) {
                continue;
            }
            List<String> here = new ArrayList<>(prefix);
            here.add(key);
            if (child instanceof CompoundTag compound) {
                flatten(compound, here, out);
            } else if (isIntegral(child)) {
                out.put(String.join(".", here), ((NumericTag) child).getAsLong());
            }
        }
    }

    private static Map<String, Long> flatten(CompoundTag tag) {
        Map<String, Long> out = new LinkedHashMap<>();
        flatten(tag, new ArrayList<>(), out);
        return out;
    }

    /**
     * Deliberate breakage, for measuring whether the comparison can reject a wrong answer.
     *
     * @param assumeLinear   accept every diff as a linear counter move, ignoring keys that
     *                       appeared, lists that changed and values that are not integers
     * @param ignoreBoundary jump the whole remaining window instead of stopping a tick short
     *                       of where a counter would cross zero
     */
    public record Mode(boolean assumeLinear, boolean ignoreBoundary, boolean noCeiling,
                       boolean staticCeiling, boolean inflateCeiling) {

        public static final Mode SAFE = new Mode(false, false, false, false, false);
        public static final Mode ASSUME_LINEAR = new Mode(true, false, false, false, false);
        public static final Mode IGNORE_BOUNDARY = new Mode(false, true, false, false, false);
        /**
         * Put whatever ceiling was arrived at far beyond the real one, so that a jump lands
         * somewhere the machine never was. The check run after a jump is what has to notice.
         */
        public static final Mode INFLATED_CEILING = new Mode(false, false, false, false, true);
        /**
         * Take the number a rising counter is heading for from a tag standing beside it, the way
         * this used to. Kept only to be measured failing: the tag is a guess, and a guess that is
         * too high carries the machine past a boundary it can never reach again.
         */
        public static final Mode STATIC_CEILING = new Mode(false, false, false, true, false);
        /**
         * Deliberately believe a ceiling far beyond the real one, so that a jump carries the
         * machine past a boundary it should have stopped at. What the check after a jump is for.
         */

        /**
         * Take away the ceiling a rising counter is read against, leaving the older behaviour of
         * refusing every rise. The measurement of what that rule buys is the difference between
         * this and {@link #SAFE}.
         */
        /** No source of a ceiling at all, so no rising counter is ever jumped. */
        public static final Mode NO_CEILING = new Mode(false, false, true, false, false);

        public String label() {
            if (assumeLinear) {
                return "assume-linear";
            }
            if (ignoreBoundary) {
                return "ignore-boundary";
            }
            if (staticCeiling) {
                return "static-ceiling";
            }
            if (inflateCeiling) {
                return "inflated-ceiling";
            }
            return noCeiling ? "no-ceiling" : "safe";
        }
    }

    /**
     * What a catch-up did, and — more usefully when it did nothing — why it would not.
     *
     * <p>A generic catch-up that declines is still correct, because the caller ticks instead.
     * It is only uninformative, so the reasons are counted rather than thrown away: "it never
     * jumped" and "it never jumped because the input list changes every tick" are different
     * results.
     */
    public static final class Result {

        private int realTicks;
        private int jumps;
        private int jumpedTicks;
        private boolean declined;
        private boolean overshot;
        private String declineReason;
        private final Map<String, Integer> refusals = new LinkedHashMap<>();
        private final Map<String, Integer> writes = new LinkedHashMap<>();
        private String firstRefusal;

        /** Which write-back mechanism carried each jump. */
        public Map<String, Integer> writes() {
            return writes;
        }

        private void refuse(String category, String detail) {
            refusals.merge(category, 1, Integer::sum);
            if (firstRefusal == null) {
                firstRefusal = category + ": " + detail;
            }
        }

        public int realTicks() {
            return realTicks;
        }

        public int jumps() {
            return jumps;
        }

        public int jumpedTicks() {
            return jumpedTicks;
        }

        public boolean declined() {
            return declined;
        }

        /**
         * Whether a jump was found, after the fact, to have carried the machine past a boundary.
         *
         * <p>Set by the tick run immediately after a jump: that tick has to move the same counters
         * by the same amounts the tick before the jump did, because the jump is only ever allowed
         * to travel within one regime. When it does not, the number the jump was aimed at was not
         * the boundary, and no more jumping is done on that machine.
         */
        public boolean overshot() {
            return overshot;
        }

        public String declineReason() {
            return declineReason;
        }

        public Map<String, Integer> refusals() {
            return refusals;
        }

        public String firstRefusal() {
            return firstRefusal;
        }

        @Override
        public String toString() {
            return "realTicks=" + realTicks + " jumps=" + jumps + " jumpedTicks=" + jumpedTicks
                    + (overshot ? " OVERSHOT" : "")
                    + (declined ? " DECLINED(" + declineReason + ")" : "")
                    + " writes=" + writes + " refusals=" + refusals
                    + (firstRefusal == null ? "" : " first=[" + firstRefusal + "]");
        }
    }

    /**
     * One integral tag that the observed tick moved.
     *
     * @param ceiling the value a rising counter is heading for, taken from a tag that sits beside
     *                it and did not move, or {@link Long#MIN_VALUE} when nothing beside it could
     *                be one. Meaningless for a counter that is going down, whose limit is zero.
     */
    private record Delta(List<String> path, byte type, long delta, long after, long ceiling) {

        static final long NO_CEILING = Long.MIN_VALUE;

        String pathString() {
            return String.join(".", path);
        }
    }

    // ---- dispatch ---------------------------------------------------------------------

    /**
     * Runs one real tick of whatever is at {@code pos}, through the block's own ticker.
     *
     * <p>The same route the game itself uses, and the same route both arms of a comparison
     * must use: reaching a block entity any other way would make a dispatch difference look
     * like a catch-up bug.
     *
     * @return false when there is nothing tickable there
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean tickOnce(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null || !(state.getBlock() instanceof EntityBlock entityBlock)) {
            return false;
        }
        BlockEntityTicker ticker =
                entityBlock.getTicker(level, state, (BlockEntityType) blockEntity.getType());
        if (ticker == null) {
            return false;
        }
        ticker.tick(level, pos, state, blockEntity);
        return true;
    }

    /** Runs {@code ticks} real ticks. */
    public static int tick(ServerLevel level, BlockPos pos, int ticks) {
        int ran = 0;
        for (int i = 0; i < ticks; i++) {
            if (!tickOnce(level, pos)) {
                break;
            }
            ran++;
        }
        return ran;
    }

    // ---- the catch-up ------------------------------------------------------------------

    public static Result catchUp(ServerLevel level, BlockPos pos, int ticks, Mode mode) {
        Result result = new Result();
        HolderLookup.Provider registries = level.registryAccess();

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            result.declined = true;
            result.declineReason = "no block entity at " + pos;
            return result;
        }

        String typeKey = String.valueOf(
                BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()));
        Map<String, Long> lastValues = flatten(blockEntity.saveWithoutMetadata(registries));
        Map<String, Long> lastRise = new LinkedHashMap<>();
        // Survey only: the step a counter was falling by, so that the tick it turns back up on
        // can be written down. Read by nothing that decides anything.
        Map<String, Long> lastFall = new LinkedHashMap<>();

        int remaining = ticks;
        boolean jumpsAllowed = true;
        // The movement that authorised the last jump. The tick after a jump has to repeat it.
        List<Delta> awaitingCheck = null;

        while (remaining > 0) {
            BlockState stateBefore = level.getBlockState(pos);
            CompoundTag before = blockEntity.saveWithoutMetadata(registries);

            if (!tickOnce(level, pos)) {
                result.declined = true;
                result.declineReason = "nothing tickable at " + pos;
                return result;
            }
            remaining--;
            result.realTicks++;
            if (remaining <= 0) {
                break;
            }

            // The block entity object can be replaced by a tick that changes the block.
            blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) {
                result.declined = true;
                result.declineReason = "block entity vanished mid-window";
                return result;
            }

            BlockState stateAfter = level.getBlockState(pos);
            CompoundTag after = blockEntity.saveWithoutMetadata(registries);

            // Watched on every tick, whether or not the diff was clean enough to jump on. The
            // tick a counter turns over is exactly the tick the rest of the machine moves too,
            // so the tick that teaches the most is the one no jump could have been made from.
            Map<String, Long> nowValues = flatten(after);
            for (Map.Entry<String, Long> entry : nowValues.entrySet()) {
                Long was = lastValues.get(entry.getKey());
                if (was == null) {
                    continue;
                }
                long movement = entry.getValue() - was;
                if (movement > 0) {
                    lastRise.put(entry.getKey(), movement);
                    // Survey only. A counter that had been falling and is now rising was as low
                    // as it was going to get on the tick before this one, whether it turned round
                    // immediately or stood still first.
                    Long fell = lastFall.remove(entry.getKey());
                    if (fell != null) {
                        recordTrough(typeKey, entry.getKey(), was, fell, entry.getValue(), pos);
                    }
                } else if (movement < 0) {
                    countFall(typeKey, entry.getKey());
                    lastFall.put(entry.getKey(), -movement);
                    Long rise = lastRise.get(entry.getKey());
                    if (rise != null && isRewind(was, entry.getValue(), rise)) {
                        recordPeak(typeKey, entry.getKey(), was + rise, pos, was,
                                entry.getValue(), rise);
                    }
                    // Either it turned over, in which case the peak is now written down, or it
                    // changed direction, in which case the step it was rising by is stale.
                    lastRise.remove(entry.getKey());
                }
            }
            lastValues = nowValues;

            if (stateAfter.equals(stateBefore) && after.equals(before)) {
                // A tick that changed nothing at all, in a subject nothing else can reach,
                // will keep changing nothing. Same assumption the typed version makes, and
                // the same one invalidation notification exists to protect.
                result.jumps++;
                result.jumpedTicks += remaining;
                remaining = 0;
                break;
            }

            // The tick just run came straight after a jump, so it is the evidence about that
            // jump. A jump is only ever allowed to travel inside one regime, and the span is cut
            // so that a tick of that regime is still left over afterwards; if this tick did not
            // move the same counters by the same amounts, the number the jump was aimed at was
            // not where the regime ended. That is the only signal available: the write-back check
            // compares the tag against what the arithmetic intended, so a wrong ceiling produces
            // a perfectly self-consistent tag and passes it.
            if (awaitingCheck != null) {
                List<Delta> observed = new ArrayList<>();
                String[] verifyBlocker = new String[1];
                boolean readable = stateAfter.equals(stateBefore)
                        && collect(before, after, new ArrayList<>(), observed, verifyBlocker,
                                mode, typeKey);
                boolean sameRegime = readable && sameMovement(awaitingCheck, observed);
                // A second signal, for the machines the first one cannot see. A boundary tested
                // with == rather than >= simply stops arriving once it has been stepped over, and
                // the machine goes on moving exactly as before — nothing in the movement says
                // anything is wrong. What does change is that the number it was counting towards
                // is now behind it, so the tag that was standing there as a ceiling no longer
                // qualifies. A counter that had somewhere to go and now has nowhere has passed it.
                String past = readable ? passedItsPeak(typeKey, observed) : null;
                List<Delta> expected = awaitingCheck;
                awaitingCheck = null;
                if (!sameRegime || past != null) {
                    result.overshot = true;
                    jumpsAllowed = false;
                    result.refuse("overshoot", past != null
                            ? "after a jump, " + past + ", which is beyond where this counter has"
                                    + " been watched turning over, so the jump carried it past"
                                    + " the only boundary that was ever observed"
                            : "the tick after a jump moved " + describe(observed)
                                    + " where the tick before it moved " + describe(expected)
                                    + ", so the jump had already left that regime");
                    continue;
                }
            }

            if (!jumpsAllowed) {
                continue;
            }

            if (!stateAfter.equals(stateBefore) && !mode.assumeLinear()) {
                result.refuse("block-state-changed", stateBefore + " -> " + stateAfter);
                continue;
            }

            List<Delta> deltas = new ArrayList<>();
            String[] blocker = new String[1];
            if (!collect(before, after, new ArrayList<>(), deltas, blocker, mode, typeKey)) {
                result.refuse(category(blocker[0]), blocker[0]);
                continue;
            }
            if (deltas.isEmpty()) {
                result.refuse("no-integral-movement",
                        "the tick changed something the diff cannot express as a counter");
                continue;
            }

            int span = span(deltas, remaining, mode, result);
            if (span <= 0) {
                continue;
            }

            int step = Math.min(span, remaining);
            CompoundTag intended = after.copy();
            for (Delta delta : deltas) {
                apply(intended, delta, step);
            }

            String writer = writeBack(blockEntity, registries, deltas, step, after, intended, result);
            if (writer == null) {
                if (result.declined) {
                    return result;
                }
                jumpsAllowed = false;
                continue;
            }
            result.writes.merge(writer, 1, Integer::sum);

            result.jumps++;
            result.jumpedTicks += step;
            remaining -= step;
            awaitingCheck = deltas;
            // Re-read after the write-back, so the next comparison spans one real tick and not a
            // tick plus a jump. Without this the rise carried into the turnover is the jump's own
            // arithmetic, and the value learned is the counter's real peak plus the jump: a
            // furnace jumped from 5 to 198 and then ticked to 199 recorded a rise of 194, and
            // turning over at 199 was written down as 393 rather than 200.
            lastValues = flatten(blockEntity.saveWithoutMetadata(registries));
            lastRise.clear();
        }

        return result;
    }

    /**
     * Carries the jump into the live block entity, and refuses to believe it worked.
     *
     * <p>Two mechanisms, tried in order, neither of them knowing what the block entity is.
     * The first assigns the counters as fields, found by matching the NBT key to a field name
     * and confirming that the field currently holds the value the diff said it held. The
     * second writes the whole tag back through {@code loadWithComponents}.
     *
     * <p>Both are then checked the same way: serialise again and require the result to be the
     * tag that was intended. That check is what makes name-matching safe rather than merely
     * plausible — a wrong field, a missed field, or a loader that normalises or rebuilds part
     * of its state all show up here as a tag that is not the one asked for. Create's kinetic
     * block entities fail the second mechanism outright, because their serialised speed and
     * network are re-derived on load rather than read, so a reload of their own output is not
     * the state they were in.
     *
     * @return which mechanism worked, or null when neither did
     */
    private static String writeBack(BlockEntity blockEntity, HolderLookup.Provider registries,
                                    List<Delta> deltas, int step, CompoundTag after,
                                    CompoundTag intended, Result result) {
        List<Field> fields = locateFields(blockEntity, deltas);
        if (fields != null) {
            assign(fields, deltas, blockEntity, step);
            if (blockEntity.saveWithoutMetadata(registries).equals(intended)) {
                return "field";
            }
            assign(fields, deltas, blockEntity, 0);
            if (!blockEntity.saveWithoutMetadata(registries).equals(after)) {
                result.declined = true;
                result.declineReason = "a field write could not be undone; state is unknown";
                return null;
            }
            result.refuse("field-write-mismatch",
                    "assigning " + deltas.get(0).pathString() + " as a field did not serialise"
                            + " back to the intended tag");
        }

        blockEntity.loadWithComponents(intended.copy(), registries);
        if (blockEntity.saveWithoutMetadata(registries).equals(intended)) {
            return "nbt";
        }
        blockEntity.loadWithComponents(after.copy(), registries);
        if (!blockEntity.saveWithoutMetadata(registries).equals(after)) {
            result.declined = true;
            result.declineReason = "the block entity does not round-trip through its own NBT,"
                    + " so a write-back cannot be undone and the state after one is unknown";
            return null;
        }
        result.refuse("nbt-write-mismatch",
                "loading the jumped tag did not serialise back to it");
        return null;
    }

    /** Whether two ticks moved the same paths by the same amounts. */
    private static boolean sameMovement(List<Delta> expected, List<Delta> observed) {
        if (expected.size() != observed.size()) {
            return false;
        }
        List<String> left = new ArrayList<>();
        List<String> right = new ArrayList<>();
        for (Delta delta : expected) {
            left.add(delta.pathString() + "=" + delta.delta());
        }
        for (Delta delta : observed) {
            right.add(delta.pathString() + "=" + delta.delta());
        }
        java.util.Collections.sort(left);
        java.util.Collections.sort(right);
        return left.equals(right);
    }

    /**
     * A rising counter now standing beyond the value it has been watched turning over at.
     *
     * <p>The second of the two signals, and the one that catches the machines the first cannot.
     * A boundary written {@code progress == total} stops arriving once it has been stepped over,
     * and the machine goes on moving exactly as it did — nothing in the movement says anything is
     * wrong, and nothing ever will again. What can still be said is that the counter is now past
     * the only place it has ever been seen turn over, which no correct jump can produce.
     *
     * <p>Compared against the table rather than against whatever ceiling the current mode handed
     * out, so that a mode which inflates the ceiling is still measured against the observation.
     */
    @Nullable
    private static String passedItsPeak(String typeKey, List<Delta> observed) {
        for (Delta delta : observed) {
            if (delta.delta() <= 0) {
                continue;
            }
            Long peak = peakOf(typeKey, delta.pathString());
            if (peak != null && delta.after() > peak) {
                return delta.pathString() + " is at " + delta.after() + " against a peak of "
                        + peak;
            }
        }
        return null;
    }

    private static String describe(List<Delta> deltas) {
        List<String> out = new ArrayList<>();
        for (Delta delta : deltas) {
            out.add(delta.pathString() + " by " + delta.delta());
        }
        return out.isEmpty() ? "nothing a counter can express" : out.toString();
    }

    /**
     * The live fields behind the counters that moved, or null when they cannot be identified.
     *
     * <p>A field qualifies only if its name matches the NBT key, it is an integral primitive,
     * and it currently holds exactly the value the tag says. One candidate, or nothing.
     */
    private static List<Field> locateFields(BlockEntity blockEntity, List<Delta> deltas) {
        List<Field> found = new ArrayList<>();
        for (Delta delta : deltas) {
            if (delta.path().size() != 1) {
                return null;
            }
            Field field = locateField(blockEntity, delta.path().get(0), delta.after());
            if (field == null) {
                return null;
            }
            found.add(field);
        }
        return found;
    }

    private static Field locateField(BlockEntity blockEntity, String key, long expected) {
        Field candidate = null;
        for (Class<?> type = blockEntity.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getName().equalsIgnoreCase(key) || !isIntegral(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    if (field.getLong(blockEntity) != expected) {
                        continue;
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    continue;
                }
                if (candidate != null) {
                    return null;
                }
                candidate = field;
            }
        }
        return candidate;
    }

    private static void assign(List<Field> fields, List<Delta> deltas, BlockEntity blockEntity,
                               int step) {
        for (int i = 0; i < fields.size(); i++) {
            Delta delta = deltas.get(i);
            long value = delta.after() + delta.delta() * (long) step;
            try {
                Field field = fields.get(i);
                Class<?> type = field.getType();
                if (type == int.class) {
                    field.setInt(blockEntity, (int) value);
                } else if (type == long.class) {
                    field.setLong(blockEntity, value);
                } else if (type == short.class) {
                    field.setShort(blockEntity, (short) value);
                } else {
                    field.setByte(blockEntity, (byte) value);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException("field assignment failed", e);
            }
        }
    }

    private static boolean isIntegral(Class<?> type) {
        return type == int.class || type == long.class || type == short.class || type == byte.class;
    }

    private static String category(String blocker) {
        int colon = blocker.indexOf(' ');
        return colon < 0 ? blocker : blocker.substring(0, colon);
    }

    /**
     * The diff, as a list of integral counters that moved, or a refusal.
     *
     * <p>Anything that is not "an integer at a path that exists in both, moved" is a refusal:
     * a key appearing or disappearing, a tag changing type, a list, string or byte array
     * changing, a float or double changing. Those are the ticks where the machine did
     * something, and they are exactly the ticks that must be run for real.
     */
    private static boolean collect(CompoundTag before, CompoundTag after, List<String> prefix,
                                   List<Delta> out, String[] blocker, Mode mode, String typeKey) {
        if (!before.getAllKeys().equals(after.getAllKeys())) {
            if (!mode.assumeLinear()) {
                blocker[0] = "key-set changed at " + path(prefix) + " " + before.getAllKeys()
                        + " -> " + after.getAllKeys();
                return false;
            }
        }

        for (String key : before.getAllKeys()) {
            Tag tagBefore = before.get(key);
            Tag tagAfter = after.get(key);
            if (tagBefore == null || tagAfter == null) {
                continue;
            }
            List<String> here = new ArrayList<>(prefix);
            here.add(key);

            if (tagBefore.getId() != tagAfter.getId()) {
                if (mode.assumeLinear()) {
                    continue;
                }
                blocker[0] = "tag-type changed at " + path(here);
                return false;
            }
            if (tagBefore instanceof CompoundTag compoundBefore
                    && tagAfter instanceof CompoundTag compoundAfter) {
                if (!collect(compoundBefore, compoundAfter, here, out, blocker, mode, typeKey)) {
                    return false;
                }
                continue;
            }
            if (tagBefore.equals(tagAfter)) {
                continue;
            }
            if (isIntegral(tagBefore)) {
                long valueBefore = ((NumericTag) tagBefore).getAsLong();
                long valueAfter = ((NumericTag) tagAfter).getAsLong();
                long movement = valueAfter - valueBefore;
                long ceiling = Delta.NO_CEILING;
                if (movement > 0 && !mode.noCeiling()) {
                    if (mode.staticCeiling()) {
                        ceiling = ceilingFor(before, after, key, valueAfter);
                    } else {
                        Long learned = peakOf(typeKey, String.join(".", here));
                        if (learned == null) {
                            ceiling = Delta.NO_CEILING;
                        } else {
                            // A tag standing beside the counter never authorises a jump — only a
                            // watched turnover does — but it may shorten one. A machine of one
                            // type whose counter runs to a different number per recipe would
                            // otherwise be jumped against the longest recipe ever seen; taking
                            // the smaller of the two costs a shorter jump and nothing else.
                            long beside = ceilingFor(before, after, key, valueAfter);
                            ceiling = beside == Delta.NO_CEILING
                                    ? learned
                                    : Math.min(learned, beside);
                        }
                    }
                    if (mode.inflateCeiling()) {
                        ceiling = inflate(ceiling);
                    }
                }
                out.add(new Delta(here, tagBefore.getId(), movement, valueAfter, ceiling));
                continue;
            }
            if (mode.assumeLinear()) {
                continue;
            }
            blocker[0] = "non-integral changed at " + path(here) + " (" + brief(tagBefore)
                    + " -> " + brief(tagAfter) + ")";
            return false;
        }
        return true;
    }

    /**
     * How far the counters may be carried forward.
     *
     * <p>A counter going down is heading for zero, and zero is a boundary in every state
     * machine that has one, so the jump stops while it is still at one or more. A counter
     * going up is heading for a number this has no way to see, so it is never jumped.
     */
    /**
     * Ticks of a regime kept out of every jump: one for the boundary, one to check the jump by.
     */
    private static final int VERIFY_MARGIN = 2;

    private static int span(List<Delta> deltas, int remaining, Mode mode, Result result) {
        if (mode.ignoreBoundary()) {
            return remaining;
        }

        long span = Long.MAX_VALUE;
        for (Delta delta : deltas) {
            if (delta.delta() == 0) {
                continue;
            }
            // Which number the counter is heading for. Down is easy: zero is a boundary in every
            // state machine that has one, and it is the same zero for every machine. Up needs a
            // number from somewhere, and the only place to look without knowing what the machine
            // is, is the tag beside it.
            long limit = delta.delta() > 0 ? delta.ceiling() : 0L;
            if (delta.delta() > 0 && limit == Delta.NO_CEILING) {
                result.refuse("peak-not-seen",
                        delta.pathString() + " rose by " + delta.delta()
                                + " and has not yet been watched turning over at the same value"
                                + " twice, so where it is heading is not established");
                return 0;
            }
            // Two ticks are held back rather than one. The first is the boundary itself, which
            // has always been left to the machine; the second is the tick that checks the jump,
            // and it has to fall inside the regime the jump travelled through or it cannot say
            // anything about it.
            long headroom = delta.delta() > 0
                    ? limit - delta.after() - VERIFY_MARGIN
                    : delta.after() - VERIFY_MARGIN;
            if (headroom <= 0) {
                result.refuse("at-boundary",
                        delta.pathString() + " is at " + delta.after()
                                + (delta.delta() > 0 ? " against " + limit : ""));
                return 0;
            }
            span = Math.min(span, headroom / Math.abs(delta.delta()));
        }
        if (span == Long.MAX_VALUE || span <= 0) {
            result.refuse("no-safe-span", "nothing bounds the jump");
            return 0;
        }
        return (int) Math.min(span, Integer.MAX_VALUE);
    }

    /**
     * The number a rising counter is heading for, or {@link Delta#NO_CEILING}.
     *
     * <p>A candidate is an integral tag in the same compound as the counter, which the observed
     * tick did not move, and whose value is at or above where the counter now is. A furnace keeps
     * {@code CookTimeTotal} next to {@code CookTime} and this finds it; so does anything else
     * that writes down what it is counting towards, whatever it calls it.
     *
     * <p>The nearest such value is taken rather than one picked by name. {@code Total} and
     * {@code Max} are conventions, not rules, and a comparison that leans on them stops working
     * on the first machine whose author chose a different word. The nearest candidate is also
     * the conservative one: a ceiling that is too low costs a jump, while one that is too high
     * would carry the machine past a boundary, so of two candidates the smaller is the one to
     * be wrong with.
     *
     * <p>What this cannot do is tell a limit from a number that merely happens to be larger.
     * A compound holding an identifier, a capacity and a counter offers all of them, and the
     * nearest is chosen without knowing which is which. The write-back check that follows every
     * jump does not catch this — a wrong ceiling produces a perfectly consistent tag — so the
     * protection is that the boundary tick itself is always left to the machine.
     */
    private static long ceilingFor(CompoundTag before, CompoundTag after, String movingKey,
                                   long current) {
        long nearest = Delta.NO_CEILING;
        for (String key : after.getAllKeys()) {
            if (key.equals(movingKey)) {
                continue;
            }
            Tag candidate = after.get(key);
            Tag was = before.get(key);
            if (candidate == null || was == null || !isIntegral(candidate)
                    || !candidate.equals(was)) {
                continue;
            }
            long value = ((NumericTag) candidate).getAsLong();
            if (value < current) {
                continue;
            }
            if (nearest == Delta.NO_CEILING || value < nearest) {
                nearest = value;
            }
        }
        return nearest;
    }

    /** The control: a ceiling put far past anything the machine could really be counting to. */
    private static long inflate(long ceiling) {
        return ceiling == Delta.NO_CEILING ? ceiling : ceiling * 64L + 4096L;
    }

    private static void apply(CompoundTag tag, Delta delta, int step) {
        CompoundTag holder = tag;
        List<String> path = delta.path();
        for (int i = 0; i < path.size() - 1; i++) {
            holder = holder.getCompound(path.get(i));
        }
        String key = path.get(path.size() - 1);
        long value = delta.after() + delta.delta() * (long) step;
        switch (delta.type()) {
            case Tag.TAG_BYTE -> holder.put(key, ByteTag.valueOf((byte) value));
            case Tag.TAG_SHORT -> holder.put(key, ShortTag.valueOf((short) value));
            case Tag.TAG_INT -> holder.put(key, IntTag.valueOf((int) value));
            case Tag.TAG_LONG -> holder.put(key, LongTag.valueOf(value));
            default -> throw new IllegalStateException("not an integral tag: " + delta.type());
        }
    }

    private static boolean isIntegral(Tag tag) {
        byte id = tag.getId();
        return id == Tag.TAG_BYTE || id == Tag.TAG_SHORT || id == Tag.TAG_INT || id == Tag.TAG_LONG;
    }

    private static String path(List<String> segments) {
        return segments.isEmpty() ? "<root>" : String.join(".", segments);
    }

    private static String brief(Tag tag) {
        String text = tag.toString();
        return text.length() <= 60 ? text : text.substring(0, 60) + "...";
    }

    // ---- diagnostics -------------------------------------------------------------------

    /**
     * The paths whose values differ, for a log line that says what a machine moves each tick.
     * Measurement only: nothing decides anything on this.
     */
    public static List<String> changedPaths(CompoundTag before, CompoundTag after) {
        List<String> out = new ArrayList<>();
        changedPaths(before, after, new ArrayList<>(), out);
        return out;
    }

    private static void changedPaths(CompoundTag before, CompoundTag after, List<String> prefix,
                                     List<String> out) {
        for (String key : before.getAllKeys()) {
            List<String> here = new ArrayList<>(prefix);
            here.add(key);
            Tag tagBefore = before.get(key);
            Tag tagAfter = after.get(key);
            if (tagAfter == null) {
                out.add("-" + path(here));
                continue;
            }
            if (tagBefore instanceof CompoundTag compoundBefore
                    && tagAfter instanceof CompoundTag compoundAfter) {
                changedPaths(compoundBefore, compoundAfter, here, out);
                continue;
            }
            if (!tagBefore.equals(tagAfter)) {
                out.add(path(here) + "[" + brief(tagBefore) + "->" + brief(tagAfter) + "]");
            }
        }
        for (String key : after.getAllKeys()) {
            if (!before.contains(key)) {
                List<String> here = new ArrayList<>(prefix);
                here.add(key);
                out.add("+" + path(here));
            }
        }
    }
}
