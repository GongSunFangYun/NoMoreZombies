<div align="center">

<img src="src/main/resources/icon.png" alt="Logo" width="160" height="160">

# NoMoreZombies - Hypixel Zombies 辅助模组

[English](README.md) | [简体中文](README_ZHT.md)

[![GitHub release](https://img.shields.io/github/v/release/GongSunFangYun/NoMoreZombies?style=flat-square)]()
[![Downloads](https://img.shields.io/github/downloads/GongSunFangYun/NoMoreZombies/total?style=flat-square)]()
[![Stars](https://img.shields.io/github/stars/GongSunFangYun/NoMoreZombies?style=flat-square)]()
[![Forks](https://img.shields.io/github/forks/GongSunFangYun/NoMoreZombies?style=flat-square)]()
[![Issues](https://img.shields.io/github/issues/GongSunFangYun/NoMoreZombies?style=flat-square)]()
[![License](https://img.shields.io/github/license/GongSunFangYun/NoMoreZombies?style=flat-square)]()

</div>

NoMoreZombies 是一个 **纯客户端** Fabric mod（**Minecraft 1.21.4**），用于辅助 **Hypixel Zombies** 小游戏。它只读取你的客户端本就可见的数据（聊天、计分板、标题、世界音效、实体元数据），不会在服务器端做任何改动。

界面与所有提示均为**中英双语**，自动跟随客户端语言。

---

## 免责声明

- **纯客户端**。NoMoreZombies 只读取你的客户端本就收到的数据（聊天、计分板、标题、世界音效、实体元数据）。它不会修改服务器、不会发送任何数据包，也不会改变其他玩家能看到的内容。
- **服务器规则与封号风险**。根据服务器的规则，本 mod 的部分功能（例如穿墙 ESP）可能被视为作弊。使用本 mod 可能导致警告或账号封禁。请自行承担使用方式与场合的责任。
- **风险自负**。本 mod 按「现状」提供，不附带任何形式的担保。作者不对因使用本 mod 造成的任何损失或损害负责。
- **无关联声明**。本 mod 与 Hypixel Inc.、Mojang Studios 或 Microsoft 无任何关联、认可或从属关系。
- **不保证不被检测**。本 mod 不试图规避服务器的反作弊系统，部分功能可能被检测到。不保证任何功能安全或不可检测。

---

## 功能总览

### 波次计时
- 当前回合每一波的出生秒数表，并高亮下一波。
- 按地图可选的出生音效提醒，末波前 3-2-1 倒计时。
- 「外星游乐园」首领波颜色预警。

### 强化道具
- 通过三条冗余通道检测道具（盔甲架扫描、实体元数据、聊天激活）。
- 首次观测后锁定地图道具规律，预测刷新回合。
- 掉落与拾取提醒，外加屏幕计时器显示道具已生效时长与下一次刷新倒计时。

### 队伍统计
- 左上角计分板风格 HUD：每位队友的血量、状态（战斗中 / 倒地 / 死亡 / 退出）、击杀、倒地、死亡与金币。
- 数据缓存到本地文件，快速重进后可恢复。

### 常驻计时 HUD
- 整局游戏时长与本回合用时；游戏结束（通关或团灭）时冻结显示值。

### 回合记录（RKPM）
- 每回合耗时与击杀总数，回合结束在聊天中总结，含 **RKPM**（分均击杀 = 净击杀 × 60 / 回合秒数）。点击消息即可复制。

### 玩家战绩查询
- 配置 Hypixel API Key 后，可按玩家名或 UUID 查询任意玩家，或每回合自动查询当前队友的 Zombies 战绩。

### 聊天过滤与侧边栏增强
- 隐藏嘈杂的聊天行（金币获得、窗户修复、击中目标、幸运箱、开启区域、玩家进出）。
- 清理 Hypixel 侧边栏：剥离空行与玩家行；开启计时 HUD 时移除原生时间行。

### 实体 ESP
- 为队友（战斗中绿色、倒地黄色）、僵尸等敌对生物（红色）与已刷新道具（白色）绘制线框。
- 每种 ESP 均有独立开关与**渲染机制**：**常规**（尊重遮挡，墙后隐藏）或**穿墙**（可穿透障碍物显示）。
- 全局**穿墙渲染距离**滑条（5-200 格）控制穿墙效果的最大生效范围。

### 僵尸血条
- 每个敌对生物头顶的世界空间血条（`[#######------] 12/20HP`），按剩余血量变色。

### AA 自动指挥
- 在「外星游乐园」：回合指挥 HUD（巨人刷新 / 长者刷新 / 难度 / 推荐点位），外加每回合开始时的自动聊天播报。

### 电击棒充能冷却 HUD
- 在「外星游乐园」：4 槽 HUD 追踪每根电击棒 20 秒冷却，按剩余时间变色。

### QoL 工具箱
- **平滑缩放**（按键触发，滚轮热调、缓动曲线、灵敏度补偿）。
- **永久潜行** / **永久疾跑** / **伽马覆写**（强制亮度）。
- **自由视角**（相机脱离身体自由观察）。
- **隐藏附近玩家**（半透明，减少视野遮挡）。
- **隐藏原生 Boss 血条**与**原生计分板**。
- **仅右键开火**（屏蔽非开火右键交互）。
- **无发射粒子**与**无火焰覆盖**。
- **CPS 统计**（每秒左右键点击数）。

### HUD 编辑器
- 在配置界面中单独拖动、缩放、开关每个 HUD 元素。

---

## 安装

1. 安装 **Fabric Loader 0.16.14** 并创建 **1.21.4** 版本实例。
2. 将 `NoMoreZombies-pre-release-0.1.jar` 放入你的 `mods/` 文件夹。
3. 依赖（需同时安装）：
   - **Fabric API**
   - **MaLiLib**（外部依赖，需单独安装）

## 配置

- 从 ModMenu（NoMoreZombies > Config， 如果你安装了它）打开配置，或按默认热键 **Z+X**。
- 所有功能开关**默认关闭**（Tweakeroo 风格）。可在配置界面为任一开关绑定热键，游戏中即时切换。
- 配置文件为 `config/nomorezombies.json`（明文，可手动编辑）。
- 数据表（波次时间、道具规律）在游戏内按 **F3+T** 热重载。

## 备注

- 本 mod 纯客户端：从不修改服务器、不发送数据包，也不改变其他玩家能看到的内容。
- 面向全球 Hypixel Zombies 玩家社区设计；所有解析与提示均兼容中英文游戏文本。
- 如有 Bug 报告或功能建议，请描述你的操作与出现时的日志内容。
