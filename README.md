# RIABandwidthSaver-PE

## Overview | 概述

**RIABandwidthSaver-PE** is a high-performance bandwidth throttling plugin built on the **PacketEvents** framework. Developed by the **Mangocraft Code Team**, it is a modernized fork of the legacy [RIABandwidthSaver](https://github.com/Ghost-chu/RIABandwidthSaver) by **Ghost-chu**.

RIABandwidthSaver-PE 是一个基于 **PacketEvents** 的高性能节流插件。由 **Mangocraft Code Team** 基于 Ghost-chu 的旧版插件进行修改优化，旨在玩家处于 AFK 状态期间抑制不必要的数据包和区块发送，缓解服务器带宽压力。

### Improvements | 改进点
* **Modern Framework:** Switched from ProtocolLib to **PacketEvents** for superior stability. (前置从 ProtocolLib 改为 PacketEvents，更稳定兼容)
* **Refined Logic:** Optimized filtering algorithms for higher precision. (优化过滤算法，更准确高效)
* **Folia Support:** Native compatibility with Folia. (增加对 Folia 服务器的支持)

---

> [!IMPORTANT]
> * **Dependency:** You **must** install [PacketEvents](https://github.com/retrooper/packetevents) for this plugin to function. (必须安装 PacketEvents 插件)
> * **Note on Stats:** Traffic statistics represent **uncompressed** data. Actual billed bandwidth may differ due to server-side compression. (统计信息为未压缩流量，实际流量因服务器压缩配置会有所出入)

---

## Features | 功能

### 1. Dynamic View Distance | 动态视距
* Lowers client-side view distance for AFK players without affecting server-side simulation distance.
* 降低 AFK 玩家的客户端视野距离，不影响服务器模拟距离，减少区块数据传输。

### 2. AFK Detection | AFK 检测机制
* **Perspective-Based:** Monitors camera rotation (Default: 300s). 基于视角移动检测（默认 300 秒）。
* **Auto-Exit:** Automatically restores traffic flow upon taking damage or using teleport commands (`/tp`, `/spawn`, `/home`, etc.). 受到攻击或使用传送命令时自动退出 AFK 模式。
* **Automation Friendly:** Compatible with AFK pools and auto-clickers. 支持自动攻击和 AFK 池。

### 3. Packet Filtering | 数据包过滤详情
* **Cancelled (100% Suppression) | 取消发送:**
  * Animations, Block break, Sounds, Particles, Explosions, Time sync, Light updates, TAB list headers/footers, World events, Potion effects, Map data, etc.
  * 动画、方块破坏、声音、粒子、爆炸、时间同步、光照更新、TAB 列表、世界事件、药水效果、地图数据等。
* **Throttled (Reduced Rate) | 频率削减:**
  * **2% Pass Rate:** Entity movement, Position, Velocity, Experience orbs. (实体移动/位置/速度、经验球)
  * **5% Pass Rate:** Entity metadata. (实体元数据)
  * **20% Pass Rate:** Head orientation. (实体头部朝向)

---

## Commands & Permissions | 命令与权限

| Command | Description |
| :--- | :--- |
| `/riabandwidthsaver` | View bandwidth saving stats (查看流量节省统计) |
| `/riabandwidthsaver unfiltered` | View raw consumption (查看实际消耗统计) |
| `/riabandwidthsaver reload` | Reload configuration (重载配置) |

| Permission | Description |
| :--- | :--- |
| `riabandwidthsaver.bypass` | Bypass AFK detection (绕过 AFK 检测) |
| `riabandwidthsaver.admin` | Access admin commands (管理员权限) |

---

## Configuration | 配置文件

```yaml
# Calculate all packets (required for /riabandwidthsaver unfiltered)
calcAllPackets: true

# Dynamically modify player view distance when AFK
modifyPlayerViewDistance: false

# AFK threshold in seconds
afkPerspectiveThresholdSeconds: 300

# Enable console logging for filtering details
debug: false

message:
  playerEcoEnable: '§a🍃 ECO 节能模式已启用，限制数据传输，可能会看着卡顿，实际正常，不会影响机器运行'
  playerEcoDisable: '§8🍃 ECO 节能模式已停用，数据传输将恢复正常'
