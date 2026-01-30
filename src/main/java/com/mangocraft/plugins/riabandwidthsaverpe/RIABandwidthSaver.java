package com.mangocraft.plugins.riabandwidthsaverpe;

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
    private final Set<UUID> AFK_PLAYERS = new HashSet<>();
    private final Map<UUID, Float> LAST_YAW = new ConcurrentHashMap<>(); // 记录玩家最后的yaw（左右视角）
    private final Map<UUID, Float> LAST_PITCH = new ConcurrentHashMap<>(); // 记录玩家最后的pitch（上下视角）
    private final Map<UUID, Long> LAST_HEAD_MOVEMENT_TIME = new ConcurrentHashMap<>(); // 记录最后头部移动时间
    private final Map<UUID, Long> ENTER_AFK_TIME = new ConcurrentHashMap<>(); // 记录进入AFK的时间
    private static final float HEAD_MOVEMENT_THRESHOLD = 45.0f; // 视角移动阈值（度）
    private long afkThresholdMs = 300000; // AFK阈值：5分钟（毫秒），可从配置文件修改
    private static final long MIN_HEAD_MOVEMENT_INTERVAL_MS = 1000; // 最小头部移动检测间隔：1秒
    
    // 实体追踪数据结构，用于智能过滤
    private final Map<Integer, Long> LAST_ENTITY_UPDATE = new ConcurrentHashMap<>(); // 记录实体最后更新时间
    private final Map<Integer, Double> LAST_ENTITY_DISTANCE = new ConcurrentHashMap<>(); // 记录实体最后距离
    private final Map<Integer, Integer> ENTITY_UPDATE_COUNT = new ConcurrentHashMap<>(); // 记录实体更新频率
    
    // 机械装置活动跟踪数据结构
    private final Map<UUID, Map<String, Long>> MECHANICAL_DEVICE_ACTIVITY = new ConcurrentHashMap<>(); // 记录玩家附近机械装置活动
    private final Map<String, Long> LAST_MECHANICAL_ACTIVITY = new ConcurrentHashMap<>(); // 记录全局机械装置最后活动时间
    private static final long MECHANICAL_ACTIVITY_WINDOW_MS = 5000; // 机械装置活动时间窗口：5秒
    private static final double MECHANICAL_ACTIVITY_SENSITIVITY = 0.7; // 机械装置活动敏感度
    
    // 实体密度和生命周期跟踪数据结构 - 为了性能优化，减少对高频实体的复杂处理
    private final Map<String, Integer> REGION_ENTITY_COUNT = new ConcurrentHashMap<>(); // 记录区域实体数量
    private static final int ENTITY_DENSITY_THRESHOLD = 20; // 区域实体密度阈值
    private static final long DENSITY_CHECK_WINDOW_MS = 5000; // 密度检查时间窗口：5秒
    
    // 调试日志限流相关数据结构
    private final Map<String, Long> DEBUG_LOG_TIMERS = new ConcurrentHashMap<>(); // 记录各类调试日志的最后记录时间
    private static final long DEBUG_LOG_INTERVAL_MS = 5000; // 调试日志最小间隔时间：5秒
    
    // 高频实体识别数据结构 - 简化以减少计算开销
    private static final int HIGH_FREQUENCY_ENTITY_THRESHOLD = 10; // 高频实体活动阈值
    private static final long ACTIVITY_WINDOW_MS = 5000; // 活动时间窗口：5秒
    
    private final Map<Object, PacketInfo> PKT_TYPE_STATS = new ConcurrentHashMap<>();
    private final Map<UUID, PacketInfo> PLAYER_PKT_SAVED_STATS = new ConcurrentHashMap<>();
    private final Map<Object, PacketInfo> UNFILTERED_PKT_TYPE_STATS = new ConcurrentHashMap<>();
    private final Map<UUID, PacketInfo> UNFILTERED_PLAYER_PKT_SAVED_STATS = new ConcurrentHashMap<>();
    private final ThreadLocalRandom RANDOM = ThreadLocalRandom.current();
    private boolean calcAllPackets = false;
    private final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(2);
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask afkCheckTask = null;

    private com.github.retrooper.packetevents.PacketEventsAPI packetEventsAPI;
    private ChunkDataFilter chunkDataFilter;

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
        
        // Initialize ChunkDataFilter
        chunkDataFilter = ChunkDataFilter.getInstance();
        chunkDataFilter.registerListener();
        
        // Register packet listener
        packetEventsAPI.getEventManager().registerListener(new BandwidthSaverListener());
        
        reloadConfig();
        
        // Start AFK check task
        startAfkCheckTask();
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
            
            // READ PACKET SIZE IN MAIN THREAD BEFORE CANCELLATION - CRITICAL FOR BYTEBUF LIFECYCLE
            long packetSize = getPacketSizeFromEvent(event); // Read in main thread before cancellation
            
            // --- ✅ 修正开始：使用 PacketType 枚举对比 ---
            com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon type = event.getPacketType();

            // 1. 完全取消的数据包 (直接列出 PacketType)
            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_ANIMATION ||
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.BLOCK_BREAK_ANIMATION ||
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.SOUND_EFFECT ||
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_SOUND_EFFECT || // 注意：Named Sound 和 Entity Sound
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.PARTICLE ||
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.EXPLOSION ||
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_HEAD_LOOK || // 修正：是 HEAD_LOOK 不是 HEAD_ROTATION
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.DAMAGE_EVENT ||     // 1.19.4+
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.PLAYER_LIST_HEADER_AND_FOOTER || // 修正名称
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_EFFECT ||
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.MAP_DATA ||
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.UPDATE_ATTRIBUTES ||
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.PLAYER_INFO_UPDATE ||
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.UPDATE_LIGHT || // 🔥 必杀技1: 光照更新 - 节省大量流量
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.BOSS_BAR || // 🛡️ 必杀技3: Boss栏 - AFK玩家不需要看到公告
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_TELEPORT) { // 🚀 必杀技2: 实体传送 - 全部拦截ENTITY_TELEPORT
                
                event.setCancelled(true);
                handleCancelledPacketWithSize(event, uuid, packetSize);
                return;
            }

            // 2. 特殊处理：受伤动画 (EntityStatus)
            // 原代码中的 "HURT_ANIMATION" 是无效的
            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_STATUS) {
                com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus statusWrapper = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus(event);
                // 使用正确的方法名
                if (statusWrapper.getStatus() == 2) { // 2 代表受伤变红
                    event.setCancelled(true);
                    handleCancelledPacketWithSize(event, uuid, packetSize);
                }
                return;
            }

            // 3. 概率过滤的数据包
            // 实体移动类 (修正了名称)
            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_RELATIVE_MOVE ||
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION ||
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_ROTATION || // 原代码的 ENTITY_LOOK
                type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_VELOCITY) {
                
                if (RANDOM.nextDouble() < 0.02) { // 2% 放行
                    return;
                }
                event.setCancelled(true);
                handleCancelledPacketWithSize(event, uuid, packetSize);
                return;
            }

            // 载具移动
            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.VEHICLE_MOVE) {
                if (RANDOM.nextInt(3) > 0) { // 33% 放行
                    return;
                }
                event.setCancelled(true);
                handleCancelledPacketWithSize(event, uuid, packetSize);
                return;
            }

            // 实体生成 (保持可见性)
            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.SPAWN_ENTITY) {
                if (RANDOM.nextInt(2) > 0) {
                    return;
                }
                event.setCancelled(true);
                handleCancelledPacketWithSize(event, uuid, packetSize);
                return;
            }

            // 头部旋转
            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_HEAD_LOOK) {
                if (RANDOM.nextDouble() < 0.20) {
                    return;
                }
                event.setCancelled(true);
                handleCancelledPacketWithSize(event, uuid, packetSize);
                return;
            }
            
            // 元数据更新
            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_METADATA) {
                 if (RANDOM.nextDouble() < 0.05) {
                    return;
                }
                event.setCancelled(true);
                handleCancelledPacketWithSize(event, uuid, packetSize);
                return;
            }

            // 4. 需要复杂逻辑判断的包 (Block Action / Change)
            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.BLOCK_ACTION) {
                if (!shouldAllowBlockActionPacket(event, uuid)) {
                    event.setCancelled(true);
                    handleCancelledPacketWithSize(event, uuid, packetSize);
                }
                return;
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

    private void startAfkCheckTask() {
        // 使用定时任务检查玩家AFK状态
        afkCheckTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            long currentTime = System.currentTimeMillis();
            
            // 检查所有在线玩家的AFK状态
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID playerId = player.getUniqueId();
                
                // 检查玩家是否有绕过权限
                if (player.hasPermission("riabandwidthsaver.bypass")) {
                    // 如果玩家有绕过权限且处于AFK状态，则退出AFK
                    if (AFK_PLAYERS.contains(playerId)) {
                        playerEcoDisable(player);
                    }
                    continue; // 跳过对该玩家的AFK检查
                }
                
                // 检查玩家是否不在AFK状态且应该进入AFK状态
                if (!AFK_PLAYERS.contains(playerId)) {
                    Long lastHeadMovementTime = LAST_HEAD_MOVEMENT_TIME.get(playerId);
                    
                    if (lastHeadMovementTime != null) {
                        long timeSinceLastHeadMovement = currentTime - lastHeadMovementTime;
                        
                        // 如果头部在一段时间内没有显著移动，则进入AFK状态
                        if (timeSinceLastHeadMovement >= afkThresholdMs) {
                            playerEcoEnable(player);
                            ENTER_AFK_TIME.put(playerId, currentTime); // 记录进入AFK的时间
                        }
                    }
                }
            }
        }, 20, 20); // 每秒检查一次 (20 ticks = 1 second)
        
        // 初始化所有在线玩家的视角信息
        for (Player player : Bukkit.getOnlinePlayers()) {
            initializePlayerHeadTracking(player);
        }
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
        AFK_PLAYERS.add(player.getUniqueId());
        if (chunkDataFilter != null) {
            chunkDataFilter.addAfkPlayer(player.getUniqueId());
        }
        
        // Log AFK entry to console
        getLogger().info("Player " + player.getName() + " (" + player.getUniqueId() + ") entered AFK mode");
    }

    public void playerEcoDisable(Player player) {
        AFK_PLAYERS.remove(player.getUniqueId());
        if(getConfig().getBoolean("modifyPlayerViewDistance")) {
            player.setSendViewDistance(-1);
        }
        player.resetPlayerTime();
        String message = getConfig().getString("message.playerEcoDisable", "");
        if(!message.isEmpty()){
            player.sendMessage(message);
        }
        if (chunkDataFilter != null) {
            chunkDataFilter.removeAfkPlayer(player.getUniqueId());
        }
        
        // Log AFK exit to console
        getLogger().info("Player " + player.getName() + " (" + player.getUniqueId() + ") exited AFK mode");
    }

    // Player activity event handlers
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // 检查玩家是否有绕过权限
        if (player.hasPermission("riabandwidthsaver.bypass")) {
            // 如果玩家有绕过权限且处于AFK状态，则退出AFK
            if (AFK_PLAYERS.contains(playerId)) {
                playerEcoDisable(player);
            }
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
                
                // 检查是否需要退出AFK
                if (AFK_PLAYERS.contains(playerId)) {
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
        if (player.hasPermission("riabandwidthsaver.bypass")) {
            // If player has bypass permission and is in AFK, exit AFK
            if (AFK_PLAYERS.contains(playerId)) {
                playerEcoDisable(player);
            }
            return; // Don't process AFK logic for bypass players
        }
        
        // Interactions no longer cause AFK exit - only head movements affect AFK status
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        // Only head movements matter for AFK detection
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Check if player has bypass permission
        if (player.hasPermission("riabandwidthsaver.bypass")) {
            // If player has bypass permission and is in AFK, exit AFK
            if (AFK_PLAYERS.contains(playerId)) {
                playerEcoDisable(player);
            }
            return; // Don't process AFK logic for bypass players
        }
        
        // If player is in AFK, chatting might indicate they're active again
        if (AFK_PLAYERS.contains(playerId)) {
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
        if (player.hasPermission("riabandwidthsaver.bypass")) {
            // If player has bypass permission and is in AFK, exit AFK
            if (AFK_PLAYERS.contains(playerId)) {
                playerEcoDisable(player);
            }
            return; // Don't process AFK logic for bypass players
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
        
        // If player is in AFK and used a teleport command, exit AFK
        if (AFK_PLAYERS.contains(playerId) && isTeleportCommand) {
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
        initializePlayerHeadTracking(player);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerEcoDisable(event.getPlayer());
        PLAYER_PKT_SAVED_STATS.remove(event.getPlayer().getUniqueId());
        UNFILTERED_PLAYER_PKT_SAVED_STATS.remove(event.getPlayer().getUniqueId());
        // Clean up perspective tracking data
        LAST_YAW.remove(event.getPlayer().getUniqueId());
        LAST_PITCH.remove(event.getPlayer().getUniqueId());
        LAST_HEAD_MOVEMENT_TIME.remove(event.getPlayer().getUniqueId());
        ENTER_AFK_TIME.remove(event.getPlayer().getUniqueId());
    }



    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onVehicleMove(VehicleMoveEvent event) {
        // Vehicle movement doesn't directly affect AFK status in the new system
        // Only head movements matter for AFK detection
        // Vehicle movement alone shouldn't impact AFK state
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityDamageByEntity(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        // 检查是否是玩家受到了攻击
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            UUID playerId = player.getUniqueId();
            
            // 检查玩家是否有绕过权限
            if (player.hasPermission("riabandwidthsaver.bypass")) {
                // 如果玩家有绕过权限且处于AFK状态，则退出AFK
                if (AFK_PLAYERS.contains(playerId)) {
                    playerEcoDisable(player);
                }
                return; // 不进行后续AFK检测
            }
            
            // 如果玩家处于AFK状态，受到攻击时退出AFK
            if (AFK_PLAYERS.contains(playerId)) {
                playerEcoDisable(player);
            }
            
            // 更新最后头部移动时间，避免立即再次进入AFK
            LAST_HEAD_MOVEMENT_TIME.put(playerId, System.currentTimeMillis());
        }
    }


    

    
    /**
     * 从数据包中提取实体ID
     */
    private int getEntityIdFromPacket(Object packet) {
        try {
            // 由于我们不能直接访问原始包对象，我们需要通过PacketSendEvent获取相关信息
            // 在智能过滤函数中，我们可以通过事件获取更准确的信息
            return 0; // 临时返回值，实际逻辑在shouldSendEntityPacket中处理
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * 从数据包中提取实体X坐标
     */
    private double getEntityXFromPacket(Object packet) {
        try {
            return 0.0; // 临时返回值
        } catch (Exception e) {
            return 0.0;
        }
    }
    
    /**
     * 从数据包中提取实体Z坐标
     */
    private double getEntityZFromPacket(Object packet) {
        try {
            return 0.0; // 临时返回值
        } catch (Exception e) {
            return 0.0;
        }
    }
    

    


    @Override
    public void onDisable() {
        // Plugin shutdown logic
        if (afkCheckTask != null) {
            afkCheckTask.cancel();
        }
        EXECUTOR_SERVICE.shutdown();
        try {
            if (!EXECUTOR_SERVICE.awaitTermination(5, TimeUnit.SECONDS)) {
                EXECUTOR_SERVICE.shutdownNow();
            }
        } catch (InterruptedException e) {
            EXECUTOR_SERVICE.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Terminate ChunkDataFilter
        if (chunkDataFilter != null) {
            chunkDataFilter.unregisterListener();
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
        // Check if sender has admin permission for all commands
        if (!sender.hasPermission("riabandwidthsaver.admin")) {
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

    public static String humanReadableByteCount(long bytes, boolean si) {
        int unit = si ? 1000 : 1024;
        if (bytes < unit) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp - 1) + (si ? "" : "i");
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }
    
    /**
     * 智能判断是否应该允许BLOCK_ACTION数据包通过
     * 根据方块类型和距离实施差异化过滤策略
     */
    private boolean shouldAllowBlockActionPacket(PacketSendEvent event, UUID playerUuid) {
        try {
            // 获取玩家对象
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null) {
                return true; // 如果找不到玩家，发送数据包
            }
            
            // 获取玩家位置
            Location playerLoc = player.getLocation();
            double playerX = playerLoc.getX();
            double playerY = playerLoc.getY();
            double playerZ = playerLoc.getZ();
            
            // 尝试获取BLOCK_ACTION数据包中的方块位置
            // 使用PacketEvents的WrapperPlayServerBlockAction来获取方块位置
            try {
                WrapperPlayServerBlockAction wrapper = new WrapperPlayServerBlockAction(event);
                
                // 获取方块位置
                double blockX = wrapper.getBlockPosition().x;
                double blockY = wrapper.getBlockPosition().y;
                double blockZ = wrapper.getBlockPosition().z;
                
                // 计算玩家与方块的距离
                double distance = Math.sqrt(Math.pow(blockX - playerX, 2) + 
                                          Math.pow(blockY - playerY, 2) + 
                                          Math.pow(blockZ - playerZ, 2));
                
                boolean isAfk = AFK_PLAYERS.contains(playerUuid);
                
                if (!isAfk) {
                    return true; // 非AFK玩家，允许通过
                }
                
                // 对于AFK玩家，使用上下文感知的智能过滤策略
                // 根据距离、机械装置类型和最近活动情况给予不同的通过率
                
                // 基础通过率
                double baseProbability = 0.3;  // 默认基础通过率（相比之前降低，但机械装置会提升）
                
                // 根据距离调整基础通过率
                if (distance <= 16) {
                    baseProbability = 0.6;     // 近距离提高通过率
                } else if (distance <= 32) {
                    baseProbability = 0.4;     // 中距离适中通过率
                } else {
                    baseProbability = 0.1;     // 远距离降低通过率
                }
                
                // 检查是否为机械装置相关动作
                boolean isMechanical = isMechanicalDeviceAction(wrapper);
                
                if (isMechanical) {
                    // 检查机械装置活动上下文
                    String deviceKey = "mech_" + (int)blockX + "_" + (int)blockZ; // 使用区块级粒度
                    long currentTime = System.currentTimeMillis();
                    Long lastActivity = LAST_MECHANICAL_ACTIVITY.get(deviceKey);
                    
                    if (lastActivity != null && (currentTime - lastActivity) < MECHANICAL_ACTIVITY_WINDOW_MS) {
                        // 在机械装置活动窗口期内，提高通过率
                        baseProbability = Math.max(baseProbability, 0.9); // 活动期间90%通过率
                    } else {
                        // 非活动窗口期，但仍是机械装置，保持较高通过率
                        baseProbability = Math.max(baseProbability, 0.8); // 机械装置80%通过率
                    }
                    
                    // 更新最后机械装置活动时间
                    LAST_MECHANICAL_ACTIVITY.put(deviceKey, currentTime);
                    
                    // 为特定玩家也记录机械装置活动
                    MECHANICAL_DEVICE_ACTIVITY.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>())
                                             .put(deviceKey, currentTime);
                }
                
                return Math.random() < baseProbability;
            } catch (Exception wrapperException) {
                // 如果无法获取详细信息，使用简化的过滤策略
                boolean isAfk = AFK_PLAYERS.contains(playerUuid);
                
                if (!isAfk) {
                    return true; // 非AFK玩家，允许通过
                }
                
                // 对于AFK玩家，使用默认策略
                return Math.random() < 0.3; // 30%基础通过率
            }
        } catch (Exception e) {
            // 出错时保守处理，发送数据包
            return true;
        }
    }
    
    /**
     * 检查BLOCK_ACTION是否为机械装置相关动作
     */
    private boolean isMechanicalDeviceAction(WrapperPlayServerBlockAction wrapper) {
        if (wrapper == null) {
            return true; // 如果无法获取详细信息，保守处理
        }
        
        try {
            // 获取方块类型名称进行判断
            String blockTypeName = wrapper.getBlockType().toString().toLowerCase();
            
            // 检查是否为机械装置相关方块
            return blockTypeName.contains("piston") || 
                   blockTypeName.contains("trapdoor") || 
                   blockTypeName.contains("door") || 
                   blockTypeName.contains("lever") || 
                   blockTypeName.contains("button") || 
                   blockTypeName.contains("redstone") ||
                   blockTypeName.contains("dispenser") ||
                   blockTypeName.contains("dropper") ||
                   blockTypeName.contains("observer");
        } catch (Exception e) {
            // 如果无法获取方块类型，保守处理
            return true;
        }
    }
    
    /**
     * 智能判断是否应该允许BLOCK_CHANGE数据包通过
     * 根据方块类型和距离实施差异化过滤策略
     */
    private boolean shouldAllowBlockChangePacket(PacketSendEvent event, UUID playerUuid) {
        try {
            // 获取玩家对象
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null) {
                return true; // 如果找不到玩家，发送数据包
            }
            
            // 获取玩家位置
            Location playerLoc = player.getLocation();
            double playerX = playerLoc.getX();
            double playerY = playerLoc.getY();
            double playerZ = playerLoc.getZ();
            
            // 使用PacketEvents的WrapperPlayServerBlockChange来获取方块位置
            try {
                WrapperPlayServerBlockChange wrapper = new WrapperPlayServerBlockChange(event);
                
                // 获取方块位置
                int blockX = wrapper.getBlockPosition().x;
                int blockY = wrapper.getBlockPosition().y;
                int blockZ = wrapper.getBlockPosition().z;
                
                // 计算玩家与方块的距离
                double distance = Math.sqrt(Math.pow(blockX - playerX, 2) + 
                                          Math.pow(blockY - playerY, 2) + 
                                          Math.pow(blockZ - playerZ, 2));
                
                boolean isAfk = AFK_PLAYERS.contains(playerUuid);
                
                if (!isAfk) {
                    return true; // 非AFK玩家，允许通过
                }
                
                // 对于AFK玩家，使用上下文感知的智能过滤策略
                // 根据距离、机械装置类型和最近活动情况给予不同的通过率
                
                // 基础通过率
                double baseProbability = 0.3;  // 默认基础通过率
                
                // 根据距离调整基础通过率
                if (distance <= 16) {
                    baseProbability = 0.7;     // 近距离提高通过率
                } else if (distance <= 32) {
                    baseProbability = 0.4;     // 中距离适中通过率
                } else {
                    baseProbability = 0.1;     // 远距离降低通过率
                }
                
                // 检查是否为机械装置相关的方块变化
                boolean isMechanical = isMechanicalBlockChange(wrapper);
                
                if (isMechanical) {
                    // 检查机械装置活动上下文
                    String deviceKey = "mech_" + (int)blockX + "_" + (int)blockZ; // 使用区块级粒度
                    long currentTime = System.currentTimeMillis();
                    Long lastActivity = LAST_MECHANICAL_ACTIVITY.get(deviceKey);
                    
                    if (lastActivity != null && (currentTime - lastActivity) < MECHANICAL_ACTIVITY_WINDOW_MS) {
                        // 在机械装置活动窗口期内，提高通过率
                        baseProbability = Math.max(baseProbability, 0.95); // 活动期间95%通过率
                    } else {
                        // 非活动窗口期，但仍是机械装置，保持较高通过率
                        baseProbability = Math.max(baseProbability, 0.85); // 机械装置85%通过率
                    }
                    
                    // 更新最后机械装置活动时间
                    LAST_MECHANICAL_ACTIVITY.put(deviceKey, currentTime);
                    
                    // 为特定玩家也记录机械装置活动
                    MECHANICAL_DEVICE_ACTIVITY.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>())
                                             .put(deviceKey, currentTime);
                }
                
                return Math.random() < baseProbability;
            } catch (Exception wrapperException) {
                // 如果无法获取详细信息，使用简化的过滤策略
                boolean isAfk = AFK_PLAYERS.contains(playerUuid);
                
                if (!isAfk) {
                    return true; // 非AFK玩家，允许通过
                }
                
                // 对于AFK玩家，使用默认策略
                return Math.random() < 0.35; // 35%基础通过率
            }
        } catch (Exception e) {
            // 出错时保守处理，发送数据包
            return true;
        }
    }
    
    /**
     * 检查BLOCK_CHANGE是否为机械装置相关的方块变化
     */
    private boolean isMechanicalBlockChange(WrapperPlayServerBlockChange wrapper) {
        if (wrapper == null) {
            return true; // 如果无法获取详细信息，保守处理
        }
        
        try {
            // 获取方块状态，判断是否为机械装置
            String blockTypeName = wrapper.getBlockState().toString().toLowerCase();
            
            // 检查是否为机械装置相关方块
            return blockTypeName.contains("piston") || 
                   blockTypeName.contains("trapdoor") || 
                   blockTypeName.contains("door") || 
                   blockTypeName.contains("lever") || 
                   blockTypeName.contains("button") || 
                   blockTypeName.contains("redstone") ||
                   blockTypeName.contains("dispenser") ||
                   blockTypeName.contains("dropper") ||
                   blockTypeName.contains("observer") ||
                   blockTypeName.contains("torch") ||  // 火把可能被活塞推落
                   blockTypeName.contains("rail");     // 轨道可能被活塞影响
        } catch (Exception e) {
            // 如果无法获取方块类型，保守处理
            return true;
        }
    }
    

    
}
