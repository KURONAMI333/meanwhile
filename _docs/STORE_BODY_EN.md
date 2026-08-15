# Meanwhile

Advances the machines in a chunk forward by the ticks they missed while it was unloaded, without needing to know what kind of machine it is.

None of this happens while you're away. The chunk is genuinely unloaded and nothing in it is ticking. The catch-up runs when the chunk loads again: it ticks a block entity once and reads what changed in its own data, and if a value moved by a fixed amount, it repeats that change enough times to cover the ticks that were missed, then lets the block's real logic take over for whatever comes next. Because it never checks what the block entity actually is, a modded machine goes down the same path a furnace does: there is no list of supported machines, and nothing to wait for when a mod adds one. How far any particular machine can be carried in one go depends on what its own data does. Where the reading is not clear enough to extrapolate from, that machine is ticked for real instead of skipped, so it still arrives, just more slowly. The moment something finishes — a smelt, a batch, a craft — that completion always runs through the block's own real logic, so it can't produce more than the block's actual inputs allow.

If you were only placing a chunk loader to keep a furnace or two smelting while you're away, you don't need one for that anymore. A line of several machines feeding each other through hoppers still needs to stay loaded: Meanwhile catches up each machine on its own, but it doesn't move items between them while you're gone.

If you also run Unloaded Activity, Meanwhile steps back from vanilla furnaces, blast furnaces, and smokers so the two don't double up on the same block. Everything else is Meanwhile's alone.

It doesn't use mixins, so it doesn't rewrite any other mod's code. Server-side only: nothing to install on the client, and nothing changes for players who don't have it.

## Known limits

- Catch-up applies to a chunk that fully unloaded and later reloaded. A chunk that stays loaded but sits outside your simulation distance stops ticking without unloading. The machines in it stop, and that time isn't made up.
- A line of machines connected by hoppers or similar item transport doesn't progress while unloaded, because transport only happens on a real tick. It resumes from wherever it stopped once the chunk is loaded and ticking again.
- Anything that depends on entities is out of scope entirely: item sorting, minecart transport, breeding, mob-driven farms. None of it is caught up.
- While a chunk is unloaded, nothing crosses its boundary. A machine that pushes its output into the next chunk over is caught up in place, but that output doesn't go anywhere until the chunk is loaded and ticking again. Whether that hand-off across a boundary resumes correctly hasn't been verified by testing.
- How well this covers the full range of machines across the modding ecosystem hasn't been measured at scale. Testing was limited to a small number of vanilla and modded block entities the test harness could drive, not a representative sample of everything that's out there.

All Rights Reserved. Free to put in any modpack, on any platform, monetised or not - no permission needed, no credit required. Source is published so you can read exactly what it does.

Source and issues: https://github.com/KURONAMI333/meanwhile

Author: KURONAMI
