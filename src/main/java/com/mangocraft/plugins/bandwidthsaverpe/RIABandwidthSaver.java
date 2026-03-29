package com.mangocraft.plugins.bandwidthsaverpe;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockAction;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import io.netty.buffer.ByteBuf;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class RIABandwidthSaver extends JavaPlugin implements Listener {
    // 视角AFK检测相关数据结构
    private final Set<UUID> AFK_PLAYERS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, Float> LAST_YAW = new ConcurrentHashMap<>(); // 记录玩家最后的yaw（左右视角）
    private final Map<UUID, Float> LAST_PITCH = new ConcurrentHashMap<>(); // 记录玩家最后的pitch（上下视角）
    private final Map<UUID, Long> LAST_HEAD_MOVEMENT_TIME = new ConcurrentHashMap<>(); // 记录最后头部移动时间
    private final Map<UUID, Long> ENTER_AFK_TIME = new ConcurrentHashMap<>(); // 记录进入AFK的时间
    private static final float HEAD_MOVEMENT_THRESHOLD = 45.0f; // 视角移动阈值（度）
    private long afkThresholdMs = 300000; // AFK阈值：5分钟（毫秒），可从配置文件修改
    private static final long MIN_HEAD_MOVEMENT_INTERVAL_MS = 1000; // 最小头部移动检测间隔：1秒
    

    

    
    private final Map<Object, PacketInfo> PKT_TYPE_STATS = new ConcurrentHashMap<>();
    private final Map<UUID, PacketInfo> PLAYER_PKT_SAVED_STATS = new ConcurrentHashMap<>();
    private final Map<Object, PacketInfo> UNFILTERED_PKT_TYPE_STATS = new ConcurrentHashMap<>();
    private final Map<UUID, PacketInfo> UNFILTERED_PLAYER_PKT_SAVED_STATS = new ConcurrentHashMap<>();
    private boolean calcAllPackets = false;
    private final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(2);


    // ECO模式BossBar相关数据结构
    private final Map<UUID, UUID> ECO_BAR_UUIDS = new ConcurrentHashMap<>(); // 存储 <玩家UUID, ECO条的UUID>
    
    // 硬核AFK模式相关数据结构
    private final Set<UUID> HARDCORE_AFK_PLAYERS = java.util.concurrent.ConcurrentHashMap.newKeySet(); // 存储处于硬核AFK模式的玩家
    
    // Folia玩家专用任务相关数据结构
    private final Map<UUID, io.papermc.paper.threadedregions.scheduler.ScheduledTask> PLAYER_TASKS = new ConcurrentHashMap<>(); // 存储每个玩家的专用任务
    
    // 钓鱼检测相关数据结构
    private final Map<UUID, Boolean> IS_FISHING_CACHE = new ConcurrentHashMap<>(); // 存储玩家是否在钓鱼的状态

    private com.github.retrooper.packetevents.PacketEventsAPI packetEventsAPI;

    @Override
    public void onEnable() {
        // Plugin startup logic
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        
        // Initialize PacketEvents
        packetEventsAPI = PacketEvents.getAPI();
        packetEventsAPI.getSettings()
                .checkForUpdates(false)
                .bStats(true);
        packetEventsAPI.load();
        

        
        // Register packet listener
        packetEventsAPI.getEventManager().registerListener(new BandwidthSaverListener());
        
        reloadConfig();
        
        // 为现有在线玩家创建专用任务
        for (Player player : Bukkit.getOnlinePlayers()) {
            schedulePlayerTask(player);
        }
    }
    
    private class BandwidthSaverListener extends PacketListenerAbstract {
        protected BandwidthSaverListener() {
            super(PacketListenerPriority.HIGHEST);
        }

        @Override
        public void onPacketSend(PacketSendEvent event) {
            // Get the player from the event
            User user = event.getUser();
            UUID userUUID = user.getUUID();
            
            // Check if UUID is null (can happen during connection establishment)
            if (userUUID == null) {
                return;
            }
            
            Player player = Bukkit.getPlayer(userUUID);
            
            if (player == null) {
                return;
            }
            
            UUID uuid = player.getUniqueId();
            
            // Handle unfiltered statistics if enabled - READ PACKET SIZE IN MAIN THREAD BEFORE ANY CANCELLATIONS
            if (calcAllPackets) {
                long packetSize = getPacketSizeFromEvent(event); // Read in main thread before cancellation
                Object packetType = event.getPacketType();
                
                // Use LongAdder directly for high concurrency performance
                UNFILTERED_PKT_TYPE_STATS.computeIfAbsent(packetType, k -> new PacketInfo()).addValues(1, packetSize);
                UNFILTERED_PLAYER_PKT_SAVED_STATS.computeIfAbsent(uuid, k -> new PacketInfo()).addValues(1, packetSize);
            }
            
            // Check if player is AFK
            if (!AFK_PLAYERS.contains(uuid)) {
                return;
            }
            
            // 检查玩家是否在钓鱼，如果是，则对某些数据包放行
            boolean isFishing = IS_FISHING_CACHE.getOrDefault(uuid, false);
            
            // READ PACKET SIZE IN MAIN THREAD BEFORE CANCELLATION - CRITICAL FOR BYTEBUF LIFECYCLE
            long packetSize = getPacketSizeFromEvent(event); // Read in main thread before cancellation
            
            // --- ✅ 修正开始：使用 PacketType 枚举对比 ---
            com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon type = event.getPacketType();

            // 如果玩家在钓鱼，对特定数据包放行
            if (isFishing) {
                if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.SOUND_EFFECT ||
                    type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_SOUND_EFFECT ||
                    type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_VELOCITY ||
                    type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_RELATIVE_MOVE ||
                    type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
                    // 对钓鱼玩家放行声音效果和鱼漂动画相关数据包
                    return; // 不取消数据包，让其通过
                }
            }

            // 1. 完全取消的数据包 (直接列出 PacketType)
            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_ANIMATION ||
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.BLOCK_BREAK_ANIMATION ||
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.SOUND_EFFECT ||
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_SOUND_EFFECT || // 注意：Named Sound 和 Entity Sound
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.PARTICLE ||
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.EXPLOSION ||
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.DAMAGE_EVENT ||     // 1.19.4+
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.PLAYER_LIST_HEADER_AND_FOOTER || // 修正名称
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.MAP_DATA ||
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.PLAYER_INFO_UPDATE ||
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.UPDATE_LIGHT || // 光照更新 - 节省大量流量
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_TELEPORT) { // 实体传送 - 全部拦截 ENTITY_TELEPORT
                
                event.setCancelled(true);
                handleCancelledPacketWithSize(event, uuid, packetSize);
                return;
            }
            
            // 特殊处理：BOSS_BAR - 白名单机制 (零分配极速版)
            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.BOSS_BAR) {
                io.netty.buffer.ByteBuf buf = (io.netty.buffer.ByteBuf) event.getByteBuf();
                // UUID 长度为 16 字节 (两个 8 字节的 long)
                if (buf != null && buf.readableBytes() >= 16) {
                    buf.markReaderIndex();
                    long mostSigBits = buf.readLong();  // 读取 UUID 高位
                    long leastSigBits = buf.readLong(); // 读取 UUID 低位
                    buf.resetReaderIndex();
                    
                    // 获取允许通过的 ECO BossBar UUID
                    UUID allowedUuid = ECO_BAR_UUIDS.get(uuid);
                    
                    // 直接进行 long 基础数据类型对比！不产生任何临时 UUID 对象！速度极快！
                    if (allowedUuid != null 
                        && allowedUuid.getMostSignificantBits() == mostSigBits 
                        && allowedUuid.getLeastSignificantBits() == leastSigBits) {
                        return; // 匹配成功，放行 ECO 提示条
                    }
                    
                    // 不匹配，直接拦截，完全避开了庞大的 BossBar 内容解析
                    event.setCancelled(true);
                    handleCancelledPacketWithSize(event, uuid, packetSize);
                    return;
                }
            }

            // 2. 特殊处理：受伤动画 (EntityStatus) - 零分配极速读取
            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_STATUS) {
                io.netty.buffer.ByteBuf buf = (io.netty.buffer.ByteBuf) event.getByteBuf();
                // 确保数据包长度足够 (int 4 字节 + byte 1 字节 = 5 字节)
                if (buf != null && buf.readableBytes() >= 5) {
                    buf.markReaderIndex(); // ⚠️ 必须：标记当前读取指针位置
                    buf.skipBytes(4);      // 跳过前 4 个字节的 Entity ID
                    byte status = buf.readByte(); // 读取第 5 个字节（状态码）
                    buf.resetReaderIndex(); // ⚠️ 必须：把读取指针归位！否则后续放行的话服务端会读错数据导致断开连接
                    
                    if (status == 2) { // 2 代表受伤变红
                        event.setCancelled(true);
                        handleCancelledPacketWithSize(event, uuid, packetSize);
                    }
                }
                return;
            }

            // 3. 概率过滤的数据包
            // 实体移动类 (修正了名称)
            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_RELATIVE_MOVE ||
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION ||
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_ROTATION || // 原代码的 ENTITY_LOOK
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_VELOCITY) {
                
                if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < 0.02) { // 2% 放行
                    return;
                }
                event.setCancelled(true);
                handleCancelledPacketWithSize(event, uuid, packetSize);
                return;
            }

            // 实体生成 (SPAWN_ENTITY) - 依然保持 50% 拦截来省流量，但不再记录 ID
            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.SPAWN_ENTITY) {
                if (java.util.concurrent.ThreadLocalRandom.current().nextInt(2) > 0) { // 50% 放行
                    return;
                }
                event.setCancelled(true);
                handleCancelledPacketWithSize(event, uuid, packetSize);
                return;
            }

            // 实体销毁 (DESTROY_ENTITIES) - 100% 放行！
            // 解决死掉的生物一直红色的倒在地上不消失的 Bug
            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.DESTROY_ENTITIES) {
                return; // 绝不拦截，让客户端正常清理尸体
            }

            // 头部旋转
            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_HEAD_LOOK) {
                if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < 0.20) {
                    return;
                }
                event.setCancelled(true);
                handleCancelledPacketWithSize(event, uuid, packetSize);
                return;
            }
            
            // 元数据更新
            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_METADATA) {
                 if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < 0.05) {
                    return;
                }
                event.setCancelled(true);
                handleCancelledPacketWithSize(event, uuid, packetSize);
                return;
            }

            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.BLOCK_ACTION) {
                // BLOCK_ACTION: 全部通过，不进行拦截 - 取消拦截
                return; // Don't cancel, allow through
            }

            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.BLOCK_CHANGE) {
                // 不再过滤BLOCK_CHANGE数据包，直接允许通过 - 解决过多bug问题
                return; // Don't cancel, allow through
            }

            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
                // MULTI_BLOCK_CHANGE: 全部通过，不进行拦截 - 避免方块状态同步问题
                return; // Don't cancel, allow through
            }
        }

        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            // We don't need to handle received packets in this plugin
        }
    }

    /**
     * 为指定玩家调度专用任务
     * @param player 玩家
     */
    private void schedulePlayerTask(Player player) {
        UUID playerId = player.getUniqueId();
        
        // 如果已有任务，先取消
        if (PLAYER_TASKS.containsKey(playerId)) {
            PLAYER_TASKS.get(playerId).cancel();
        }
        
        // 创建针对单个玩家的专用任务
        io.papermc.paper.threadedregions.scheduler.ScheduledTask task = player.getScheduler().runAtFixedRate(
            this,
            scheduledTask -> {
                // 确保玩家仍在线
                if (!player.isOnline()) {
                    // 玩家离线，取消任务
                    scheduledTask.cancel();
                    return;
                }
                
                UUID uuid = player.getUniqueId();
                
                // 检查玩家是否有绕过权限
                if (player.hasPermission("bandwidthsaver.bypass")) {
                    // 如果玩家有绕过权限且处于AFK状态，则退出AFK
                    if (AFK_PLAYERS.contains(uuid)) {
                        playerEcoDisable(player);
                    }
                    return; // 跳过对该玩家的AFK检查
                }
                
                // 检查是否为手动AFK模式
                if (HARDCORE_AFK_PLAYERS.contains(uuid)) {
                    // 手动AFK模式下，强制保持AFK状态
                    if (!AFK_PLAYERS.contains(uuid)) {
                        playerEcoEnable(player);
                    }
                    return; // 跳过常规AFK检查
                }
                
                // 检查玩家是否不在AFK状态且应该进入AFK状态
                if (!AFK_PLAYERS.contains(uuid)) {
                    Long lastHeadMovementTime = LAST_HEAD_MOVEMENT_TIME.get(uuid);
                    
                    if (lastHeadMovementTime != null) {
                        long currentTime = System.currentTimeMillis();
                        long timeSinceLastHeadMovement = currentTime - lastHeadMovementTime;
                        
                        // 如果头部在一段时间内没有显著移动，则进入AFK状态
                        if (timeSinceLastHeadMovement >= afkThresholdMs) {
                            playerEcoEnable(player);
                            ENTER_AFK_TIME.put(uuid, currentTime); // 记录进入AFK的时间
                        }
                    }
                }
                
                // 更新钓鱼状态缓存
                updateFishingStatus(player);
            },
            null,
            20L,  // 初始延迟 1 秒
            20L   // 每秒执行一次 (20 ticks)
        );
        
        // 存储任务引用以便后续取消
        PLAYER_TASKS.put(playerId, task);
    }
    
    /**
     * 更新玩家的钓鱼状态
     * @param player 玩家
     */
    private void updateFishingStatus(Player player) {
        UUID playerId = player.getUniqueId();
        
        // 检查玩家主手或副手是否持有钓鱼竿
        boolean isHoldingFishingRod = player.getInventory().getItemInMainHand().getType() == org.bukkit.Material.FISHING_ROD ||
                                     player.getInventory().getItemInOffHand().getType() == org.bukkit.Material.FISHING_ROD;
        
        // 更新钓鱼状态缓存
        IS_FISHING_CACHE.put(playerId, isHoldingFishingRod);
    }
    
    /**
     * 初始化玩家视角跟踪
     * @param player 玩家
     */
    private void initializePlayerHeadTracking(Player player) {
        UUID playerId = player.getUniqueId();
        // 初始化玩家的视角信息
        LAST_YAW.put(playerId, player.getLocation().getYaw());
        LAST_PITCH.put(playerId, player.getLocation().getPitch());
        // 只在不存在时才初始化最后头部移动时间
        LAST_HEAD_MOVEMENT_TIME.putIfAbsent(playerId, System.currentTimeMillis());
    }
    
    // 新的视角检测机制不需要这些活动记录方法

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        this.calcAllPackets = getConfig().getBoolean("calcAllPackets", true);
        
        // Load AFK threshold for perspective-based detection (in seconds, convert to milliseconds)
        int afkThresholdSeconds = getConfig().getInt("afkPerspectiveThresholdSeconds", 300); // Default to 5 minutes
        this.afkThresholdMs = afkThresholdSeconds * 1000L; // Convert seconds to milliseconds
        
        // Since we register the listener once at startup, we don't need to re-register
        // Just reconfigure the plugin settings
        this.calcAllPackets = getConfig().getBoolean("calcAllPackets", true);
    }

    private void initPacketEvents() {
        // Already registered via BandwidthSaverListener class
    }
    
    private void handleCancelledPacket(PacketSendEvent event, UUID uuid) {
        // For backward compatibility - read packet size in this method
        long packetSize = getPacketSizeFromEvent(event);
        handleCancelledPacketWithSize(event, uuid, packetSize);
    }
    
    private void handleCancelledPacketWithSize(PacketSendEvent event, UUID uuid, long packetSize) {
        // Process cancelled packet statistics using LongAdder directly for high concurrency
        Object packetType = event.getPacketType();
        
        // Use computeIfAbsent with LongAdder's add() method for better performance on Folia
        PKT_TYPE_STATS.computeIfAbsent(packetType, k -> new PacketInfo()).addValues(1, packetSize);
        PLAYER_PKT_SAVED_STATS.computeIfAbsent(uuid, k -> new PacketInfo()).addValues(1, packetSize);
    }
    
    private long getPacketSizeFromEvent(PacketSendEvent event) {
        try {
            Object rawBuffer = event.getByteBuf();
            if (rawBuffer != null) {
                ByteBuf byteBuf = (ByteBuf) rawBuffer;
                return ByteBufHelper.readableBytes(byteBuf);
            } else {
                return 0L;
            }
        } catch (Exception e) {
            return -1L;
        }
    }



    public void playerEcoEnable(Player player) {
        String message = getConfig().getString("message.playerEcoEnable", "");
        if(!message.isEmpty()){
            player.sendMessage(message);
        }
        if(getConfig().getBoolean("modifyPlayerViewDistance")) {
                    player.setSendViewDistance(8);
                }
        
        // 创建ECO模式BossBar提示
        UUID playerUuid = player.getUniqueId();
        UUID ecoBarUuid = UUID.randomUUID(); // 生成随机UUID作为ECO条的ID
        ECO_BAR_UUIDS.put(playerUuid, ecoBarUuid);

        // 使用PacketEvents发送BossBar数据包
        try {
            // 从配置文件获取BossBar参数
            String titleText = getConfig().getString("bossbar.eco-enabled-title", "<green><bold>🍃 ECO 节能模式</bold> <gray>|</gray> <yellow>⬇ 已暂停高频数据传输</yellow> <gray>|</gray> <white>↔ 轻晃视角以恢复</white>");
            float health = (float) getConfig().getDouble("bossbar.eco-enabled-health", 1.0);
            String colorStr = getConfig().getString("bossbar.eco-enabled-color", "YELLOW");
            String overlayStr = getConfig().getString("bossbar.eco-enabled-overlay", "PROGRESS");
            
            // 构建BossBar标题组件
            Component title = MiniMessage.miniMessage().deserialize(titleText);
            
            // 创建ADD类型的BossBar包
            WrapperPlayServerBossBar bossBarPacket = new WrapperPlayServerBossBar(
                ecoBarUuid,
                WrapperPlayServerBossBar.Action.ADD
            );
            
            // 设置BossBar属性
            bossBarPacket.setTitle(title);
            bossBarPacket.setHealth(health);
            
            // 根据配置设置颜色
            net.kyori.adventure.bossbar.BossBar.Color color;
            try {
                color = net.kyori.adventure.bossbar.BossBar.Color.valueOf(colorStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid bossbar color: " + colorStr + ", using YELLOW as default.");
                color = net.kyori.adventure.bossbar.BossBar.Color.YELLOW;
            }
            
            // 根据配置设置样式
            net.kyori.adventure.bossbar.BossBar.Overlay overlay;
            try {
                overlay = net.kyori.adventure.bossbar.BossBar.Overlay.valueOf(overlayStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid bossbar overlay: " + overlayStr + ", using PROGRESS as default.");
                overlay = net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS;
            }
            
            bossBarPacket.setColor(color);
            bossBarPacket.setOverlay(overlay);
            
            // 修复空指针报错：显式设置flags字段
            bossBarPacket.setFlags(java.util.EnumSet.noneOf(net.kyori.adventure.bossbar.BossBar.Flag.class));
            
            // 发送BossBar数据包给玩家
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, bossBarPacket);
        } catch (Exception e) {
            getLogger().warning("Failed to send ECO BossBar to player " + player.getName() + ": " + e.getMessage());
        }

        // 使用延迟调度器添加AFK标记，避免竞态条件
        // 确保BossBar数据包先通过监听器（此时玩家尚未被标记为AFK，监听器会直接return放行）
        // 然后再将玩家标记为AFK
        player.getScheduler().runDelayed(this, (task) -> {
            if (player.isOnline()) {
                AFK_PLAYERS.add(player.getUniqueId());
                getLogger().info("Player " + player.getName() + " (" + player.getUniqueId() + ") entered AFK mode");
            }
        }, null, 1L);
    }

    public void playerEcoDisable(Player player) {
        UUID playerUuid = player.getUniqueId();
        AFK_PLAYERS.remove(playerUuid);
        if(getConfig().getBoolean("modifyPlayerViewDistance")) {
            player.setSendViewDistance(-1);
        }
        player.resetPlayerTime();
        String message = getConfig().getString("message.playerEcoDisable", "");
        if(!message.isEmpty()){
            player.sendMessage(message);
        }
        
        // 移除 ECO 模式 BossBar 提示
        UUID ecoBarUuid = ECO_BAR_UUIDS.get(playerUuid);
        if (ecoBarUuid != null) {
            try {
                // 创建 REMOVE 类型的 BossBar 包
                WrapperPlayServerBossBar removeBossBarPacket = new WrapperPlayServerBossBar(
                    ecoBarUuid,
                    WrapperPlayServerBossBar.Action.REMOVE
                );
                
                // 修复空指针报错：显式设置 flags 字段
                removeBossBarPacket.setFlags(java.util.EnumSet.noneOf(net.kyori.adventure.bossbar.BossBar.Flag.class));
                
                // 发送移除 BossBar 数据包给玩家
                PacketEvents.getAPI().getPlayerManager().sendPacket(player, removeBossBarPacket);
                
                // 从映射中移除 UUID
                ECO_BAR_UUIDS.remove(playerUuid);
            } catch (Exception e) {
                getLogger().warning("Failed to remove ECO BossBar for player " + player.getName() + ": " + e.getMessage());
            }
        }
        
        // 强制刷新玩家周围的实体位置，修复"幽灵实体"Bug
        player.getScheduler().run(this, task -> {
            try {
                // ✅ 绝对安全：使用 player.getNearbyEntities 只获取该玩家周边的实体，不会触发 Folia 跨区报错
                java.util.List<org.bukkit.entity.Entity> nearbyEntities = player.getNearbyEntities(48, 48, 48);
                
                for (org.bukkit.entity.Entity entity : nearbyEntities) {
                    if (entity.isValid()) {
                        // ✅ 新增：绝对不要刷新玩家自己正在乘坐的载具，否则客户端秒崩！
                        if (player.isInsideVehicle() && entity.equals(player.getVehicle())) {
                            continue;
                        }
                        
                        if (entity instanceof Player) {
                            Player nearbyPlayer = (Player) entity;
                            player.hidePlayer(this, nearbyPlayer);
                            player.showPlayer(this, nearbyPlayer);
                        } else {
                            // ✅ 极其关键：必须刷新怪物和掉落物，否则玩家退出 AFK 后会被隐形怪物攻击！
                            org.bukkit.entity.EntityType type = entity.getType();
                            if (type != org.bukkit.entity.EntityType.ARMOR_STAND && 
                                type != org.bukkit.entity.EntityType.ITEM_FRAME && 
                                type != org.bukkit.entity.EntityType.PAINTING) {
                                
                                player.hideEntity(this, entity);
                                player.showEntity(this, entity);
                            }
                        }
                    }
                }
                getLogger().info("Player " + player.getName() + " entity refresh completed after exiting AFK mode");
            } catch (Exception e) {
                getLogger().warning("Failed to refresh entities: " + e.getMessage());
            }
        }, null);
        
        // Log AFK exit to console
        getLogger().info("Player " + player.getName() + " (" + player.getUniqueId() + ") exited AFK mode");
    }

    // Player activity event handlers
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // 检查玩家是否有绕过权限
        if (player.hasPermission("bandwidthsaver.bypass")) {
            // 如果玩家有绕过权限且处于AFK状态，则退出AFK
            if (AFK_PLAYERS.contains(playerId)) {
                playerEcoDisable(player);
            }
            return; // 不进行后续AFK检测
        }
        
        // 检查是否为硬核AFK模式
        if (HARDCORE_AFK_PLAYERS.contains(playerId)) {
            // 在硬核AFK模式下，不响应视角移动来退出AFK
            // 仅更新视角信息，但不退出AFK状态
            float currentYaw = player.getLocation().getYaw();
            float currentPitch = player.getLocation().getPitch();
            
            LAST_YAW.put(playerId, currentYaw);
            LAST_PITCH.put(playerId, currentPitch);
            
            return; // 不进行后续AFK检测
        }
        
        // 检查玩家视角是否发生变化（头部移动）
        float currentYaw = player.getLocation().getYaw();
        float currentPitch = player.getLocation().getPitch();
        
        Float lastYaw = LAST_YAW.get(playerId);
        Float lastPitch = LAST_PITCH.get(playerId);
        
        if (lastYaw != null && lastPitch != null) {
            // 计算视角变化角度
            float yawDiff = Math.abs(Math.abs(currentYaw - lastYaw) - 180) - 180;
            float pitchDiff = Math.abs(currentPitch - lastPitch);
            float totalAngleDiff = Math.abs(yawDiff) + Math.abs(pitchDiff);
            
            // 如果视角变化超过阈值，认为玩家在活动
            if (totalAngleDiff > HEAD_MOVEMENT_THRESHOLD) {
                // 更新最后视角信息
                LAST_YAW.put(playerId, currentYaw);
                LAST_PITCH.put(playerId, currentPitch);
                
                // 检查是否需要退出AFK（但不包括硬核AFK模式）
                if (AFK_PLAYERS.contains(playerId) && !HARDCORE_AFK_PLAYERS.contains(playerId)) {
                    // 玩家有显著的头部移动，退出AFK
                    playerEcoDisable(player);
                }
                
                // 更新最后头部移动时间
                LAST_HEAD_MOVEMENT_TIME.put(playerId, System.currentTimeMillis());
            }
        } else {
            // 初始化玩家的视角信息
            LAST_YAW.put(playerId, currentYaw);
            LAST_PITCH.put(playerId, currentPitch);
            // 只有在没有记录的情况下才初始化最后头部移动时间为当前时间
            // 这样避免了每次移动都重置AFK计时器
            LAST_HEAD_MOVEMENT_TIME.putIfAbsent(playerId, System.currentTimeMillis());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Interactions don't directly affect AFK status in the new system
        // Only head movements matter for AFK detection
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Check if player has bypass permission
        if (player.hasPermission("bandwidthsaver.bypass")) {
            // If player has bypass permission and is in AFK, exit AFK
            if (AFK_PLAYERS.contains(playerId)) {
                playerEcoDisable(player);
            }
            return; // Don't process AFK logic for bypass players
        }
        
        // 检查是否为硬核AFK模式
        if (HARDCORE_AFK_PLAYERS.contains(playerId)) {
            // 在硬核AFK模式下，交互不会导致退出AFK
            return; // 不处理AFK逻辑
        }
        
        // Interactions no longer cause AFK exit - only head movements affect AFK status
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        // Only head movements matter for AFK detection
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Check if player has bypass permission
        if (player.hasPermission("bandwidthsaver.bypass")) {
            // If player has bypass permission and is in AFK, exit AFK
            if (AFK_PLAYERS.contains(playerId)) {
                playerEcoDisable(player);
            }
            return; // Don't process AFK logic for bypass players
        }
        
        // 检查是否为硬核AFK模式
        if (HARDCORE_AFK_PLAYERS.contains(playerId)) {
            // 在硬核AFK模式下，聊天不会导致退出AFK
            return; // 不处理AFK逻辑
        }
        
        // If player is in AFK, chatting might indicate they're active again
        if (AFK_PLAYERS.contains(playerId) && !HARDCORE_AFK_PLAYERS.contains(playerId)) {
            // Chat indicates player is active, exit AFK
            playerEcoDisable(player);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        // Commands don't directly affect AFK status in the new system
        // Only head movements matter for AFK detection
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Check if player has bypass permission
        if (player.hasPermission("bandwidthsaver.bypass")) {
            // If player has bypass permission and is in AFK, exit AFK
            if (AFK_PLAYERS.contains(playerId)) {
                playerEcoDisable(player);
            }
            return; // Don't process AFK logic for bypass players
        }
        
        // 检查是否为硬核AFK模式
        if (HARDCORE_AFK_PLAYERS.contains(playerId)) {
            // 在硬核AFK模式下，命令不会导致退出AFK
            return; // 不处理AFK逻辑
        }
        
        String command = event.getMessage().toLowerCase(); // Includes the '/' and arguments
        
        // List of teleportation commands that should exit AFK
        String[] teleportCommands = {
            "/tpaccept", "/tpa", "/tpahere", 
            "/spawn", "/warp", "/back", 
            "/home", "/res tp",
            "/huskhomes:back", "/huskhomes:tpaccept"
        };
        
        // Check if the command matches any teleportation command
        boolean isTeleportCommand = false;
        for (String teleportCmd : teleportCommands) {
            if (command.startsWith(teleportCmd.toLowerCase())) {
                isTeleportCommand = true;
                break;
            }
        }
        
        // If player is in AFK and used a teleport command, exit AFK (but not in hardcore AFK mode)
        if (AFK_PLAYERS.contains(playerId) && isTeleportCommand && !HARDCORE_AFK_PLAYERS.contains(playerId)) {
            playerEcoDisable(player);
        }
        
        // If this was a teleport command, update the head movement time to prevent immediate re-AFK
        if (isTeleportCommand) {
            LAST_HEAD_MOVEMENT_TIME.put(playerId, System.currentTimeMillis());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        initializePlayerHeadTracking(player);
        
        // 为新加入的玩家调度专用任务
        schedulePlayerTask(player);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        playerEcoDisable(player);
        PLAYER_PKT_SAVED_STATS.remove(playerId);
        UNFILTERED_PLAYER_PKT_SAVED_STATS.remove(playerId);
        // Clean up perspective tracking data
        LAST_YAW.remove(playerId);
        LAST_PITCH.remove(playerId);
        LAST_HEAD_MOVEMENT_TIME.remove(playerId);
        ENTER_AFK_TIME.remove(playerId);
        IS_FISHING_CACHE.remove(playerId); // 清除钓鱼状态缓存
        HARDCORE_AFK_PLAYERS.remove(playerId); // 清除手动 AFK 状态
        
        // 取消玩家的专用任务
        if (PLAYER_TASKS.containsKey(playerId)) {
            PLAYER_TASKS.get(playerId).cancel();
            PLAYER_TASKS.remove(playerId);
        }
    }



    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onVehicleMove(VehicleMoveEvent event) {
        // Vehicle movement doesn't directly affect AFK status in the new system
        // Only head movements matter for AFK detection
        // Vehicle movement alone shouldn't impact AFK state
    }

    // 换成范围更广的 EntityDamageEvent，涵盖跌落、火焰、岩浆、溺水等所有伤害
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        // 检查是否是玩家受到了伤害
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            UUID playerId = player.getUniqueId();
            
            // 检查玩家是否有绕过权限
            if (player.hasPermission("bandwidthsaver.bypass")) {
                if (AFK_PLAYERS.contains(playerId)) {
                    playerEcoDisable(player);
                }
                return; // 不进行后续 AFK 检测
            }

            // 新增：硬核 AFK 模式保护
            if (HARDCORE_AFK_PLAYERS.contains(playerId)) {
                // 如果是手动输入的 /afk，被打时不退出 AFK（硬核到底）
                // 或者你也可以在这里加上 HARDCORE_AFK_PLAYERS.remove(playerId); 让他彻底醒来
                return;
            }
            
            // 普通自动 AFK 状态，受到任何伤害时退出 AFK
            if (AFK_PLAYERS.contains(playerId)) {
                playerEcoDisable(player);
            }
            
            // 更新最后头部移动时间，避免立即再次进入 AFK
            LAST_HEAD_MOVEMENT_TIME.put(playerId, System.currentTimeMillis());
        }
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        // 取消所有玩家的专用任务
        for (io.papermc.paper.threadedregions.scheduler.ScheduledTask task : PLAYER_TASKS.values()) {
            task.cancel();
        }
        PLAYER_TASKS.clear();
        
        EXECUTOR_SERVICE.shutdown();
        try {
            if (!EXECUTOR_SERVICE.awaitTermination(5, TimeUnit.SECONDS)) {
                EXECUTOR_SERVICE.shutdownNow();
            }
        } catch (InterruptedException e) {
            EXECUTOR_SERVICE.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Terminate PacketEvents
        if (packetEventsAPI != null) {
            packetEventsAPI.terminate();
        }
    }



    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return  List.of(
                    "reload",
                    "unfiltered"
            );
        }
        return null;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // 检查是否是afk命令
        if (command.getName().equalsIgnoreCase("afk")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "此命令只能由玩家执行！");
                return true;
            }
            
            Player player = (Player) sender;
            UUID playerId = player.getUniqueId();
            
            if (HARDCORE_AFK_PLAYERS.contains(playerId)) {
                // 退出硬核AFK模式
                HARDCORE_AFK_PLAYERS.remove(playerId);
                if (AFK_PLAYERS.contains(playerId)) {
                    playerEcoDisable(player);
                }
                player.sendMessage(ChatColor.GREEN + "您已退出手动AFK模式！");
            } else {
                // 进入硬核AFK模式
                HARDCORE_AFK_PLAYERS.add(playerId);
                if (!AFK_PLAYERS.contains(playerId)) {
                    playerEcoEnable(player);
                }
                player.sendMessage(ChatColor.YELLOW + "您已进入手动AFK模式！再次输入/afk退出此模式。");
            }
            return true;
        }
        
        // 检查是否是bandwidthsaver命令
        if (command.getName().equalsIgnoreCase("bandwidthsaver")) {
            // Check if sender has admin permission for all commands
            if (!sender.hasPermission("bandwidthsaver.admin")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this command!");
                return true;
            }
            
            if (args.length == 0) {
                sender.sendMessage(ChatColor.GREEN + "🍃 ECO 节能模式 - 统计信息：");
                long pktCancelled = PKT_TYPE_STATS.values().stream().mapToLong(r -> r.getPktCounter().longValue()).sum();
                long pktSizeSaved = PKT_TYPE_STATS.values().stream().mapToLong(r -> r.getPktSize().longValue()).sum();
                sender.sendMessage(ChatColor.YELLOW + "共减少发送数据包：" + ChatColor.AQUA + pktCancelled + " 个");
                sender.sendMessage(ChatColor.YELLOW + "共减少发送数据包：" + ChatColor.AQUA + humanReadableByteCount(pktSizeSaved, false) + " （不包含视距优化的增益数据）");
                Map<Object, PacketInfo> sortedPktMap = new LinkedHashMap<>();
                Map<UUID, PacketInfo> sortedPlayerMap = new LinkedHashMap<>();
                PKT_TYPE_STATS.entrySet().stream().sorted(Map.Entry.<Object, PacketInfo>comparingByValue().reversed()).forEachOrdered(e -> sortedPktMap.put(e.getKey(), e.getValue()));
                PLAYER_PKT_SAVED_STATS.entrySet().stream().sorted(Map.Entry.<UUID, PacketInfo>comparingByValue().reversed()).forEachOrdered(e -> sortedPlayerMap.put(e.getKey(), e.getValue()));
                sender.sendMessage(ChatColor.YELLOW + " -- 数据包类型节约 TOP 15 --");
                sortedPktMap.entrySet().stream().limit(15).forEach(entry -> sender.sendMessage(ChatColor.GRAY + entry.getKey().toString() + " - " + entry.getValue().getPktCounter().longValue() + " 个 (" + humanReadableByteCount(entry.getValue().getPktSize().longValue(), false) + ")"));
                sender.sendMessage(ChatColor.YELLOW + " -- 玩家流量节约 TOP 5 --");
                sortedPlayerMap.entrySet().stream().limit(5).forEach(entry -> sender.sendMessage(ChatColor.GRAY + Bukkit.getOfflinePlayer(entry.getKey()).getName() + " - " + entry.getValue().getPktCounter().longValue() + " 个 (" + humanReadableByteCount(entry.getValue().getPktSize().longValue(), false) + ")"));
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("unfiltered")) {
                sender.sendMessage(ChatColor.GREEN + "🍃 UN-ECO - 数据总计 - 统计信息：");
                long pktSent = UNFILTERED_PKT_TYPE_STATS.values().stream().mapToLong(r -> r.getPktCounter().longValue()).sum();
                long pktSize = UNFILTERED_PKT_TYPE_STATS.values().stream().mapToLong(r -> r.getPktSize().longValue()).sum();
                sender.sendMessage(ChatColor.YELLOW + "共发送数据包：" + ChatColor.AQUA + pktSent + " 个");
                sender.sendMessage(ChatColor.YELLOW + "共发送数据包：" + ChatColor.AQUA + humanReadableByteCount(pktSize, false));
                Map<Object, PacketInfo> sortedPktMap = new LinkedHashMap<>();
                Map<UUID, PacketInfo> sortedPlayerMap = new LinkedHashMap<>();
                UNFILTERED_PKT_TYPE_STATS.entrySet().stream().sorted(Map.Entry.<Object, PacketInfo>comparingByValue().reversed()).forEachOrdered(e -> sortedPktMap.put(e.getKey(), e.getValue()));
                UNFILTERED_PLAYER_PKT_SAVED_STATS.entrySet().stream().sorted(Map.Entry.<UUID, PacketInfo>comparingByValue().reversed()).forEachOrdered(e -> sortedPlayerMap.put(e.getKey(), e.getValue()));
                sender.sendMessage(ChatColor.YELLOW + " -- 数据包类型 TOP 15 --");
                sortedPktMap.entrySet().stream().limit(15).forEach(entry -> sender.sendMessage(ChatColor.GRAY + entry.getKey().toString() + " - " + entry.getValue().getPktCounter().longValue() + " 个 (" + humanReadableByteCount(entry.getValue().getPktSize().longValue(), false) + ")"));
                sender.sendMessage(ChatColor.YELLOW + " -- 玩家流量 TOP 5 --");
                sortedPlayerMap.entrySet().stream().limit(5).forEach(entry -> sender.sendMessage(ChatColor.GRAY + Bukkit.getOfflinePlayer(entry.getKey()).getName() + " - " + entry.getValue().getPktCounter().longValue() + " 个 (" + humanReadableByteCount(entry.getValue().getPktSize().longValue(), false) + ")"));
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                sender.sendMessage(ChatColor.GREEN + "🍃 ECO - 配置文件已重载");
            }
            return true;
        }
        
        return false;
    }

    public static String humanReadableByteCount(long bytes, boolean si) {
        int unit = si ? 1000 : 1024;
        if (bytes < unit) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp - 1) + (si ? "" : "i");
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }
    

    

    

    
}
