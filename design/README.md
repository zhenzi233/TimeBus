# design —— 素材源文件

这里存放贴图/模型的**原始设计文件**（Photoshop / Blockbench 工程），它们是
`src/main/resources/assets/timebus/textures/` 下 PNG 的可编辑母版。

## 为什么放在这里，而不是 `src/main/resources/`

Gradle 的 `processResources` 会把 `src/main/resources/` 下的**所有**文件打进 jar，
**不管 `.gitignore` 怎么写**。v1.0.16 的发布 jar 里就混进了 3 个设计源文件：

| 文件 | 大小 |
| --- | --- |
| `time_fluid_flow.psb` | 103 KB |
| `1.psb` | 45 KB |
| `2.psd` | 45 KB |

合计 194 KB，占当时 jar 解压体积的 **33.2%**——纯粹是浪费。

搬到 `design/` 之后，`build.gradle` 里另有一道兜底：

```groovy
processResources {
    exclude '**/*.psd', '**/*.psb'
}
```

即使有人把源文件误放回 `src/main/resources/`，也不会再被打进发布 jar。

## 文件清单

| 文件 | 格式 | 通道 | 尺寸 | 对应 PNG |
| --- | --- | --- | --- | --- |
| `time_fluid_flow.psb` | PSB v2 | RGB | 16 × 416 | `textures/blocks/time_fluid_flow.png` |
| `1.psb` | PSB v2 | RGBA | 16 × 192 | `textures/items/time_processor.png` 或 `time_circuit_board.png` |
| `2.psd` | PSD v1 | RGBA | 16 × 192 | 同上，二选一 |

> ⚠️ `1.psb` 与 `2.psd` 的文件名没有意义，两者尺寸和通道数又完全相同，仅凭文件头
> 无法区分各自对应哪张贴图。**建议确认后重命名为 `time_processor.*` /
> `time_circuit_board.*`**，省得以后再次困惑。

## 待办

- `time_bus_base.blockbench.bak`（Blockbench 模型备份）目前仍留在仓库根目录，
  按同样的理由可以搬到这里。