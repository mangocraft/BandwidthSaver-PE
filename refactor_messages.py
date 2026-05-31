import re

filepath = "src/main/java/com/mangocraft/plugins/bandwidthsaverpe/RIABandwidthSaver.java"

with open(filepath, "r", encoding="utf-8") as f:
    content = f.read()

replacements = [
    (
        'player.sendMessage(ChatColor.YELLOW + "检测到特殊状态 (睡觉/飞行/世界切换)，已自动退出超级省流模式。为了恢复地形加载，建议重新进入服务器！");',
        'sendRichMessage(player, getConfig().getString("message.superEcoDisable_special", "§e检测到特殊状态 (睡觉/飞行/世界切换)，已自动退出超级省流模式。为了恢复地形加载，建议重新进入服务器！"));'
    ),
    (
        'player.sendMessage(ChatColor.YELLOW + "检测到特殊状态 (睡觉/飞行)，已自动关闭省流模式。");',
        'sendRichMessage(player, getConfig().getString("message.ecoDisable_special", "§e检测到特殊状态 (睡觉/飞行)，已自动关闭省流模式。"));'
    ),
    (
        'player.sendMessage(ChatColor.RED + "您已在挂机期间死亡，超级省流模式已自动解除！为了重新加载周围区块，请重新连接服务器。");',
        'sendRichMessage(player, getConfig().getString("message.superEcoDisable_death", "§c您已在挂机期间死亡，超级省流模式已自动解除！为了重新加载周围区块，请重新连接服务器。"));'
    ),
    (
        'player.sendMessage(ChatColor.YELLOW + "您已在挂机期间死亡，已为您自动退出手动 AFK 模式。");',
        'sendRichMessage(player, getConfig().getString("message.ecoDisable_death", "§e您已在挂机期间死亡，已为您自动退出手动 AFK 模式。"));'
    ),
    (
        'player.sendMessage(ChatColor.YELLOW + "检测到传送，已为您自动退出超级省流模式。为了恢复地形加载，建议重新进入服务器！");',
        'sendRichMessage(player, getConfig().getString("message.superEcoDisable_teleport", "§e检测到传送，已为您自动退出超级省流模式。为了恢复地形加载，建议重新进入服务器！"));'
    ),
    (
        'player.sendMessage(ChatColor.YELLOW + "检测到传送，已为您自动退出手动 AFK 模式以加载地形。");',
        'sendRichMessage(player, getConfig().getString("message.ecoDisable_teleport", "§e检测到传送，已为您自动退出手动 AFK 模式以加载地形。"));'
    ),
    (
        'player.sendMessage(ChatColor.YELLOW + "检测到展开鞘翅，已自动退出超级省流模式。为了恢复地形加载，建议重新进入服务器！");',
        'sendRichMessage(player, getConfig().getString("message.superEcoDisable_glide", "§e检测到展开鞘翅，已自动退出超级省流模式。为了恢复地形加载，建议重新进入服务器！"));'
    ),
    (
        'player.sendMessage(ChatColor.YELLOW + "检测到展开鞘翅，已自动退出省流模式。");',
        'sendRichMessage(player, getConfig().getString("message.ecoDisable_glide", "§e检测到展开鞘翅，已自动退出省流模式。"));'
    ),
    (
        'sender.sendMessage(ChatColor.RED + "此命令只能由玩家执行！");',
        'sender.sendMessage(getConfig().getString("message.player_only", "§c此命令只能由玩家执行！"));'
    ),
    (
        'player.sendMessage(ChatColor.YELLOW + "已关闭自动超级省流挂机状态！当您达到设定的挂机时间时，将进入普通挂机模式。");',
        'sendRichMessage(player, getConfig().getString("message.superAlways_disabled", "§e已关闭自动超级省流挂机状态！当您达到设定的挂机时间时，将进入普通挂机模式。"));'
    ),
    (
        'player.sendMessage(ChatColor.GREEN + "已开启自动超级省流挂机状态！当您达到设定的挂机时间时，将直接进入超级省流模式。");',
        'sendRichMessage(player, getConfig().getString("message.superAlways_enabled", "§a已开启自动超级省流挂机状态！当您达到设定的挂机时间时，将直接进入超级省流模式。"));'
    ),
    (
        'player.sendMessage(ChatColor.RED + "⚠️ [进入确认] 您即将开启默认超级省流挂机偏好！");',
        'sendRichMessage(player, getConfig().getString("message.superAlways_confirm1", "§c⚠️ [进入确认] 您即将开启默认超级省流挂机偏好！"));'
    ),
    (
        'player.sendMessage(ChatColor.YELLOW + "在超级省流模式下，所有需要挂机玩家物理操作（如点击攻击、手动使用/放置方块等）的挂机机器均无法工作。");',
        'sendRichMessage(player, getConfig().getString("message.superAlways_confirm2", "§e在超级省流模式下，所有需要挂机玩家物理操作（如点击攻击、手动使用/放置方块等）的挂机机器均无法工作。"));'
    ),
    (
        'player.sendMessage(ChatColor.YELLOW + "此模式仅适用于纯被动型机器（如刷铁机、农作物机、全自动刷怪塔等）。");',
        'sendRichMessage(player, getConfig().getString("message.superAlways_confirm3", "§e此模式仅适用于纯被动型机器（如刷铁机、农作物机、全自动刷怪塔等）。"));'
    ),
    (
        'player.sendMessage(ChatColor.YELLOW + "且每次挂机唤醒均需重新登入服务器刷新区块。");',
        'sendRichMessage(player, getConfig().getString("message.superAlways_confirm4", "§e且每次挂机唤醒均需重新登入服务器刷新区块。"));'
    ),
    (
        'player.sendMessage(ChatColor.AQUA + "若您确认要默认启用该模式，请在 3 分钟内再次输入指令: " + ChatColor.GREEN + "/afk super always");',
        'sendRichMessage(player, getConfig().getString("message.superAlways_confirm5", "§b若您确认要默认启用该模式，请在 3 分钟内再次输入指令: §a/afk super always"));'
    ),
    (
        'player.sendMessage(ChatColor.GREEN + "您已退出超级省流模式！");',
        'sendRichMessage(player, getConfig().getString("message.superEco_exit", "§a您已退出超级省流模式！"));'
    ),
    (
        'player.sendMessage(ChatColor.RED + "⚠️ [进入确认] 您即将进入手动超级省流挂机模式！");',
        'sendRichMessage(player, getConfig().getString("message.superEco_confirm1", "§c⚠️ [进入确认] 您即将进入手动超级省流挂机模式！"));'
    ),
    (
        'player.sendMessage(ChatColor.YELLOW + "在此模式下，任何需要您物理操作（如点击攻击、手动使用/放置方块等）的挂机机器均无法正常工作。");',
        'sendRichMessage(player, getConfig().getString("message.superEco_confirm2", "§e在此模式下，任何需要您物理操作（如点击攻击、手动使用/放置方块等）的挂机机器均无法正常工作。"));'
    ),
    (
        'player.sendMessage(ChatColor.YELLOW + "此模式仅适用于纯被动型机器（如刷铁机、农作物机、全自动刷怪塔等）。");',
        'sendRichMessage(player, getConfig().getString("message.superEco_confirm3", "§e此模式仅适用于纯被动型机器（如刷铁机、农作物机、全自动刷怪塔等）。"));'
    ),
    (
        'player.sendMessage(ChatColor.YELLOW + "且退出后必须重新进入服务器才能加载刷新周围地形区块。");',
        'sendRichMessage(player, getConfig().getString("message.superEco_confirm4", "§e且退出后必须重新进入服务器才能加载刷新周围地形区块。"));'
    ),
    (
        'player.sendMessage(ChatColor.AQUA + "若您确认要开启，请在 3 分钟内再次输入指令进行二次确认: " + ChatColor.GREEN + "/afk super");',
        'sendRichMessage(player, getConfig().getString("message.superEco_confirm5", "§b若您确认要开启，请在 3 分钟内再次输入指令进行二次确认: §a/afk super"));'
    ),
    (
        'player.sendMessage(ChatColor.GREEN + "您已退出手动 AFK 模式！");',
        'sendRichMessage(player, getConfig().getString("message.eco_exit", "§a您已退出手动 AFK 模式！"));'
    ),
    (
        'player.sendMessage(ChatColor.YELLOW + "您已进入手动 AFK 模式！再次输入/afk 退出此模式。");',
        'sendRichMessage(player, getConfig().getString("message.eco_enter", "§e您已进入手动 AFK 模式！再次输入/afk 退出此模式。"));'
    )
]

for old, new in replacements:
    content = content.replace(old, new)

with open(filepath, "w", encoding="utf-8") as f:
    f.write(content)
print("Done")
