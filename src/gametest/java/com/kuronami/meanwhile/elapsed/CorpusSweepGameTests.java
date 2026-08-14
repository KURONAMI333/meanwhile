package com.kuronami.meanwhile.elapsed;

import com.kuronami.meanwhile.Meanwhile;
import com.kuronami.meanwhile.generic.FloorSurvey;
import com.kuronami.meanwhile.generic.GenericCatchUp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * What {@link GenericCatchUp} makes of every block entity the loaded mods can produce.
 *
 * <p>Five machines is a demonstration. The claim this mod is built on — that a catch-up which
 * knows no types can be pointed at modded machinery — is about a population, so this walks the
 * whole block registry, places each block that carries a block entity, and writes down what the
 * catch-up did with it and why.
 *
 * <h3>Nothing is powered, and that is the point</h3>
 * <p>The blocks are placed bare: no input, no fuel, no rotation, no network. That is not a
 * shortcut, it is the case that dominates. A chunk nobody has visited for hours holds machines
 * that ran out of something long ago and have been standing still ever since, and a catch-up that
 * only works on a fed machine would be answering a question the world rarely asks.
 *
 * <h3>What is being measured</h3>
 * <p>Acceptance and refusal, not correctness. Whether the state a jump produced is the state
 * ticking would have produced is settled elsewhere, on machines that are actually running and
 * can be compared; here there is nothing to compare a stopped machine against. The output is a
 * count of what was accepted, a histogram of why the rest was refused, and the cost of the ones
 * that were accepted.
 *
 * <p>Every type is also run a second time with {@code IGNORE_STATIC_CEILING}, which is how the
 * ceiling rule's reach is measured without instrumenting the code being measured: a type that is
 * cheaper under {@code SAFE} than without it is a type whose rising counter found a neighbour to
 * count towards. Those are listed by name, with the tag that was available to be chosen, because
 * whether that tag is really a limit is a judgement about each machine rather than something this
 * can decide.
 */
public final class CorpusSweepGameTests {

    /** The window each type is offered. Reported per 1000, so this is also the denominator. */
    private static final int GAP = 1000;
    /** Types looked at per server tick, so one long tick cannot stall the run. */
    private static final int PER_TICK = 12;
    /** Where each candidate is stood up, one at a time. */
    private static final BlockPos STAND = new BlockPos(4, 1, 4);
    /** Real server ticks a powered candidate is given before it is measured. */
    private static final int SETTLE_POWERED = 10;

    private static final net.minecraft.resources.ResourceLocation MOTOR =
            net.minecraft.resources.ResourceLocation.parse("create:creative_motor");
    private static final net.minecraft.resources.ResourceLocation ENERGY =
            net.minecraft.resources.ResourceLocation.parse("mekanism:creative_energy_cube");

    private CorpusSweepGameTests() {
    }

    /** One type's result. */
    private record Verdict(String block, String type, boolean hasBlockEntity, boolean hasTicker,
                           String ticker, int realTicks, int jumps, int jumpedTicks,
                           boolean declined, String declineReason, Map<String, Integer> refusals,
                           int realTicksWithoutCeiling, boolean overshot,
                           boolean onlyJumpedOnAGuess, boolean guessOvershot, boolean ran,
                           List<String> ceilingCandidates, @Nullable String error) {

        boolean accepted() {
            return hasTicker && !declined && jumps > 0;
        }

        /** Whether the whole window went in one step because nothing moved at all. */
        boolean stoodStill() {
            return accepted() && refusals.isEmpty() && realTicks <= 2 && jumpedTicks >= GAP - 2;
        }

        boolean neededCeiling() {
            return accepted() && realTicksWithoutCeiling > realTicks;
        }

        double realTicksPerThousand() {
            return realTicks * 1000.0 / GAP;
        }
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty9x5x9", templateNamespace = Meanwhile.MODID,
            batch = "corpus", timeoutTicks = 24000)
    public static void everyTickingBlockEntityIsOfferedAWindow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(STAND);
        List<Block> candidates = candidates();
        List<Verdict> verdicts = new ArrayList<>();
        int[] cursor = {0};
        int[] settle = {-1};
        boolean powered = poweredRequested();

