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
│   ├── ItemTimeWand.java   # 时间杖：存储单元 + 右键加速/总线批量
│   ├── ItemMachineParallelCard.java  # 机器并行卡（压印机并行，见 §6）
│   └── TimeBusCreativeTab.java       # 本 mod 创造 Tab（itemGroup.timebus）
├── mixin/mod/              # 混入 AE2 类的 Mixin（见 §6.3 的坑）
│   ├── MixinUpgradeInvFilter.java    # 升级槽放行机器并行卡
│   └── MixinTileInscriber.java       # 压印机并行逻辑（N+1 消耗/产出）
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

## 6. 机器并行卡（Machine Parallel Card）与压印机堆叠支持

### 6.1 功能

机器并行卡（`ItemMachineParallelCard`，注册名 `machine_parallel_card`）作用于 **AE2 压印机（Inscriber）**：

- **三个槽都支持堆叠**：上印模槽、下印模槽、材料输入槽的 maxStack 全部提升到 64（原版都是 1）
- **无并行卡**：堆叠材料可被原版逻辑连续处理——每次完成**三槽各消耗 1 个、产出 1 份**；配方无效时压印机不 tick、进度条不空转
- **有并行卡**：升级槽中每 1 张卡 → 每次完成**三槽各多消耗 1 个、额外产出 1 份**（N 张卡 → 每槽消耗 (N+1) 份、产出 (N+1) 份），**批次受三槽中最少者限制**；工作流程（进度条、冲压动画、耗电）与原版**完全一致**，只改完成时的消耗/产出
- 卡片是普通物品（不实现 `IUpgradeModule`），通过 mixin 放行进升级槽

### 6.2 实现（Mixin 混入 AE2）

`mixin/mod/MixinTileInscriber.java` 混入 `appeng.tile.misc.TileInscriber`，共 5 个注入点：

1. `@Inject(<init> TAIL)`：`topItemHandler / bottomItemHandler / sideItemHandler` 三个槽 `setMaxStackSize(0, 64)`
2. `@Redirect(getTask 三参精确描述符)`：`getTask` 内所有 `ItemStack.getCount()` 调用返回 1（绕过原版 `count > 1` 单物品限制，RandomComplement 同款思路）——这是"无卡堆叠也能处理"的关键
3. `@Inject(hasWork HEAD, cancellable)`：输入非空**且配方有效**时返回 true——原版 `hasWork()` 会因堆叠输入返回 false，导致 AE2 网格调度器让压印机 sleep、`tickingRequest` 不再被调；同时必须检查配方有效，否则无效配方也会保持 tick 空转进度
4. `@ModifyArg(tickingRequest setStackInSlot ordinal 0/1/2)`：原版完成阶段用 `setStackInSlot(0, ItemStack.EMPTY)` **清空整个槽**（假设只有 1 个物品），绕过 count 限制后会把整个堆叠吞掉只产 1 个——改为**消耗 batch 个、剩余保留**（top / bottom / side 三处，保持原版 PRESS/INScribe 的条件语义）
5. `@ModifyArg(tickingRequest insertItem ordinal 0/1)`：产出 ×batch（smash 完成实际插入 + 输出槽预检模拟两处）
6. **batch 缓存**：`@Unique timebus$batch` 字段 + `@Inject(tickingRequest HEAD)` 每 tick 重置；`scaleOutput`（完成序列最先执行，此时三槽未扣）一次性计算 `batch = min(N+1, 三槽数量, 空槽跳过)`，三个 consume handler **复用缓存**——绝不能在每个 handler 里重算（前一个 handler 消耗后，后续算出的 batch 会变 → 消耗量错乱）

`mixin/mod/MixinUpgradeInvFilter.java` 混入 `appeng/parts/automation/UpgradeInventory$UpgradeInvFilter`，`@Inject(allowInsert HEAD, cancellable)` 放行 `ItemMachineParallelCard`（`AppEngInternalInventory.insertItem / isItemValidForSlot` 都走 `filter.allowInsert`）。

### 6.3 Cleanroom 混入 mod 类（AE2）的坑（重要，踩过一遍）

