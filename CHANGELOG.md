# Changelog

> 说明：本仓库自 v1.0.4 起维护 CHANGELOG；更早版本（v1.0.0–v1.0.3）为开发期版本，未收录变更记录。

## v1.0.16

- Feature: **时间总线/减速总线黑白名单**——两条总线各自独立配置（`bus` / `slowBus`
  分类各一套）：按方块注册名过滤加速/减速目标（仅匹配方块本身、无视 NBT），支持
  `modid:*` 通配、裸名自动补 `minecraft:`、**lit 变体匹配**（熔炉点燃后
  `minecraft:lit_furnace` 同样命中名单）；`BLACKLIST`（名单内禁止）/ `WHITELIST`
  （仅名单内允许）两种模式 + 总开关；时间杖不受名单影响。新增
  `Block List Enabled` / `Block List Mode` / `Block List` 配置项（中英双语）
- Fix: Forge 1.12.2 配置 GUI 保存不生效（Cleanroom 环境 / 多 @Config 类共享单文件
  时 `shouldReadFromVar` 方向判定反向覆盖 GUI 修改）——配置同步改为**无条件
  Configuration→字段**（参考 Mekanism 方案），GUI 保存即时生效；新增 **cfg 文件
  热重载**——直接改 `timebus.cfg` 1 秒内生效，无需重启
- Fix: 黑白名单开启时名单外**随机刻方块**（作物/草蔓延/冰融化等）不再被随机刻
  加速（名单外方块整块跳过，不进 PHASE_RANDOM）
- Fix: 名单开启瞬间 PHASE_TILE 跨 tick 残留连拍——PHASE_TILE / PHASE_RANDOM
  进入时复查名单；名单 filter 缓存自校验（字段变化立即重建，不依赖事件失效）
- Perf: 减速 mixin / Mek 加速查询**全局短路**（全服无减速总线/无加速 Mek 机器时，
  热路径退化为一次 volatile 读，零同步查表）
- Perf: tile 分类结果缓存 + doWork 分类复用（同一 tick 不再重复走 instanceof 分类链）
- Perf: 流体耗尽退避（每 10 tick 才重试探测一次）、生成器缓存 AEApi materials 引用、
  减速总线空范围退避（前方无 ITickable 目标时降频扫描）

## v1.0.15 (beta)

- Feature: **时间减速总线（Time Slow Bus）**——新的 AE2 线缆部件，让时间"变慢"：
  正面一排 ITickable 方块每 N tick 才执行一次 update（速度卡档位默认
  `2,4,8,16,32` → 半速~1/32 速），覆盖熔炉/酿造台/漏斗等原版机器、普通
  模组机器与 MM/Mek 控制器；燃料与进度同步降频，总产出守恒；与时间总线/
  时间杖的加速互斥（减速优先）。新增 `slowBus` 配置组与专属 GUI
- Fix: 时间减速总线 GUI 打开崩溃——补注册 `FUZZY_MODE` 设置
  （ContainerUpgradeable 读取缺失即抛异常）
- Fix: MM 配方时长加速封顶 32x（`MAX_MM_EFFECTIVE_SPEED`）——多台总线/
  魔杖叠加或配置调大时，配方总时长被 `Math.round` 取整为 0/1 tick 导致
  "完成但不出货"、进度异常；能耗 modifier 与时长共用同一有效倍率，单次
  配方总耗电守恒不破坏
- Fix: `MixinWorldTileUpdate` 改为**早期 mixin**（最小 coremod +
  `timebus.early.mixin.json`），修复 World 核心类在 MOD 阶段已加载导致的
  MixinTargetAlreadyLoadedException / Re-entrance 启动崩溃
- Change: 时间减速总线外观改用 AE2 输入总线（import bus）原版结构——几何
  （主体 Z=0-2 + 三均匀阶梯）、纹理、物品模型三处全部同步；合成配方改为
  与时间总线相同布局，中间用 AE 破坏核心（Annihilation Core）

## v1.0.14

- Feature: **爆炸合成**——将奇点与铁锭/铁块（物品实体，丢在地上）置于爆炸波及范围，
  爆炸结算时自动转化为**时间压印模板**（机制参照 AE2 量子缠绕态奇点；不消耗 AE
  能量）。JEI 时间压印模板物品页显示合成方法文字说明。新增 `explosion` 配置组
  （Enabled / Singularity Cost / Iron Cost / Output Count，默认 1:1:1）
