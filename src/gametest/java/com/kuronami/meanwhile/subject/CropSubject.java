package com.kuronami.meanwhile.subject;

import com.kuronami.meanwhile.catchup.CropCatchUp;
import com.kuronami.meanwhile.harness.CatchUpSubject;
import com.kuronami.meanwhile.harness.Disturbance;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Wheat on a lit farm plot. Stochastic: vanilla advances the crop by one age step per
 * random tick with probability {@code 1/((int)(25/growthSpeed)+1)}, so only the
 * distribution of the resulting age can be compared.
 *
 * <p>{@code growthPenalty} exists to break the implementation on purpose. Anything other
 * than zero shifts the probability denominator, which is the off-by-one a careless
 * reimplementation of vanilla's formula would produce. Running the harness against a
 * penalised copy is how the comparison's power to fail gets measured instead of assumed.
 */
public class CropSubject implements CatchUpSubject {

    protected static final BlockPos CROP = new BlockPos(4, 2, 4);
    private static final BlockPos LAMP = new BlockPos(2, 2, 4);
    private static final int MIN_BRIGHTNESS = 9;

    private final int growthPenalty;
    /** Set by a disturbance, so an undisturbed reset stays as cheap as it was. */
    private boolean sceneDirty;

    public CropSubject() {
        this(0);
    }

    public CropSubject(int growthPenalty) {
        this.growthPenalty = growthPenalty;
    }

    @Override
    public String name() {
        return growthPenalty == 0 ? "wheat" : "wheat(penalty=" + growthPenalty + ")";
    }

    @Override
    public void setup(GameTestHelper helper) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                helper.setBlock(CROP.below().offset(dx, 0, dz), Blocks.FARMLAND);
            }
        }
        helper.setBlock(CROP, Blocks.WHEAT);
        helper.setBlock(LAMP, Blocks.GLOWSTONE);
    }

    @Override
    @Nullable
    public String precondition(GameTestHelper helper) {
        int brightness = helper.getLevel().getRawBrightness(helper.absolutePos(CROP), 0);
        if (brightness < MIN_BRIGHTNESS) {
            return "farm plot is not lit (brightness " + brightness + "); both arms would report"
                    + " zero growth and the comparison would prove nothing";
        }
        return null;
    }

    /**
     * Rebuilds the plot, not just the crop.
     *
     * <p>A disturbance that turns the farmland to dirt would otherwise persist into every
     * later trial, quietly halving the growth rate of a comparison that thinks it is
     * measuring an undisturbed field.
     */
    @Override
    public void reset(GameTestHelper helper) {
        if (sceneDirty) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    helper.setBlock(CROP.below().offset(dx, 0, dz), Blocks.FARMLAND);
                }
            }
            sceneDirty = false;
        }
        helper.getLevel().setBlock(helper.absolutePos(CROP), wheat().getStateForAge(0), 2);
    }

    @Override
    public void simulate(GameTestHelper helper, int ticks, RandomSource random) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(CROP);
        for (int tick = 0; tick < ticks; tick++) {
            level.getBlockState(pos).randomTick(level, pos, random);
        }
    }

    @Override
    public boolean catchUp(GameTestHelper helper, int ticks, RandomSource random) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(CROP);
        CropBlock crop = wheat();
        BlockState state = level.getBlockState(pos);
        int age = CropCatchUp.catchUpAge(level, pos, state, crop, ticks, random, growthPenalty);
        level.setBlock(pos, crop.getStateForAge(age), 2);
        return true;
    }

    @Override
    public double[] observe(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(CROP);
        return new double[]{wheat().getAge(helper.getLevel().getBlockState(pos))};
    }

    @Override
    public String[] observationLabels() {
        return new String[]{"age"};
    }

    @Override
    public List<Disturbance> disturbances() {
        return List.of(new FarmlandLost());
    }

    /**
     * The farmland around the crop turns back to dirt, which is what happens when a player
     * walks over a field or a piston moves through it.
     *
     * <p>The obvious alternative, nightfall, is not a hazard at all: {@code CropBlock} gates
     * on {@code getRawBrightness(pos, 0)}, and that zero is how much to subtract from sky
     * light for the time of day, so the clock never enters the gate. Shading the plot with
     * blocks does close it, but cannot be tested from inside a trial loop, because the
     * server's light engine refuses to run on demand: {@code runLightUpdates} throws
     * "Ran automatically on a different thread!". Losing farmland needs no light engine — it
     * changes {@code getGrowthSpeed} the instant the blocks change, moving the growth
     * probability from 1/7 to 1/13.
     */
    private final class FarmlandLost implements Disturbance {

        @Override
        public String name() {
            return "farmland lost";
        }

        @Override
        public void apply(GameTestHelper helper) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    helper.setBlock(CROP.below().offset(dx, 0, dz), Blocks.DIRT);
                }
            }
            sceneDirty = true;
        }
    }

    protected static CropBlock wheat() {
        return (CropBlock) Blocks.WHEAT;
    }
}
