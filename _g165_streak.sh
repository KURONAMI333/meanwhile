#!/usr/bin/env bash
# The acceptance streaks for G165, with a distinct log name per run so that a failure keeps its
# log (_run_gate.sh truncates when a name is reused; two failure logs were lost that way, G157).
#
# G163's runner recorded the cause its fix removed. This one records what its own change adds:
# the growth the ceiling in catchUpLeavesTheFurnaceTickableByTheGame is judging, so that a run
# where the reading crept towards the ceiling is visible rather than merely green. Kept from
# G163: the nbt-writing catch-ups inside the window, and this gate's own forget line.
#
# Also counted per run: forget lines from SchedulerGameTests. Its sibling gate got the same
# pre-window forget, but that class is registered only when meanwhile-loaded.properties exists,
# so the expected figure is 0 and a non-zero one means the standing suite changed shape.
# Usage: _g165_streak.sh idle|load <count>
set -u
cd "$(dirname "$0")" || exit 1
KIND="$1"
COUNT="$2"
SUMMARY="_g165_${KIND}_summary.txt"
: > "$SUMMARY"

for i in $(seq 1 "$COUNT"); do
  NAME="g165_${KIND}${i}"
  if [ "$KIND" = "load" ]; then
    OUT="$(./_run_gate_loaded.sh "$NAME" 2>&1)"
  else
    OUT="$(./_run_gate.sh "$NAME" 2>&1)"
  fi
  EXIT=$?
  LOG="ucu_${NAME}.log"
  REQUIRED="$(printf '%s\n' "$OUT" | grep -oE "All [0-9]+ required tests passed|[0-9]+ required tests failed" | tail -1)"
  HARNESS="$(printf '%s\n' "$OUT" | grep -o "harness baseline diff lines: .*" | tail -1)"
  V2="$(printf '%s\n' "$OUT" | grep -o "v2 baseline diff lines: .*" | tail -1)"
  TICKABLE="$(grep -o "\[harness\] tickable after catch-up.*" "$LOG" | sed 's/\r$//' | tail -1)"
  # The window the frozen line is read in: the batch it runs in, up to the line itself.
  START="$(grep -n "Running test batch 'catchupprimitive:0'" "$LOG" | tail -1 | cut -d: -f1)"
  END="$(grep -n "tickable after catch-up" "$LOG" | tail -1 | cut -d: -f1)"
  if [ -n "$START" ] && [ -n "$END" ]; then
    NBT="$(sed -n "${START},${END}p" "$LOG" | grep -c "writes={nbt=")"
  else
    NBT="<window not found>"
  fi
  FORGET="$(grep -o "\[catchup\] forget | chunks=[^|]*| .*catchUpLeavesTheFurnaceTickableByTheGame" "$LOG" | sed 's/\r$//' | tail -1)"
  SIBLING="$(grep -c "\[catchup\] forget .*SchedulerGameTests" "$LOG")"
  printf '%s | exit=%s | %s | %s | %s\n' "$NAME" "$EXIT" "${REQUIRED:-<no required line>}" \
      "${HARNESS:-<none>}" "${V2:-<none>}" >> "$SUMMARY"
  printf '        %s | nbt-writing catch-ups in window: %s | sibling forget lines: %s\n' \
      "${TICKABLE:-<no tickable line>}" "$NBT" "$SIBLING" >> "$SUMMARY"
  printf '        %s\n' "${FORGET:-<no forget line from this gate>}" >> "$SUMMARY"
  tail -3 "$SUMMARY"
done

echo "=== $KIND streak done"
cat "$SUMMARY"
