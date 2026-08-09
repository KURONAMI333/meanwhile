package com.kuronami.meanwhile.generic;

import com.kuronami.meanwhile.Meanwhile;
import com.kuronami.meanwhile.harness.CatchUpSubject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A Create millstone grinding a stack, driven by a creative motor underneath it.
 *
 * <p>The subject knows about Create, because somebody has to build the arena: which block is
 * a millstone, which way a motor faces, what a milling recipe takes. {@link GenericCatchUp}
 * knows none of it and is handed nothing but a position. That split is the whole point of the
 * measurement — a catch-up that had to be told what a millstone is would not be evidence of
 * anything, since the machines that matter are the ones nobody wrote a case for.
 *
 * <p>The arena is built once and then restored from a snapshot of its own NBT rather than by
 * replacing the block. Replacing it would spill the inventory into the compared region as
 * randomised entities and would drop the millstone out of its kinetic network, which takes
 * several real server ticks to re-form and cannot be waited for inside a comparison.
 */
public class MillstoneSubject implements CatchUpSubject {

    private static final BlockPos MOTOR = new BlockPos(4, 1, 4);
    private static final BlockPos MILLSTONE = new BlockPos(4, 2, 4);

    private static final ResourceLocation MILLSTONE_ID =
            ResourceLocation.parse("create:millstone");
    private static final ResourceLocation MOTOR_ID =
            ResourceLocation.parse("create:creative_motor");
    private static final ResourceLocation MILLING =
            ResourceLocation.parse("create:milling");
    /** Milling recipes tried before giving up on finding a reproducible one. */
    private static final int PROBE_LIMIT = 24;
    /** Long enough for a candidate recipe to complete several times at the millstone's rate. */
    private static final int PROBE_TICKS = 1200;
    /**
     * Independent runs a candidate has to reproduce across.
     *
     * <p>Two is not enough. Create rolls a bonus output per completed recipe, so a chancy
     * recipe agrees with itself whenever the rolls happen to land the same way, and one that
     * slipped through on two runs was then measured diverging over a longer window — a
     * flakily chosen arena is worse than no arena, because it makes the catch-up look wrong.
     */
    private static final int PROBE_RUNS = 3;

    private static final int FEED_COUNT = 64;
    /** Completed grinds the window must contain, so the completion branch is actually taken. */
    private static final int REQUIRED_OUTPUT = 2;

    private final GenericCatchUp.Mode mode;
    private final boolean neverCatchUp;
    /** Extra real ticks appended to both arms, to let a phase-shifted hidden counter show. */
    private final int tail;

    private CompoundTag pristine;
    private ItemStack feed = ItemStack.EMPTY;
    private String recipeId = "<none>";
    private GenericCatchUp.Result last;

    public MillstoneSubject() {
        this(GenericCatchUp.Mode.SAFE, false, 0);
    }

    public MillstoneSubject(GenericCatchUp.Mode mode) {
        this(mode, false, 0);
    }

    /**
     * @param neverCatchUp makes {@link #catchUp} decline unconditionally, so the harness ticks
     *                     both arms. Any mismatch then belongs to the arena rather than to the
     *                     catch-up, which is the only way a match in the real comparison means
     *                     anything.
     * @param tail         real ticks run after the window in both arms
     */
    public MillstoneSubject(GenericCatchUp.Mode mode, boolean neverCatchUp, int tail) {
        this.mode = mode;
        this.neverCatchUp = neverCatchUp;
        this.tail = tail;
    }

    @Override
    public String name() {
        StringBuilder name = new StringBuilder("millstone");
        if (neverCatchUp) {
            name.append("(no-catch-up)");
        } else if (!mode.equals(GenericCatchUp.Mode.SAFE)) {
            name.append('(').append(mode.label()).append(')');
        }
        if (tail > 0) {
            name.append("+tail").append(tail);
        }
        return name.toString();
    }

    // ---- arena ------------------------------------------------------------------------

    /**
     * Places the machine. Separate from {@link #setup}, because the kinetic network needs
     * several real server ticks to form and a comparison runs entirely inside one.
     */
    public boolean place(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Block millstone = block(MILLSTONE_ID);
        Block motor = block(MOTOR_ID);
        if (millstone == null || motor == null) {
            return false;
        }
        level.setBlock(helper.absolutePos(MOTOR), facing(motor, Direction.UP), 3);
        level.setBlock(helper.absolutePos(MILLSTONE), millstone.defaultBlockState(), 3);
        level.setBlock(helper.absolutePos(MILLSTONE.above()), Blocks.AIR.defaultBlockState(), 3);
        return true;
    }

