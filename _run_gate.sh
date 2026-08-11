#!/usr/bin/env bash
# One GameTest run, with the log kept and the observations diffed against the two baselines:
# the retired loaded-chunk scheme's (BASELINE_meanwhile_harness.txt) and the type-agnostic
# catch-up's (BASELINE_meanwhile_v2.txt). Neither replaces the other.
# Usage: _run_gate.sh <name>
set -u
cd "$(dirname "$0")" || exit 1
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot"
NAME="$1"
LOG="ucu_${NAME}.log"

./gradlew runGameTestServer --console=plain --no-build-cache >"$LOG" 2>&1
EXIT=$?

echo "=== $NAME | gradle exit=$EXIT"
grep -o "GAME TESTS COMPLETE.*\|All [0-9]* required tests passed.*\|[0-9]* required tests failed.*\|tests are now running.*" "$LOG" | sed 's/\r$//' | tail -5
grep -c "Game test server shutting down" "$LOG" | sed 's/^/shutdown lines: /'

# Which line kinds are in and which family is excluded is written out in the header of
# BASELINE_meanwhile_harness.txt; keep the two in step.
grep -o "\[harness\].*" "$LOG" | sed 's/\r$//' \
  | grep -v "^\[harness\] millstone" \
  | sort -u > "ucu_${NAME}_harness.txt"
HARNESS_EXPECTED="$(mktemp)"
grep -v '^#' ../_handoff/BASELINE_meanwhile_harness.txt > "$HARNESS_EXPECTED"
echo "--- harness baseline diff (retired loaded-chunk scheme) ---"
diff "ucu_${NAME}_harness.txt" "$HARNESS_EXPECTED"
echo "--- harness baseline diff lines: $(diff "ucu_${NAME}_harness.txt" "$HARNESS_EXPECTED" | wc -l)"
rm -f "$HARNESS_EXPECTED"

# The v2 observations. Which line kinds are in and which two fields are masked is written out
# in the header of BASELINE_meanwhile_v2.txt; keep the two in step.
grep -oE "\[(corpus|furnace|furnacewide|debt|scaffold|guard|duel|peaks)\] .*" "$LOG" \
  | sed 's/\r$//' \
  | grep -E "^\[(corpus|furnace|furnacewide|peaks)\] |^\[debt\] RESULT |^\[scaffold\] (GATE|RESULT|arm) |^\[guard\] threshold |^\[duel\] WIDE shape " \
  | sed -e 's/\$\$Lambda\/0x[0-9a-f]*/$$Lambda\/<jvm>/g' -e 's/worstDrain=[0-9]*us/worstDrain=<us>/g' \
  | LC_ALL=C sort -u > "ucu_${NAME}_v2.txt"
EXPECTED="$(mktemp)"
grep -v '^#' ../_handoff/BASELINE_meanwhile_v2.txt > "$EXPECTED"
echo "--- v2 baseline diff (type-agnostic catch-up) ---"
diff "ucu_${NAME}_v2.txt" "$EXPECTED"
echo "--- v2 baseline diff lines: $(diff "ucu_${NAME}_v2.txt" "$EXPECTED" | wc -l)"
rm -f "$EXPECTED"
exit $EXIT
