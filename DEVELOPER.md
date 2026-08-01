# TimeBus 开发者文档

本仓库是 Minecraft 1.12.2 + CleanroomLoader 的 AE2（Applied Energistics 2）附属模组。本文档面向想理解、修改或扩展该模组的开发者。

## 1. 模块结构

```
src/main/java/com/zhenzi233/timebus/
├── TimeBus.java            # 主类：preInit 注册（流体/方块/物品/升级卡/网络包/模型）
├── config/TimeBusConfig.java  # Forge 配置（全部可调项）
├── fluid/
│   ├── TimeBusFluids.java  # 时间流体注册（幂等 + 归属校验）
│   └── BlockTimeFluid.java # 流体方块：触碰 buff（速度III+急迫III 10s）+ 满光照
├── part/
│   ├── ItemTimeBus.java    # part 物品（挂在线缆上的方块）
│   └── PartTimeBus.java    # 核心加速方块：预算状态机 + 调度
├── item/
│   └── ItemTimeWand.java   # 时间杖：存储单元 + 右键加速/总线批量
├── util/AccelerateHelper.java  # 加速引擎（唯一加速逻辑归属）
├── tile/                   # 时间流体发生器（方块实体 + 容器 + GUI）
├── client/gui/             # 发生器 GUI / 时间总线 GUI
└── network/                # 模式切换网络包
```

## 2. 加速引擎（AccelerateHelper）

所有"加速一个方块"的逻辑集中在 `util/AccelerateHelper.java`，PartTimeBus 和 ItemTimeWand 共用，不复制代码。

### 2.1 单次完整加速 `accelerateOnce(world, pos, speed)`

一次"加速爆发"包含三段（与 Time Bus 对单个方块的处理一致）：

1. `world.scheduleBlockUpdate(pos, block, 1, 0)` —— 调度一次方块更新（MC 按 (pos,block) 去重）
2. `runTileUpdates(...)` —— 对 tile 执行 `(speed-1)` 次加速调用
3. `runRandomTicks(...)` —— 对随机 tick 方块执行 `speed * 20` 次 `updateTick`

每个动作 try/catch 隔离，单个坏方块不会毁掉整批。

### 2.2 Tile 加速分派 `runTileUpdates(world, target, n, speed)`

按 tile 类型分派（**顺序很重要**）：

| 目标类型 | 处理方式 | 说明 |
|---|---|---|
| `appeng.tile.misc.TileCharger` | 反射调用 private `doWork()` | Charger 是网格驱动，无 ITickable |
| `appeng.tile.misc.TileInscriber` | `tickingRequest(null, max(1,speed))` | `ticksSinceLastCall` 参与进度推进 |
| `appeng.tile.crafting.TileMolecularAssembler` | `tickingRequest(null, max(1,speed))` | 同上，进度按 `ticksSinceLastCall×speedFactor` |
| `appeng.tile.misc.TileVibrationChamber` | `tickingRequest(null, max(1,speed))` | 发电机：更快烧燃料产电 |
| `appeng.tile.storage.TileIOPort` | `tickingRequest(null, 1)` | **忽略** ticksSinceLastCall，靠调用次数加速 |
| `net.minecraft.util.ITickable` | `tickingRequest` 之外的通用路径：`update()` | 普通机器 |

关键约定：
- `tickingRequest` 的 `node` 参数在 AE2 这些 tile 内部未被使用，传 `null` 安全
- 所有调用 try/catch，失败只记日志不中断
- 返回实际执行次数，供预算统计

### 2.3 工作预算（PartTimeBus）

`doWork()` 里用 `maxCallsPerTick`（默认 128）限制每 tick 的加速调用总数：

- 状态机：`PHASE_SCHEDULE → PHASE_TILE → PHASE_RANDOM`，按方块推进
- 超出预算的工作顺延到下一 tick（`workActive` 保持），保证服务端 tick 平滑
- 预算使用量通过 AE2 `@GuiSync` 同步到 GUI 显示

### 2.4 如何让 TimeBus 加速一种新机器

两处改动：

1. `AccelerateHelper.runTileUpdates` 加一个 `instanceof` 分支（照抄 IOPort/Inscriber 分支的模式）
2. `PartTimeBus` 的 PHASE_SCHEDULE 调度门加对应 `instanceof`（否则该 tile 会在调度阶段被跳过，预算不涨）

