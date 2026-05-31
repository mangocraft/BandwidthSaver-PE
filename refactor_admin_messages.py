import re

filepath = "src/main/java/com/mangocraft/plugins/bandwidthsaverpe/RIABandwidthSaver.java"

with open(filepath, "r", encoding="utf-8") as f:
    content = f.read()

replacements = [
    (
        'sender.sendMessage(ChatColor.RED + "You don\'t have permission to use this command!");',
        'sender.sendMessage(getConfig().getString("message.no_permission", "§cYou don\'t have permission to use this command!"));'
    ),
    (
        'sender.sendMessage(ChatColor.RED + "未找到玩家或该玩家从未进入过服务器：" + targetName);',
        'sender.sendMessage(getConfig().getString("message.admin_player_not_found", "§c未找到玩家或该玩家从未进入过服务器：").replace("%player%", targetName));'
    ),
    (
        'sender.sendMessage(ChatColor.YELLOW + "玩家 " + tName + " 已经在自动超级省流挂机列表中了！");',
        'sender.sendMessage(getConfig().getString("message.admin_already_in_list", "§e玩家 %player% 已经在自动超级省流挂机列表中了！").replace("%player%", tName));'
    ),
    (
        'sender.sendMessage(ChatColor.GREEN + "已将玩家 " + tName + " 添加到自动超级省流挂机列表中！");',
        'sender.sendMessage(getConfig().getString("message.admin_added_to_list", "§a已将玩家 %player% 添加到自动超级省流挂机列表中！").replace("%player%", tName));'
    ),
    (
        'target.getPlayer().sendMessage(ChatColor.GREEN + "管理员已将您添加到自动超级省流挂机列表中！");',
        'target.getPlayer().sendMessage(getConfig().getString("message.admin_target_added", "§a管理员已将您添加到自动超级省流挂机列表中！"));'
    ),
    (
        'sender.sendMessage(ChatColor.YELLOW + "玩家 " + tName + " 不在自动超级省流挂机列表中！");',
        'sender.sendMessage(getConfig().getString("message.admin_not_in_list", "§e玩家 %player% 不在自动超级省流挂机列表中！").replace("%player%", tName));'
    ),
    (
        'sender.sendMessage(ChatColor.GREEN + "已将玩家 " + tName + " 从自动超级省流挂机列表中移除！");',
        'sender.sendMessage(getConfig().getString("message.admin_removed_from_list", "§a已将玩家 %player% 从自动超级省流挂机列表中移除！").replace("%player%", tName));'
    ),
    (
        'target.getPlayer().sendMessage(ChatColor.YELLOW + "管理员已将您从自动超级省流挂机列表中移除！");',
        'target.getPlayer().sendMessage(getConfig().getString("message.admin_target_removed", "§e管理员已将您从自动超级省流挂机列表中移除！"));'
    ),
    (
        'sender.sendMessage(ChatColor.RED + "未知操作，请使用 /bandwidthsaver admin add/remove <player>");',
        'sender.sendMessage(getConfig().getString("message.admin_usage", "§c未知操作，请使用 /bandwidthsaver admin add/remove <player>"));'
    ),
    (
        'sender.sendMessage(ChatColor.GREEN + "🍃 ECO 节能模式 - 统计信息：");',
        'sender.sendMessage(getConfig().getString("message.stats_eco_title", "§a🍃 ECO 节能模式 - 统计信息："));'
    ),
    (
        'sender.sendMessage(ChatColor.YELLOW + "共减少发送数据包：" + ChatColor.AQUA + pktCancelled + " 个");',
        'sender.sendMessage(getConfig().getString("message.stats_eco_packets", "§e共减少发送数据包：§b%count% 个").replace("%count%", String.valueOf(pktCancelled)));'
    ),
    (
        'sender.sendMessage(ChatColor.YELLOW + "共减少发送数据包：" + ChatColor.AQUA + humanReadableByteCount(pktSizeSaved, false) + " （不包含视距优化的增益数据）");',
        'sender.sendMessage(getConfig().getString("message.stats_eco_bytes", "§e共减少发送数据包：§b%size% （不包含视距优化的增益数据）").replace("%size%", humanReadableByteCount(pktSizeSaved, false)));'
    ),
    (
        'sender.sendMessage(ChatColor.YELLOW + " -- 数据包类型节约 TOP 15 --");',
        'sender.sendMessage(getConfig().getString("message.stats_eco_top_types", "§e -- 数据包类型节约 TOP 15 --"));'
    ),
    (
        'sender.sendMessage(ChatColor.YELLOW + " -- 玩家流量节约 TOP 5 --");',
        'sender.sendMessage(getConfig().getString("message.stats_eco_top_players", "§e -- 玩家流量节约 TOP 5 --"));'
    ),
    (
        'sender.sendMessage(ChatColor.GREEN + "🍃 UN-ECO - 数据总计 - 统计信息：");',
        'sender.sendMessage(getConfig().getString("message.stats_uneco_title", "§a🍃 UN-ECO - 数据总计 - 统计信息："));'
    ),
    (
        'sender.sendMessage(ChatColor.YELLOW + "共发送数据包：" + ChatColor.AQUA + pktSent + " 个");',
        'sender.sendMessage(getConfig().getString("message.stats_uneco_packets", "§e共发送数据包：§b%count% 个").replace("%count%", String.valueOf(pktSent)));'
    ),
    (
        'sender.sendMessage(ChatColor.YELLOW + "共发送数据包：" + ChatColor.AQUA + humanReadableByteCount(pktSize, false));',
        'sender.sendMessage(getConfig().getString("message.stats_uneco_bytes", "§e共发送数据包：§b%size%").replace("%size%", humanReadableByteCount(pktSize, false)));'
    ),
    (
        'sender.sendMessage(ChatColor.YELLOW + " -- 数据包类型 TOP 15 --");',
        'sender.sendMessage(getConfig().getString("message.stats_uneco_top_types", "§e -- 数据包类型 TOP 15 --"));'
    ),
    (
        'sender.sendMessage(ChatColor.YELLOW + " -- 玩家流量 TOP 5 --");',
        'sender.sendMessage(getConfig().getString("message.stats_uneco_top_players", "§e -- 玩家流量 TOP 5 --"));'
    ),
    (
        'sender.sendMessage(ChatColor.GREEN + "🍃 ECO - 配置文件已重载");',
        'sender.sendMessage(getConfig().getString("message.reload_success", "§a🍃 ECO - 配置文件已重载"));'
    )
]

# Specifically replace the targetName and replace issue by just finding the exact text blocks
for old, new in replacements:
    content = content.replace(old, new)
    
with open(filepath, "w", encoding="utf-8") as f:
    f.write(content)
print("Done admin")
