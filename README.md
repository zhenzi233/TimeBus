# Time Bus

[English](README.md) | [中文](README_zh.md)

[![Release](https://img.shields.io/github/v/tag/zhenzi233/TimeBus)](https://github.com/zhenzi233/TimeBus/releases)

An **Applied Energistics 2** addon for Minecraft 1.12.2 (CleanroomLoader) centered on **time manipulation**: accelerate blocks and AE2 machines, produce and store a special **Time Fluid**, and wield the **Time Wand** for on-demand acceleration and bus batch transfers.

## Features

### Time Bus (ME cable part)

- **Time acceleration**: accelerates a row of blocks in front of the bus — scheduled block updates, tile entities, and random ticks.
- **AE2 device acceleration**: works on grid-ticked AE2 machines too:
  - **Charger** (via its private `doWork()`), **Inscriber**, **Molecular Assembler**, **Vibration Chamber**, **IO Port**.
- **Speed upgrade cards**: up to 4 speed cards, each multiplying the acceleration rate (`2,4,8,16,32x` by default). A bus with **no speed cards already accelerates at 2x** (the first configured multiplier), so acceleration starts at 2x, not 1x.
- **Capacity upgrade cards**: up to 3 capacity cards, extending the acceleration range (`1,3,9,15` blocks by default).
- **Redstone control**: high / low / pulse modes (requires 1 redstone card); acceleration stops immediately when the redstone condition is not met or power is lost (MM machines included, and resumes automatically when the signal returns).
- **Fuzzy mode**: AE2 fuzzy card support.
- **Two fuel modes**:
  - **AE power** (default): draws AE energy from the ME network while accelerating.
  - **Fluid mode** (configurable): consumes a fluid from the ME network instead (bundled with the `time_fluid`, or any registered fluid).
- **Performance-safe**: each bus has its own work budget (`maxCallsPerTick`, default 128 acceleration calls per tick). The budget is **per bus, not global** - N buses can issue up to N x 128 calls/tick - and excess work carries over to the next tick, so a fully-upgraded bus never spikes a single tick.
- **GUI**: shows current speed, range, power draw, and live work-budget usage.

#### What one acceleration burst does

A burst on one block = 1 scheduled block update + a tile-entity phase + `speed x 20` random ticks (when the block ticks randomly). The tile phase depends on the machine:

| Target | Tile phase of one burst |
| --- | --- |
| `ITickable` machines (furnaces, crops, ...) | `speed - 1` `update()` calls |
| AE2 Charger | `speed - 1` `doWork()` calls |
| Inscriber / Molecular Assembler / Vibration Chamber | `speed - 1` calls of `tickingRequest(null, speed)`, each advancing `speed` ticks of progress |
| IO Port | `speed - 1` calls of `tickingRequest(null, 1)`, each moving one transfer batch |

So the `32x` rating is an approximation: a 32x bus advances ITickable machines ~31 ticks, grid machines ~31 x 32 = 992 ticks of progress, and IO Ports ~31 batches per burst.

### Time Slow Bus (ME cable part)

The mirror image of the Time Bus - it makes time **run slower** for a row of blocks in front of it, by running their `ITickable.update()` only once every N ticks:

- **Frequency-based slowdown**: targeted blocks run their update once every N ticks (N = speed-card level, default `2,4,8,16,32` -> half speed, quarter speed, ... 1/32 speed). Vanilla machines (furnaces, brewing stands, hoppers), most regular mod machines, and MM/Mek controllers (restricted-tick machines) are all covered; fuel and progress slow down together, total output stays constant - symmetric to acceleration.
- **Speed upgrade cards**: up to 4, each doubling the level (once every N ticks).
- **Capacity upgrade cards**: up to 3, widen the slowdown range (shares the `1,3,9,15` config with the Time Bus).
- **Redstone control**: high / low / pulse mode (needs 1 redstone card); when the condition is unmet or power is lost, registration stops and blocks recover normal speed within 10 ticks.
- **Mutual exclusion with acceleration**: while a block is slowed down, Time Bus / Time Wand acceleration yields (slowdown wins).
- **Power**: idle + per-slowdown-unit AE drawn from the ME network.
- **GUI**: shows the current slowdown level, range and power draw.
- **Crafting**: same layout as the Time Bus (2 Time Processors + Matter Ball + Piston), with an AE Annihilation Core in the center.
- Known boundary: AE2 grid-ticked machines (Inscriber / Molecular Assembler / IO Port, driven by AE2's grid tick) are not covered.

### Time Fluid

- Bundled fluid **`time_fluid`**, storable/extractable through the ME network.
- **Emits full light** (light level 15).
- Entering a Time Fluid block grants **Speed III + Haste III for 10 seconds**.
- Can be spawned quickly with the Debug Wand (below).

### Debug Wand

- Right-click a block or air with the **Debug Wand** to spawn a Time Fluid source block — handy for testing.

### Time Fluid Generator

A Matter-Condenser-style block that converts materials into Time Fluid:

- **Input**: Matter Balls or Singularities, switched with a GUI button (two modes only, no destroy mode).
- **Conversion** (configurable, defaults):
  - 64000 Matter Balls = 1000 mB of Time Fluid
  - 64 Singularities = 1000 mB of Time Fluid (1 Singularity = 1000 Matter Ball units)
- **Progress retention**: progress accumulates in unified "units", so switching input mode mid-way keeps the progress. Removing the storage component hides the progress bar but the stored progress stays in the machine; reinsert it to continue.
- **Storage component**: insert an AE2 storage component (1k/4k/16k…) to decide the fluid buffer capacity.
- **GUI**: vertical progress bar for production (hover it for a tooltip), fluid tank shows the stored amount (full coverage with a bucket-number overlay at the bottom-right), hover tooltip shows the name and mB.
- **Bottling / pouring**: click the fluid tank with a fluid container (bucket, AE2 portable fluid cell, …) to fill or empty it.
- **No AE power cost**: pure material conversion.

### Time Wand

An AE-powered, 512-byte **fluid storage cell** that holds only Time Fluid (modeled on the Matter Cannon's item inheritance):

- **Right-click a block (shift)**: accelerates it once, consuming **10 mB of Time Fluid + 1000 AE** from the wand's own cell. Speed is determined by the installed Speed Cards (via the AE2 cell workbench) through the independent `wandSpeedMultipliers` config (`32,64,128,256,512` by default: 0 cards = 32x).
  - **On a Modular Machinery (CE) controller** (with `MM Acceleration Enabled`): the currently running recipe is accelerated until it finishes - recipe duration shrinks to 1/speed and per-tick energy cost/production scale accordingly (as governed by `MM Energy Cost Follows Speed`, keeping total energy per recipe constant); MM auto-restores normal speed when the recipe finishes (or fails), leaving no persistent state, and clicking an idle machine costs nothing (with a status message). **Note: the MM path uses the Time Bus's `Speed Multipliers` (0 cards = 2x ... 4 cards = 32x), not the wand's own 32-512x table.**
- **Right-click an ME Import/Export Bus**: performs a **batch transfer** — consumes the same cost, then runs the bus's work `wandBatchSize` (default 16) times, so one click moves many stacks into/out of the ME network. A particle burst shows the effect (broadcast by the server, visible to nearby players).
- **Fluid terminal / GUI interaction**: works as a regular fluid container — fill it from tanks, empty it into machines, and it shows the usual bytes/types tooltip in the inventory.

## Requirements

- Minecraft 1.12.2
- CleanroomLoader (`0.5.17-alpha` or compatible)
- AE2 Extended Life (`rv6-stable-7` or newer)
- Java 17 (Gradle runtime) + Java 25 (compile toolchain)


## Build

```bat
gradlew.bat build
```

Output jars land in `build/libs/`:
- `timebus-<version>.jar` — release jar (remapped)
- `timebus-<version>-dev.jar` — development jar
- `timebus-<version>-sources.jar` — sources

(`<version>` follows `mod_version` in `gradle.properties`; released jars use the actual published version.)

Prebuilt jars are published on the [Releases](https://github.com/zhenzi233/TimeBus/releases) page.

## Configuration

> ⚠️ v1.0.13: the config file is now grouped into categories (`bus` /
> `timeGenerator` / `wand` / `mm` / `mek`). Keys from older versions are no longer
> read — **delete `run/client/config/timebus.cfg` once after updating** so it
> regenerates with the new layout.

Runtime config is generated at `run/client/config/timebus.cfg` (Forge config, localized via `config.timebus.*` lang keys in both `en_US` and `zh_CN`):

### Time Bus

| Option | Default | Description |
| --- | --- | --- |
| `Speed Multipliers` | `2,4,8,16,32` | Per-card speed multipliers (comma-separated) |
| `Capacity Widths` | `1,3,9,15` | Per-card acceleration widths (comma-separated) |
| `Idle Power Draw` | `1.0` | AE/t drawn while idle |
| `Power Cost per Speed Unit` | `0.5` | AE power multiplier per speed unit |
| `Fluid Mode Enabled` | `false` | Use fluid instead of AE power |
| `Consumed Fluid` | `water` | Fluid registry name to consume |
| `Fluid per Tick` | `1.0` | mB consumed per tick |
| `Fluid Consumption Multiplier` | `2.0` | Per-card fluid consumption multiplier |
| `Minimum Fluid` | `1000` | Min mB in the ME network before operating |
| `Max Calls per Tick` | `128` | Work budget (acceleration calls per server tick) |
| `Random Tick Calls per Speed Unit` | `20` | Block.randomTick calls per speed unit on randomly-ticking blocks (at 32x = 640 calls/block; lower it if random-tick acceleration starves the budget) |

### Time Slow Bus

| Option | Default | Description |
| --- | --- | --- |
| `Slowdown Multipliers` | `2,4,8,16,32` | Per-card slowdown levels, comma-separated: the Nth value = with N-1 cards the block tick runs once every that many ticks (0 cards = half speed, 4 cards = 1/32 speed) |
| `Idle Power Draw` | `1.0` | AE/t drawn while idle |
| `Power Cost per Slowdown Unit` | `0.5` | AE power multiplier per slowdown level |

### Time Fluid Generator

| Option | Default | Description |
| --- | --- | --- |
| `Matter Ball Unit Value` | `1` | Progress units contributed by one Matter Ball |
| `Singularity Unit Value` | `1000` | Progress units contributed by one Singularity |
| `Units per Batch` | `64000` | Total units needed to produce one batch (64000 balls or 64 singularities) |
| `Time Fluid per Batch` | `1000` | mB of Time Fluid produced per full batch |
| `Inputs Consumed per Update` | `64` | Input items consumed per update call (throughput scales with bus acceleration; the slot itself always stacks up to 64) |

### Time Wand

| Option | Default | Description |
| --- | --- | --- |
| `Wand Cell Size (Bytes)` | `512` | Storage size of the wand's fluid cell in AE bytes (1 byte = 8000 mB) |
| `Wand Speed Multipliers` | `32,64,128,256,512` | Per-card wand speed multipliers (comma-separated) |
| `Wand Fluid Cost` | `10` | mB of Time Fluid consumed per use |
| `Wand Energy Cost` | `1000` | AE consumed per use |
| `Wand Batch Size` | `16` | Bus work runs per right-click on an Import/Export Bus |

### Modular Machinery (optional)

| Option | Default | Description |
| --- | --- | --- |
| `MM Acceleration Enabled` | `false` | Whether to speed up MM controllers by compressing their recipe duration (does not apply to restricted-tick machines like Mek) |
| `MM Keep Idle Threads` | `true` | Whether factory controllers currently accelerated by a Time Bus keep idle extra threads alive (disables MM 200-tick recycle, which clears the injected speed modifier and briefly drops the thread back to normal speed) |
| `MM Energy Cost Follows Speed` | `true` | Scale per-tick energy cost and production by the same factor as the speed-up: duration x1/N and energy xN, keeping total energy per recipe constant |
| `MM Context Refresh Interval` | `20` | Self-heal interval (ticks) for MM context-pool desync: force re-applies modifiers every N ticks to trigger an MM refresh, 20 = 1 second; 0 = disabled (threads may stay unaccelerated until the speed changes) |

### Mekanism (optional)

| Option | Default | Description |
| --- | --- | --- |
| `Mek Acceleration Enabled` | `false` | Re-run Mek machines' recipe tick multiple times per server tick: N extra ticks of progress per tick (N = bus speed multiplier, per-tick energy xN, total energy per recipe unchanged; covers all recipe-cache-driven machines incl. factories/chemical machines/PRC, excluding generators and instant-conversion machines) |
| `Generator Acceleration Enabled` | `true` | Also speed up Mek generators (wind / gas / bio / solar / advanced solar / heat / large multiblock): while accelerated they insert N times more energy per tick without extra fuel - pure free power generation, intentionally unbalanced; independent of the recipe-acceleration switch above |

> Known deviation: the Antiprotonic Nucleosynthesizer's recipe monitor advances
> one extra tick itself, so it accelerates at N+1 with slightly higher per-tick
> energy; accepted behavior.

### Explosion Synthesis

| Option | Default | Description |
| --- | --- | --- |
| `Enabled` | `true` | Whether to enable explosion synthesis: a Singularity and an Iron Ingot/Block (item entities, dropped on the ground) caught in an explosion are transformed into Time Inscriber Templates |
| `Singularity Cost` | `1` | Singularities consumed per craft |
| `Iron Cost` | `1` | Iron consumed per craft (iron ingot or block, ore dict `ingotIron`/`blockIron`) |
| `Output Count` | `1` | Time Inscriber Templates produced per craft |

Usage: drop a Singularity and an Iron Ingot (or Iron Block) **on the ground** (item entities), then detonate any explosion (TNT, creeper, ...) near them - they are transformed into Time Inscriber Templates when the explosion resolves. The JEI page of the Time Inscriber Template shows this crafting method as text info (same style as AE2).

## Usage

1. Place the Time Bus on any side of an AE2 cable/bus network.
2. Aim the face of the bus at the blocks you want to accelerate (e.g. furnaces, crops, machines, AE2 chargers/inscribers).
3. Insert speed / capacity / redstone / fuzzy upgrade cards via the GUI (right-click the bus).
4. Power it from the ME network (or configure fluid mode).
5. Charge and fill the Time Wand at a charger / fluid tank, then shift+right-click blocks to accelerate them on demand.

## Development

- Build toolchain: [Unimined](https://github.com/wagyourtail/Unimined) with CleanroomFG3
- Mixins: Cleanroom sponge-mixin (`0.8.7`)
- IDE run configs are generated as `1. Build`, `2. Run Client`, `3. Run Server` (IntelliJ Gradle tasks)
- See [DEVELOPER.md](DEVELOPER.md) for architecture and extension notes.

## Contributors

- **zhenzi233** — author, design, testing
- **Codewhale** — AI coding assistance (crash fixes, work-budget performance model, GUI budget sync, AE2 integration, build & release verification)

## License

MIT © zhenzi233