1. **用 `@env(MOD)` 配置 + jar manifest `MixinConfigs`**。`ILateMixinLoader` 已废弃（Cleanroom fork 了 Mixin），别用；也别把 mod 类放进 `@env(DEFAULT)` 配置（早期阶段看不到 mod 类，会让 AE2 加载时 `ClassNotFound`）
2. **dev 模式加载靠 `-Dcrl.dev.mixin`**：IDE 运行配置（`runConfigurations/+runClient.xml`）的 VM 参数必须带 `-Dcrl.dev.mixin=timebus.default.mixin.json,timebus.mod.mixin.json`，否则 mixin **静默不加载**（不崩、不生效、断点全不触发）；发布 jar 走 manifest 不需要它
3. **`@Redirect` 的 `method` 必须写精确描述符**：`method = "getTask"` 会匹配无参 + 三参两个 overload，无参 `getTask()` 体内没有 `getCount()` 调用，导致注入静默失效（三参的也不生效）——要写 `"getTask(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;)Lappeng/api/features/IInscriberRecipe;"`
4. **`@At` 的 target 要显式 `remap = false`**（AE2 是外部 mod，方法名是 MCP 名，不在 MC 映射 refmap 里；直接 mcp 名匹配 dev 字节码）
5. **`@Shadow` 只认目标类自身声明的方法/字段**——继承方法（`getProxy`、`extractAEPower`、`saveChanges`、`markForUpdate`）不要 `@Shadow`，用 `((TileInscriber)(Object)this)` 强转调用
6. **`@Invoker` 用 `org.spongepowered.asm.mixin.gen.Invoker`**（sponge-mixin 移除了 `injection.Invoker`）；构造 `@Redirect` 非法（0.8.7 禁止），构造改动用 `@Inject(<init> TAIL)`
7. **AE2 网格调度靠 `hasWork()` 决定是否 tick**：原版 `hasWork()` 内部用无参 `getTask()`（受 count>1 限制）——堆叠输入时返回 false → 压印机 sleep 不处理。注入 `hasWork` 且**必须校验配方有效**（无条件 true 会让无效配方也空转进度条）
8. **原版完成逻辑清空整个输入槽**：`setStackInSlot(0, ItemStack.EMPTY)` 假设槽里只有 1 个物品——绕过 count 限制后必须用 `@ModifyArg`（按 ordinal 定位 top/bottom/side 三处）改为"消耗 1 个、剩余保留"，否则堆叠材料被一次吞光只产 1 个
9. **IDE 断点在 mixin 方法上不生效**：mixin 运行时把注入方法重命名（日志里可见 `handler$zzb000$main$timebus$xxx`）且行号表偏移——用 `System.out.println` 日志验证执行，不要依赖断点
10. **mixin 配置的 plugin 类必须在自己配置的包下**（跨配置引用 = 包归属违规 → `required:true` 时直接崩）——本 mod 的 mod 配置已去掉 plugin
11. **`@Inject` 注入到非 void 方法，handler 必须用 `CallbackInfoReturnable<T>`**（即使不 cancellable）——用 `CallbackInfo` 会 `InvalidInjectionException: CallbackInfoReturnable is required` → **mixin 应用失败 → 目标类加载失败 → 宿主 mod（AE2）`NoClassDefFoundError` 崩溃**（AE2 自己 preInit 引用 TileInscriber 时爆）
12. **多个 `@ModifyArg` 共享的派生值必须缓存**：batch（批次大小）在完成开始时算一次（`@Unique` 字段 + `@Inject(HEAD)` 每 tick 重置），**不要在**每个 handler 里重算——前面的 handler 消耗槽位后，后续 handler 算出的 batch 会变（空槽被跳过、数量变小），导致消耗量错乱（日志曾见 `bottom=-1` 后 batch 从 1 膨胀到 3，中间槽多扣）
13. **附属功能优先"叠加"而非"接管"**：改原版逻辑尽量只动"完成点"（`@ModifyArg` 改参数），不要整体接管 `tickingRequest`——接管会破坏原版进度条/冲压动画/耗电语义，且"1 tick 一批"太快无法观察；正确姿势是"工作流程跟原版一致，只有消耗原料和产出有变动"
14. **PowerShell 写含 `$` 的代码会被吞变量**：`timebus$batch` 在双引号 here-string 里变成 `timebus`（`$batch` 被当变量替换为空）→ 字段/方法名损坏 → 编译报"找不到符号"——写含 `$` 的 Java 代码用 File 工具直接写，或 PowerShell 单引号字符串（不转义 `$`），写完必须验证

### 6.4 创造 Tab

`item/TimeBusCreativeTab.java`：本 mod 所有物品（Time Bus、Debug Wand、Time Wand、Machine Parallel Card、Time Fluid Generator）统一归入 `itemGroup.timebus` 标签页（中英双语 lang key：`itemGroup.timebus`）。

### 6.5 v1.0.1 配方与物品线教训