        Meanwhile.LOGGER.info("[corpus] start | blocksWithBlockEntities={} gap={} powered={}"
                        + " mods={}",
                candidates.size(), GAP, powered, loadedNamespaces(candidates));
        // Fences the floor survey to this walk. The recorder is global and every other gate feeds
        // it too, including arenas built to move a counter pathologically, and a floor sourced
        // from one of those is not an observation about the shipped ecosystem.
        FloorSurvey.mark("corpus");

        // The same schedule either way. A Create machine driven by a motor has no speed until
        // its network has formed, which needs real server ticks; but giving those ticks only to
        // the powered run would also mean only the powered run gets its machines past whatever
        // they do on their first few ticks, and the two populations would not be comparable.
        int ticksNeeded = candidates.size() * (SETTLE_POWERED + 2) + 8;

        helper.startSequence()
                .thenExecuteFor(ticksNeeded, () -> {
                    if (cursor[0] >= candidates.size()) {
                        return;
                    }
                    if (settle[0] < 0) {
                        stand(level, pos, candidates.get(cursor[0]), powered);
                        settle[0] = 0;
                        return;
                    }
                    if (++settle[0] <= SETTLE_POWERED) {
                        return;
                    }
                    verdicts.add(examinePlaced(level, pos, candidates.get(cursor[0])));
                    clear(level, pos, powered);
                    cursor[0]++;
                    settle[0] = -1;
                })
                .thenExecute(() -> {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    report(verdicts, cursor[0], candidates.size());
                    FloorSurvey.report("corpus");
                    if (cursor[0] < candidates.size()) {
                        helper.fail("only " + cursor[0] + " of " + candidates.size()
                                + " blocks were reached before the sequence ran out");
                        return;
                    }
                    helper.succeed();
                })
                .thenSucceed();
    }

    /**
     * Whether to stand a power source next to every candidate.
     *
     * <p>Without it the sweep measures machines with nothing to run on, which is the state an
     * unvisited chunk's machinery is usually in and is worth measuring on its own. With it the
     * same population is measured while it is doing something, which is the other half.
     */
    private static boolean poweredRequested() {
        java.nio.file.Path cwd = java.nio.file.Path.of("").toAbsolutePath();
        for (java.nio.file.Path candidate : List.of(
                cwd.resolve("meanwhile-corpus-powered.properties"),
                cwd.resolve("run").resolve("meanwhile-corpus-powered.properties"),
                cwd.getParent() == null ? cwd.resolve("meanwhile-corpus-powered.properties")
                        : cwd.getParent().resolve("meanwhile-corpus-powered.properties"))) {
            if (java.nio.file.Files.isRegularFile(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** Every registered block that says it carries a block entity. */
    private static List<Block> candidates() {
        List<Block> found = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            try {
                if (block instanceof EntityBlock && block.defaultBlockState().hasBlockEntity()) {
                    found.add(block);
                }
            } catch (RuntimeException | LinkageError e) {
                // A block that cannot even be asked belongs in the report, but there is no
                // identity to file it under yet; counted as unreachable below.
            }
        }
        found.sort(Comparator.comparing(block -> String.valueOf(BuiltInRegistries.BLOCK.getKey(block))));
        return found;
    }

    /**
     * Stands one block up, offers it the window twice, and takes it down again.
     *
     * <p>Placed fresh for each of the two runs rather than restored from NBT between them. A
     * rebuild from a tag is not always the machine it was taken from — that has been measured on
     * Create's kinetics — and a bare placement is both the honest starting state and the cheap
     * one.
     */
    /** Stands a candidate up, optionally with something next to it that supplies power. */
    private static void stand(ServerLevel level, BlockPos pos, Block block, boolean powered) {
        clear(level, pos, powered);
        if (powered) {
            // Every side, because which side a machine takes its drive from is exactly the kind
            // of thing this is not allowed to know. Rotation is pointed back at the candidate.
            Block motor = BuiltInRegistries.BLOCK.get(MOTOR);
            Block cube = BuiltInRegistries.BLOCK.get(ENERGY);
            for (net.minecraft.core.Direction from : POWER_SIDES) {
                BlockPos side = pos.relative(from);
                if (motor != Blocks.AIR) {
                    level.setBlock(side, faceTowards(motor.defaultBlockState(), from.getOpposite()),
                            3);
                }
            }
            if (cube != Blocks.AIR) {
                level.setBlock(pos.above(), cube.defaultBlockState(), 3);
                level.setBlock(pos.east(), cube.defaultBlockState(), 3);
            }
        }
        level.setBlock(pos, block.defaultBlockState(), 3);
        if (powered) {
            feed(level, pos);
        }
    }

    /**
     * Puts something in every slot that will take it.
     *
     * <p>Drive alone starts very little: a mill with nothing in it turns and grinds nothing, a
     * furnace with no fuel and no ore sits still. Nothing here knows what any machine wants, so
     * it offers a short list of common inputs to every slot the item-handler capability exposes
     * and keeps whatever is accepted. A machine that wants something else stays idle, which is
     * the honest outcome rather than a reason to start writing per-machine recipes.
     */
    private static void feed(ServerLevel level, BlockPos pos) {
        net.neoforged.neoforge.items.IItemHandler handler = level.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null) {
            return;
        }
        List<net.minecraft.world.item.Item> offers = List.of(
                net.minecraft.world.item.Items.RAW_IRON,
                net.minecraft.world.item.Items.COAL,
                net.minecraft.world.item.Items.ANDESITE,
                net.minecraft.world.item.Items.COBBLESTONE,
                net.minecraft.world.item.Items.WHEAT,
                net.minecraft.world.item.Items.REDSTONE);
        for (int slot = 0; slot < handler.getSlots() && slot < 32; slot++) {
            for (net.minecraft.world.item.Item offer : offers) {
                net.minecraft.world.item.ItemStack stack =
                        new net.minecraft.world.item.ItemStack(offer, 64);
                if (handler.insertItem(slot, stack, true).getCount() < 64) {
                    handler.insertItem(slot, stack, false);
                    break;
                }
            }
        }
    }

    /** Sides a drive is stood on. Not up or east; those hold the energy cubes. */
    private static final List<net.minecraft.core.Direction> POWER_SIDES = List.of(
            net.minecraft.core.Direction.DOWN, net.minecraft.core.Direction.NORTH,
            net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.WEST);

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState faceTowards(BlockState state,
                                                                    net.minecraft.core.Direction at) {
        for (net.minecraft.world.level.block.state.properties.Property<?> property
                : state.getProperties()) {
            if (!"facing".equals(property.getName())
                    && !"axis".equals(property.getName())) {
                continue;
            }
            for (Comparable<?> value : property.getPossibleValues()) {
                boolean matches = value == at
                        || String.valueOf(value).equals(at.getAxis().getSerializedName());
                if (matches) {
                    return state.setValue(
                            (net.minecraft.world.level.block.state.properties.Property<T>) property,
                            (T) value);
                }
            }
        }
        return state;
    }

    private static void clear(ServerLevel level, BlockPos pos, boolean powered) {
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        if (powered) {
            for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
                level.setBlock(pos.relative(side), Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private static Verdict examine(ServerLevel level, BlockPos pos, Block block) {
        stand(level, pos, block, false);
        return examinePlaced(level, pos, block);
    }

    /** Measures whatever is currently standing at {@code pos}. */
    private static Verdict examinePlaced(ServerLevel level, BlockPos pos, Block block) {
        String id = String.valueOf(BuiltInRegistries.BLOCK.getKey(block));
        try {
            BlockState state = level.getBlockState(pos);
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) {
                return new Verdict(id, "<none>", false, false, "<none>", 0, 0, 0, false, null,
                        Map.of(), 0, false, false, false, false, List.of(), null);
            }
            String type = blockEntity.getClass().getName();
            String ticker = tickerName(level, pos, state, blockEntity);
            if ("<null>".equals(ticker) || "<none>".equals(ticker)) {
                return new Verdict(id, type, true, false, ticker, 0, 0, 0, false, null,
                        Map.of(), 0, false, false, false, false, List.of(), null);
            }

            CompoundTag beforeWindow = blockEntity.saveWithoutMetadata(level.registryAccess());
            BlockState stateBefore = level.getBlockState(pos);
            GenericCatchUp.Result safe =
                    GenericCatchUp.catchUp(level, pos, GAP, GenericCatchUp.Mode.SAFE);
            // "Running" is decided by whether the window moved anything, not by whether the
            // catch-up liked it: a machine with no power and no input keeps exactly the state it
            // was placed in, and that is the case this sweep used to be entirely made of.
            BlockEntity afterWindow = level.getBlockEntity(pos);
            boolean ran = afterWindow == null
                    || !beforeWindow.equals(afterWindow.saveWithoutMetadata(level.registryAccess()))
                    || !stateBefore.equals(level.getBlockState(pos));

            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(pos, state, 3);
            int withoutCeiling = level.getBlockEntity(pos) == null ? safe.realTicks()
                    : GenericCatchUp.catchUp(level, pos, GAP,
                            GenericCatchUp.Mode.NO_CEILING).realTicks();

            // The old behaviour, for comparison: a rising counter aimed at whatever integral tag
            // happens to be standing next to it. A type that jumps here and not under SAFE is a
            // type that used to travel on a guess.
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(pos, state, 3);
            GenericCatchUp.Result guessed = level.getBlockEntity(pos) == null ? safe
                    : GenericCatchUp.catchUp(level, pos, GAP, GenericCatchUp.Mode.STATIC_CEILING);

            // Only for the types the rule actually reached: one more placement, one real tick,
            // and a diff, to name what was standing beside the counter that rose.
            List<String> candidates = List.of();
            if (withoutCeiling > safe.realTicks()) {
                candidates = candidatesFor(level, pos, state);
            }
            return new Verdict(id, type, true, true, ticker, safe.realTicks(), safe.jumps(),
                    safe.jumpedTicks(), safe.declined(), safe.declineReason(),
                    new LinkedHashMap<>(safe.refusals()), withoutCeiling, safe.overshot(),
                    guessed.jumps() > 0 && safe.jumps() == 0, guessed.overshot(), ran,
                    candidates, null);
        } catch (RuntimeException | LinkageError | StackOverflowError e) {
            return new Verdict(id, "<threw>", false, false, "<none>", 0, 0, 0, false, null,
                    Map.of(), 0, false, false, false, false, List.of(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            try {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            } catch (RuntimeException | LinkageError e) {
                Meanwhile.LOGGER.warn("[corpus] could not clear {} | {}", id, e.toString());
            }
        }
    }

    /** One fresh placement, one real tick, and what the diff offers as a ceiling. */
    private static List<String> candidatesFor(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(pos, state, 3);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return List.of();
        }
        CompoundTag before = blockEntity.saveWithoutMetadata(level.registryAccess());
        if (!GenericCatchUp.tickOnce(level, pos)) {
            return List.of();
        }
        BlockEntity now = level.getBlockEntity(pos);
        if (now == null) {
            return List.of();
        }
        return ceilingCandidates(before, now.saveWithoutMetadata(level.registryAccess()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String tickerName(ServerLevel level, BlockPos pos, BlockState state,
                                     BlockEntity blockEntity) {
        if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
            return "<none>";
        }
        BlockEntityTicker ticker =
                entityBlock.getTicker(level, state, (BlockEntityType) blockEntity.getType());
        return ticker == null ? "<null>" : ticker.getClass().getName();
    }

    // ---- what came of it ------------------------------------------------------------------

    private static void report(List<Verdict> verdicts, int reached, int total) {
        int withBlockEntity = 0;
        int withTicker = 0;
        int accepted = 0;
        int stoodStill = 0;
        int threw = 0;
        int neededCeiling = 0;
        int overshot = 0;
        int onlyOnAGuess = 0;
        int guessOvershot = 0;
        int ranCount = 0;
        int ranAccepted = 0;
        Map<String, Integer> ranReasons = new TreeMap<>();
        Map<String, Integer> ranCostBands = new TreeMap<>();
        Map<String, Integer> reasons = new TreeMap<>();
        Map<String, Integer> costBands = new TreeMap<>();

        for (Verdict verdict : verdicts) {
            if (verdict.error() != null) {
                threw++;
                Meanwhile.LOGGER.info("[corpus] threw | {} {}", verdict.block(), verdict.error());
                continue;
            }
            if (!verdict.hasBlockEntity()) {
                continue;
            }
            withBlockEntity++;
            if (verdict.onlyJumpedOnAGuess()) {
                onlyOnAGuess++;
                Meanwhile.LOGGER.info("[corpus] would have jumped on a guess | {}"
                                + " guessOvershot={} candidates={}",
                        verdict.block(), verdict.guessOvershot(), verdict.ceilingCandidates());
            }
            if (verdict.guessOvershot()) {
                guessOvershot++;
            }
            if (!verdict.hasTicker()) {
                reasons.merge("no-ticker", 1, Integer::sum);
                continue;
            }
            withTicker++;
            if (verdict.ran()) {
                ranCount++;
                if (verdict.accepted()) {
                    ranAccepted++;
                    ranCostBands.merge(band(verdict.realTicksPerThousand()), 1, Integer::sum);
                } else if (verdict.declined()) {
                    ranReasons.merge("declined:" + category(verdict.declineReason()), 1,
                            Integer::sum);
                } else if (verdict.refusals().isEmpty()) {
                    ranReasons.merge("no-jump:no-reason-recorded", 1, Integer::sum);
                } else {
                    for (Map.Entry<String, Integer> refusal : verdict.refusals().entrySet()) {
                        ranReasons.merge("refused:" + refusal.getKey(), 1, Integer::sum);
                    }
                }
            }
            if (verdict.accepted()) {
                accepted++;
                costBands.merge(band(verdict.realTicksPerThousand()), 1, Integer::sum);
                if (verdict.stoodStill()) {
                    stoodStill++;
                }
                if (verdict.overshot()) {
                    overshot++;
                    Meanwhile.LOGGER.info("[corpus] overshoot | {} realTicks={} candidates={}",
                            verdict.block(), verdict.realTicks(), verdict.ceilingCandidates());
                }
                if (verdict.neededCeiling()) {
                    neededCeiling++;
                    Meanwhile.LOGGER.info("[corpus] ceiling used | {} realTicks={} without={}"
                                    + " candidates={}",
                            verdict.block(), verdict.realTicks(),
                            verdict.realTicksWithoutCeiling(), verdict.ceilingCandidates());
                }
                continue;
            }
            if (verdict.declined()) {
                reasons.merge("declined:" + category(verdict.declineReason()), 1, Integer::sum);
            } else {
                // Ran the whole window without ever jumping: the reasons say why.
                if (verdict.refusals().isEmpty()) {
                    reasons.merge("no-jump:no-reason-recorded", 1, Integer::sum);
                }
                for (Map.Entry<String, Integer> refusal : verdict.refusals().entrySet()) {
                    reasons.merge("refused:" + refusal.getKey(), 1, Integer::sum);
                }
            }
        }

        for (Verdict verdict : verdicts) {
            if (verdict.hasTicker() && verdict.error() == null) {
                Meanwhile.LOGGER.info("[corpus] type | {} ticker={} accepted={} realTicks={}"
                                + " per1000={} jumps={} jumpedTicks={} declined={} reason={}"
                                + " refusals={} withoutCeiling={} overshot={} be={}",
                        verdict.block(), shortName(verdict.ticker()), verdict.accepted(),
                        verdict.realTicks(), String.format("%.1f", verdict.realTicksPerThousand()),
                        verdict.jumps(), verdict.jumpedTicks(), verdict.declined(),
                        verdict.declineReason(), verdict.refusals(),
                        verdict.realTicksWithoutCeiling(), verdict.overshot(),
                        shortName(verdict.type()));
            }
        }

        Meanwhile.LOGGER.info("[corpus] SUMMARY | reached={}/{} withBlockEntity={} withTicker={}"
                        + " accepted={} acceptanceRate={} stoodStill={} neededCeiling={}"
                        + " overshot={} onlyJumpedOnAGuess={} guessOvershot={} threw={}",
                reached, total, withBlockEntity, withTicker, accepted,
                withTicker == 0 ? "n/a" : String.format("%.1f%%", accepted * 100.0 / withTicker),
                stoodStill, neededCeiling, overshot, onlyOnAGuess, guessOvershot, threw);
        Meanwhile.LOGGER.info("[corpus] REASONS | {}", reasons);
        Meanwhile.LOGGER.info("[corpus] COST realTicks per 1000 gap | {}", costBands);
        Meanwhile.LOGGER.info("[corpus] RUNNING | powered={} ran={} ofWithTicker={} accepted={}"
                        + " acceptanceRate={}",
                poweredRequested(), ranCount, withTicker, ranAccepted,
                ranCount == 0 ? "n/a" : String.format("%.1f%%", ranAccepted * 100.0 / ranCount));
        Meanwhile.LOGGER.info("[corpus] RUNNING REASONS | {}", ranReasons);
        Meanwhile.LOGGER.info("[corpus] RUNNING COST realTicks per 1000 gap | {}", ranCostBands);
    }

    private static String band(double perThousand) {
        if (perThousand <= 2.0) {
            return "a: <=2";
        }
        if (perThousand <= 10.0) {
            return "b: 3-10";
        }
        if (perThousand <= 50.0) {
            return "c: 11-50";
        }
        if (perThousand <= 200.0) {
            return "d: 51-200";
        }
        return "e: >200";
    }

    private static String category(@Nullable String reason) {
        if (reason == null) {
            return "none";
        }
        int space = reason.indexOf(" at ");
        return space < 0 ? reason : reason.substring(0, space);
    }

    private static String shortName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static String loadedNamespaces(List<Block> blocks) {
        Map<String, Integer> perNamespace = new TreeMap<>();
        for (Block block : blocks) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            perNamespace.merge(id == null ? "?" : id.getNamespace(), 1, Integer::sum);
        }
        return perNamespace.toString();
    }

    // ---- the ceiling candidates, worked out independently -----------------------------------

    /**
     * What a rising counter in this tag has beside it that the ceiling rule could choose.
     *
     * <p>Worked out here rather than read out of {@link GenericCatchUp}, which is the thing being
     * measured and is not instrumented for this. It mirrors the rule — an integral tag in the same
     * compound, unmoved by the tick, at or above the counter — so what it lists is what was
     * available to be chosen, and the nearest of them is what would have been.
     */
    static List<String> ceilingCandidates(CompoundTag before, CompoundTag after) {
        List<String> out = new ArrayList<>();
        for (String key : before.getAllKeys()) {
            Tag was = before.get(key);
            Tag now = after.get(key);
            if (was == null || now == null || !isIntegral(was) || !isIntegral(now)) {
                continue;
            }
            long from = ((NumericTag) was).getAsLong();
            long to = ((NumericTag) now).getAsLong();
            if (to <= from) {
                continue;
            }
            List<String> siblings = new ArrayList<>();
            for (String other : after.getAllKeys()) {
                if (other.equals(key)) {
                    continue;
                }
                Tag candidate = after.get(other);
                Tag previously = before.get(other);
                if (candidate == null || previously == null || !isIntegral(candidate)
                        || !candidate.equals(previously)) {
                    continue;
                }
                long value = ((NumericTag) candidate).getAsLong();
                if (value >= to) {
                    siblings.add(other + "=" + value);
                }
            }
            out.add(key + " rose " + from + "->" + to + " candidates=" + siblings);
        }
        return out;
    }

    private static boolean isIntegral(Tag tag) {
        byte id = tag.getId();
        return id == Tag.TAG_BYTE || id == Tag.TAG_SHORT || id == Tag.TAG_INT || id == Tag.TAG_LONG;
    }
}
