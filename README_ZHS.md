<div align="center">

<img src="src/main/resources/icon.png" alt="Logo" width="160" height="160">

# NoMoreZombies — Hypixel Zombies 辅助模组

[English](README.md) | [简体中文](README_ZHS.md)

[![GitHub release](https://img.shields.io/github/v/release/GongSunFangYun/NoMoreZombies?style=flat-square)]()
[![Downloads](https://img.shields.io/github/downloads/GongSunFangYun/NoMoreZombies/total?style=flat-square)]()
[![Stars](https://img.shields.io/github/stars/GongSunFangYun/NoMoreZombies?style=flat-square)]()
[![Forks](https://img.shields.io/github/forks/GongSunFangYun/NoMoreZombies?style=flat-square)]()
[![Issues](https://img.shields.io/github/issues/GongSunFangYun/NoMoreZombies?style=flat-square)]()
[![License](https://img.shields.io/github/license/GongSunFangYun/NoMoreZombies?style=flat-square)]()

</div>

NoMoreZombies 是一个 Fabric 模组，运行于 Minecraft 1.21.4，仅作用于客户端。其功能限定于读取客户端已接收的数据（聊天消息、计分板、标题、世界音效、实体元数据），不向服务端发送任何数据包，也不对服务端状态产生任何影响。

所有功能均严格限定于 Hypixel Zombies 小游戏内生效，非 Zombies 模式下所有功能均不可使用。

模组界面及所有提示信息均提供中英文两种语言，显示语言由客户端设置决定。

---

## 免责声明

- **客户端限定。** NoMoreZombies 仅读取客户端已接收的数据（聊天、计分板、标题、世界音效、实体元数据）。模组不修改服务端数据，不发送数据包，不影响其他玩家的游戏画面或服务端逻辑。
- **服务器规则与封禁风险。** 不同服务器对辅助工具的定义与处罚措施不同。本模组的部分功能（例如穿墙 ESP）在某些服务器上可能被认定为违规。使用本模组可能导致警告或账号封禁。使用者需自行评估风险并承担相应责任。
- **按现状提供。** 本模组按「现状」提供，不附带任何明示或暗示的担保。作者不对使用本模组造成的任何直接或间接损失承担责任。
- **无关联声明。** 本模组与 Hypixel Inc.、Mojang Studios 或 Microsoft 不存在任何关联、认可或从属关系。
- **反作弊检测。** 本模组不试图规避服务器反作弊系统，也不对任何功能的可检测性或安全性做出保证。

---

## 功能列表

### 波次计时
- 以表格形式显示当前回合每一波的出生时间，并高亮下一波。
- 支持按地图配置波次音效提醒，末波前播放 3-2-1 倒计时音效。
- 在 Alien Arcadium 地图中，首领波次以颜色变化提示。

### 强化道具追踪
- 通过三条独立通道检测强化道具：盔甲架扫描、实体元数据解析、聊天消息匹配。
- 首次观测后自动匹配当前地图的道具刷新规律，并预测后续刷新回合。
- 提供道具掉落与拾取通知，屏幕计时器显示当前道具剩余生效时间及下一次刷新倒计时。

### 队伍统计面板
- 左上角以计分板形式显示每位队友的血量、状态（战斗中/倒地/死亡/退出）、击杀数、倒地次数、死亡次数、金币数。
- 统计数据缓存至本地文件，重连后自动恢复。

### 游戏计时 HUD
- 显示总游戏时间与当前回合已用时间。游戏结束（通关或团灭）时时间数值固定，不再更新。

### 回合记录（RKPM）
- 每回合结束时在聊天栏输出本回合耗时、击杀总数及 RKPM（分均击杀 = 净击杀 × 60 / 回合秒数）。点击消息可复制内容。

### 玩家战绩查询
- 配置 Hypixel API Key 后，支持按玩家名或 UUID 查询任意玩家的 Zombies 战绩，也可每回合自动查询当前队友的战绩。

### 聊天过滤与侧边栏优化
- 可屏蔽以下类型的聊天消息：金币拾取、窗户修复、命中反馈、幸运箱开启、区域解锁、玩家进出。
- 清理 Hypixel 侧边栏：移除空行和玩家列表行；启用计时 HUD 时移除原生的时间显示行。

### 实体 ESP
- 为以下实体绘制边框：队友（正常状态为绿色，倒地状态为黄色）、僵尸及敌对生物（红色）、已刷新的强化道具（白色）。
- 每类 ESP 可独立开关，并分别设置渲染模式：
  - **常规模式**：遵守遮挡关系，墙后不可见。
  - **穿墙模式**：无视遮挡，始终可见。
- 提供全局穿墙渲染距离滑条，范围 5 至 200 格，控制穿墙模式的有效范围。

### 僵尸血条
- 在敌对生物头顶绘制世界空间血条，格式为 `[#######------] 12/20HP`，颜色随剩余血量比例变化。

### Alien Arcadium 自动指挥
- 在 Alien Arcadium 地图中，显示回合指挥信息：巨人刷新、长者刷新、难度等级、推荐守点。
- 每回合开始时自动在聊天栏发送指挥消息。

### 电击棒冷却 HUD
- 在 Alien Arcadium 地图中，以四槽位 HUD 显示每根电击棒的 20 秒冷却状态，颜色随剩余冷却时间变化。

### 便捷功能工具集
- **平滑缩放**：按键触发，滚轮调节缩放倍率，支持缓动曲线与灵敏度补偿。
- **永久潜行** / **永久疾跑** / **伽马覆写**（强制亮度）。
- **自由视角**：相机脱离角色模型，自由观察周围环境。
- **隐藏附近玩家**：将附近玩家模型变为半透明，减少视野遮挡。
- **隐藏原生 Boss 血条**与**原生计分板**。
- **仅右键开火**：屏蔽非开火目的的右键交互。
- **禁用枪械射击粒子**与**禁用火焰覆盖效果**。
- **CPS 计数器**：显示左键与右键每秒点击次数。

### HUD 编辑器
- 在配置界面中可对每个 HUD 元素单独进行拖拽移动、缩放和开关操作。

---

## 安装步骤

1. 安装 Fabric Loader 0.16.14，并创建 Minecraft 1.21.4 版本的游戏实例。
2. 将 `NoMoreZombies-pre-release-0.1.jar` 放入 `mods/` 文件夹。
3. 安装以下依赖模组（需一并放入 `mods/` 文件夹）：
   - Fabric API
   - MaLiLib

---

## 配置方式

- 通过 ModMenu 打开配置界面（NoMoreZombies > Config），或使用默认热键 **Z + X**。
- 所有功能开关默认处于关闭状态。可在配置界面为任意开关绑定热键，游戏内即时切换。
- 配置文件位于 `config/nomorezombies.json`，为明文 JSON 格式，可手动编辑。
- 数据表（波次时间、道具刷新规律）支持游戏内热重载，按 **F3 + T** 即可生效。

---

## 注意事项

- 本模组为纯客户端模组，不修改服务端数据，不发送数据包，不影响其他玩家的游戏画面或服务端逻辑。
- 文本解析与消息输出兼容中英文两种游戏语言环境。
- 提交 Bug 报告或功能建议时，请描述操作步骤并提供对应的日志内容。

---

## 参考声明

本模组的部分主要功能实现参考了以下项目：
- [ShowSpawnTime](https://github.com/Seosean/ShowSpawnTime)
- [NotEnoughZombies](https://github.com/PingIsFun/NotEnoughZombies)
- [Hypixel-Zombies-Mod](https://github.com/FairCauth/Hypixel-Zombies-Mod)

本模组的部分次要功能实现参考了以下项目：
- [Zoomify](https://github.com/isXander/Zoomify)
- [tweakeroo](https://github.com/maruohon/tweakeroo)

本模组并未直接引用以上项目的代码，仅进行功能参考并自行实现逻辑。