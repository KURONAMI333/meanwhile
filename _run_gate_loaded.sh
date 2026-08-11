#!/usr/bin/env bash
# _run_gate.sh with the host put under CPU contention first.
#
# Why this exists. Two required gates have failed exactly once each in dozens of idle runs and
# could not be reproduced by repeating them: darkcropdoesnotgrow (fixed, GAP_LOG G139 §1) and
# roundtripblockentitykeepstickingnaturally (G139 §6). Both wait a number of server ticks for
# something a background thread finishes in wall-clock time, and GameTestServer does not sleep —
# it runs about 3,850 ticks a second — so a background task that takes longer in real time
# consumes more of the tick-counted window. Loading every core is what turns that from a story
# into a measurement: it made an unmodified commit go red one run in three (G139).
#
# 24 spinners on a 24-core host, started before the run and killed after it, whatever the run did.
# Usage: _run_gate_loaded.sh <name> [spinners]
set -u
cd "$(dirname "$0")" || exit 1
NAME="$1"
SPINNERS="${2:-24}"

PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null
  done
}
trap cleanup EXIT INT TERM

for _ in $(seq 1 "$SPINNERS"); do
  # Busy arithmetic in the shell itself: no dependency on a tool being installed, and it stays
  # on the CPU rather than on the disk, which is the resource the gates are racing for.
  ( while :; do :; done ) &
  PIDS+=($!)
done
echo "=== $NAME | $SPINNERS spinners up on $(nproc) cores"

./_run_gate.sh "$NAME"
EXIT=$?
cleanup
PIDS=()
exit $EXIT
