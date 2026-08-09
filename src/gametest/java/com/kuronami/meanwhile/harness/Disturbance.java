package com.kuronami.meanwhile.harness;

import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Something outside the subject reaching in and changing it partway through a window: a
 * hopper feeding a furnace, farmland drying out under a crop, night falling.
 *
 * <p>Every one of these breaks a precondition the catch-up relies on, which is why a
 * subject declares its own. The scheduler's whole safety claim is that deferring work is
 * not the same as discarding it, and that claim is only true while nothing disturbs the
 * subject unnoticed.
 */
public interface Disturbance {

    String name();

    void apply(GameTestHelper helper);
}
