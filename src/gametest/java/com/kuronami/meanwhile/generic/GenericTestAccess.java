package com.kuronami.meanwhile.generic;

/**
 * The measurement-only entry points of {@link GenericCatchUp}, reachable by a gate that does not
 * live in this package.
 *
 * <p>Both stayed {@code public} because the gates that call them are in {@code elapsed} while the
 * peak table is in {@code generic}, not because the product calls either — it calls neither
 * (GAP_LOG G157). This class is on the {@code gametest} source set and shares the package name, so
 * the product side can be package-private and a jar consumer reaches nothing.
 */
public final class GenericTestAccess {

    private GenericTestAccess() {
    }

    /** See {@link GenericCatchUp#forgetPeaks}. */
    public static void forgetPeaks() {
        GenericCatchUp.forgetPeaks();
    }

    /** See {@link GenericCatchUp#setRewindDistinction}. */
    public static void setRewindDistinction(boolean on) {
        GenericCatchUp.setRewindDistinction(on);
    }
}