1. **不要用 PowerShell 字符串拼接写 Java 代码**：单引号字符串里 `\'` 不转义会截断字符串；双引号里 `\"` 不是转义（需反引号），且 `$var` 会被展开吞字符（曾把 `Items.PISTON` 改坏成 `Items.Pis`）。**写代码用编辑器/文件工具，别用 PowerShell Replace 拼 Java**。
2. **1.12.2 配方 API**：`GameRegistry.addShapelessRecipe(ResourceLocation, ResourceLocation, ItemStack, Ingredient...)` 参数是 `Ingredient...` 不是 `ItemStack`（编译报 varargs 不匹配）；活塞是 `Blocks.PISTON`（`Items.PISTON` 不存在，活塞是方块）。
3. **AE2 压印配方**：`AEApi.instance().registries().inscriber().builder()`——`withInputs(ItemStack...)`（中间）、`withTopOptional/withBottomOptional`（上下，可为空）、`withOutput`、`withProcessType(InscriberProcessType.INSCRIBE/PRESS)`，最后 `.build()` 交给 `reg.addRecipe`；引用 AE2 物品用 `AEApi.instance().definitions().materials()/items()/blocks()` 的 `maybeStack(1)`（如 `matterBall()`、`engProcessorPress()`、`siliconPrint()`、`advCard()`、`blocks().inscriber()`）。
4. **脚本按行处理文件有丢行风险**：修复脚本的 else 分支若漏写会吞掉后续行导致文件缺闭合（`TimeBusRecipes.java:66 语法错误：已到文件结尾`）——**脚本改完必须重新编译验证**。
5. **动画材质**：竖条拼图 png（如 16x192 = 12 帧）+ 同名 `.png.mcmeta`：`{"animation":{"frametime":4}}`，`frametime` 是帧 tick（每 4 tick 切一帧）。
6. **设计稿源文件（psb/psd）不入库**：git add 前排除，只提交最终 png/png.mcmeta。
7. **配方注册在 postInit**：压印机配方（`IInscriberRegistry`）与工作台配方（`GameRegistry`）都在 `TimeBus.postInit` 统一注册，物品必须先注册完成。

### 6.6 正式 jar（混淆环境）Mixin 的坑（v1.0.1 发布修复实录）

1. **`@Redirect` 到 MC 类方法在发布 jar 静默失效**：`ItemStack.getCount()` 运行时是 srg 名 `func_190916_E`，而发布 jar 的 refmap（Unimined remapJar 会重写成只有类名映射）没有方法映射 → 注入找不到。**避开 MC 类 target**：改用 `@ModifyVariable` 替换 AE2 方法参数（AE2 类方法名在 dev/jar 一致，无需映射）。
2. **`@ModifyVariable` 要处理全部相关参数**：`getTask(input, plateA, plateB)` 三个参数都要换成 count=1 副本——只换 input 时，plate 堆叠会导致 `recipe.matches` 失败、压印机不工作。
3. **注入"MC 方法的 override"时 method 名是 srg 名**：`SlotRestrictedInput.isItemValid`（override 了 MC `Slot.isItemValid`）运行时叫 `func_75214_a`——正式 jar 用 MCP 名找不到。解决：**双注入**（`isItemValid` + `func_75214_a`，`require=0` 让不匹配的那个静默跳过）。
4. **mixin 调用目标类成员必须 `@Shadow`**（mixin 类不继承目标类，编译器不认识 `this.getXxx()`）；优先 `@Shadow` **方法**（public getter）而非私有字段。
5. **`remap=false` 只对"AE2 自己的方法名"安全**（AE2 不混淆）；MC 类方法及其 override 必须处理 srg 名（双注入或 refmap）。
6. **Unimined remapJar 会重写 refmap**（只保留类名映射）——不要依赖手写方法级 refmap 进发布 jar。
7. **mixin annotation processor（apt）生成空 refmap**（Unimined 不提供映射数据）——apt 输出指到 scratch 目录（`-AoutRefMapFile=build/apt-refmap/...`），避免覆盖 src 里手写的 refmap。
8. **运行时 javap 验证发布版 AE2**：用 `javap -p -cp <用户实际 AE2 jar>` 确认类/方法名（如 `ae2-uel-v0.56.8-novaeng_ver.jar`）——**发布版可能与开发源码不同**，务必以运行时 jar 为准。
9. **诊断用 `System.out.println` + latest.log**：IDE 断点在 mixin 方法上不可靠；println 到日志最直接——一次运行区分"mixin 没应用" vs "注入点没被调" vs "条件不满足"。
10. **同方法多注入**：`@Inject(method=...)` 多个 handler 共存没问题；`@Unique` 公共方法 + 多个 `@Inject`/`@ModifyVariable` 转发是复用逻辑的标准写法。
11. **lang 文件名必须小写**（`en_us.lang` / `zh_cn.lang`）：Minecraft 的 `ResourceLocation` 强制路径小写（请求 `lang/en_us.lang`），而 jar（zip）大小写敏感——`en_US.lang`（大写）在发布 jar 里找不到 → 全部物品名显示原始 key（`item.timebus.xxx.name`）；dev 正常是因为 Windows 文件系统大小写不敏感。**这是 v1.0.1 物品名丢失的根因**。

