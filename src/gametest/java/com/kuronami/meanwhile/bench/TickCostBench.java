package com.kuronami.meanwhile.bench;

import com.kuronami.meanwhile.Meanwhile;
import com.kuronami.meanwhile.scheduler.CatchUpTarget;
import com.kuronami.meanwhile.scheduler.DeferralScheduler;
import com.kuronami.meanwhile.scheduler.TargetRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Measurement only. Nothing here is on a production path and nothing here runs unless a
 * marker file asks for it.
 *
 * <p>What it measures: the wall-clock duration of a server tick with N furnaces smelting in
 * loaded, ticking chunks, with {@link DeferralScheduler#setEnabled(boolean)} true and false,
 * alternated within one process so that JIT state, GC state and machine load are shared
 * between the two arms rather than confounded with them.
 *
 * <h3>Why a GameTest and not the dedicated server</h3>
 * <p>{@code runServer} needs {@code run/eula.txt}, which is a licence agreement this process
 * is not entitled to accept on anybody's behalf. {@code GameTestServer} does not check it,
 * which is why the existing suite runs without one. The bench is therefore a single long
 * GameTest that holds the server alive while the state machine below drives it; the server
 * would otherwise shut down the moment the last test finished.
 *
 * <p>The cost of that choice is that the tick being measured is a GameTestServer tick, which
 * runs unthrottled rather than at 20 TPS. That changes what a tick *is* only by removing the
 * sleep between ticks, which is not measured either way.
 *
 * <h3>Why it is invisible to the existing gate</h3>
 * <p>No {@code @GameTestHolder}: NeoForge discovers holders by scanning the classpath, so an
 * annotated class would join the suite permanently. This one is registered through
 * {@code RegisterGameTestsEvent} from {@link Meanwhile}, and only when
 * {@link #isRequested()} finds the marker file. A run without the marker registers 40 tests;
 * a run with it registers 41. The count in the clean run is the evidence.
 */
public final class TickCostBench {

    /** Written next to the project (and/or in the run directory) to ask for a bench run. */
    private static final String MARKER = "meanwhile-bench.properties";

    private static final String BATCH = "bench";

    private static final TickCostBench INSTANCE = new TickCostBench();

    // ---- geometry ---------------------------------------------------------------------

    /**
     * A block of the flat test world nothing else uses. Furnaces are handed out of it by a
     * cursor that never goes back: a position is used by exactly one phase, ever.
     *
     * <p>Reuse would be cheaper and wrong. The ledger is keyed by position and its distrust
     * is permanent, so a position that carried a deferred furnace through one phase would
     * enter the next one with a stale baseline. Fresh positions cost some chunk area and buy
     * every phase an identical starting state.
     */
    private static final int X0 = 64;
    private static final int Z0 = 64;
    /** Flat-world air starts at -60; the furnaces sit on the surface and stack upwards. */
    private static final int Y0 = -60;
    private static final int SIDE = 64;
    private static final int PER_LAYER = SIDE * SIDE;

    // ---- configuration (from the marker file) ------------------------------------------

    private int rounds = 3;
    /** Ticks with the scheduler off in both arms, so the furnaces light and the light engine drains. */
    private int settleTicks = 30;
    /** Ticks after the arm is applied whose samples are thrown away. */
    private int warmTicks = 60;
    /** Ticks whose samples are kept. */
    private int measureTicks = 200;
    /** Furnaces in the throwaway phases run before the matrix, to get the JIT past cold. */
    private int prewarmCount = 500;
    /** Ticks spent waiting for the forced chunks before the first phase. */
    private int bootTicks = 40;
    /** Furnace counts, the in-run comparison baseline. */
    private int[] furnaceCounts = {2000};
    /** Counts for the modded machines, which are expected to be far heavier per unit. */
    private int[] machineCounts = {50, 200};
    /** Machine blocks to measure, by registry id. */
    private String[] machineIds = {"create:mechanical_press", "create:encased_fan"};
    private String motorId = "create:creative_motor";
    /** Item pushed into a machine's input for the WORK cells, by registry id. */
    private String feedId = "minecraft:cobblestone";
    private int feedCount = 64;
    /** Ticks a probe candidate is left alone before its speed is read. */
    private static final int PROBE_SETTLE = 6;

    // ---- run state ----------------------------------------------------------------------

    private boolean running;
    private boolean finished;
    private String failure;

    private ServerLevel level;
    private final List<Phase> plan = new ArrayList<>();
    private int phaseIndex = -1;
    private int phaseTick;
    private Stage stage = Stage.BOOT;
    private long tickStartNanos;

    private int cursor;
    private final List<BlockPos> live = new ArrayList<>();
    /** Motors attached to the machines in {@link #live}; torn down with them. */
    private final List<BlockPos> support = new ArrayList<>();
    private final Map<String, Probe> probes = new LinkedHashMap<>();
    private final List<Object[]> probeCandidates = new ArrayList<>();
    private int probeIndex;
    private int probeTick;
    private int turningAtMeasureStart;
    private BlockEntityType<?> skipType;
    private long fedTotal;
    private int[] nbtAtMeasureStart = new int[0];
    /** Well clear of the measured region, so probe debris cannot land in a phase's area. */
    private static final BlockPos PROBE_SITE = new BlockPos(X0 + 1, Y0 + 50, Z0 + 1);
    private long[] samples = new long[0];
    private int sampleCount;

    private int windowsAtMeasureStart;
    private long elapsedAtMeasureStart;
    private int realAtMeasureStart;

    private Path resultsPath;
    private Path rawPath;

    private enum Stage { BOOT, PROBE, SETTLE, WARM, MEASURE, DONE }

    /**
     * @param blockId  what to place, by registry id; {@code null} for an empty phase
     * @param running  for a kinetic machine, whether a creative motor is attached to it
     */
    private record Phase(int round, String label, String blockId, int count, boolean running,
                         boolean fed, boolean skipped, boolean discard) {
    }

    /** A machine orientation that was observed to actually turn, found by trying them. */
    private record Probe(BlockState machine, boolean motorAbove, BlockState motor) {
    }

    private TickCostBench() {
    }

    // ---- gating -------------------------------------------------------------------------

    /**
     * Whether a marker file asks for the bench.
     *
     * <p>A file rather than a system property because the run task's JVM arguments are set in
     * {@code build.gradle}, which this measurement is not allowed to touch.
     */
    public static boolean isRequested() {
        return markerPath() != null;
    }

    private static Path markerPath() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path candidate : List.of(
                cwd.resolve(MARKER),
                cwd.resolve("run").resolve(MARKER),
                cwd.getParent() == null ? cwd.resolve(MARKER) : cwd.getParent().resolve(MARKER))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // ---- the test that holds the server alive -------------------------------------------

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = BATCH, timeoutTicks = 1_000_000)
    public static void tickCost(GameTestHelper helper) {
        INSTANCE.begin(helper.getLevel());
        helper.succeedWhen(() -> {
            if (INSTANCE.failure != null) {
                throw new IllegalStateException("[bench] " + INSTANCE.failure);
            }
            if (!INSTANCE.finished) {
                throw new GameTestAssertException("[bench] still running");
            }
        });
    }

    // ---- lifecycle ----------------------------------------------------------------------

    private void begin(ServerLevel serverLevel) {
        if (running || finished) {
            return;
        }
        this.level = serverLevel;
        readConfig();
        buildPlan();
        openOutputs();

        for (int cx = X0 >> 4; cx <= (X0 + SIDE - 1) >> 4; cx++) {
            for (int cz = Z0 >> 4; cz <= (Z0 + SIDE - 1) >> 4; cz++) {
                serverLevel.setChunkForced(cx, cz, true);
            }
        }

        wipeRegion();

        NeoForge.EVENT_BUS.register(this);
        DeferralScheduler.setEnabled(false);
        stage = Stage.BOOT;
        phaseTick = 0;
        running = true;
        Meanwhile.LOGGER.info("[bench] begin | furnace.n={} machine.n={} machines={} motor={} "
                        + "rounds={} settle={} warm={} measure={} results={}",
                Arrays.toString(furnaceCounts), Arrays.toString(machineCounts),
                Arrays.toString(machineIds), motorId, rounds, settleTicks, warmTicks,
                measureTicks, resultsPath);
    }

    /**
     * Clears every position this run will use, before it uses any of them.
     *
     * <p>The dev world under {@code run/} survives between runs, and forced chunks and block
     * entities survive with it. A run that was interrupted leaves its furnaces smelting in
     * chunks that are still forced, and the next run then measures them: the first attempt at
     * this bench recorded 95ms per tick with N=0 for exactly that reason. Starting by clearing
     * the region makes a run independent of how the previous one ended.
     */
    private void wipeRegion() {
        int needed = 0;
        for (Phase phase : plan) {
            needed += 2 * phase.count();
        }
        // Generous: a previous run may have used more of the region than this one will, and
        // the leftovers tick whether or not this run allocates their positions.
        needed = Math.max(needed, 12 * PER_LAYER);
        int cleared = 0;
        for (int i = 0; i < needed; i++) {
            int layer = 2 * (i / PER_LAYER);
            int rem = i % PER_LAYER;
            BlockPos pos = new BlockPos(X0 + (rem % SIDE), Y0 + layer, Z0 + (rem / SIDE));
            BlockPos above = pos.above();
            if (!level.getBlockState(above).isAir()) {
                emptyAndClear(above);
            }
            if (!level.getBlockState(pos).isAir()) {
                emptyAndClear(pos);
                cleared++;
            }
        }
        int layers = (needed + PER_LAYER - 1) / PER_LAYER;
        AABB box = regionBox(layers);
        List<ItemEntity> dropped = level.getEntitiesOfClass(ItemEntity.class, box);
        for (ItemEntity item : dropped) {
            item.discard();
        }
        Meanwhile.LOGGER.info("[bench] region wiped | positions={} cleared={} items_removed={}",
                needed, cleared, dropped.size());
    }

    /**
     * Removes the block after emptying it.
     *
     * <p>{@code FurnaceBlock#onRemove} drops the container's contents, so taking a furnace out
     * of the world the obvious way spawns three item entities per furnace. Those live for five
     * minutes and tick the whole time, so a run that tore down 2000 furnaces per phase was
     * measuring its own litter: the tick cost tracked the number of furnaces ever removed
     * rather than the number currently ticking, which is what made the ON arm — always the
     * later of the pair — look more expensive than the OFF arm.
     */
    private void emptyAndClear(BlockPos pos) {
        drainHandler(pos);
        if (level.getBlockEntity(pos) instanceof Container container) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                container.setItem(slot, ItemStack.EMPTY);
            }
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
    }

    private AABB regionBox(int layers) {
        return new AABB(X0 - 2, Y0 - 2, Z0 - 2, X0 + SIDE + 2, Y0 + 2 * layers + 4, Z0 + SIDE + 2);
    }

    private int sweepItems() {
        List<ItemEntity> dropped = level.getEntitiesOfClass(ItemEntity.class, regionBox(26));
        for (ItemEntity item : dropped) {
            item.discard();
        }
        return dropped.size();
    }

    /**
     * Teaches the scheduler to stop ticking the bench's machine, for measurement only.
     *
     * <p>Reflective rather than a new registry method, so that not one line of the production
     * path changes to make this measurement possible. The target is deliberately not correct:
     * it defers unconditionally and accounts for a window by doing nothing at all. That is
     * fine here because nothing checks the machine's answer — the question is only what the
     * server stops spending when the block entity's tick goes away.
     */
    private boolean installSkipTarget(String id) {
        Block block = blockOf(id);
        if (block == null) {
            return false;
        }
        BlockEntity sample = level.getBlockEntity(PROBE_SITE);
        BlockEntityType<?> type = skipType;
        if (type == null && sample != null) {
            type = sample.getType();
        }
        if (type == null) {
            return false;
        }
        try {
            java.lang.reflect.Field field = TargetRegistry.class.getDeclaredField("TARGETS");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<BlockEntityType<?>, CatchUpTarget> targets =
                    (Map<BlockEntityType<?>, CatchUpTarget>) field.get(null);
            targets.put(type, new CatchUpTarget() {
                @Override
                public boolean canDefer(ServerLevel serverLevel, BlockPos pos) {
                    return true;
                }

                @Override
                public long fingerprint(ServerLevel serverLevel, BlockPos pos) {
                    return 0L;
                }

                @Override
                public int catchUp(ServerLevel serverLevel, BlockPos pos, int ticks) {
                    return 0;
                }
            });
            skipType = type;
            Meanwhile.LOGGER.info("[bench] skip target installed | {} type={}", id, type);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Meanwhile.LOGGER.warn("[bench] skip target could not be installed | {}", e.toString());
            return false;
        }
    }

    private void readConfig() {
        Path marker = markerPath();
        if (marker == null) {
            return;
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(marker)) {
            props.load(in);
        } catch (IOException e) {
            Meanwhile.LOGGER.warn("[bench] marker unreadable, using defaults | {}", e.toString());
            return;
        }
        furnaceCounts = intsProp(props, "furnace.n", furnaceCounts);
        machineCounts = intsProp(props, "machine.n", machineCounts);
        String machines = props.getProperty("machines");
        if (machines != null && !machines.isBlank()) {
            machineIds = Arrays.stream(machines.split(",")).map(String::trim)
                    .filter(v -> !v.isEmpty()).toArray(String[]::new);
        }
        motorId = props.getProperty("motor", motorId);
        feedId = props.getProperty("feed", feedId);
        feedCount = intProp(props, "feed.count", feedCount);
        rounds = intProp(props, "rounds", rounds);
        settleTicks = intProp(props, "settle", settleTicks);
        warmTicks = intProp(props, "warm", warmTicks);
        measureTicks = intProp(props, "measure", measureTicks);
        prewarmCount = intProp(props, "prewarm", prewarmCount);
        bootTicks = intProp(props, "boot", bootTicks);
    }

    private static int[] intsProp(Properties props, String key, int[] fallback) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(v -> !v.isEmpty())
                .mapToInt(Integer::parseInt).toArray();
    }

    private static int intProp(Properties props, String key, int fallback) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(raw.trim());
    }

    /**
     * Off, on, off, on ... within each round, and the whole matrix repeated per round, so a
     * drift in machine load over the run shows up as a difference between rounds rather than
     * as a difference between arms.
     */
    private void buildPlan() {
        if (prewarmCount > 0) {
            plan.add(new Phase(0, "FURNACE", "minecraft:furnace", prewarmCount, false, false, false, true));
        }
        for (int round = 1; round <= rounds; round++) {
            plan.add(new Phase(round, "EMPTY", null, 0, false, false, false, false));
            for (int n : furnaceCounts) {
                plan.add(new Phase(round, "FURNACE", "minecraft:furnace", n, false, false, false, false));
            }
            for (int n : machineCounts) {
                plan.add(new Phase(round, "MOTOR", motorId, n, true, false, false, false));
            }
            for (String id : machineIds) {
                String name = id.substring(id.indexOf(':') + 1).toUpperCase();
                for (int n : machineCounts) {
                    // Stopped, turning-but-empty, and turning-with-input: the three states the
                    // comparison needs, all inside one run against one baseline.
                    plan.add(new Phase(round, name + "_STOPPED", id, n, false, false, false, false));
                    plan.add(new Phase(round, name + "_TURNING", id, n, true, false, false, false));
                    plan.add(new Phase(round, name + "_WORKING", id, n, true, true, false, false));
                    // The same two cells again with the block entity's tick taken away, which
                    // is the whole question: how much of a turning machine's cost is inside
                    // the tick a deferral could withhold.
                    plan.add(new Phase(round, name + "_TURNING_SKIPPED", id, n, true, false, true, false));
                    plan.add(new Phase(round, name + "_WORKING_SKIPPED", id, n, true, true, true, false));
                }
            }
        }
    }

    private void openOutputs() {
        Path dir = Path.of("").toAbsolutePath();
        resultsPath = dir.resolve("bench_results.csv");
        rawPath = dir.resolve("bench_raw.csv");
        write(resultsPath, "round,furnaces,arm,discard,samples,mean_us,median_us,p95_us,sd_us,min_us,max_us,"
                + "first20_us,last20_us,deferred_at_end,deferred_probe,windows,elapsed_ticks,real_ticks,"
                + "output_items,input_left,lit_furnaces,deferred_after_teardown\n", false);
        write(rawPath, "phase,round,furnaces,arm,discard,tick,nanos\n", false);
    }

    private void write(Path path, String text, boolean append) {
        try {
            if (append) {
                Files.writeString(path, text, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(path, text, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            Meanwhile.LOGGER.warn("[bench] write failed | {} {}", path, e.toString());
        }
    }

    // ---- the tick brackets ---------------------------------------------------------------

    /**
     * The measured quantity is the span from {@code ServerTickEvent.Pre} to
     * {@code ServerTickEvent.Post} of the same tick, taken with {@link System#nanoTime()}.
     * That is the body of {@code MinecraftServer#tickServer}, and it excludes whatever the
     * server does outside the two events and the sleep between ticks.
     */
    @SubscribeEvent
    public void onPre(ServerTickEvent.Pre event) {
        if (running) {
            tickStartNanos = System.nanoTime();
        }
    }

    @SubscribeEvent
    public void onPost(ServerTickEvent.Post event) {
        if (!running) {
            return;
        }
        long duration = System.nanoTime() - tickStartNanos;
        try {
            if (stage == Stage.MEASURE && sampleCount < samples.length) {
                samples[sampleCount++] = duration;
            }
            phaseTick++;
            advance(event);
        } catch (RuntimeException e) {
            failure = e.toString();
            running = false;
            finished = true;
            Meanwhile.LOGGER.error("[bench] aborted", e);
        }
    }

    private void advance(ServerTickEvent.Post event) {
        if (stage == Stage.BOOT) {
            if (phaseTick >= bootTicks) {
                beginProbe();
            }
            return;
        }
        if (stage == Stage.PROBE) {
            tickProbe();
            return;
        }
        if (stage == Stage.SETTLE && phaseTick >= settleTicks) {
            // After the machines have spun up, so both arms enter the window in the same
            // physical state and only the tick dispatch differs.
            DeferralScheduler.setEnabled(plan.get(phaseIndex).skipped());
            stage = Stage.WARM;
            return;
        }
        if (stage == Stage.WARM && phaseTick >= settleTicks + warmTicks) {
            turningAtMeasureStart = countTurning();
            nbtAtMeasureStart = new int[live.size()];
            for (int i = 0; i < live.size(); i++) {
                BlockEntity be = level.getBlockEntity(live.get(i));
                nbtAtMeasureStart[i] = be == null ? 0 : be.saveWithoutMetadata(level.registryAccess()).hashCode();
            }
            stage = Stage.MEASURE;
            return;
        }
        if (stage == Stage.MEASURE && phaseTick >= settleTicks + warmTicks + measureTicks) {
            finishPhase(event);
        }
    }

    // ---- orientation probe ----------------------------------------------------------------

    /**
     * Finds, by trying them in the world, an orientation in which each machine actually turns.
     *
     * <p>Which face of a Create machine takes rotation is not something to reconstruct from
     * memory: being wrong produces a machine that sits at zero speed while the bench reports it
     * as running, which is the one failure that would invalidate the whole measurement. Every
     * candidate is therefore built for real, left for a few ticks, and kept only if
     * {@code KineticBlockEntity#getSpeed} comes back non-zero. The winner is logged.
     */
    private void beginProbe() {
        probeCandidates.clear();
        Block motor = blockOf(motorId);
        for (String id : machineIds) {
            Block machine = blockOf(id);
            if (machine == null || motor == null) {
                Meanwhile.LOGGER.warn("[bench] probe skipped, block missing | machine={} motor={}",
                        id, motorId);
                continue;
            }
            for (BlockState machineState : orientations(machine)) {
                for (boolean above : new boolean[] {true, false}) {
                    BlockState motorState = facing(motor, above ? Direction.DOWN : Direction.UP);
                    probeCandidates.add(new Object[] {id, machineState, above, motorState});
                }
            }
        }
        probeIndex = 0;
        probeTick = 0;
        stage = Stage.PROBE;
        placeProbeCandidate();
    }

    private List<BlockState> orientations(Block block) {
        List<BlockState> out = new ArrayList<>();
        Property<?> facing = block.getStateDefinition().getProperty("facing");
        if (facing == null) {
            out.add(block.defaultBlockState());
            return out;
        }
        for (Comparable<?> value : facing.getPossibleValues()) {
            out.add(withValue(block.defaultBlockState(), facing, value));
        }
        return out;
    }

    private static BlockState facing(Block block, Direction direction) {
        if (block == null) {
            return Blocks.AIR.defaultBlockState();
        }
        Property<?> facing = block.getStateDefinition().getProperty("facing");
        if (facing == null || !facing.getPossibleValues().contains(direction)) {
            return block.defaultBlockState();
        }
        return withValue(block.defaultBlockState(), facing, direction);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState withValue(BlockState state,
                                                                  Property<?> property,
                                                                  Comparable<?> value) {
        return state.setValue((Property<T>) property, (T) value);
    }

    private Block blockOf(String id) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        return block == Blocks.AIR ? null : block;
    }

    private void placeProbeCandidate() {
        clearProbeSite();
        if (probeIndex >= probeCandidates.size()) {
            return;
        }
        Object[] candidate = probeCandidates.get(probeIndex);
        BlockState machineState = (BlockState) candidate[1];
        boolean above = (Boolean) candidate[2];
        BlockState motorState = (BlockState) candidate[3];
        level.setBlock(PROBE_SITE, machineState, 2);
        level.setBlock(above ? PROBE_SITE.above() : PROBE_SITE.below(), motorState, 2);
    }

    private void clearProbeSite() {
        emptyAndClear(PROBE_SITE);
        emptyAndClear(PROBE_SITE.above());
        emptyAndClear(PROBE_SITE.below());
    }

    private void tickProbe() {
        probeTick++;
        if (probeTick < PROBE_SETTLE) {
            return;
        }
        probeTick = 0;
        if (probeIndex < probeCandidates.size()) {
            Object[] candidate = probeCandidates.get(probeIndex);
            String id = (String) candidate[0];
            float speed = speedOf(level.getBlockEntity(PROBE_SITE));
            if (!probes.containsKey(id) && speed != 0f && !Float.isNaN(speed)) {
                probes.put(id, new Probe((BlockState) candidate[1], (Boolean) candidate[2],
                        (BlockState) candidate[3]));
                BlockEntity sample = level.getBlockEntity(PROBE_SITE);
                if (sample != null && skipType == null) {
                    skipType = sample.getType();
                    installSkipTarget(id);
                }
                Meanwhile.LOGGER.info("[bench] probe hit | {} state={} motorAbove={} speed={}",
                        id, candidate[1], candidate[2], speed);
            }
            probeIndex++;
        }
        if (probeIndex >= probeCandidates.size()) {
            clearProbeSite();
            for (String id : machineIds) {
                if (!probes.containsKey(id)) {
                    Meanwhile.LOGGER.warn("[bench] probe found no turning orientation | {}", id);
                }
            }
            startPhase(0);
            return;
        }
        placeProbeCandidate();
    }

    /** Create's {@code KineticBlockEntity#getSpeed}, reached without compiling against Create. */
    private static float speedOf(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return Float.NaN;
        }
        try {
            return ((Number) blockEntity.getClass().getMethod("getSpeed").invoke(blockEntity))
                    .floatValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Float.NaN;
        }
    }

    /** How many items the machine is actually holding, across every slot it exposes. */
    private int countHeld(BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null) {
            return 0;
        }
        int held = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            held += handler.getStackInSlot(slot).getCount();
        }
        return held;
    }

    private int countTurning() {
        int turning = 0;
        for (BlockPos pos : live) {
            float speed = speedOf(level.getBlockEntity(pos));
            if (!Float.isNaN(speed) && speed != 0f) {
                turning++;
            }
        }
        return turning;
    }

    // ---- phases ---------------------------------------------------------------------------

    private void startPhase(int index) {
        phaseIndex = index;
        Phase phase = plan.get(index);
        phaseTick = 0;
        stage = Stage.SETTLE;
        sampleCount = 0;
        samples = new long[measureTicks];

        // Off for the whole run: this measurement is about what a machine's tick costs, not
        // about what the scheduler saves, so the hook must not be in the picture at all.
        DeferralScheduler.setEnabled(false);

        live.clear();
        support.clear();
        for (int i = 0; i < phase.count(); i++) {
            if (phase.blockId() == null) {
                break;
            }
            BlockPos pos = allocate();
            if ("minecraft:furnace".equals(phase.blockId())) {
                level.setBlock(pos, Blocks.FURNACE.defaultBlockState(), 2);
                if (level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace) {
                    furnace.setItem(0, new ItemStack(Items.RAW_IRON, 64));
                    furnace.setItem(1, new ItemStack(Items.COAL, 64));
                }
                live.add(pos);
                continue;
            }
            if (phase.blockId().equals(motorId)) {
                level.setBlock(pos, facing(blockOf(motorId), Direction.UP), 2);
                live.add(pos);
                continue;
            }
            Probe probe = probes.get(phase.blockId());
            if (probe == null) {
                Block machine = blockOf(phase.blockId());
                if (machine == null) {
                    break;
                }
                level.setBlock(pos, machine.defaultBlockState(), 2);
                live.add(pos);
                continue;
            }
            level.setBlock(pos, probe.machine(), 2);
            live.add(pos);
            if (phase.running()) {
                BlockPos motorPos = probe.motorAbove() ? pos.above() : pos.below();
                level.setBlock(motorPos, probe.motor(), 2);
                support.add(motorPos);
            }
            if (phase.fed()) {
                feed(pos);
            }
        }
        fedTotal = 0;
        if (phase.fed()) {
            for (BlockPos pos : live) {
                fedTotal += countHeld(pos);
            }
        }
        Meanwhile.LOGGER.info("[bench] phase {}/{} start | round={} label={} n={} running={} fed={} "
                        + "discard={} inputHeld={}",
                index + 1, plan.size(), phase.round(), phase.label(), phase.count(),
                phase.running(), phase.fed(), phase.discard(), fedTotal);
    }

    /**
     * Pushes input into the machine through the item-handler capability.
     *
     * <p>A Create machine's inventory is an {@code IItemHandler} rather than a vanilla
     * {@code Container}, so there is no {@code setItem} to call; and dropping items on top of it
     * would litter the world with the entities this bench has already been burned by once. The
     * capability is NeoForge's, not Create's, so this compiles without a dependency on Create.
     */
    private int feed(BlockPos pos) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(feedId));
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null || item == null) {
            return 0;
        }
        int inserted = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack remainder = handler.insertItem(slot, new ItemStack(item, feedCount), false);
            inserted += feedCount - remainder.getCount();
            if (inserted > 0) {
                break;
            }
        }
        return inserted;
    }

    /** Empties an item-handler inventory so removing the block cannot drop it on the floor. */
    private void drainHandler(BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null) {
            return;
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            handler.extractItem(slot, Integer.MAX_VALUE, false);
        }
    }

    private BlockPos allocate() {
        int index = cursor++;
        // Two world layers per allocation layer: a kinetic unit is a machine with a motor
        // directly above it, and the motor must not land on a position handed to somebody else.
        int layer = 2 * (index / PER_LAYER);
        int rem = index % PER_LAYER;
        return new BlockPos(X0 + (rem % SIDE), Y0 + layer, Z0 + (rem / SIDE));
    }

    /**
     * Ends the phase in the tick after the last recorded sample, so that the catch-up storm
     * the ON arm needs — potentially thousands of windows folded up at once — cannot land
     * inside the distribution it would otherwise dominate.
     */
    /**
     * Ends the phase in the tick after the last recorded sample, so no teardown work can land
     * inside the distribution it would otherwise dominate.
     */
    private void finishPhase(ServerTickEvent.Post event) {
        Phase phase = plan.get(phaseIndex);

        int turningAtEnd = countTurning();
        DeferralScheduler scheduler = DeferralScheduler.of(level);
        int deferredProbe = 0;
        for (BlockPos pos : live) {
            if (scheduler.isDeferred(pos)) {
                deferredProbe++;
            }
        }
        DeferralScheduler.setEnabled(false);
        long heldAtEnd = 0;
        if (plan.get(phaseIndex).fed()) {
            for (BlockPos pos : live) {
                heldAtEnd += countHeld(pos);
            }
        }
        long cooking = 0;
        int nbtChanged = 0;
        for (BlockPos pos : live) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof AbstractFurnaceBlockEntity furnace) {
                cooking += furnace.cookingProgress;
            }
        }
        // For a kinetic machine there is no counter as legible as cookingProgress, so the
        // evidence that something happened is the machine's own saved state changing across the
        // window. Taken here against the snapshot made when the window opened.
        for (int i = 0; i < live.size(); i++) {
            BlockEntity be = level.getBlockEntity(live.get(i));
            if (be == null) {
                continue;
            }
            int hash = be.saveWithoutMetadata(level.registryAccess()).hashCode();
            if (i < nbtAtMeasureStart.length && nbtAtMeasureStart[i] != hash) {
                nbtChanged++;
            }
        }

        for (BlockPos pos : support) {
            emptyAndClear(pos);
        }
        for (BlockPos pos : live) {
            emptyAndClear(pos);
        }
        int stillPresent = 0;
        for (BlockPos pos : live) {
            if (level.getBlockEntity(pos) != null) {
                stillPresent++;
            }
        }
        if (stillPresent > 0) {
            Meanwhile.LOGGER.warn("[bench] teardown left {} of {} block entities in place",
                    stillPresent, live.size());
        }
        // Every teardown, not just the wipe: a block that drops its contents litters the world
        // with item entities that tick for five minutes, and that litter is what made the first
        // attempt at this bench measure its own rubbish instead of its subjects.
        int itemsSwept = sweepItems();

        record(phase, turningAtMeasureStart, turningAtEnd, cooking, nbtChanged, stillPresent,
                itemsSwept, fedTotal, heldAtEnd, deferredProbe);

        if (phaseIndex + 1 < plan.size()) {
            startPhase(phaseIndex + 1);
        } else {
            stop(event);
        }
    }

    private void stop(ServerTickEvent.Post event) {
        for (int cx = X0 >> 4; cx <= (X0 + SIDE - 1) >> 4; cx++) {
            for (int cz = Z0 >> 4; cz <= (Z0 + SIDE - 1) >> 4; cz++) {
                level.setChunkForced(cx, cz, false);
            }
        }
        DeferralScheduler.setEnabled(true);
        stage = Stage.DONE;
        running = false;
        finished = true;
        crossCheck(event);
        Meanwhile.LOGGER.info("[bench] done | phases={} results={} raw={}", plan.size(), resultsPath, rawPath);
    }

    /**
     * One line comparing the bracket used here against the server's own tick timings, so the
     * choice of instrument is falsifiable rather than asserted. Reflective because the field
     * is not part of any API this mod otherwise depends on; a failure here costs the
     * cross-check and nothing else.
     */
    private void crossCheck(ServerTickEvent.Post event) {
        try {
            var field = event.getServer().getClass().getSuperclass() == null
                    ? null : findTickTimes(event.getServer().getClass());
            if (field == null) {
                Meanwhile.LOGGER.info("[bench] crosscheck | server tick-time array not found");
                return;
            }
            field.setAccessible(true);
            long[] times = (long[]) field.get(event.getServer());
            long sum = 0;
            int n = 0;
            for (long t : times) {
                if (t > 0) {
                    sum += t;
                    n++;
                }
            }
            Meanwhile.LOGGER.info("[bench] crosscheck | server {}={} entries mean={}us (last 100 ticks, "
                            + "idle teardown ticks) | instrument used for the table = Pre->Post nanoTime",
                    field.getName(), n, n == 0 ? 0 : (sum / n) / 1000);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Meanwhile.LOGGER.info("[bench] crosscheck unavailable | {}", e.toString());
        }
    }

    private static java.lang.reflect.Field findTickTimes(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType() == long[].class && f.getName().toLowerCase().contains("ticktime")) {
                    return f;
                }
            }
        }
        return null;
    }

    // ---- statistics ------------------------------------------------------------------------

    private void record(Phase phase, int turningStart, int turningEnd, long cooking,
                        int nbtChanged, int stillPresent, int itemsSwept, long fedIn,
                        long heldAtEnd, int deferredProbe) {
        long[] taken = Arrays.copyOf(samples, sampleCount);
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < taken.length; i++) {
            raw.append(phaseIndex).append(',').append(phase.round()).append(',')
                    .append(phase.label()).append(',').append(phase.count()).append(',')
                    .append(phase.running()).append(',').append(phase.discard()).append(',')
                    .append(i).append(',').append(taken[i]).append('\n');
        }
        write(rawPath, raw.toString(), true);

        long[] sorted = taken.clone();
        Arrays.sort(sorted);
        double mean = mean(taken, 0, taken.length);
        double sd = sd(taken, mean);
        double median = sorted.length == 0 ? 0 : sorted[sorted.length / 2];
        double p95 = sorted.length == 0 ? 0 : sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.95))];
        double first20 = mean(taken, 0, Math.min(20, taken.length));
        double last20 = mean(taken, Math.max(0, taken.length - 20), taken.length);

        String line = String.format("%d,%s,%d,%s,%s,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,"
                        + "%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                phase.round(), phase.label(), phase.count(), phase.running(), phase.discard(),
                taken.length, mean / 1000.0, median / 1000.0, p95 / 1000.0, sd / 1000.0,
                (sorted.length == 0 ? 0 : sorted[0]) / 1000.0,
                (sorted.length == 0 ? 0 : sorted[sorted.length - 1]) / 1000.0,
                first20 / 1000.0, last20 / 1000.0,
                turningStart, turningEnd, cooking, nbtChanged, stillPresent, itemsSwept,
                fedIn, heldAtEnd, deferredProbe);
        write(resultsPath, line, true);

        Meanwhile.LOGGER.info("[bench] r{} {} n={} running={} discard={} | mean={}us median={}us "
                        + "p95={}us sd={}us first20={}us last20={}us | turning={}->{} cooking={} "
                        + "nbtChanged={} stillPresent={} itemsSwept={} fedIn={} heldAtEnd={} deferred={}",
                phase.round(), phase.label(), phase.count(), phase.running(), phase.discard(),
                fmt(mean), fmt(median), fmt(p95), fmt(sd), fmt(first20), fmt(last20),
                turningStart, turningEnd, cooking, nbtChanged, stillPresent, itemsSwept,
                fedIn, heldAtEnd, deferredProbe);
    }

    private static String fmt(double nanos) {
        return String.format("%.1f", nanos / 1000.0);
    }

    private static double mean(long[] values, int from, int to) {
        if (to <= from) {
            return 0;
        }
        long sum = 0;
        for (int i = from; i < to; i++) {
            sum += values[i];
        }
        return (double) sum / (to - from);
    }

    private static double sd(long[] values, double mean) {
        if (values.length < 2) {
            return 0;
        }
        double acc = 0;
        for (long v : values) {
            double d = v - mean;
            acc += d * d;
        }
        return Math.sqrt(acc / (values.length - 1));
    }
}
