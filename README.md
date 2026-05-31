# BandwidthSaver-PE

## Overview | 概述

**BandwidthSaver-PE** is a high-performance bandwidth throttling plugin built on the **PacketEvents** framework. Developed by the **Mangocraft Code Team**, it is a modernized fork of the legacy [RIABandwidthSaver](https://github.com/Ghost-chu/RIABandwidthSaver) by **Ghost-chu**.

BandwidthSaver-PE 是一个基于 **PacketEvents** 的高性能节流插件。由 **芒果方块的服务器开发制作组** 基于 Ghost-chu 的旧版插件RIABandwidthSaver进行修改优化，旨在玩家处于 AFK 状态期间抑制不必要的数据包和区块发送，缓解服务器带宽压力。
![当进入ECO模式时会通过BOSSBAR提醒](https://cdn.modrinth.com/data/cached_images/27b6c74e463f97ef01749fadcc19c192c80c59b3.jpeg)
![减少数据包展示Limit packet display.](https://cdn.modrinth.com/data/cached_images/262cf137d40a4d17fb47bbbe0c726ffa50bf66d7.png)
（All text within images can be modified in config.yml. Feel free to translate or customize the content.图片内文字均可在config.yml中修改，可自行翻译或自定义内容）

### Improvements | 改进点
* **Modern Framework:** Switched from ProtocolLib to **PacketEvents** for superior stability. (前置从 ProtocolLib 改为 PacketEvents，更稳定兼容)
* **Refined Logic:** Optimized filtering algorithms for higher precision. (优化过滤算法，更准确高效)
* **Folia Support:** Native compatibility with Folia. (增加对 Folia 服务器的支持)
* **AFK Fishing Fix:** Added detection and handling for AFK fishing machines. (增加对自动钓鱼机的检测和处理)
* **Manual AFK Mode:** Added `/afk` command for manual AFK mode that persists regardless of player movement. (新增/afk命令，提供手动AFK模式，不受玩家移动影响)
* **Super Bandwidth-Saving Mode:** Added `/afk super` and `/afk super always` to completely freeze chunks and entities for maximum bandwidth savings. (新增超级省流模式和默认超级省流挂机偏好，实现最极限的宽带节省)
* **Safety Refinements:** Blocks inventory usage, drops, and interacts in Super AFK to avoid UI desync. Auto-exits on death (`PlayerDeathEvent`) to prevent respawn locks. (全面锁定背包、丢弃与物品交互防刷防错乱；死亡立刻解除挂机，防止幽灵生存卡死)
* **High-Concurrency Lookup:** Uses O(1) HashSet for packet whitelisting to minimize CPU cycles on crowded Folia regions. (采用 O(1) HashSet 极速匹配包白名单，降低密集型 Folia 服务器开销)

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
* **Manual AFK Mode:** Players can use `/afk` command to enter manual AFK mode that persists regardless of player movement. Use `/afk` again or rejoin the game to exit. 手动AFK模式：玩家可使用/afk命令进入手动AFK模式，不受玩家移动影响。再次使用/afk或重新加入游戏可退出。
* **Super Bandwidth-Saving Mode:** Toggled with `/afk super`. Restricts virtually all incoming data packets (except keepalive, bossbar, chat, disconnect, and plugin messages). Forces the client to play in a static, frozen world. Upon exiting, players must reconnect to refresh terrain chunks. 超级省流模式：使用 /afk super 开启。拦截白名单（核心心跳、聊天、通道和断连）外的几乎所有封包。退出后需重连服务器以刷新区块。
* **Preferred Auto-Super AFK:** Toggled with `/afk super always`. Persists in `super-always.yml` so players enter Super AFK automatically instead of normal ECO mode when idle. **Note:** Auto-entered Super AFK allows players to wake up by head movement or taking damage, while manually activated `/afk super` can only be exited via command or reconnect. Under any condition, exiting Super AFK requires reconnecting to properly reload terrain chunks.  
  默认超级省流偏好：通过 `/afk super always` 切换，记录于 `super-always.yml` 中。闲置超时后直接进入超级省流而非普通挂机。**注意**：超时自动进入的超级省流模式允许玩家通过晃动视角或受到伤害自动唤醒，而手动输入的 `/afk super` 则必须通过再次输入指令或重新登入退出。无论以何种方式退出超级省流，均需重连服务器以重载区块。

### 3. Packet Filtering | 数据包过滤详情
* **Cancelled (100% Suppression) | 取消发送:**
  * Animations, Block break, Sounds, Particles, Explosions, Time sync, Light updates, TAB list headers/footers, World events, Potion effects, Map data, etc.
  * 动画、方块破坏、声音、粒子、爆炸、时间同步、光照更新、TAB 列表、世界事件、药水效果、地图数据等。
* **Throttled (Reduced Rate) | 频率削减:**
  * **2% Pass Rate:** Entity movement, Position, Velocity, Experience orbs. (实体移动/位置/速度、经验球)
  * **5% Pass Rate:** Entity metadata. (实体元数据)
  * **20% Pass Rate:** Head orientation. (实体头部朝向)
* **Special Handling for AFK Fishing | 自动钓鱼机特殊处理:**
  * When a player holds a fishing rod, certain packets (sound effects, entity velocity/movement) are allowed to pass through to support fishing activities.
  * 当玩家手持钓鱼竿时，某些数据包（声音效果、实体速度/移动）会被允许通过，以支持钓鱼活动。

---

## Commands & Permissions | 命令与权限

| Command | Description |
| :--- | :--- |
| `/bandwidthsaver` | View bandwidth saving stats (查看流量节省统计) |
| `/bandwidthsaver unfiltered` | View raw consumption (查看实际消耗统计) |
| `/bandwidthsaver reload` | Reload configuration (重载配置) |
| `/bandwidthsaver admin add/remove <player>` | Add or remove a player from auto Super AFK list (将玩家加入或移出自动超级省流名单) |
| `/afk` | Toggle manual AFK mode (切换手动AFK模式) |
| `/afk super` | Toggle manual Super AFK mode (切换手动超级省流模式) |
| `/afk super always` | Toggle preferred automatic Super AFK mode (切换超时自动进入超级省流偏好) |

| Permission | Description |
| :--- | :--- |
| `bandwidthsaver.bypass` | Bypass AFK detection (绕过 AFK 检测) |
| `bandwidthsaver.admin` | Access admin commands (管理员权限) |

---

## Configuration | 配置文件

```yaml
# Calculate all packets (required for /bandwidthsaver unfiltered)
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
  playerSuperEcoEnable: '§c⚠️ [超级省流模式已启用] 仅限挂机，不能进行任何操作/有视角要求的行为，退出后需重连以刷新区块。'
  playerSuperEcoDisable: '§c⚠️ 您已退出超级省流模式！为了恢复周围区块和实体的正常加载，请重新进入服务器。'
  # (And many more customizable messages for i18n / 更多可自定义的提示信息用于多语言支持)
bossbar:
  eco-enabled-title: "<green><bold>🍃 ECO 节能模式</bold> <gray>|</gray> <yellow>⬇ 已暂停高频数据传输</yellow> <gray>|</gray> <white>↔ 轻晃视角以恢复</white>"
  eco-enabled-health: 1.0
  eco-enabled-color: "YELLOW" # YELLOW, BLUE, RED, GREEN, PINK, WHITE, PURPLE, or ORANGE
  eco-enabled-overlay: "PROGRESS" # PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, or NOTCHED_20
  super-eco-enabled-title: "<red><bold>⚡ SUPER ECO 超级省流模式</bold> <gray>|</gray> <yellow>🔒 数据拦截最大化</yellow> <gray>|</gray> <white>⚠️ 退出后需重连刷新区块</white>"
  super-eco-enabled-health: 1.0
  super-eco-enabled-color: "RED"
  super-eco-enabled-overlay: "PROGRESS"
```