- Change: 移除时间压印模板的工作台合成（8 物质球 + 工程压印模板）；获得方式改为
  爆炸合成与压印机复制（模板 + 铁块，INSCRIBE）
- Change: 时间杖默认速度倍率改为 **32, 64, 128, 256, 512**（0 卡 = 32x）
- Fix: 时间杖对不可加速目标（石头、空线缆、时间总线本体等）不再空扣
  10 mB + 1000 AE，改为提示「该方块无法被加速」；MM 控制器在开关关闭时提示
  「MM 加速未开启」引导开配置
- Fix: 时间杖与 ME 导入/导出总线的互动从任意角度可用（`selectPartGlobal`
  精确命中定位，与预览高亮一致），斜向点击不再误报无法加速
- Fix: 时间杖对 MM 控制器的配方加速登记进独立追踪表，拆机/区块卸载/关服时
  兜底清理，加速状态不会残留进存档
- Perf: 时间杖单次点击工作量封顶（tile 更新 ≤ 512 次、随机刻 ≤ 2048 次），
  满配 512x 点击不会再造成单 tick 卡顿；极端配置不再 int 溢出
- Fix: 时间杖粒子爆发改由服务端广播（多人可见），客户端不再本地绘制
- Fix: 时间杖预览高亮与行为对齐——仅潜行显示、4.5 格交互距离、高亮所有
  可加速方块（熔炉/作物/机器/MM 控制器等），高亮 = 点击必有效果
- Fix: 时间杖流体单元容量显示溢出 clamp；`ActiveSpeedTable` 过期条目查询时清理
- Change: 移除废弃的 `MixinMekanismUtils` 死文件

## v1.0.13

> ⚠️ **配置重组**：配置文件改为按分类组织（`bus` / `timeGenerator` / `wand` / `mm` /
> `mek`）。旧版本平铺在 `general` 分类下的键不再被读取——**更新后请删除
> `run/client/config/timebus.cfg`（或重置配置）一次**，让配置文件按新结构重新生成。

- Feature: Time Bus / Time Wand now speed up Mekanism generators (wind / gas / bio /
  solar / advanced solar / heat / large multiblock) — while accelerated they insert
  N times more energy per tick without extra fuel, pure free power generation
  (intentionally unbalanced; new `Generator Acceleration Enabled` option, default true)
- Fix: generator boost no longer swallows the energy insert (the @Redirect handler
  now performs the real container insert)
- Fix: Time Bus GUI speed / range / power / fluid rate now sync from the server-side
  part, so the display updates immediately when upgrades are inserted
- Change: config fields and descriptions are localized (Chinese when the game
  language is Chinese, English otherwise)
- Test: JUnit skeleton with 16 passing tests (config parsing, active speed table,
  index fallback)
- Chore: README version badge is now dynamic, mod metadata + Forge update JSON added,
  code cleanup, upgrade-count caching, javadoc jar enabled

## v1.0.12

- Change: Mekanism acceleration now works by re-running the machine's recipe
  tick multiple times per server tick instead of injecting virtual speed
  cards - Mek CE caches `ticksRequired` and only recomputes it on upgrade
  recalculation, so virtual speed cards had no effect while a machine was
  running. The new approach advances N ticks of progress per tick (per-tick
  energy xN, total energy per recipe unchanged) and is injected at the shared
  RecipeCacheLookupMonitor layer, covering every recipe-driven machine
  (ElectricMachine family, chance/double/advanced machines, chemical
  machines, PRC, metallurgic infuser, factories per slot, rotary
  condensentrator, solar neutron activator, ambient accumulator, thermal
  evaporation controller; generators and instant-conversion machines are
  excluded)
- Change: Mek machine GUIs now report the accelerated per-tick energy draw
  (base energyPerTick x speed) via Mek's own sync channel - display only, the
  actual power drain is untouched
- Fix: MM restore logs no longer spam - the "restored" log now counts actual
  removed modifiers and is debug-level, so periodic restore calls (e.g. every
  20 ticks while a bus is unpowered) stay silent unless something was really
  removed

