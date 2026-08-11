# Meanwhile — working rules

> Read this before changing anything here. What follows is what this mod's test campaign settled,
> stated as rules. The evidence is in the campaign GAP_LOG (`_handoff/GAP_LOG_meanwhile.md`, in the
> workspace above this repo, not in it); entries are cited as G-numbers rather than restated.

## What the mod is

Advances block entities by the ticks they missed while their chunk was unloaded, **without knowing
the type**: it ticks the block entity once, diffs its NBT, extrapolates integer tags that moved
linearly, and stops before a boundary it has watched a counter turn over at. That is why it works on
modded machines nobody wrote support for, and it is the property to protect when changing anything
in `generic/` or `elapsed/`.

`scheduler/` and `mixin/` hold a **retired** scheme that deferred the ticks of loaded chunks. It was
abandoned after measurement and is kept deliberately. Do not revive it, and do not enable
`[[mixins]]` in the toml, without a decision that says so.

## Build and gate

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot"
./gradlew build
./_run_gate.sh <name>          # one GameTest run, diffed against both baselines
./_run_gate_loaded.sh <name>   # the same, with every core loaded first
```

Acceptance is **62 required tests**, green 7/7 at idle and 10/10 under load.

- **`_run_gate_loaded.sh` is the honest way to run the suite.** Load-sensitive defects do not appear
  at idle: two required gates each failed exactly once in dozens of idle runs and could not be
  reproduced until every core was busy, at which point an unmodified commit went red one run in
  three (G139). A green idle streak is not evidence about a gate that waits on a background thread.
- **Do not launch a Minecraft client from a session that shares this machine.**
  `_tools/runclient_fresh.sh` kills other sessions' clients, and a killed run looks like a crash
  (gradle exit 1, a log that stops mid-startup, no crash report).

## Evidence rules

- **A deliberate red is an acceptance condition, not a nicety.** An assertion nobody has watched
  fail is not evidence that it can fail. If an assertion cannot be independently falsified, do not
  add it — stage 4b declined one on exactly that ground. Stage the red, capture the log, revert it.
- **Predict before measuring.** Write down the expected result, then measure, then report both —
  including, especially, when the prediction was wrong. A wrong prediction that is reported is an
  independent check on the reasoning; one that is quietly dropped is not (G155 has a worked example).
- **Report numbers in pairs, with the denominator stated.** "1 of 269 scanned, and 1 of the 8 that
  actually moved." A single ratio over a population that mostly stood still reads far stronger than
  the data supports; G81 ruled exactly that misreading.
- **Regenerating a baseline is only ever done from a green suite**, and the old/new diff is explained
  line by line before it is committed. Regenerating from a red run is the standard way a failure
  becomes expected output for good.
- **No required assertion on wall-clock time.** Work done is deterministic; elapsed time is a
  property of the host. A microsecond comparison in a required gate flaked for two rounds before
  this was settled (ruling 6). Assert on counted quantities — instalments, ticks, machines carried —
  and inject the clock when a time budget has to be exercised.

## Gates must not corrupt other gates

GameTest runs many tests in one server. `ChunkCatchUp`'s worklist, debts, mode, stamp offset and
counters are **global**, so a gate that resets them is reaching into every other gate's measurement.

- **`ChunkCatchUp.forget(level)` throws if any catch-up work is in flight.** It empties the worklist
  and zeroes every chunk's debt in the dimension; called while another gate has jobs queued, it
  destroys that work, which is then re-queued and paid off inside whichever window is open at the
  time. Nothing fails when this happens — the offending gate stays green while other gates' frozen
  values move (2 of 22 runs) and a required control goes red (1 of 36). That is G156, the tenth
  instance in this campaign of a failure that does not look like one.
- The check is on **the state that would be destroyed**, not on the name of the calling method. A
  rule keyed to `arm()` versus `restore()` needs a list of which names count as teardown, and such a
  list is one entry away from exempting the next offender. Call `ChunkCatchUp.workInFlight()` and
  wait, as `DimensionKeyGameTests` does, rather than discovering the rule by crashing the run.
- **The blast radius is still global** even when the call is legal: every `forget` measured clears
  12–20 chunks' debt, not one (G157). Adding a gate that calls it is not free.

## The shipped configuration

- **Zero mixins.** That is the mod's strongest compatibility asset and the reason it can claim to sit
  alongside anything. The six mixins that exist belong to the retired scheme and are not in the
  loaded config.
- **Server-side.** No client class, no renderer, no packet, no screen. `side="SERVER"` on both
  dependencies follows the 1.21.1 jars of the mods doing the same job.
- **No user-facing strings.** No `Component`, no config screen, no registered block or item, so the
  mod ships no `assets/`. If that ever changes, the family convention is one lang file per locale,
  full Minecraft locale codes (`ja_jp`, `pt_br`, `zh_cn`), with **the mod's name left untranslated**.
- **`verifyNoTestScaffoldingInJar` is the machine gate that keeps test code out of the jar**, and it
  is wired into `check`. Nine thousand lines of measurement and fifteen GameTest classes are one
  `git mv` from being shipped; on 26.x a shipped GameTest class lands in a real registry and fails
  the configuration sync a client performs on join. Do not relax its five readings.
- **Test seams are package-private where the product does not need them wider.** The gametest source
  set shares the package names, so it still reaches them and a consumer of the jar does not. Do not
  re-widen one to `public` to reach it from a test in another package; put the test in the package,
  or add the accessor to the gametest source set.

## Scope rules

- **Do not weaken a gate to make a change fit**: no `required = false`, no widened tolerance, no
  removed assertion, no dropped test. Ten separate instances of a gate that quietly stopped gating
  have already been found in this campaign.
- **`SLICE_TICKS`, `BUDGET_NANOS`, `pay()` and `drain()` are settled.** Changing them reopens stages 3 to 5.
- A 14-line `[furnace] no-ceiling` v2 baseline diff while every required test is green is the known
  stage 3 residual. Stop and report it. **Any other baseline diff is your own change**: predict it,
  measure it, explain it.
