# BandwidthSaver-PE

## Overview | 概述

**BandwidthSaver-PE** is a high-performance bandwidth throttling plugin built on the **PacketEvents** framework. Developed by the **Mangocraft Code Team**, it is a modernized fork of the legacy [RIABandwidthSaver](https://github.com/Ghost-chu/RIABandwidthSaver) by **Ghost-chu**.

BandwidthSaver-PE 是一个基于 **PacketEvents** 的高性能节流插件。由 **芒果方块服务器开发组** 基于 Ghost-chu 的经典旧版插件 RIABandwidthSaver 进行了现代化重构与深度优化。本插件的核心目标是在玩家处于 AFK（挂机）状态时，智能拦截并抑制不必要的网络数据包及区块发送，从而显著降低服务器的网络带宽压力与渲染开销。
![当进入ECO模式时会通过BOSSBAR提醒](https://cdn.modrinth.com/data/cached_images/27b6c74e463f97ef01749fadcc19c192c80c59b3.jpeg)
![减少数据包展示Limit packet display.](https://cdn.modrinth.com/data/cached_images/262cf137d40a4d17fb47bbbe0c726ffa50bf66d7.png)
*(Note: All text within images can be modified in config.yml. / 提示：图片中展示的所有提示文本，均可在 `config.yml` 中自由修改和汉化。)*

### Improvements | 改进点
* **Modern Framework:** Switched from ProtocolLib to **PacketEvents** for superior stability. (底层重构：采用 PacketEvents 替代老旧的 ProtocolLib，大幅提升发包拦截的稳定性与多版本兼容性。)
* **Refined Logic:** Optimized filtering algorithms for higher precision. (智能过滤：优化了数据包过滤算法，拦截更精准，服务端性能损耗更低。)
* **Folia Support:** Native compatibility with Folia. (Folia 原生支持：全面兼容 Folia 区域化多线程架构，无缝衔接现代高性能服务端。)
* **AFK Fishing Fix:** Added detection and handling for AFK fishing machines. (钓鱼机兼容：新增挂机钓鱼检测，即使在挂机状态下也能保证全自动钓鱼机的正常运作。)
* **Manual AFK Mode:** Added `/afk` command for manual AFK mode that persists regardless of player movement. (手动 AFK 模式：引入 `/afk` 命令，允许玩家强制进入挂机状态，不再受到物理移动或水流的干扰而意外退出。)
* **Super Bandwidth-Saving Mode:** Added `/afk super` and `/afk super always` to completely freeze chunks and entities for maximum bandwidth savings. (极限超级省流：引入 `/afk super` 及 `super always` 偏好设置。在超级省流模式下，客户端世界将被彻底冻结，实现最大化带宽节省。)
* **Safety Refinements:** Blocks inventory usage, drops, and interacts in Super AFK to avoid UI desync. Auto-exits on death (`PlayerDeathEvent`) to prevent respawn locks. (防刷安全机制：在超级省流状态下，全面锁定玩家的背包操作、物品丢弃与交互，彻底杜绝 UI 不同步导致的刷物品漏洞；若玩家意外死亡将自动强制解除挂机，防止出现幽灵存活卡死现象。)
* **High-Concurrency Lookup:** Uses O(1) HashSet for packet whitelisting to minimize CPU cycles on crowded Folia regions. (极速发包匹配：采用 O(1) 复杂度的 HashSet 进行白名单高速查表，将高并发下的网络线程开销降至冰点。)

---

> [!IMPORTANT]
> * **Dependency:** You **must** install [PacketEvents](https://github.com/retrooper/packetevents) for this plugin to function. (必须提前安装前置插件 PacketEvents 才能正常运行。)
> * **Note on Stats:** Traffic statistics represent **uncompressed** data. Actual billed bandwidth may differ due to server-side compression. (插件统计的数据量为未压缩的原始流量，由于各服务端配置的网络压缩阈值不同，实际节省的真实物理带宽可能存在一定偏差。)

---

## Features | 功能介绍

### 1. Dynamic View Distance | 动态视距控制
* Lowers client-side view distance for AFK players without affecting server-side simulation distance.
* 挂机期间主动调低该玩家的客户端视距（View Distance），从根源上减少区块数据的下发，且完全不会影响服务端的实际模拟距离（Simulation Distance）或刷怪农场。

### 2. AFK Detection & Modes | 挂机检测与模式
* **Perspective-Based:** Monitors camera rotation (Default: 300s). (自动检测：基于玩家视角（Camera）移动判定，默认为 300 秒无动作则自动判定为 AFK。)
* **Auto-Exit:** Automatically restores traffic flow upon taking damage or using teleport commands. (自动唤醒：当玩家受到伤害，或使用传送指令如 `/tp`, `/spawn` 跨越区块时，自动解除挂机状态以恢复地形加载。)
* **Automation Friendly:** Compatible with AFK pools and auto-clickers. (自动化友好：完全兼容连点器自动挥剑攻击以及被动的水流 AFK 挂机池。)
* **Manual AFK Mode:** Players can use `/afk` command to enter manual AFK mode that persists regardless of player movement. Use `/afk` again or rejoin the game to exit. (手动挂机：玩家可通过 `/afk` 主动进入挂机状态，此模式下完全免疫位移干扰。再次输入或重新进服即可退出。)
* **Super Bandwidth-Saving Mode:** Toggled with `/afk super`. Restricts virtually all incoming data packets. Forces the client to play in a static, frozen world. (超级省流模式：通过 `/afk super` 开启。插件会拦截除了心跳包、聊天信息、跨服通道以外的所有数据！客户端将在玩家眼前彻底冻结。**注意：退出该模式后玩家必须重新连接服务器以重载周围地形区块。**)
* **Preferred Auto-Super AFK:** Toggled with `/afk super always`. Persists in `super-always.yml` so players enter Super AFK automatically instead of normal ECO mode when idle. (默认超级省流偏好：通过 `/afk super always` 切换并记录。闲置超时后，玩家将跳过普通挂机，直接进入超级省流。**提示：由于是超时自动进入，玩家仍可通过晃动鼠标自动唤醒；无论哪种退出方式，最终都需重新连接服务器刷新区块。**)

### 3. Packet Filtering Details | 数据包过滤策略
* **Cancelled (100% Suppression) | 完全拦截:**
  * Animations, Block break, Sounds, Particles, Explosions, Time sync, Light updates, TAB list headers/footers, World events, Potion effects, Map data, etc.
  * 诸如生物动画、方块破坏进度、环境音效、粒子效果、爆炸、时间同步、光照更新、TAB 列表刷新、药水粒子等无关紧要的高频渲染包。
* **Throttled (Reduced Rate) | 频率削减:**
  * **2% Pass Rate (放行率):** Entity movement, Position, Velocity, Experience orbs. (实体移动/位置/速度、经验球掉落)
  * **5% Pass Rate (放行率):** Entity metadata. (实体元数据，如发光、隐身状态等)
  * **20% Pass Rate (放行率):** Head orientation. (实体头部朝向更新)
  * **100% Pass Rate (特别放行):** Armor Stands are 100% passed to avoid rendering bugs in AFK farms. (盔甲架实体的生成数据包 100% 放行，解决自动化农场中机器刷新盔甲架导致客户端隐身无法识别的问题。)
* **Special Handling for AFK Fishing | 自动钓鱼机特殊处理:**
  * When a player holds a fishing rod, certain packets (sound effects, entity velocity/movement) are allowed to pass through to support fishing activities.
  * 当检测到挂机玩家手持钓鱼竿时，特定数据包（如鱼漂音效、鱼漂实体位移等）将被特殊放行，确保自动钓鱼机不受任何影响。

---

## Commands & Permissions | 命令与权限

| Command (命令) | Description (说明) |
| :--- | :--- |
| `/bandwidthsaver` | View bandwidth saving stats (查看全服流量节省统计) |
| `/bandwidthsaver unfiltered` | View raw consumption (查看全服实际消耗统计) |
| `/bandwidthsaver reload` | Reload configuration (重载配置文件) |
| `/bandwidthsaver admin add/remove <player>` | Add or remove a player from auto Super AFK list (管理员特权：将某位玩家强制加入或移出自动超级省流名单) |
| `/afk` | Toggle manual AFK mode (切换手动普通挂机模式) |
| `/afk super` | Toggle manual Super AFK mode (切换手动超级省流模式) |
| `/afk super always` | Toggle preferred automatic Super AFK mode (切换超时自动进入超级省流的个人偏好) |

| Permission (权限节点) | Description (说明) |
| :--- | :--- |
| `bandwidthsaver.bypass` | Bypass AFK detection (拥有此权限的玩家将永远不会被判定为挂机) |
| `bandwidthsaver.admin` | Access admin commands (管理员权限，允许查看统计和使用管理命令) |

---

## Configuration | 配置文件

```yaml
# Calculate all packets (required for /bandwidthsaver unfiltered stats)
# 计算所有数据包（即启用 /bandwidthsaver unfiltered 的统计信息）
calcAllPackets: false
# Dynamically modify player view distance when AFK
# 是否在挂机时动态修改玩家视距
modifyPlayerViewDistance: false
# AFK perspective detection threshold in seconds (default: 300s / 5 mins)
# 视角检测 AFK 阈值（秒），默认为 300 秒（5 分钟）
afkPerspectiveThresholdSeconds: 300
# Whether to intercept TAB list related packets (default: true)
# Set to false if you notice the TAB list not updating, Ping not refreshing, or title display errors.
# 是否拦截 TAB 列表相关数据包 (默认为 true)
# 如果发现 TAB 列表不刷新、延迟 (Ping) 不更新或头衔显示错误，请将其设为 false
intercept-tab-list: true
# Whether to intercept chunk and light packets
# Enabling this saves a massive amount of bandwidth, but exiting AFK might result in void chunks
# that require the player to reconnect to load properly, affecting player experience.
# 是否拦截区块和光照数据包 
# 开启后可极大幅度节省流量，但退出 AFK 时可能产生只能重进才能加载的虚空区块，比较影响玩家体验
intercept-chunk-packets: false
# Whether to be compatible with dynamic view distance plugins like PVDC when force-refreshing chunks on exiting AFK (Valid only if the above option is true)
# When enabled, it delays for 2 seconds waiting for PVDC to calculate view distance, then does a "+1" view distance pull to prevent view distance from being locked.
# 退出 AFK 强制刷新区块时，是否兼容 PVDC 等动态视距插件 (仅在开启上一项时有效)
# 开启后，会延迟 2 秒等待 PVDC 结算视距，然后再进行"+1"的视距拉扯刷新，防止视距被锁死
compatible-with-pvdc: false
# ==========================================
# Target World Settings (Whitelist Mechanism) / 作用世界设置（白名单机制）
# ==========================================
# Players will only enter bandwidth saving mode in the worlds listed below.
# Worlds not in the list, such as lobbies or minigames, will automatically have the bandwidth saver disabled.
# 只有在以下列表中的世界，玩家才会进入省流模式。
# 像大厅 (lobby)、小游戏 (minigames) 等未在列表里的世界，会自动禁用省流功能。
enabled-worlds:
  - "world"
  - "world_nether"
  - "world_the_end"
# Debug mode switch. When enabled, it outputs intelligent filtering zone information in the console.
# 调试模式开关，开启后会在控制台输出智能过滤区域信息
debug: false
# ==========================================
# Language & Messages Settings / 语言与提示信息设置
# ==========================================
# You can translate or customize all messages sent to players below.
# 你可以在下方翻译或自定义所有发送给玩家的提示信息。
message:
  playerEcoEnable: '§a🍃 ECO 节能模式已启用，限制数据传输，可能会看着卡顿，实际正常，不会影响机器运行'
  playerEcoDisable: '§8🍃 ECO 节能模式已停用，数据传输将恢复正常'
  playerSuperEcoEnable: '§c⚠️ [超级省流模式已启用] 仅限挂机，不能进行任何操作/有视角要求的行为，退出后需重连以刷新区块。'
  playerSuperEcoDisable: '§c⚠️ 您已退出超级省流模式！为了恢复周围区块和实体的正常加载，请重新进入服务器。'
  superEcoDisable_special: '§e检测到特殊状态 (睡觉/飞行/世界切换)，已自动退出超级省流模式。为了恢复地形加载，建议重新进入服务器！'
  ecoDisable_special: '§e检测到特殊状态 (睡觉/飞行)，已自动关闭省流模式。'
  superEcoDisable_death: '§c您已在挂机期间死亡，超级省流模式已自动解除！为了重新加载周围区块，请重新连接服务器。'
  ecoDisable_death: '§e您已在挂机期间死亡，已为您自动退出手动 AFK 模式。'
  superEcoDisable_teleport: '§e检测到传送，已为您自动退出超级省流模式。为了恢复地形加载，建议重新进入服务器！'
  ecoDisable_teleport: '§e检测到传送，已为您自动退出手动 AFK 模式以加载地形。'
  superEcoDisable_glide: '§e检测到展开鞘翅，已自动退出超级省流模式。为了恢复地形加载，建议重新进入服务器！'
  ecoDisable_glide: '§e检测到展开鞘翅，已自动退出省流模式。'
  player_only: '§c此命令只能由玩家执行！'
  superAlways_disabled: '§e已关闭自动超级省流挂机状态！当您达到设定的挂机时间时，将进入普通挂机模式。'
  superAlways_enabled: '§a已开启自动超级省流挂机状态！当您达到设定的挂机时间时，将直接进入超级省流模式。'
  superAlways_confirm1: '§c⚠️ [进入确认] 您即将开启默认超级省流挂机偏好！'
  superAlways_confirm2: '§e在超级省流模式下，所有需要挂机玩家物理操作（如点击攻击、手动使用/放置方块等）的挂机机器均无法工作。'
  superAlways_confirm3: '§e此模式仅适用于纯被动型机器（如刷铁机、农作物机、全自动刷怪塔等）。'
  superAlways_confirm4: '§e且每次挂机唤醒均需重新登入服务器刷新区块。'
  superAlways_confirm5: '§b若您确认要默认启用该模式，请在 3 分钟内再次输入指令: §a/afk super always'
  superEco_exit: '§a您已退出超级省流模式！'
  superEco_confirm1: '§c⚠️ [进入确认] 您即将进入手动超级省流挂机模式！'
  superEco_confirm2: '§e在此模式下，任何需要您物理操作（如点击攻击、手动使用/放置方块等）的挂机机器均无法正常工作。'
  superEco_confirm3: '§e此模式仅适用于纯被动型机器（如刷铁机、农作物机、全自动刷怪塔等）。'
  superEco_confirm4: '§e且退出后必须重新进入服务器才能加载刷新周围地形区块。'
  superEco_confirm5: '§b若您确认要开启，请在 3 分钟内再次输入指令进行二次确认: §a/afk super'
  eco_exit: '§a您已退出手动 AFK 模式！'
  eco_enter: '§e您已进入手动 AFK 模式！再次输入/afk 退出此模式。'
  no_permission: '§cYou don''t have permission to use this command!'
  admin_player_not_found: '§c未找到玩家或该玩家从未进入过服务器：%player%'
  admin_already_in_list: '§e玩家 %player% 已经在自动超级省流挂机列表中了！'
  admin_added_to_list: '§a已将玩家 %player% 添加到自动超级省流挂机列表中！'
  admin_target_added: '§a管理员已将您添加到自动超级省流挂机列表中！'
  admin_not_in_list: '§e玩家 %player% 不在自动超级省流挂机列表中！'
  admin_removed_from_list: '§a已将玩家 %player% 从自动超级省流挂机列表中移除！'
  admin_target_removed: '§e管理员已将您从自动超级省流挂机列表中移除！'
  admin_usage: '§c未知操作，请使用 /bandwidthsaver admin add/remove <player>'
  stats_eco_title: '§a🍃 ECO 节能模式 - 统计信息：'
  stats_eco_packets: '§e共减少发送数据包：§b%count% 个'
  stats_eco_bytes: '§e共减少发送数据包：§b%size% （不包含视距优化的增益数据）'
  stats_eco_top_types: '§e -- 数据包类型节约 TOP 15 --'
  stats_eco_top_players: '§e -- 玩家流量节约 TOP 5 --'
  stats_uneco_title: '§a🍃 UN-ECO - 数据总计 - 统计信息：'
  stats_uneco_packets: '§e共发送数据包：§b%count% 个'
  stats_uneco_bytes: '§e共发送数据包：§b%size%'
  stats_uneco_top_types: '§e -- 数据包类型 TOP 15 --'
  stats_uneco_top_players: '§e -- 玩家流量 TOP 5 --'
  reload_success: '§a🍃 ECO - 配置文件已重载'
  stats_eco_type_item: '§7%type% - %count% 个 (%size%)'
  stats_eco_player_item: '§7%player% - %count% 个 (%size%)§a [挂机时长: %time%]'
  stats_uneco_player_item: '§7%player% - %count% 个 (%size%)'
# ==========================================
# BossBar Settings / Boss栏设置
# ==========================================
# You can customize the bossbar display here.
# 你可以在下方自定义上方 Boss 栏的显示。
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