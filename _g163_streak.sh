#!/usr/bin/env bash
# The acceptance streaks for G163, with a distinct log name per run so that a failure keeps its
# log (_run_gate.sh truncates when a name is reused; two failure logs were lost that way, G157).
#
# Beyond the two baseline diffs, this records the cause the run was supposed to have removed,
# because a green streak on its own is weak evidence here: the defect showed in 2 runs of 18, so
# 17 clean runs happen by luck about one time in seven. Per run it counts, inside the window the
# frozen line is read in, the catch-ups that wrote NBT -- the thing that moved the reading -- and
# echoes this gate's own forget line.
# Usage: _g163_streak.sh idle|load <count>
set -u
cd "$(dirname "$0")" || exit 1
KIND="$1"
COUNT="$2"
SUMMARY="_g163_${KIND}_summary.txt"
: > "$SUMMARY"

for i in $(seq 1 "$COUNT"); do
  NAME="g163_${KIND}${i}"
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
  printf '%s | exit=%s | %s | %s | %s\n' "$NAME" "$EXIT" "${REQUIRED:-<no required line>}" \
      "${HARNESS:-<none>}" "${V2:-<none>}" >> "$SUMMARY"
  printf '        %s | nbt-writing catch-ups in window: %s\n' \
      "${TICKABLE:-<no tickable line>}" "$NBT" >> "$SUMMARY"
  printf '        %s\n' "${FORGET:-<no forget line from this gate>}" >> "$SUMMARY"
  tail -3 "$SUMMARY"
done

echo "=== $KIND streak done"
cat "$SUMMARY"
