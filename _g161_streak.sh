#!/usr/bin/env bash
# The acceptance streaks for G161, with a distinct log name per run so that a failure keeps its
# log (_run_gate.sh truncates when a name is reused; two failure logs were lost that way, G157).
# Usage: _g161_streak.sh idle|load <count>
set -u
cd "$(dirname "$0")" || exit 1
KIND="$1"
COUNT="$2"
SUMMARY="_g161_${KIND}_summary.txt"
: > "$SUMMARY"

for i in $(seq 1 "$COUNT"); do
  NAME="g161_${KIND}${i}"
  if [ "$KIND" = "load" ]; then
    OUT="$(./_run_gate_loaded.sh "$NAME" 2>&1)"
  else
    OUT="$(./_run_gate.sh "$NAME" 2>&1)"
  fi
  EXIT=$?
  REQUIRED="$(printf '%s\n' "$OUT" | grep -oE "All [0-9]+ required tests passed|[0-9]+ required tests failed" | tail -1)"
  HARNESS="$(printf '%s\n' "$OUT" | grep -o "harness baseline diff lines: .*" | tail -1)"
  V2="$(printf '%s\n' "$OUT" | grep -o "v2 baseline diff lines: .*" | tail -1)"
  SCAFFOLD="$(grep -o "\[scaffold\] GATE .*" "ucu_${NAME}.log" | sed 's/\r$//' | tail -1)"
  printf '%s | exit=%s | %s | %s | %s\n' "$NAME" "$EXIT" "${REQUIRED:-<no required line>}" \
      "${HARNESS:-<none>}" "${V2:-<none>}" >> "$SUMMARY"
  printf '        %s\n' "${SCAFFOLD:-<no scaffold GATE line>}" >> "$SUMMARY"
  tail -2 "$SUMMARY"
done

echo "=== $KIND streak done"
cat "$SUMMARY"