- Fix: redstone / power loss now stops MM acceleration immediately - the Time
  Bus used to leave its injected duration/energy modifiers on MM controllers
  when it went to sleep (HIGH_SIGNAL with no signal, LOW_SIGNAL with signal,
  a finished pulse batch, or lost power), so machines kept running accelerated
  even though the bus was idle. Every stop path now restores the bus's own
  modifiers (multi-bus stacking is unaffected - only this source's keys are
  removed), and `restore()` also clears the applied-state snapshot so
  re-acceleration works as soon as the redstone signal returns

- Fix: Random Complement compatibility for the inscriber - RC's `setStackInSlot`
  redirect shrinks the same stack one more time, so the inscriber used to eat
  one extra input per finished recipe; with a single input left it also stopped
  consuming and kept producing forever. Consumption is now compensated when RC
  is loaded (parallel-card batches stay exact, single items are consumed and
  the machine stops), and behavior is unchanged without RC

## v1.0.11

- Fix: vanilla Forge 1.12.2 clients can now load the mod - removed the
  Cleanroom-only `ModType: CRL` manifest attribute (vanilla Forge treats an
  unknown ModType as "not an FML mod" and silently skips the jar) and rebuilt
  with a Java 8 target (class 52) so Forge's ASM 5.2 mod discovery accepts it;
  previously the client never had the mod in its list, so Cleanroom servers
  rejected the connection with "mod is not found on client"

## v1.0.10

- Fix: mod version is now sourced from `gradle.properties` via the `Reference`
  template - `TimeBus.java` previously hardcoded 1.0.5, so servers reported
  "Requires version 1.0.5" (and clients without the mod were rejected) even
  though the jar file was named 1.0.9
- Mekanism: virtual speed cards now use the real max installed count
  (`MEKCEConfig.MAXSpeedUpgrade`, default 8, configurable) instead of a
  hardcoded 8, and are rounded up (`ceil`) so the actual speed-up is at least
  the configured multiplier (previously 2x could resolve to only ~1.78x)
- Mekanism: added verification logs (config resolve / registration / mixin hit)
  to be removed after validation
- Time Wand: left a TODO placeholder for future Mek recipe acceleration

## v1.0.9

Code-review fixes (2026-08-09 review report):

- Fix: multiple Time Buses on the same cable no longer overwrite / wrongly
  clear each other's MM acceleration - the modifier source key now includes the
  part side; legacy side-less bus modifiers are purged automatically on upgrade
- Fix: the Time Wand no longer leaves MM controllers permanently accelerated -
  a wand click now accelerates the currently running recipe until it finishes
  via MM's semi-permanent modifiers (recipe duration x1/speed, per-tick energy
  x speed when `MM Energy Cost Follows Speed` is on); MM auto-restores normal
  speed when the recipe finishes or fails, so nothing is written permanently
  into the save, and idle machines are not charged
- Fix: injected MM modifiers are cleaned up when a single chunk unloads (they
  were previously written into the save and survived a server restart)
- Perf: MM acceleration skips the per-tick reflection sweep in steady state
  (state snapshot + the existing periodic force-refresh self-heals within one
  `MM Context Refresh Interval`)
- Perf: `TileCharger.doWork()` switched from cached reflection to a Mixin
  `@Invoker`; the Time Bus source key string is now lazily cached
- Balance: wand bus-batch size is capped at 256 (config range + runtime clamp);
  random-tick acceleration per speed unit is now configurable
  (`Random Tick Calls per Speed Unit`, default 20)
- Fix: Time Wand fluid capacity display was 8x too small (AE2EL stores
  8000 mB per byte, not 1000)
- Docs: corrected the `Power Cost per Speed Unit` comment to match the actual
  formula; updated wand byte/mB documentation; debug logs for swallowed config
  exceptions; MM state caches are cleaned up on restore

## v1.0.8

- MM acceleration: energy cost now follows the speed-up (new `MM Energy Cost Follows Speed`, default true) - per-tick energy consumption and production are multiplied by the same factor as the duration compression, so total energy per recipe stays constant while the output rate increases
- MM acceleration: fixed threads staying unaccelerated after MM recycles its pooled recipe crafting contexts (the modifier data was correct but the actual context had lost it) - the Time Bus now force re-applies modifiers every `MM Context Refresh Interval` ticks (default 20 = about 1 second, 0 disables), self-healing within one interval

## v1.0.7