    /**
     * Loads the input and records the state every trial starts from. Called once the network
     * has spun up, so the recorded state already has a speed in it.
     *
     * @return why the arena is not usable, or null
     */
    @Nullable
    public String arm(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockEntity blockEntity = millstone(helper);
        if (blockEntity == null) {
            return "no millstone block entity at " + MILLSTONE;
        }
        float speed = speedOf(blockEntity);
        if (speed == 0f || Float.isNaN(speed)) {
            return "the millstone is not turning (speed=" + speed + "), so both arms would"
                    + " sit still and agree perfectly";
        }

        CompoundTag empty = blockEntity.saveWithoutMetadata(level.registryAccess());
        List<ItemStack> candidates = new ArrayList<>();
        List<String> candidateIds = new ArrayList<>();
        millingRecipes(level, candidates, candidateIds);
        if (candidates.isEmpty()) {
            return "no create:milling recipe found, so nothing put in would ever be ground";
        }

        List<String> rejected = new ArrayList<>();
        int limit = Math.min(PROBE_LIMIT, candidates.size());
        for (int i = 0; i < limit; i++) {
            ItemStack candidate = candidates.get(i);
            String id = candidateIds.get(i);
            String verdict = probe(helper, empty, candidate, id);
            if (verdict == null) {
                Meanwhile.LOGGER.info("[generic] arena armed | speed={} recipe={} feed={}x{} "
                                + "rejected={} nbt={}",
                        speedOf(millstone(helper)), recipeId, feed.getItem(), FEED_COUNT,
                        rejected, pristine);
                return null;
            }
            rejected.add(id + "(" + verdict + ")");
        }
        return "no milling recipe both grinds twice in " + PROBE_TICKS + " ticks and reproduces"
                + " itself, so nothing here could be compared bit for bit: " + rejected;
    }

    /**
     * Tries one milling recipe and reports why it will not do, or null when it will.
     *
     * <p>A recipe qualifies by measurement, not by inspection. The same window is run twice
     * from the same state and the machine has to end up in exactly the same place. Create
     * rolls a recipe's outputs against a chance off a shared random, so a recipe that yields
     * one flour on one run and two on the next makes the whole arena useless for a bit-for-bit
     * comparison — and it does so quietly, looking exactly like a catch-up bug.
     */
    @Nullable
    private String probe(GameTestHelper helper, CompoundTag empty, ItemStack candidate,
                         String id) {
        ServerLevel level = helper.getLevel();
        restore(helper, empty);
        if (insert(helper, candidate) == 0) {
            return "nothing inserted";
        }
        BlockEntity blockEntity = millstone(helper);
        if (blockEntity == null) {
            return "block entity vanished";
        }
        String feedId = BuiltInRegistries.ITEM.getKey(candidate.getItem()).toString();
        CompoundTag start = blockEntity.saveWithoutMetadata(level.registryAccess());

        CompoundTag first = null;
        for (int run = 0; run < PROBE_RUNS; run++) {
            restore(helper, start);
            GenericCatchUp.tick(level, helper.absolutePos(MILLSTONE), PROBE_TICKS);
            CompoundTag outcome = blockEntity.saveWithoutMetadata(level.registryAccess());
            if (first == null) {
                first = outcome;
            } else if (!first.equals(outcome)) {
                return "not reproducible on run " + (run + 1);
            }
        }
        int ground = countItems(first, feedId, false);
        if (ground < REQUIRED_OUTPUT) {
            return "ground " + ground + " in " + PROBE_TICKS;
        }

        this.feed = candidate;
        this.recipeId = id;
        this.pristine = start;
        restore(helper, start);
        return null;
    }

    private void restore(GameTestHelper helper, CompoundTag tag) {
        BlockEntity blockEntity = millstone(helper);
        if (blockEntity == null) {
            return;
        }
        blockEntity.loadWithComponents(tag.copy(), helper.getLevel().registryAccess());
        reattach(blockEntity);
        blockEntity.setChanged();
    }

