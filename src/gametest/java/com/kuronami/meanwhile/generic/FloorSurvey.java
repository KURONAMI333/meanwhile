package com.kuronami.meanwhile.generic;

import com.kuronami.meanwhile.Meanwhile;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Where falling counters were actually seen to stop.
 *
 * <p>{@code GenericCatchUp} sends a rising counter at a number it has watched a counter turn over
 * at, twice, and refuses to jump one it has not. A falling counter is sent at zero, which is not
 * watched at all — it is the one number in the design that is assumed. A countdown that changes
 * state at twenty would be carried straight past it.
 *
 * <p>The symmetric rule has been written and measured, and it costs four required gates: a
 * burning furnace's {@code BurnTime} rises only when a fuel item is consumed, so two sightings
 * need about 3,200 ticks against a 3,000 tick window, and furnaces stop jumping (G167 item 3).
 * Before paying that, the size of the risk is measured on the population that already exists.
 * This reports it and changes nothing: {@code GenericCatchUp} writes the table down and reads
 * nothing back out of it.
 *
 * <h3>What counts as a floor</h3>
 * <p>The value a fall was last seen at before it turned back up, exactly as observed. A vanilla
 * furnace's {@code BurnTime} is seen at 1 and refuels on the tick it would reach 0; a millstone's
 * {@code Timer} is seen at 0. Neither is inferred into the other here, because which of the two a
 * machine acts on is a judgement about that machine.
 *
 * <p>A floor is separately marked when it is one the jump arithmetic does not already leave to
 * the machine. A jump aimed at zero is cut so that {@code after - VERIFY_MARGIN} steps at most
 * are taken, which lands the counter at or above {@code VERIFY_MARGIN}; every value below that is
 * therefore still reached by a real tick whatever this table says. A floor at or above it is the
 * class the assumption can actually be wrong about.
 *
 * <h3>Two populations, two denominators</h3>
 * <p>The recorder is global and runs under every gate, so the suite's hand-built arenas — which
 * include furnaces built on purpose to move a counter pathologically — are in it alongside the
 * corpus walk. A floor sourced from a negative control is not an observation about the shipped
 * ecosystem. {@link #mark} and {@link #report} therefore fence a scope: what the corpus walk
 * alone produced is reported separately from what the whole run produced.
 */
public final class FloorSurvey {

    /** Falls and troughs as they stood when a scope was opened, so the scope can be subtracted. */
    private static final Map<String, Map<String, GenericCatchUp.FloorReading>> MARKS =
            new LinkedHashMap<>();

    private FloorSurvey() {
    }

    /**
     * Asks {@link GenericCatchUp} to write the table down, and reports the whole run's at
     * shutdown.
     *
     * <p>The recorder is off in the product and this is what turns it on, so nothing outside a
     * run pays for a table only a run reads. Called from {@code MeanwhileGates}, which is
     * constructed before any gate does anything, for the same reason the per-chunk running totals
     * are asked for there: a survey that had to remember to switch its own recorder on would
     * report an empty table and call it an answer.
     */
    public static void install() {
        GenericCatchUp.setRecordFallingFloors(true);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> report("run"));
        Meanwhile.LOGGER.info("[floors] survey installed | recording on | reported at server stop");
    }

    /** Opens a scope: what is already in the table stops counting towards it. */
    public static void mark(String scope) {
        MARKS.put(scope, GenericCatchUp.floors());
    }

    /**
     * Writes out one scope.
     *
     * <p>With a mark, only what happened after it: the falls, the turnarounds and the sightings
     * of each trough value are all differences, so a counter that was already at its floor before
     * the scope opened contributes nothing to it.
     */
    public static void report(String scope) {
        Map<String, GenericCatchUp.FloorReading> now = GenericCatchUp.floors();
        Map<String, GenericCatchUp.FloorReading> since = MARKS.get(scope);
        int margin = GenericCatchUp.verifyMargin();

        int counters = 0;
        TreeSet<String> types = new TreeSet<>();
        TreeSet<String> typesThatFell = new TreeSet<>();
        TreeSet<String> typesThatTurned = new TreeSet<>();
        TreeSet<String> typesWithANonZeroFloor = new TreeSet<>();
        TreeSet<String> typesBeyondMargin = new TreeSet<>();

        for (Map.Entry<String, GenericCatchUp.FloorReading> entry : new TreeMap<>(now).entrySet()) {
            Reading reading = delta(entry.getValue(),
                    since == null ? null : since.get(entry.getKey()));
            if (reading.falls <= 0) {
                continue;
            }
            String key = entry.getKey();
            int bar = key.indexOf('|');
            String type = bar < 0 ? key : key.substring(0, bar);
            String path = bar < 0 ? "<root>" : key.substring(bar + 1);

            counters++;
            types.add(type);
            typesThatFell.add(type);

            Long floor = reading.highestTrough();
            Long corroborated = reading.corroborated();
            boolean nonZero = floor != null && floor != 0L;
            boolean beyondMargin = floor != null && floor >= margin;
            if (reading.turnarounds > 0) {
                typesThatTurned.add(type);
            }
            if (nonZero) {
                typesWithANonZeroFloor.add(type);
            }
            if (beyondMargin) {
                typesBeyondMargin.add(type);
            }

            Meanwhile.LOGGER.info("[floors] counter | scope={} type={} path={} falls={}"
                            + " turnarounds={} floor={} corroborated={} troughs={} nonZero={}"
                            + " beyondMargin={}",
                    scope, type, path, reading.falls, reading.turnarounds, floor, corroborated,
                    reading.troughs, nonZero, beyondMargin);
            if (nonZero) {
                Meanwhile.LOGGER.info("[floors] NON-ZERO | scope={} type={} path={} floor={}"
                                + " corroborated={} troughs={} beyondMargin={}",
                        scope, type, path, floor, corroborated, reading.troughs, beyondMargin);
            }
        }

        Meanwhile.LOGGER.info("[floors] SUMMARY | scope={} verifyMargin={} counters={} types={}"
                        + " typesThatMovedAFallingCounter={} typesWithATurnaround={}"
                        + " typesWithANonZeroFloor={} typesWithAFloorAtOrAboveTheMargin={}",
                scope, margin, counters, types.size(), typesThatFell.size(),
                typesThatTurned.size(), typesWithANonZeroFloor.size(), typesBeyondMargin.size());
        Meanwhile.LOGGER.info("[floors] NAMES | scope={} fell={} turned={} nonZero={} beyond={}",
                scope, typesThatFell, typesThatTurned, typesWithANonZeroFloor, typesBeyondMargin);
    }

    /** One counter's contribution to a scope. */
    private record Reading(long falls, long turnarounds, Map<Long, Integer> troughs) {

        /**
         * The highest value a fall was seen to stop at, which is the conservative reading: of two
         * floors the higher is the one a jump aimed at zero would step over first.
         */
        @Nullable
        Long highestTrough() {
            Long highest = null;
            for (Long value : troughs.keySet()) {
                if (highest == null || value > highest) {
                    highest = value;
                }
            }
            return highest;
        }

        /** The highest value seen twice, which is what a symmetric rule would be allowed to use. */
        @Nullable
        Long corroborated() {
            Long highest = null;
            for (Map.Entry<Long, Integer> entry : troughs.entrySet()) {
                if (entry.getValue() >= 2 && (highest == null || entry.getKey() > highest)) {
                    highest = entry.getKey();
                }
            }
            return highest;
        }
    }

    private static Reading delta(GenericCatchUp.FloorReading now,
                                 @Nullable GenericCatchUp.FloorReading before) {
        if (before == null) {
            return new Reading(now.falls(), now.turnarounds(), new TreeMap<>(now.troughs()));
        }
        Map<Long, Integer> troughs = new TreeMap<>();
        now.troughs().forEach((value, count) -> {
            int was = before.troughs().getOrDefault(value, 0);
            if (count - was > 0) {
                troughs.put(value, count - was);
            }
        });
        return new Reading(Math.max(0L, now.falls() - before.falls()),
                Math.max(0L, now.turnarounds() - before.turnarounds()), troughs);
    }
}