调度门当前识别：`ITickable`、`TileCharger`、`TileInscriber`、`TileMolecularAssembler`、`TileVibrationChamber`、`TileIOPort`。

## 3. 时间杖（ItemTimeWand）

`AEBasePoweredItem implements IStorageCell<IAEFluidStack>`：512 字节、1 类型、仅存时间流体、AE 供电。

### 3.1 交互

| 操作 | 行为 |
|---|---|
| 潜行右键方块 | 消耗 10 mB 时间流体 + 1000 AE，`accelerateOnce` 加速一次（speed 由加速卡决定） |
| 潜行右键 ME 输出/输入总线 | 消耗同量资源，执行总线 `tickingRequest` × `wandBatchSize`（默认 16）次 = 一次性批量传输 |
| cell workbench | 可放入加速卡（`Upgrades.SPEED` 已注册，上限 4） |
| 右键空气 | 无行为（后续可扩展） |

### 3.2 粒子

`spawnBurstParticles` 在 **客户端分支** 渲染（7 参 `World.spawnParticle` 只有客户端有效，服务端调用是空操作）。粒子从方块 6 个表面向外爆散（END_ROD 主体 + PORTAL 点缀），数量/速度已调小。

## 4. 时间流体（TimeBusFluids）

- 注册幂等：`TIME_FLUID_BLOCK != null` 时直接返回
- 归属校验：注册后确认 fluid 是本 mod 的实例，否则跳过方块创建（防崩溃）
- `BlockTimeFluid`：触碰给 `Speed III + Haste III`（200 tick），`setMaxScaledLight(15)` 满亮度发光
- 贴图：原版水材质（`water_still/water_flow` + mcmeta），在 `assets/timebus/textures/blocks/`

## 5. 配置项（TimeBusConfig）

| 配置 | 默认 | 说明 |
|---|---|---|
| `speedMultipliers` | `2,4,8,16,32` | 总线加速卡倍率（第 N 个 = N-1 张卡） |
| `idlePower` | 1.0 | 空闲耗电（AE/t） |
| `powerPerSpeed` | 0.5 | 每速度单位耗电倍率 |
| `fluidMode` / `fluidName` / `fluidPerTick` / `fluidConsumeMultiplier` / `minFluid` | - | 总线流体消耗模式 |
| `capacityWidths` | `1,3,9,15` | 容量卡宽度 |
| `maxCallsPerTick` | 128 | 工作预算（调用次数/ tick） |
| `matterBallUnit` / `singularityUnit` / `unitsPerBatch` / `timeFluidPerBatch` | 1/1000/64000/1000 | 发生器换算 |
| `wandSpeedMultipliers` | `2,4,8,16,32` | 时间杖加速卡倍率（独立于总线） |
| `wandFluidCost` / `wandEnergyCost` | 10 / 1000 | 时间杖单次消耗 |
| `wandBatchSize` | 16 | 总线批量传输的批次次数 |

配置界面已本地化（`config.timebus.*` lang keys，中英双语）。

## 6. 开发环境

- CleanroomLoader `0.5.17-alpha`，Unimined `1.4.26-kappa`，Java 25 toolchain
- 构建：`gradlew build`（产物在 `build/libs/timebus-1.0.0.jar`）
- 依赖：AE2 UEL（`curse.maven:ae2-extended-life-570458:6302098`）、JEI、The One Probe
- 注意：Cleanroom 的方法名与 MCP 不同（如 `Block.onEntityCollision` 而非 `onEntityCollidedWithBlock`），改动前先在 `F:\Mcmod\Git`（AE2 源码）或 jar 里确认签名

### 常见坑

- `srg2mcp.jar` 损坏导致 `remapJar` 失败：删 `F:\AiWork\TimeBus\.gradle\unimined\local\mappings\srg2mcp.jar` 重建
- PowerShell 写中文文件会乱码：用 `[System.IO.File]::WriteAllText(path, content, UTF8Encoding($false))` 或直接让工具写文件
- 服务端不能用 7 参 `spawnParticle`：粒子一律走客户端分支