    private int insert(GameTestHelper helper, ItemStack stack) {
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                helper.absolutePos(MILLSTONE), null);
        if (handler == null) {
            return 0;
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack remainder =
                    handler.insertItem(slot, new ItemStack(stack.getItem(), FEED_COUNT), false);
            int inserted = FEED_COUNT - remainder.getCount();
            if (inserted > 0) {
                return inserted;
            }
        }
        return 0;
    }

    /** The milling recipe the arena runs on, for the report. */
    public String recipeId() {
        return recipeId;
    }

    /** Measurement only: what Create thinks the machine is doing right now. */
    public String describe(GameTestHelper helper) {
        BlockEntity blockEntity = millstone(helper);
        return "speed=" + speedOf(blockEntity)
                + " input=" + heldOf(helper, true)
                + " ground=" + heldOf(helper, false)
                + " nbt=" + (blockEntity == null ? "<none>"
                : blockEntity.saveWithoutMetadata(helper.getLevel().registryAccess()));
    }

    public ItemStack feed() {
        return feed;
    }

    private static void millingRecipes(ServerLevel level, List<ItemStack> items, List<String> ids) {
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType());
            if (type == null || !type.equals(MILLING)) {
                continue;
            }
            ItemStack candidate = firstItem(holder);
            if (candidate.isEmpty()) {
                continue;
            }
            items.add(candidate);
            ids.add(holder.id().toString());
        }
        // The recipe manager hands them out in whatever order it pleases, and every test builds
        // its own arena. Without a stable order two tests can end up measuring different
        // machines, which is how a suite starts disagreeing with itself for no visible reason.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> ids.get(a).compareTo(ids.get(b)));
        List<ItemStack> sortedItems = new ArrayList<>();
        List<String> sortedIds = new ArrayList<>();
        for (int index : order) {
            sortedItems.add(items.get(index));
            sortedIds.add(ids.get(index));
        }
        items.clear();
        items.addAll(sortedItems);
        ids.clear();
        ids.addAll(sortedIds);
        Meanwhile.LOGGER.info("[generic] milling recipes | count={} first={}",
                ids.size(), ids.subList(0, Math.min(10, ids.size())));
    }

    private static ItemStack firstItem(RecipeHolder<?> holder) {
        for (Ingredient ingredient : holder.value().getIngredients()) {
            ItemStack[] items = ingredient.getItems();
            if (items.length > 0 && !items[0].isEmpty()) {
                return items[0].copy();
            }
        }
        return ItemStack.EMPTY;
    }

    // ---- the harness contract -----------------------------------------------------------

    @Override
    public void setup(GameTestHelper helper) {
        // Deliberately nothing. Re-placing the block would give the millstone a fresh block
        // entity with no speed, and the network cannot be re-formed without real server ticks.
    }

    @Override
    @Nullable
    public String precondition(GameTestHelper helper) {
        if (pristine == null) {
            return "the arena was never armed";
        }
        BlockEntity blockEntity = millstone(helper);
        if (blockEntity == null) {
            return "no millstone block entity at " + MILLSTONE;
        }
        reset(helper);
        float speed = speedOf(millstone(helper));
        if (speed == 0f || Float.isNaN(speed)) {
            return "the millstone is not turning (speed=" + speed + ")";
        }
        if (heldOf(helper, true) == 0) {
            return "the millstone holds no input, so nothing would be ground";
        }
        return null;
    }

    @Override
    @Nullable
    public String postcondition(GameTestHelper helper) {
        int output = heldOf(helper, false);
        if (output < REQUIRED_OUTPUT) {
            return "the window produced " + output + " ground items, under the " + REQUIRED_OUTPUT
                    + " needed to be sure it crossed a completion boundary rather than just"
                    + " moving a counter";
        }
        return null;
    }

    /**
     * Puts the machine back to the state every trial starts from.
     *
     * <p>Reloading the NBT is not enough on its own. A Create kinetic block entity writes its
     * speed, source and network but does not read them back: on load it clears them and
     * re-derives them by attaching to its neighbours, which normally happens on the block
     * entity's first tick. Reloading its own output therefore produces a millstone that is
     * stationary and would sit still in both arms, agreeing perfectly and proving nothing —
     * the precondition catches it, and this is what stops it happening.
     *
     * <p>Calling {@code attachKinetics()} is that same re-derivation, run now rather than on
     * the next tick, so that both arms start from a machine that is already turning and
     * neither spends its first tick differently from the other.
     */
    @Override
    public void reset(GameTestHelper helper) {
        BlockEntity blockEntity = millstone(helper);
        if (blockEntity == null || pristine == null) {
            return;
        }
        blockEntity.loadWithComponents(pristine.copy(), helper.getLevel().registryAccess());
        reattach(blockEntity);
        blockEntity.setChanged();
    }

    /** Create's {@code KineticBlockEntity#attachKinetics}, reached without compiling against it. */
    private static void reattach(BlockEntity blockEntity) {
        try {
            blockEntity.getClass().getMethod("attachKinetics").invoke(blockEntity);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Meanwhile.LOGGER.warn("[generic] could not re-attach the millstone | {}", e.toString());
        }
    }

    @Override
    public void simulate(GameTestHelper helper, int ticks, RandomSource random) {
        GenericCatchUp.tick(helper.getLevel(), helper.absolutePos(MILLSTONE), ticks + tail);
    }

    @Override
    public boolean catchUp(GameTestHelper helper, int ticks, RandomSource random) {
        if (neverCatchUp) {
            last = null;
            return false;
        }
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(MILLSTONE);
        GenericCatchUp.Result result = GenericCatchUp.catchUp(level, pos, ticks, mode);
        last = result;
        if (result.declined()) {
            return false;
        }
        if (tail > 0) {
            GenericCatchUp.tick(level, pos, tail);
        }
        return true;
    }

    /** What the last catch-up did, and why it refused what it refused. */
    @Nullable
    public GenericCatchUp.Result last() {
        return last;
    }

    @Override
    public double[] observe(GameTestHelper helper) {
        return new double[]{heldOf(helper, false), heldOf(helper, true)};
    }

    @Override
    public String[] observationLabels() {
        return new String[]{"ground", "input"};
    }

    /**
     * Millstone and motor together, plus the space above.
     *
     * <p>Wider than the machine on purpose. Writing NBT back into a kinetic block entity is
     * the most likely way this perturbs the network it belongs to, and with the motor outside
     * the box that perturbation would not be looked at. The extra space also catches anything
     * the reload spills onto the floor as an entity.
     */
    @Override
    public BoundingBox exactRegion(GameTestHelper helper) {
        return BoundingBox.fromCorners(helper.absolutePos(MOTOR),
                helper.absolutePos(MILLSTONE.above()));
    }

    // ---- reading the machine without knowing what it is ----------------------------------

    /**
     * Counts items the millstone is holding, from its NBT rather than through Create's API.
     *
     * <p>Anything matching the feed is input; anything else came out of the recipe. Reading
     * the serialised form avoids depending on which inventories the item-handler capability
     * chooses to expose, which is a Create implementation detail that has moved before.
     */
    private int heldOf(GameTestHelper helper, boolean wantFeed) {
        BlockEntity blockEntity = millstone(helper);
        if (blockEntity == null) {
            return 0;
        }
        CompoundTag tag = blockEntity.saveWithoutMetadata(helper.getLevel().registryAccess());
        String feedId = BuiltInRegistries.ITEM.getKey(feed.getItem()).toString();
        return countItems(tag, feedId, wantFeed);
    }

    private static int countItems(CompoundTag tag, String feedId, boolean wantFeed) {
        int total = 0;
        for (String key : tag.getAllKeys()) {
            Tag child = tag.get(key);
            if (child instanceof CompoundTag compound) {
                total += countItems(compound, feedId, wantFeed);
                continue;
            }
            if (!(child instanceof ListTag list) || list.getElementType() != Tag.TAG_COMPOUND) {
                continue;
            }
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                if (!entry.contains("id")) {
                    total += countItems(entry, feedId, wantFeed);
                    continue;
                }
                boolean isFeed = feedId.equals(entry.getString("id"));
                if (isFeed != wantFeed) {
                    continue;
                }
                total += entry.contains("count") ? entry.getInt("count")
                        : Math.max(1, entry.getInt("Count"));
            }
        }
        return total;
    }

    @Nullable
    private static BlockEntity millstone(GameTestHelper helper) {
        return helper.getLevel().getBlockEntity(helper.absolutePos(MILLSTONE));
    }

    /** Create's {@code KineticBlockEntity#getSpeed}, reached without compiling against Create. */
    static float speedOf(@Nullable BlockEntity blockEntity) {
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

    @Nullable
    private static Block block(ResourceLocation id) {
        Block block = BuiltInRegistries.BLOCK.get(id);
        return block == Blocks.AIR ? null : block;
    }

    private static BlockState facing(Block block, Direction direction) {
        Property<?> property = block.getStateDefinition().getProperty("facing");
        if (property == null || !property.getPossibleValues().contains(direction)) {
            return block.defaultBlockState();
        }
        return withValue(block.defaultBlockState(), property, direction);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState withValue(BlockState state,
                                                                 Property<?> property,
                                                                 Comparable<?> value) {
        return state.setValue((Property<T>) property, (T) value);
    }

    static BlockPos millstonePos() {
        return MILLSTONE;
    }
}
