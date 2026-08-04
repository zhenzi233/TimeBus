**Time Bus** is an **Applied Energistics 2** addon for **Minecraft 1.12.2 (CleanroomLoader)** built around **time manipulation**: accelerate blocks and AE2 machines, produce and store the special **Time Fluid**, and wield the **Time Wand** for on-demand acceleration.

## Features

### ⏱ Time Bus (ME cable part)

- Accelerates a row of blocks in front of the bus — scheduled block updates, tile entities, and random ticks
- Accelerates AE2 grid machines too: **Charger**, **Inscriber**, **Molecular Assembler**, **Vibration Chamber**, **IO Port**
- **Speed cards** (up to 4): ×2 / ×4 / ×8 / ×16 / ×32 acceleration multipliers
- **Capacity cards** (up to 3): extends the acceleration range to 1 / 3 / 9 / 15 blocks
- **Redstone control** (high / low / pulse) and **fuzzy mode** support
- Two fuel modes:
  - **AE power** (default): draws energy from the ME network
  - **Fluid mode**: consumes any fluid from the network (the bundled `time_fluid` works out of the box)
- Performance-safe **work budget** (default 128 calls per server tick) — fully upgraded buses never spike the tick
- GUI shows live speed, range, power draw, and budget usage

### 💧 Time Fluid

- A new fluid that can be stored and extracted through the ME network
- Emits full light (light level 15)
- Touching it grants **Speed III + Haste III for 10 seconds**

### 🪄 Time Wand

- An AE-powered **512-byte fluid storage cell** that holds only Time Fluid
- **Shift + right-click** a block: one acceleration burst (consumes 10 mB of Time Fluid + 1000 AE)
- **Shift + right-click** an ME Import/Export Bus: **batch transfer** (runs the bus's work 16 times per click by default)
- Works as a regular fluid container with tanks and fluid terminals

### ⚙️ Time Fluid Generator

- A Matter-Condenser-style block that converts **Matter Balls** or **Singularities** into Time Fluid
- Progress is retained across mode switches and even storage-component removal
- Insert an AE2 storage component (1k / 4k / 16k…) to set the fluid buffer size
- Fill or empty it by clicking the tank with any fluid container (bucket, portable fluid cell, …)
- No AE power cost — pure material conversion

### 🔧 Debug Wand

- Right-click to spawn a Time Fluid source block — handy for testing

## Requirements

- Minecraft **1.12.2**
- **CleanroomLoader**
- **AE2 Extended Life (AE2 UEL)** — required dependency

## Notes

- Works on both client and server (server-side logic, client-side GUI and particles)
- MIT licensed — check the repository for source and development docs