## 7. 开发环境

- CleanroomLoader `0.5.17-alpha`，Unimined `1.4.26-kappa`，Java 25 toolchain
- 构建：`gradlew build`（产物在 `build/libs/timebus-1.0.0.jar`）
- 依赖：AE2 UEL（`curse.maven:ae2-extended-life-570458:6302098`）、JEI、The One Probe
- 注意：Cleanroom 的方法名与 MCP 不同（如 `Block.onEntityCollision` 而非 `onEntityCollidedWithBlock`），改动前先在 `F:\AiWork\Mcmod\Git`（AE2 UEL 源码 git 克隆）或运行时 jar 里确认签名

### 常见坑

- `srg2mcp.jar` 损坏导致 `remapJar` 失败：删 `F:\AiWork\TimeBus\.gradle\unimined\local\mappings\srg2mcp.jar` 重建
- 服务端不能用 7 参 `spawnParticle`：粒子一律走客户端分支

### 常见坑：更新文档/写中文文件的工具坑（亲身踩过）

- **不要用 PowerShell 写含中文的内容**（尤其 Markdown）：PowerShell 5.1 解析 `.ps1` 脚本时按系统编码（GBK）读脚本文件，**脚本里的中文字符串在内存里已经是乱码**——即使 `WriteAllText` 指定了 UTF-8 写出，写出来的还是乱码（`鏈哄櫒` 这类），而且**直接覆盖原文件、没有备份**。**写中文一律用工具（File edit/write）直接操作文件**，工具写 UTF-8 是安全的
- **读文件要显式指定 UTF-8**：`[System.IO.File]::ReadAllText(path, [System.Text.Encoding]::UTF8)`——PowerShell 默认按 GBK 读无 BOM 的 UTF-8 文件，中文 `IndexOf`/`Select-String` 会匹配失败（marker 找不到）
- **区分"控制台显示乱码"和"文件真乱码"**：`Get-Content`/`Select-String` 按 GBK 解码 UTF-8 文件时显示乱码，但**文件本身可能是好的**——判断是否真乱码，必须用 UTF-8 显式读取再打印
- **文档写坏后从 git 恢复**：`git checkout -- <file>`（git 里有干净版本时）——改文档前先确认 git 状态，写坏可一键还原
- **多行 PowerShell 命令会被工具安全拦截**：复杂逻辑（here-string、循环、条件）写成 `.ps1` 文件再 `powershell -ExecutionPolicy Bypass -File xxx.ps1` 执行，用完删除
- **File 工具的 `edit` 对含花括号的大段 Java 代码可能误报 "unbalanced braces"**：改用 `File patch`（unified diff）或 PowerShell 单行精确 `Replace`（注意行尾符：文件是 LF 还是 CRLF，替换串要一致）

## 8. Part 模型与材质（AE2 UEL 兼容）

时间总线是挂在 ME 线缆上的 part，模型/材质必须与 **AE2 UEL 的渲染约定**一致（UEL fork 了模型加载，和老版 AE2 rv6 不同）。AE2 UEL 源码素材在 `F:\AiWork\Mcmod\Git\src\main\resources`。

### 8.1 文件组织（照抄 AE 的 part 写法）

```
assets/timebus/models/
├── item/part/part.json      # 纯 display 变换父模型（gui/ground/fixed/thirdperson/firstperson）
├── item/time_bus.json       # 物品模型：parent = timebus:item/part/part，自带 textures + elements
└── part/
    ├── time_bus_base.json        # 放置后本体（4 个元素）
    ├── time_bus_on.json          # 通电指示 overlay
    ├── time_bus_off.json         # 断电指示 overlay
    └── time_bus_has_channel.json # 有频道指示 overlay
```

