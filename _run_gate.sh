#!/usr/bin/env bash
# One GameTest run, with the log kept and the harness lines diffed against the baseline.
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

grep -o "\[harness\].*" "$LOG" | sed 's/\r$//' | sort -u > "ucu_${NAME}_harness.txt"
echo "--- baseline diff ---"
diff "ucu_${NAME}_harness.txt" ../_handoff/BASELINE_meanwhile_harness.txt
echo "--- baseline diff lines: $(diff "ucu_${NAME}_harness.txt" ../_handoff/BASELINE_meanwhile_harness.txt | wc -l)"
exit $EXIT
