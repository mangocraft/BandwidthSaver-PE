filepath = "src/main/resources/config.yml"

with open(filepath, "r", encoding="utf-8") as f:
    content = f.read()

replacements = [
    (
        "# 计算所有数据包（即启用 /riabandwidthsaver unfiltered 的统计信息）",
        "# Calculate all packets (required for /bandwidthsaver unfiltered stats)\n# 计算所有数据包（即启用 /bandwidthsaver unfiltered 的统计信息）"
    ),
    (
        "modifyPlayerViewDistance: false",
        "# Dynamically modify player view distance when AFK\n# 是否在挂机时动态修改玩家视距\nmodifyPlayerViewDistance: false"
    ),
    (
        "# 视角检测 AFK 阈值（秒），默认为 300 秒（5 分钟）",
        "# AFK perspective detection threshold in seconds (default: 300s / 5 mins)\n# 视角检测 AFK 阈值（秒），默认为 300 秒（5 分钟）"
    ),
    (
        "# 是否拦截 TAB 列表相关数据包 (默认为 true)\n# 如果发现 TAB 列表不刷新、延迟 (Ping) 不更新或头衔显示错误，请将其设为 false",
        "# Whether to intercept TAB list related packets (default: true)\n# Set to false if you notice the TAB list not updating, Ping not refreshing, or title display errors.\n# 是否拦截 TAB 列表相关数据包 (默认为 true)\n# 如果发现 TAB 列表不刷新、延迟 (Ping) 不更新或头衔显示错误，请将其设为 false"
    ),
    (
        "# 是否拦截区块和光照数据包 \n# 开启后可极大幅度节省流量，但退出 AFK 时可能产生只能重进才能加载的虚空区块，比较影响玩家体验",
        "# Whether to intercept chunk and light packets\n# Enabling this saves a massive amount of bandwidth, but exiting AFK might result in void chunks\n# that require the player to reconnect to load properly, affecting player experience.\n# 是否拦截区块和光照数据包 \n# 开启后可极大幅度节省流量，但退出 AFK 时可能产生只能重进才能加载的虚空区块，比较影响玩家体验"
    ),
    (
        "# 退出 AFK 强制刷新区块时，是否兼容 PVDC 等动态视距插件 (仅在开启上一项时有效)\n# 开启后，会延迟 2 秒等待 PVDC 结算视距，然后再进行\"+1\"的视距拉扯刷新，防止视距被锁死",
        "# Whether to be compatible with dynamic view distance plugins like PVDC when force-refreshing chunks on exiting AFK (Valid only if the above option is true)\n# When enabled, it delays for 2 seconds waiting for PVDC to calculate view distance, then does a \"+1\" view distance pull to prevent view distance from being locked.\n# 退出 AFK 强制刷新区块时，是否兼容 PVDC 等动态视距插件 (仅在开启上一项时有效)\n# 开启后，会延迟 2 秒等待 PVDC 结算视距，然后再进行\"+1\"的视距拉扯刷新，防止视距被锁死"
    ),
    (
        "# ==========================================\n# 作用世界设置（白名单机制）\n# ==========================================\n# 只有在以下列表中的世界，玩家才会进入省流模式。\n# 像大厅 (lobby)、小游戏 (minigames) 等未在列表里的世界，会自动禁用省流功能。",
        "# ==========================================\n# Target World Settings (Whitelist Mechanism) / 作用世界设置（白名单机制）\n# ==========================================\n# Players will only enter bandwidth saving mode in the worlds listed below.\n# Worlds not in the list, such as lobbies or minigames, will automatically have the bandwidth saver disabled.\n# 只有在以下列表中的世界，玩家才会进入省流模式。\n# 像大厅 (lobby)、小游戏 (minigames) 等未在列表里的世界，会自动禁用省流功能。"
    ),
    (
        "# 调试模式开关，开启后会在控制台输出智能过滤区域信息",
        "# Debug mode switch. When enabled, it outputs intelligent filtering zone information in the console.\n# 调试模式开关，开启后会在控制台输出智能过滤区域信息"
    )
]

for old, new in replacements:
    content = content.replace(old, new)

with open(filepath, "w", encoding="utf-8") as f:
    f.write(content)

print("Done")
