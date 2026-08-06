# Time Bus

[English](README.md) | [中文](README_zh.md)

[![Release](https://img.shields.io/badge/release-v1.0.4-blue)](https://github.com/zhenzi233/TimeBus/releases/tag/v1.0.4)

An **Applied Energistics 2** addon for Minecraft 1.12.2 (CleanroomLoader) centered on **time manipulation**: accelerate blocks and AE2 machines, produce and store a special **Time Fluid**, and wield the **Time Wand** for on-demand acceleration and bus batch transfers.

## Features

### Time Bus (ME cable part)

- **Time acceleration**: accelerates a row of blocks in front of the bus — scheduled block updates, tile entities, and random ticks.
- **AE2 device acceleration**: works on grid-ticked AE2 machines too:
  - **Charger** (via its private `doWork()`), **Inscriber**, **Molecular Assembler**, **Vibration Chamber**, **IO Port**.
- **Speed upgrade cards**: up to 4 speed cards, each multiplying the acceleration rate (`2,4,8,16,32x` by default). A bus with **no speed cards already accelerates at 2x** (the first configured multiplier), so acceleration starts at 2x, not 1x.
- **Capacity upgrade cards**: up to 3 capacity cards, extending the acceleration range (`1,3,9,15` blocks by default).
- **Redstone control**: high / low / pulse modes (requires 1 redstone card).
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

- **Right-click a block (shift)**: accelerates it once, consuming **10 mB of Time Fluid + 1000 AE** from the wand's own cell. Speed is determined by the installed Speed Cards (via the AE2 cell workbench) through the independent `wandSpeedMultipliers` config.
- **Right-click an ME Import/Export Bus**: performs a **batch transfer** — consumes the same cost, then runs the bus's work `wandBatchSize` (default 16) times, so one click moves many stacks into/out of the ME network. A particle burst shows the effect (server-side transfer, client-side particles).
- **Fluid terminal / GUI interaction**: works as a regular fluid container — fill it from tanks, empty it into machines, and it shows the usual bytes/types tooltip in the inventory.

## Requirements

- Minecraft 1.12.2
- CleanroomLoader (`0.5.17-alpha` or compatible)
- AE2 Extended Life (`rv6-stable-7` or newer)
- Java 17 (Gradle runtime) + Java 25 (compile toolchain)


## Compatibility

- Time Bus can accelerate Mekanism CE (1.12 branch) machines by advancing their processing directly (public onUpdate()); energy is drawn from the machine's own Mekanism grid - no cross-mod energy conversion. Disable with `mekAccelerationEnabled`.

## Build

```bat
gradlew.bat build
```

Output jars land in `build/libs/`:
- `timebus-1.0.4.jar` — release jar (remapped)
- `timebus-1.0.4-dev.jar` — development jar
- `timebus-1.0.4-sources.jar` — sources

Prebuilt jars are published on the [Releases](https://github.com/zhenzi233/TimeBus/releases) page.

## Configuration

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

### Time Fluid Generator

| Option | Default | Description |
| --- | --- | --- |
| `Matter Ball Unit Value` | `1` | Progress units contributed by one Matter Ball |
| `Singularity Unit Value` | `1000` | Progress units contributed by one Singularity |
| `Units per Batch` | `64000` | Total units needed to produce one batch (64000 balls or 64 singularities) |
| `Time Fluid per Batch` | `1000` | mB of Time Fluid produced per full batch |

### Time Wand

| Option | Default | Description |
| --- | --- | --- |
| `Wand Speed Multipliers` | `2,4,8,16,32` | Per-card wand speed multipliers (comma-separated) |
| `Wand Fluid Cost` | `10` | mB of Time Fluid consumed per use |
| `Wand Energy Cost` | `1000` | AE consumed per use |
| `Wand Batch Size` | `16` | Bus work runs per right-click on an Import/Export Bus |

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
