# 时间总线（Time Bus）

[English](README.md) | [中文](README_zh.md)

[![Release](https://img.shields.io/github/v/tag/zhenzi233/TimeBus)](https://github.com/zhenzi233/TimeBus/releases)

**Applied Energistics 2** 附属模组，适用于 Minecraft 1.12.2（CleanroomLoader）。核心主题是**时间操控**：加速方块与 AE2 机器、生产并存储特殊的**时间流体**，以及用**时间杖**随时加速方块、对 ME 总线进行批量传输。

## 功能

### 时间总线（Time Bus，ME 线缆部件）

- **时间加速**：加速总线前方一排方块（方块更新、方块实体、随机刻）。
- **AE2 设备加速**：同样支持 AE2 的网格驱动（grid-ticked）机器：
  - **充能器**（Charger，通过其私有 `doWork()` 调用）、**压印机**（Inscriber）、**分子装配机**（Molecular Assembler）、**震动室**（Vibration Chamber）、**IO 端口**（IO Port）。
- **速度升级卡**：最多 4 张速度卡，每张翻倍加速倍率（默认 `2,4,8,16,32x`）。
- **容量升级卡**：最多 3 张容量卡，扩大加速范围（默认 `1,3,9,15` 格）。
- **红石控制**：高电平 / 低电平 / 脉冲模式（需要 1 张红石卡）；红石条件不满足或断电时立即停止加速（含 MM 机器，恢复信号后自动恢复）。
- **模糊模式**：支持 AE2 模糊卡。
- **两种供能方式**：
  - **AE 能量**（默认）：加速时从 ME 网络消耗 AE 能量。
  - **流体模式**（可配置）：改为从 ME 网络消耗流体（自带 `time_fluid` 时间流体，也可配置为任意已注册流体）。
- **性能安全**：工作预算限制每服务器 tick 的加速调用次数（`maxCallsPerTick`，默认 128），满配总线也不会造成 tick 卡顿；超额工作顺延到下一 tick。
- **黑白名单**（可配置）：按方块注册名过滤加速目标（无视 NBT）——`BLACKLIST` 名单内禁止加速 / `WHITELIST` 仅名单内允许；支持 `modid:*` 通配与 lit 变体（熔炉点燃后同样命中）；配置 `bus.Block List Enabled / Mode / List`，改配置或直接编辑 cfg 文件即时生效。
- **GUI**：显示当前速度、范围、耗电与实时工作预算占用。

### 时间减速总线（Time Slow Bus，ME 线缆部件）

让时间"变慢"的对应物：加速总线正面一排方块的**反向**——每 N tick 才执行一次方块更新：

- **降频式减速**：目标方块的 `ITickable.update()` 每 N tick 才调用一次（N = 速度卡档位，默认 `2,4,8,16,32` → 半速、四分之一速……1/32 速）。熔炉/酿造台/漏斗等原版机器、绝大多数普通模组机器，以及 MM/Mek 控制器（受限 tick 机器）全部有效；燃料与进度同步降频，总产出守恒，语义与加速对称。
- **速度升级卡**：最多 4 张，每张把档位翻倍（每 N tick 一次）。
- **容量升级卡**：最多 3 张，扩大减速范围（与时间总线共用 `1,3,9,15` 格配置）。
- **红石控制**：高电平 / 低电平 / 脉冲模式（需要 1 张红石卡）；条件不满足或断电时停止登记，方块在 10 tick 内自动恢复原速。
- **与加速互斥**：同一方块被减速时，时间总线/时间杖的加速让路（减速优先），不会互相抵消。
- **黑白名单**（可配置，与时间总线独立）：按方块注册名过滤减速目标（无视 NBT，支持 `modid:*` 通配与 lit 变体）；配置 `slowBus.Block List Enabled / Mode / List`。
- **耗电**：空闲 + 每单位减速档位的 AE 能量，从 ME 网络消耗。
- **GUI**：显示当前减速档位、范围与耗电。
- **合成**：与时间总线相同布局（2 时间处理器 + 物质球 + 活塞），中间用 AE 破坏核心（Annihilation Core）。
- 已知边界：AE2 网格驱动的机器（压印机/分子装配机/IO 端口等，走 AE2 grid-tick）不在减速范围。

### 时间流体（Time Fluid）

- 自带流体 **`time_fluid`（时间流体）**，可通过 ME 网络存储与抽取。
- **发出满级光照**（亮度 15）。
- 生物进入时间流体方块后，会获得 **速度 III + 急迫 III，持续 10 秒** 的药水效果。
- 配合调试棒快速生成（见下）。

### 调试棒（Debug Wand）

- 手持**调试棒**对方块或空气右键，即可生成一个时间流体源块，方便测试与调试。

### 时间流体发生器（Time Fluid Generator）

一个类似 AE2 物质冷凝器的方块，将材料转化为时间流体：

- **输入**：物质球（Matter Ball）或奇点（Singularity），通过 GUI 按钮切换输入模式（仅两态，不含销毁模式）。
- **换算**（可配置，默认）：
  - 64000 个物质球 = 1000 mB 时间流体
  - 64 个奇点 = 1000 mB 时间流体（1 奇点 = 1000 物质球当量）
- **进度保留**：统一按"当量"累计进度，中途切换输入模式不会丢失已投入的进度；取出存储组件时进度条隐藏但机器内进度仍在，放回后继续。
- **存储组件**：放入 AE2 存储组件（如 1k/4k/16k 元件）决定流体缓冲区上限。
- **GUI**：垂直进度条显示生产进度（悬停可查看提示），流体槽实时显示存量（有流体即铺满，右下角显示桶当量数字），悬停显示名称与 mB 数量。
- **装瓶/倒瓶**：手持流体容器（桶、AE2 便携流体单元等）点击流体槽，可装取或倒入时间流体。
- **不消耗 AE 能量**：纯材料转换，无需接电。

### 时间杖（Time Wand）

一把使用 AE 能量、容量 **512 字节**、**仅能存储时间流体**的流体存储单元（物品继承参照 AE2 物质炮 Matter Cannon）：

- **潜行 + 右键方块**：加速一次，消耗杖内 **10 mB 时间流体 + 1000 AE**。速度取决于通过 AE2 单元工作台（Cell Workbench）插入的速度卡，倍率走独立的 `wandSpeedMultipliers` 配置（默认 `32,64,128,256,512`，0 卡 = 32x）。
  - **对 MM（Modular Machinery）控制器**（需开启 `MM Acceleration Enabled`）：点击后**当前正在运行的配方以倍率加速直到完成**——配方时长缩短为 1/倍率，每 tick 耗电/产出按 `MM Energy Cost Follows Speed` 配置相应放大（单次配方总耗电守恒）；配方完成（或失败）后自动恢复原速，不留下任何持久状态；机器空闲时点击不消耗资源并提示。**注意：MM 路径的倍率跟随时间总线的 `Speed Multipliers`（0 卡 = 2x…满配 = 32x），不是魔杖自己的 32-512x。**
- **右键 ME 输入/输出总线**：执行**批量传输**——消耗同样的成本，然后连续运行总线工作 `wandBatchSize`（默认 16）次，一次点击即可把多组物品传入/传出 ME 网络；伴随粒子爆散特效（服务端执行并广播粒子，周围玩家可见）。
- **流体终端 / GUI 互动**：可作为普通流体容器使用——从储罐装取、倒入机器，物品栏提示栏也会显示常规的 字节/类型 占用信息。

### 时间材料（v1.0.1）

新增三个时间科技材料，全部由压印机（Inscriber）加工：

- **时间压印模板**（Time Inscriber Template）：可通过**爆炸合成**获得——将奇点与铁锭（或铁块）丢在地上，引爆炸药，即转化为时间压印模板（机制参照 AE2 量子缠绕态奇点，见下方配置）；也可在压印机内用「模板（不消耗，上）+ 铁块（中）」复制（INSCRIBE）。
- **时间电路板**（Time Circuit Board）：压印机「物质球（中）+ 时间压印模板（上）」，动画材质。
- **时间处理器**（Time Processor）：压印机「红石（中）+ 时间电路板（上）+ 硅板（下，PRESS）」，动画材质。

用于合成时间总线、时间流体发生器、时间杖、机器并行卡等。

### 机器并行卡（Machine Parallel Card）

- 压印机升级卡：每张卡使每次完成**额外消耗 1 份材料、额外产出 1 份产品**（最多 4 张 → 5 份）。
- 压印机三个槽（上/下印模、中间材料）均支持堆叠 64。
- 工作流程、进度条、动画保持原版，仅消耗/产出按并行数缩放。
- 无序合成：高级卡 + 时间处理器。

## 环境要求

- Minecraft 1.12.2
- CleanroomLoader（`0.5.17-alpha` 或兼容版本）
- AE2 Extended Life（`rv6-stable-7` 或更新）
- Java 17（Gradle 运行）+ Java 25（编译工具链）


## 构建

```bat
gradlew.bat build
```

产物输出到 `build/libs/`：

- `timebus-<version>.jar` — 发布版（已重映射）
- `timebus-<version>-dev.jar` — 开发版
- `timebus-<version>-sources.jar` — 源码

（`<version>` 对应 `gradle.properties` 中的 `mod_version`，以实际发布版本为准。）

预编译 jar 已发布在 [Releases](https://github.com/zhenzi233/TimeBus/releases) 页面。

## 配置

> ⚠️ v1.0.13：配置文件改为按分类组织（`bus` / `timeGenerator` / `wand` / `mm` /
> `mek`）。旧版本的配置键不再被读取——**更新后请删除
> `run/client/config/timebus.cfg` 一次**，让其按新结构重新生成。

运行时会生成配置文件 `run/client/config/timebus.cfg`（Forge 配置，已通过 `config.timebus.*` lang 键本地化为中英双语）：

### 时间总线

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `Speed Multipliers` | `2,4,8,16,32` | 每张速度卡的加速倍率（逗号分隔） |
| `Capacity Widths` | `1,3,9,15` | 每张容量卡的加速宽度（逗号分隔） |
| `Idle Power Draw` | `1.0` | 空闲时每 tick 消耗的 AE 能量 |
| `Power Cost per Speed Unit` | `0.5` | 每单位速度的 AE 能耗倍率 |
| `Fluid Mode Enabled` | `false` | 是否使用流体代替 AE 能量 |
| `Consumed Fluid` | `water` | 消耗的流体注册名 |
| `Fluid per Tick` | `1.0` | 每 tick 消耗的 mB |
| `Fluid Consumption Multiplier` | `2.0` | 每张速度卡的流体消耗倍率 |
| `Minimum Fluid` | `1000` | ME 网络中至少保留多少 mB 才启动 |
| `Max Calls per Tick` | `128` | 工作预算（每服务器 tick 的加速调用次数） |
| `Random Tick Calls per Speed Unit` | `20` | 每个速度单位对随机刻方块执行的 Block.randomTick 调用次数（32x 时每方块 640 次；若随机刻加速挤占预算可调低） |
| `Block List Enabled` | `false` | 方块黑白名单开关：开启后按方块注册名过滤加速目标（仅按方块本身匹配，无视方块 NBT） |
| `Block List Mode` | `BLACKLIST` | 名单模式：`BLACKLIST` = 名单内的方块禁止被加速；`WHITELIST` = 只有名单内的方块会被加速 |
| `Block List` | 空 | 方块注册名列表（逗号分隔，支持 `modid:*` 通配，无命名空间的名称默认补 `minecraft:`），如 `minecraft:furnace,minecraft:chest` 或 `appliedenergistics2:*,mekanism:*` |

### 时间减速总线

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `Slowdown Multipliers` | `2,4,8,16,32` | 每张速度卡的减速档位（逗号分隔）：第 N 个值 = N-1 张卡时每多少 tick 执行一次 update（0 卡 = 半速，满配 = 1/32 速） |
| `Idle Power Draw` | `1.0` | 空闲时每 tick 消耗的 AE 能量 |
| `Power Cost per Slowdown Unit` | `0.5` | 每单位减速档位的 AE 能耗倍率 |
| `Block List Enabled` | `false` | 方块黑白名单开关：开启后按方块注册名过滤减速目标（仅按方块本身匹配，无视方块 NBT） |
| `Block List Mode` | `BLACKLIST` | 名单模式：`BLACKLIST` = 名单内的方块禁止被减速；`WHITELIST` = 只有名单内的方块会被减速 |
| `Block List` | 空 | 方块注册名列表（逗号分隔，支持 `modid:*` 通配，无命名空间的名称默认补 `minecraft:`） |

### 时间流体发生器

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `Matter Ball Unit Value` | `1` | 每个物质球贡献的当量 |
| `Singularity Unit Value` | `1000` | 每个奇点贡献的当量 |
| `Units per Batch` | `64000` | 产出一批时间流体所需的当量（64000 球或 64 奇点） |
| `Time Fluid per Batch` | `1000` | 每批产出的时间流体 mB |
| `Inputs Consumed per Update` | `64` | 每次 update 消费的输入数量（总线加速时吞吐随加速线性提升；槽位本身始终可堆叠 64 个） |

### 时间杖

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `Wand Cell Size (Bytes)` | `512` | 时间杖流体存储单元的容量（AE 字节，1 字节 = 8000 mB） |
| `Wand Speed Multipliers` | `32,64,128,256,512` | 每张速度卡的时间杖倍率（逗号分隔） |
| `Wand Fluid Cost` | `10` | 每次使用消耗的时间流体 mB |
| `Wand Energy Cost` | `1000` | 每次使用消耗的 AE 能量 |
| `Wand Batch Size` | `16` | 右键输入/输出总线时连续运行的总线工作次数 |

### Modular Machinery（可选）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `MM Acceleration Enabled` | `false` | 是否压缩 MM 控制器配方时长来加速（Mek 类受限 tick 机器不适用） |
| `MM Keep Idle Threads` | `true` | 被时间总线加速的工厂控制器是否保留空闲线程：关闭 MM 的 200 tick 线程回收，避免回收清掉加速 modifier 导致线程短暂跌回原速 |
| `MM Energy Cost Follows Speed` | `true` | 加速时每 tick 能耗（消耗与产出）按加速倍数放大：时长缩短 N 倍、能耗放大 N 倍，单次配方总耗电/总产出守恒 |
| `MM Context Refresh Interval` | `20` | MM context 池化脱节的自愈间隔（tick）：每 N tick 强制重注入 modifier 触发 MM 刷新，20 ≈ 1 秒；0 = 关闭（线程可能保持不加速直到速度变化） |

### Mekanism（可选）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `Mek Acceleration Enabled` | `false` | 是否连拍加速 Mek 机器：每 tick 额外推进 N 刻配方进度（N=总线速度倍率，每 tick 能耗同倍放大、单次配方总耗电守恒；覆盖所有基于配方缓存推进的加工机器，含工厂/化学机器/PRC 等，发电机与即时转换类机器除外） |
| `Generator Acceleration Enabled` | `true` | 是否加速 Mek 发电机（风力/燃气/生物/太阳能/先进太阳能/热力/大型多模块）：加速时每 tick 多产 N 倍电（N=总线速度倍率）且不额外消耗燃料——纯开挂产电、能量不守恒，独立于上面的连拍开关 |

> 已知偏差：反质子核合成机（Antiprotonic Nucleosynthesizer）的配方监视器自身
> 会额外推进一次，实际加速为 N+1 倍、每 tick 能耗略高（约多 1/(N+1)），属
> 预期内的已知行为。

### 爆炸合成（Explosion Synthesis）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `Enabled` | `true` | 是否启用爆炸合成：奇点与铁锭/铁块（物品实体，丢在地上）同时被爆炸波及时转化为时间压印模板 |
| `Singularity Cost` | `1` | 每次合成消耗的奇点数量 |
| `Iron Cost` | `1` | 每次合成消耗的铁数量（铁锭或铁块均计入，ore dict `ingotIron`/`blockIron`） |
| `Output Count` | `1` | 每次合成产出的时间压印模板数量 |

用法：将奇点与铁锭（或铁块）**丢在地上**（物品实体），用 TNT、苦力怕等任意爆炸引爆——爆炸结算时自动转化，产出时间压印模板。JEI 中查看时间压印模板的物品页可见此合成方法说明。

## 使用方式

1. 将时间总线放置在 AE2 线缆/总线网络的任意一侧。
2. 将总线正面朝向要加速的方块（如熔炉、作物、机器，以及 AE2 充能器/压印机）。
3. 通过 GUI（右键总线）插入速度 / 容量 / 红石 / 模糊升级卡。
4. 从 ME 网络供电（或配置流体模式）。
5. 在充能器/流体储罐处为时间杖充能并装满时间流体，之后潜行右键方块即可随时加速。

### 时间流体发生器

1. 放置时间流体发生器方块。
2. 放入一个存储组件（决定流体上限）。
3. 在输入槽放入物质球或奇点（用 GUI 按钮切换输入类型）。
4. 攒够当量后自动产出时间流体，用桶或流体单元接取。

## 开发

- 构建工具链：[Unimined](https://github.com/wagyourtail/Unimined) + CleanroomFG3
- Mixin：Cleanroom sponge-mixin（`0.8.7`）
- IDE 运行配置自动生成为 `1. Build`、`2. Run Client`、`3. Run Server`（IntelliJ Gradle 任务）
- 架构与扩展说明见 [DEVELOPER.md](DEVELOPER.md)

## 贡献者

- **zhenzi233** — 作者、设计、测试
- **Codewhale** — AI 编程辅助（崩溃修复、工作预算性能模型、GUI 预算同步、AE2 集成、构建与发布验证）

## 许可证

MIT © zhenzi233
