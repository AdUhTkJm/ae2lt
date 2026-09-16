# Thunderbolt Core

[English](README.md)

Thunderbolt Core 是 AE2 Lightning Tech 使用的 AE2 优化与底层基础设施模组，也可以单独安装为 AE2 自动合成加速器。

当前是 **Minecraft NeoForge 1.21.1** 分支。如需 Minecraft 1.20.1 与 Forge 版本，请查看 [`1.20.1`](https://github.com/ae2lt/Thunderbolt-Core/tree/1.20.1) 分支。

## 环境要求

- Minecraft `1.21.1`
- NeoForge `21.1.x`（开发基线：`21.1.219`）
- Java `21`
- Applied Energistics 2 `19.2.17`–`19.2.x`

客户端和服务端均需将 Thunderbolt Core 与 AE2 放入 `mods` 目录。

## 主要功能

- 加速 AE2 自动合成规划，支持按顺序选择规划器，并可回退到 AE2 原生规划器
- 支持批量下发、闭环合成、时间轮调度和重载样板
- 为合成供应器、高容量频道、索引存储单元和弹出端点提供扩展 API
- 为兼容的高容量网络提供基于最大流的频道分配
- 包含 Advanced AE、NeoECO、AE2 Crafting Tree 和 ExtendedAE Plus 的可选兼容钩子；仅在对应模组存在时加载

## 配置

通用配置位于 `config/thunderbolt-common.toml`：

- `planning.enableCpSatPlanner`：启用实验性的 OR-Tools CP-SAT 规划器（默认：`false`）。启用后，Thunderbolt 会在启动时下载并校验匹配的原生运行库；加载失败不会影响其他规划器。
- `channel.mode`：控制最大流频道分配（默认：`MOD`）。`MOD` 在已加载的集成明确请求时启用，`DEVICE` 也会为主动接入的设备启用，`ON` 则在存在控制器时始终启用。

高级规划诊断和安全限制可通过 JVM 系统属性设置：

- `-Dthunderbolt.planningWarnMs=<毫秒>`：慢规划警告延迟（默认：`2000`；旧名称 `thunderbolt.watchdogMs` 仍可使用）
- `-Dthunderbolt.planningTimeoutMs=<毫秒>`：协作退出期限（默认：`3000`）
- `-Dthunderbolt.planningInterruptGraceMs=<毫秒>`：发送中断前的宽限时间（默认：`2000`）
- `-Dthunderbolt.planningStopGraceMs=<毫秒>`：超时后到隔离的总宽限时间（默认：`5000`）
- `-Dthunderbolt.maxCraftSearchWork=<数量>`：规划器搜索工作量上限
- `-Dthunderbolt.maxCraftDepth=<数量>`：规划深度上限

## 开发构建

构建可分发 JAR：

```powershell
.\gradlew.bat build
```

发布到本地 Maven 仓库：

```powershell
.\gradlew.bat publishToMavenLocal
```

- 版本：`2.0.0-beta.2`
- Maven 坐标：`com.moakiee.thunderbolt:thunderbolt:2.0.0-beta.2`
- 可分发 JAR：`build/libs/thunderbolt-2.0.0-beta.2.jar`

问题反馈：[GitHub Issues](https://github.com/ae2lt/Thunderbolt-Core/issues) ·
许可证：[GNU LGPL 3.0](LICENSE)
