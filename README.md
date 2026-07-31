# Time Bus

An **Applied Energistics 2** addon for Minecraft 1.12.2 (CleanroomLoader) that adds a **time acceleration block** — the Time Bus speeds up blocks on its facing side.

## Features

- **Time acceleration**: accelerates a row of blocks in front of the bus (block updates, tile entities, and random ticks).
- **Speed upgrade cards**: up to 4 speed cards, each multiplying the acceleration rate (`2,4,8,16,32x` by default).
- **Capacity upgrade cards**: up to 3 capacity cards, extending the acceleration range (`1,3,9,15` blocks by default).
- **Redstone control**: high / low / pulse modes (requires 1 redstone card).
- **Fuzzy mode**: AE2 fuzzy card support.
- **Two fuel modes**:
  - **AE power** (default): draws AE energy from the ME network while accelerating.
  - **Fluid mode** (configurable): consumes a fluid from the ME network instead (bundled with the `time_fluid`, or any registered fluid).
- **Performance-safe**: a work budget caps the number of acceleration calls per server tick (`maxCallsPerTick`, default 128), so fully-upgraded buses never spike the tick; excess work carries over to the next tick.
- **GUI**: shows current speed, range, power draw, and live work-budget usage.

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
- `timebus-1.0.0.jar` — release jar (remapped)
- `timebus-1.0.0-dev.jar` — development jar
- `timebus-1.0.0-sources.jar` — sources

## Configuration

Runtime config is generated at `run/client/config/timebus.cfg` (Forge config):

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

## Usage

1. Place the Time Bus on any side of an AE2 cable/bus network.
2. Aim the face of the bus at the blocks you want to accelerate (e.g. furnaces, crops, machines).
3. Insert speed / capacity / redstone / fuzzy upgrade cards via the GUI (right-click the bus).
4. Power it from the ME network (or configure fluid mode).

## Development

- Build toolchain: [Unimined](https://github.com/wagyourtail/Unimined) with CleanroomFG3
- Mixins: Cleanroom sponge-mixin (`0.8.7`)
- IDE run configs are generated as `1. Build`, `2. Run Client`, `3. Run Server` (IntelliJ Gradle tasks)

## License

MIT © zhenzi233
