filepath = "src/main/java/com/mangocraft/plugins/bandwidthsaverpe/RIABandwidthSaver.java"

with open(filepath, "r", encoding="utf-8") as f:
    content = f.read()

replacements = [
    (
        'sender.sendMessage(ChatColor.GRAY + entry.getKey().toString() + " - " + entry.getValue().getPktCounter().longValue() + " 个 (" + humanReadableByteCount(entry.getValue().getPktSize().longValue(), false) + ")");',
        'sender.sendMessage(getConfig().getString("message.stats_eco_type_item", "§7%type% - %count% 个 (%size%)").replace("%type%", entry.getKey().toString()).replace("%count%", String.valueOf(entry.getValue().getPktCounter().longValue())).replace("%size%", humanReadableByteCount(entry.getValue().getPktSize().longValue(), false)));'
    ),
    (
        'sender.sendMessage(ChatColor.GRAY + name + " - " + pkts + " 个 (" + size + ")" + ChatColor.GREEN + " [挂机时长: " + formatAfkTime(totalAfk) + "]");',
        'sender.sendMessage(getConfig().getString("message.stats_eco_player_item", "§7%player% - %count% 个 (%size%)§a [挂机时长: %time%]").replace("%player%", name).replace("%count%", String.valueOf(pkts)).replace("%size%", size).replace("%time%", formatAfkTime(totalAfk)));'
    ),
    (
        'sender.sendMessage(ChatColor.GRAY + Bukkit.getOfflinePlayer(entry.getKey()).getName() + " - " + entry.getValue().getPktCounter().longValue() + " 个 (" + humanReadableByteCount(entry.getValue().getPktSize().longValue(), false) + ")");',
        'sender.sendMessage(getConfig().getString("message.stats_uneco_player_item", "§7%player% - %count% 个 (%size%)").replace("%player%", Bukkit.getOfflinePlayer(entry.getKey()).getName()).replace("%count%", String.valueOf(entry.getValue().getPktCounter().longValue())).replace("%size%", humanReadableByteCount(entry.getValue().getPktSize().longValue(), false)));'
    )
]

for old, new in replacements:
    content = content.replace(old, new)

with open(filepath, "w", encoding="utf-8") as f:
    f.write(content)
print("Done concat")