- **display 变换单独放父模型**：每个 part 物品模型继承 `item/part/part`（等价 AE2 的 `appliedenergistics2:item/part/part`），再自带 `textures` + `elements`——不要把 display 直接写进 part 放置模型
- `PartTimeBus.java` 用 `@PartModels` + `PartModel(MODEL_BASE, overlay)` 组合：`getStaticModels()` 按 激活+有频道/仅通电/断电 返回 `MODELS_HAS_CHANNEL / MODELS_ON / MODELS_OFF`

### 8.2 overlay 指示条：UEL 与 rv6 的差异（错位的根因）

**UEL 的 on/off/has_channel 指示条元素位于 `[6,6,4]-[10,10,5]`**（与 base 第 4 个元素的指示板位置重合，作为发光叠加层渲染），**不是老 rv6 的 `[6,6,16]-[10,10,17]`**——照抄 rv6 模型会导致指示条与本体错位。

UEL 自定义键（`appeng/client/render/model/UVLModelLoader.java` 处理）：

| 模型 | 关键内容 |
|---|---|
| `time_bus_on` | `"ae2_uvl_marker": true`；四个面 `"tintindex": 3` + `"uvlightmap": {"sky": 0.007, "block": 0.007}`；贴图 `monitor_sides_status_on` |
| `time_bus_off` | 无 marker/tintindex/uvlightmap（不发光）；贴图 `monitor_sides_status_off` |
| `time_bus_has_channel` | `"ae2_uvl_marker": true`；`"tintindex": 1` + uvlightmap；贴图 `monitor_sides_status_has_channel` |

`has_channel` 用专门的 `monitor_sides_status_has_channel` 贴图，别偷懒复用 `_on`。

### 8.3 物品模型要居中（槽位右下角问题的根因）

- part **放置**几何 z 轴是 `0~5`（贴线缆面为 z=0），**不能直接拿来当物品几何**——物品渲染会把 z 偏置带进视角，看起来偏到槽位右下角
- **物品几何照抄 AE2 的 `item/part/export_bus.json`**：三个元素 `[4,4,8]-[12,12,10]`、`[5,5,7]-[11,11,8]`、`[6,6,6]-[10,10,7]`（z 以 8 为中心），并保留其 UV 映射
- 验证方式：与 AE2 原版替换贴图路径后逐字符比对

### 8.4 材质自包含

从 AE2 UEL 复制的贴图（均 16×16，复制后做 SHA256 哈希校验）：

```
assets/timebus/textures/
├── parts/export_bus_sides.png               # 侧面（AE2: parts/export_bus_sides.png）
├── parts/monitor_back.png                   # 背面（AE2: parts/monitor_back.png）
├── parts/monitor_sides_status{,_on,_off,_has_channel}.png  # 指示条
└── items/part/export_bus.png                # 正面（AE2: items/part/export_bus.png）
```

模型里的纹理引用全部用本地 `timebus:parts/...`、`timebus:items/part/...`，不要在模型 JSON 里留 `appliedenergistics2:` 引用。

术语注意：AE2 中文里 "**ME输出总线**" = `export_bus`（`item.appliedenergistics2.multi_part.export_bus.name=ME输出总线`）；AE2 UEL 没有独立的 `output_bus`，找素材以 `export_bus` 为准。

### 8.5 Blockbench 导出的坑

- **通用导出（`"format_version": "1.21.11"` + `"credit": "Made with Blockbench"`）会把每个面写成裁剪 UV**（如 `"north": {"uv": [4,4,12,12]}`），把 16×16 贴图切成小区域，贴图显示被切碎、与 AE2 结构不兼容——**必须用 Blockbench 的 Minecraft 1.12.2 预设导出**，并保持面贴图为完整 16×16 不裁剪
- 模型文件可能被外部工具改写：改前先看文件 `LastWriteTime` / git status，被改写时保留备份（如 `time_bus_base.blockbench.bak`）再恢复
- 1.12.2 模型加载器只认 `parent/textures/elements/display`，其余顶层键（`format_version`、`credit`）会被忽略但不报错

### 8.6 验证清单（改完模型必做）

1. JSON 合法性：`Get-Content -Raw -Encoding UTF8 xxx.json | ConvertFrom-Json`
2. 结构比对：把 `timebus:` 路径替换回 `appliedenergistics2:` 后**去除全部空白**，与 AE2 原版逐字符比较（键顺序也要一致，否则会误报差异）
3. 贴图校验：SHA256 与源文件一致 + `System.Drawing` 读取尺寸为 16×16
4. 全目录 grep 无残留 `appliedenergistics2:` 贴图引用（GUI 的 `textures/guis/states.png` 在 Java 里引用，不算模型引用）