- Time Fluid Generator: consumes up to 64 inputs per update (configurable via `Inputs Consumed per Update`), so Time Bus acceleration now actually speeds it up; the input slot stacks up to 64
- MM acceleration: the injected duration modifier now targets `REQUIREMENT_DURATION` (previously null), which makes acceleration actually take effect and fixes a client crash (`Could not find requirementType`) caused by serializing a modifier with an empty target
- MM acceleration: removed the reflection-caching fast path - MM recycles idle factory threads (`invalidate()` clears permanent modifiers), which could leave threads permanently unaccelerated; back to per-tick verification
- MM factory controllers: new `MM Keep Idle Threads` option (default true) - accelerated controllers skip the 200-tick idle-thread recycle so threads stay accelerated
- dev: added Mekanism-CE-Unofficial runtime dependency for compatibility testing

## v1.0.6

- MM acceleration now stacks: each Time Bus / wand injects its own per-source
  duration modifier, and MM multiplies them together (8x + 4x = 32x); removing
  a bus restores its modifier (previously it stayed on the machine and was
  even saved into the world save)
- MM acceleration also supports factory controllers (TileFactoryController)
- dev toolchain: fixed a Groovy quirk in runClient extra JVM args handling
  (`split { "\\s+" }` coerced the result into a char list and corrupted the
  javaagent command line)
- docs: DEVELOPER.md / README (EN/中文) updated

Code-review fixes (14-item review of v1.0.5):

- Time Bus: pulse mode no longer truncates large acceleration batches - the
  device stays awake until the whole batch finishes, then sleeps again, so a
  single redstone pulse completes a fully-upgraded run instead of ~1%
- Time Bus: fluid billing is now aligned with what was actually drained
  (partial extraction carries the remainder over instead of silently
  over-billing)
- Time Bus: unloaded chunks in the acceleration range are skipped cheaply
  instead of forcing synchronous disk loads on chunk borders
- Time Bus: per-tick config string parsing removed - speedMultipliers,
  capacityWidths and wandSpeedMultipliers are parsed once and cached
  (invalidated on config change); kept as strings so existing cfg files
  migrate cleanly
- Time Bus: fluid and storage-channel lookups in fluid mode are cached and
  re-resolved only when the configured fluid name changes
- Time Generator: an empty input slot is polled every 10 ticks instead of
  every tick; TRASH mode (if forced externally) consumes the input instead of
  leaving it stuck in the slot
- Acceleration: AE2 machine classification unified into AccelerateHelper's
  TileKind, so the bus scheduling gate and the update dispatcher can never
  drift apart
- Mixins: removed the dead TimeBusMixinConfigPlugin and leftover TEMP DIAG
  comments
- Build: removed hardcoded local JDK paths from gradle.properties (JAVA_HOME
  for Gradle 17, JDK25 env var for the compile toolchain); disabled the unused
  JUnit test configuration

## v1.0.5

Modular Machinery acceleration, wand fixes and dev-env improvements:

- Time Wand: emptying into the Time Fluid Generator no longer destroys the fluid (the fill is simulated first; output-only tank rejections are a no-op)
- Time Wand: cell size is now configurable via wandBytes (default 512, 1 byte = 1000 mB)
- Modular Machinery (CE) acceleration: restricted-tick machines are sped up by compressing the recipe duration in MM's own modifier system instead of calling update() (which their anti-acceleration design blocks); the multiplier follows the bus's speed cards (0 = 2x, 4 = 32x), opt-in via mmAccelerationEnabled, per-batch material/energy cost unchanged
- Dev environment: CraftTweaker FMLAT entries merged into timebus_at.cfg so CraftTweaker loads under Cleanroom dev + Java 25
- docs: DEVELOPER.md updated (MM acceleration, config table, dev dependencies)

## v1.0.4

Code-review fixes and UX improvements:

- Inscriber mixin hardening: consume step guards against an uncomputed batch (no item duplication), and scaled output is clamped to the item stack limit (no item loss)
- Acceleration: random-tick acceleration now calls Block.randomTick, so grass spread, ice melt, snow, and torch burnout are accelerated too
- Time Wand: no more fake particle feedback - the client prechecks resources, the server reports exactly what is missing (energy / fluid / bus idle), and batch transfer only charges when the bus actually moves items
- Time Bus: idle buses back off to a slower tick rate instead of scanning every tick
- lang files: fixed tracked file case (en_us / zh_cn), added new message keys
- docs: README acceleration semantics table, per-bus budget wording, 0-card 2x note; MODRINTH description

Requires: Minecraft 1.12.2 + CleanroomLoader + AE2 UEL
