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
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
    // 新增：记录玩家总计AFK时间
    private final Map<UUID, java.util.concurrent.atomic.LongAdder> TOTAL_AFK_TIME_MS = new ConcurrentHashMap<>();
    private static final float HEAD_MOVEMENT_THRESHOLD = 45.0f; // 视角移动阈值（度）
    private long afkThresholdMs = 300000; // AFK阈值：5分钟（毫秒），可从配置文件修改
    private static final long MIN_HEAD_MOVEMENT_INTERVAL_MS = 1000; // 最小头部移动检测间隔：1秒
    

    

    
    private final Map<Object, PacketInfo> PKT_TYPE_STATS = new ConcurrentHashMap<>();
    private final Map<UUID, PacketInfo> PLAYER_PKT_SAVED_STATS = new ConcurrentHashMap<>();
    private final Map<Object, PacketInfo> UNFILTERED_PKT_TYPE_STATS = new ConcurrentHashMap<>();
    private final Map<UUID, PacketInfo> UNFILTERED_PLAYER_PKT_SAVED_STATS = new ConcurrentHashMap<>();
    private boolean calcAllPackets = false;
    private boolean interceptTabList = true;
    private boolean interceptChunkPackets = false;
    private boolean compatibleWithPvdc = false;
    private List<String> enabledWorlds = new ArrayList<>();


    // ECO模式BossBar相关数据结构
    private final Map<UUID, UUID> ECO_BAR_UUIDS = new ConcurrentHashMap<>(); // 存储 <玩家UUID, ECO条的UUID>
    
    // 硬核AFK模式相关数据结构
    private final Set<UUID> HARDCORE_AFK_PLAYERS = java.util.concurrent.ConcurrentHashMap.newKeySet(); // 存储处于硬核AFK模式的玩家
    
    // Folia玩家专用任务相关数据结构
    private final Map<UUID, io.papermc.paper.threadedregions.scheduler.ScheduledTask> PLAYER_TASKS = new ConcurrentHashMap<>(); // 存储每个玩家的专用任务
    
    // 钓鱼检测相关数据结构
    private final Map<UUID, Boolean> IS_FISHING_CACHE = new ConcurrentHashMap<>();

    // 缓存需要拦截的数据包类型，提升匹配性能
    private final Set<com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon> CANCELLED_PACKET_TYPES = new HashSet<>();
    private final Set<com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon> TAB_PACKET_TYPES = new HashSet<>();
    private final Set<com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon> CHUNK_PACKET_TYPES = new HashSet<>();

    private com.github.retrooper.packetevents.PacketEventsAPI packetEventsAPI;

    @Override
    public void onEnable() {
        // Plugin startup logic
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        
        // 初始化 PacketEvents
        packetEventsAPI = PacketEvents.getAPI();
        packetEventsAPI.getSettings()
                .checkForUpdates(false)
                .bStats(true);
        packetEventsAPI.load();
        
        // 预定义需要拦截的数据包类型集合
        initializePacketTypeSets();
        
        // 注册数据包监听器
        packetEventsAPI.getEventManager().registerListener(new BandwidthSaverListener());
        
        reloadConfig();
        
        // 为当前在线玩家启动专用检测任务
        for (Player player : Bukkit.getOnlinePlayers()) {
            schedulePlayerTask(player);
        }
    }

    /**
     * 初始化数据包类型集合，避免在监听器中频繁创建或进行多重 if 判断
     */
    private void initializePacketTypeSets() {
        CANCELLED_PACKET_TYPES.add(PacketType.Play.Server.ENTITY_ANIMATION);
        CANCELLED_PACKET_TYPES.add(PacketType.Play.Server.BLOCK_BREAK_ANIMATION);
        CANCELLED_PACKET_TYPES.add(PacketType.Play.Server.SOUND_EFFECT);
        CANCELLED_PACKET_TYPES.add(PacketType.Play.Server.ENTITY_SOUND_EFFECT);
        CANCELLED_PACKET_TYPES.add(PacketType.Play.Server.PARTICLE);
        CANCELLED_PACKET_TYPES.add(PacketType.Play.Server.EXPLOSION);
        CANCELLED_PACKET_TYPES.add(PacketType.Play.Server.DAMAGE_EVENT);
        CANCELLED_PACKET_TYPES.add(PacketType.Play.Server.MAP_DATA);
        CANCELLED_PACKET_TYPES.add(PacketType.Play.Server.UPDATE_LIGHT);
        CANCELLED_PACKET_TYPES.add(PacketType.Play.Server.ENTITY_TELEPORT);

        TAB_PACKET_TYPES.add(PacketType.Play.Server.PLAYER_LIST_HEADER_AND_FOOTER);
        TAB_PACKET_TYPES.add(PacketType.Play.Server.PLAYER_INFO_UPDATE);

        CHUNK_PACKET_TYPES.add(PacketType.Play.Server.CHUNK_DATA);
        CHUNK_PACKET_TYPES.add(PacketType.Play.Server.UNLOAD_CHUNK);
    }
    
    private class BandwidthSaverListener extends PacketListenerAbstract {
        protected BandwidthSaverListener() {
            super(PacketListenerPriority.HIGHEST);
        }

        @Override
        public void onPacketSend(PacketSendEvent event) {
            User user = event.getUser();
            UUID userUUID = user.getUUID();
            
            // 基础校验：确保 UUID 有效
            if (userUUID == null) return;
            
            Player player = Bukkit.getPlayer(userUUID);
            if (player == null) return;
            
            UUID uuid = player.getUniqueId();
            com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon type = event.getPacketType();

            // 性能优化：只有在确实需要统计时才计算数据包大小
            long packetSize = -1;

            // 处理无过滤的全量数据统计（可选功能，性能开销较大）
            if (calcAllPackets) {
                packetSize = getPacketSizeFromEvent(event);
                UNFILTERED_PKT_TYPE_STATS.computeIfAbsent(type, k -> new PacketInfo()).addValues(1, packetSize);
                UNFILTERED_PLAYER_PKT_SAVED_STATS.computeIfAbsent(uuid, k -> new PacketInfo()).addValues(1, packetSize);
            }
            
            // 核心逻辑：如果玩家不处于 AFK 状态，则不干涉数据包
            if (!AFK_PLAYERS.contains(uuid)) {
                return;
            }
            
            // 检查玩家是否在钓鱼，钓鱼时需要放行关键的反馈包
            boolean isFishing = IS_FISHING_CACHE.getOrDefault(uuid, false);
            if (isFishing) {
                if (type == PacketType.Play.Server.SOUND_EFFECT ||
                    type == PacketType.Play.Server.ENTITY_SOUND_EFFECT ||
                    type == PacketType.Play.Server.ENTITY_VELOCITY ||
                    type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE ||
                    type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION ||
                    type == PacketType.Play.Server.PARTICLE || 
                    type == PacketType.Play.Server.SPAWN_ENTITY ||
                    type == PacketType.Play.Server.ENTITY_METADATA) {
                    return; 
                }
            }

            // 1. 完全拦截：高频视觉/听觉类数据包
            if (CANCELLED_PACKET_TYPES.contains(type)) {
                cancelEvent(event, uuid, packetSize);
                return;
            }
            
            // 2. 动态拦截：TAB 列表数据包
            if (interceptTabList && TAB_PACKET_TYPES.contains(type)) {
                cancelEvent(event, uuid, packetSize);
                return;
            }

            // 3. 动态拦截：区块加载/卸载数据包（地形冻结）
            if (interceptChunkPackets && CHUNK_PACKET_TYPES.contains(type)) {
                cancelEvent(event, uuid, packetSize);
                return;
            }

            // 4. 特殊处理：受伤动画（通过字节流检测状态位）
            if (type == PacketType.Play.Server.ENTITY_STATUS) {
                io.netty.buffer.ByteBuf buf = (io.netty.buffer.ByteBuf) event.getByteBuf();
                if (buf != null && buf.readableBytes() >= 5) {
                    buf.markReaderIndex();
                    buf.skipBytes(4);
                    byte status = buf.readByte();
                    buf.resetReaderIndex();
                    
                    if (status == 2) { // 2 代表受击动画
                        cancelEvent(event, uuid, packetSize);
                    }
                }
                return;
            }

            // 5. 概率拦截：大幅度减少实体移动同步频率 (保留 2% 确保位置不漂移过远)
            if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE ||
                type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION ||
                type == PacketType.Play.Server.ENTITY_ROTATION ||
                type == PacketType.Play.Server.ENTITY_VELOCITY) {
                
                if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() >= 0.02) {
                    cancelEvent(event, uuid, packetSize);
                }
                return;
            }

            // 6. 概率拦截：实体生成 (拦截 50%，减少密集实体区的渲染压力)
            if (type == PacketType.Play.Server.SPAWN_ENTITY) {
                if (java.util.concurrent.ThreadLocalRandom.current().nextBoolean()) {
                    cancelEvent(event, uuid, packetSize);
                }
                return;
            }

            // 7. 概率拦截：头部旋转 (拦截 80%)
            if (type == PacketType.Play.Server.ENTITY_HEAD_LOOK) {
                if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() >= 0.20) {
                    cancelEvent(event, uuid, packetSize);
                }
                return;
            }
            
            // 8. 概率拦截：元数据更新 (拦截 95%，如附魔发光等)
            if (type == PacketType.Play.Server.ENTITY_METADATA) {
                if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() >= 0.05) {
                    cancelEvent(event, uuid, packetSize);
                }
                return;
            }
        }

        /**
         * 辅助方法：取消事件并记录统计信息
         */
        private void cancelEvent(PacketSendEvent event, UUID uuid, long packetSize) {
            event.setCancelled(true);
            long size = packetSize >= 0 ? packetSize : getPacketSizeFromEvent(event);
            handleCancelledPacketWithSize(event, uuid, size);
        }

        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            User user = event.getUser();
            if (user == null) return;
            UUID uuid = user.getUUID();
            if (uuid == null) return;

            com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon type = event.getPacketType();

            // 监听客户端发给服务端的视角移动包，解决骑乘状态下 Bukkit 不触发 PlayerMoveEvent 的痛点
            if (type == PacketType.Play.Client.PLAYER_ROTATION || 
                type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
                
                com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying flyingPacket = 
                        new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying(event);
                
                // 确认该包包含视角数据
                if (flyingPacket.hasRotationChanged()) {
                    float currentYaw = flyingPacket.getLocation().getYaw();
                    float currentPitch = flyingPacket.getLocation().getPitch();
                    
                    Float lastYaw = LAST_YAW.get(uuid);
                    Float lastPitch = LAST_PITCH.get(uuid);
                    
                    if (lastYaw != null && lastPitch != null) {
                        float yawDiff = Math.abs(Math.abs(currentYaw - lastYaw) - 180) - 180;
                        float pitchDiff = Math.abs(currentPitch - lastPitch);
                        float totalAngleDiff = Math.abs(yawDiff) + Math.abs(pitchDiff);
                        
                        // 如果视角变化超过阈值
                        if (totalAngleDiff > HEAD_MOVEMENT_THRESHOLD) {
                            LAST_YAW.put(uuid, currentYaw);
                            LAST_PITCH.put(uuid, currentPitch);
                            LAST_HEAD_MOVEMENT_TIME.put(uuid, System.currentTimeMillis());
                            
                            // 核心唤醒逻辑：如果在普通的 AFK 模式中，立刻强制唤醒
                            if (AFK_PLAYERS.contains(uuid) && !HARDCORE_AFK_PLAYERS.contains(uuid)) {
                                Player player = Bukkit.getPlayer(uuid);
                                if (player != null && player.isOnline()) {
                                    // Folia 要求所有实体状态修改必须在玩家所在的 Region 线程中执行
                                    player.getScheduler().run(RIABandwidthSaver.this, task -> {
                                        // 再次检测，防止多线程重复触发
                                        if (AFK_PLAYERS.contains(uuid)) {
                                            playerEcoDisable(player);
                                        }
                                    }, null);
                                }
                            }
                        }
                    } else {
                        LAST_YAW.put(uuid, currentYaw);
                        LAST_PITCH.put(uuid, currentPitch);
                        LAST_HEAD_MOVEMENT_TIME.putIfAbsent(uuid, System.currentTimeMillis());
                    }
                }
            }
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
                
                // 免死金牌检测：特权、睡觉、载具、滑翔 (鞘翅)、或不在白名单世界
                String currentWorld = player.getWorld().getName().toLowerCase();
                
                // 增强骑乘与被骑乘检测（完美兼容 GSit 叠罗汉）
                boolean isRiding = player.getVehicle() != null;
                boolean hasPassenger = !player.getPassengers().isEmpty();

                if (player.hasPermission("bandwidthsaver.bypass") || player.isSleeping() || player.isInsideVehicle() || isRiding || hasPassenger || player.isGliding() || !enabledWorlds.contains(currentWorld)) {
                    
                    // 若已处于 AFK 状态，立即强制唤醒
                    if (AFK_PLAYERS.contains(uuid)) {
                        playerEcoDisable(player);
                        
                        // 同步关闭硬核 AFK 模式，防止逻辑冲突
                        if (HARDCORE_AFK_PLAYERS.contains(uuid)) {
                            HARDCORE_AFK_PLAYERS.remove(uuid);
                            player.sendMessage(ChatColor.YELLOW + "检测到特殊状态 (乘车/飞行)，已自动关闭省流模式。");
                        }
                    }
                    
                    // 刷新最后活动时间，防止刚脱离免死状态就秒进 AFK
                    LAST_HEAD_MOVEMENT_TIME.put(uuid, System.currentTimeMillis());
                    
                    return; // 跳过后续所有 AFK 检测逻辑
                }
                
                // 手动 AFK 模式检测
                if (HARDCORE_AFK_PLAYERS.contains(uuid)) {
                    // 强制保持 AFK 状态
                    if (!AFK_PLAYERS.contains(uuid)) {
                        playerEcoEnable(player);
                    }
                    return; // 跳过常规 AFK 检测
                }
                
                // 常规 AFK 检测：检查是否应该进入 AFK 状态
                if (!AFK_PLAYERS.contains(uuid)) {
                    Long lastHeadMovementTime = LAST_HEAD_MOVEMENT_TIME.get(uuid);
                    
                    if (lastHeadMovementTime != null) {
                        long currentTime = System.currentTimeMillis();
                        long timeSinceLastHeadMovement = currentTime - lastHeadMovementTime;
                        
                        // 如果头部在一段时间内没有显著移动，则进入AFK状态
                        if (timeSinceLastHeadMovement >= afkThresholdMs) {
                            playerEcoEnable(player);
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
    


    @Override
    public void reloadConfig() {
        super.reloadConfig();
        this.calcAllPackets = getConfig().getBoolean("calcAllPackets", true);
        this.interceptTabList = getConfig().getBoolean("intercept-tab-list", true);
        this.interceptChunkPackets = getConfig().getBoolean("intercept-chunk-packets", false);
        this.compatibleWithPvdc = getConfig().getBoolean("compatible-with-pvdc", false);
        
        // 读取世界白名单，统一转小写以防匹配出错
        this.enabledWorlds = getConfig().getStringList("enabled-worlds").stream()
                .map(String::toLowerCase)
                .collect(Collectors.toList());
        
        // 加载 AFK 阈值配置（秒转毫秒）
        int afkThresholdSeconds = getConfig().getInt("afkPerspectiveThresholdSeconds", 300);
        this.afkThresholdMs = afkThresholdSeconds * 1000L;
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
        // 处理被拦截数据包的统计信息
        Object packetType = event.getPacketType();
        
        PKT_TYPE_STATS.computeIfAbsent(packetType, k -> new PacketInfo()).addValues(1, packetSize);
        PLAYER_PKT_SAVED_STATS.computeIfAbsent(uuid, k -> new PacketInfo()).addValues(1, packetSize);
    }
    
    private long getPacketSizeFromEvent(PacketSendEvent event) {
        try {
            Object rawBuffer = event.getByteBuf();
            if (rawBuffer != null) {
                ByteBuf byteBuf = (ByteBuf) rawBuffer;
                return ByteBufHelper.readableBytes(byteBuf);
            }
            return 0L;
        } catch (Exception e) {
            return 0L;
        }
    }



    public void playerEcoEnable(Player player) {
        String message = getConfig().getString("message.playerEcoEnable", "");
        if(!message.isEmpty()){
            sendRichMessage(player, message);
        }
        if(getConfig().getBoolean("modifyPlayerViewDistance")) {
            player.setSendViewDistance(8);
        }
        
        // 创建 ECO 模式 BossBar
        UUID playerUuid = player.getUniqueId();
        UUID ecoBarUuid = UUID.randomUUID();
        ECO_BAR_UUIDS.put(playerUuid, ecoBarUuid);

        try {
            // 从配置文件获取 BossBar 参数
            String titleText = getConfig().getString("bossbar.eco-enabled-title", "<green><bold>🍃 ECO 节能模式</bold> <gray>|</gray> <yellow>⬇ 已暂停高频数据传输</yellow> <gray>|</gray> <white>↔ 轻晃视角以恢复</white>");
            float health = (float) getConfig().getDouble("bossbar.eco-enabled-health", 1.0);
            String colorStr = getConfig().getString("bossbar.eco-enabled-color", "YELLOW");
            String overlayStr = getConfig().getString("bossbar.eco-enabled-overlay", "PROGRESS");
            
            Component title = MiniMessage.miniMessage().deserialize(titleText);
            
            WrapperPlayServerBossBar bossBarPacket = new WrapperPlayServerBossBar(
                ecoBarUuid,
                WrapperPlayServerBossBar.Action.ADD
            );
            
            bossBarPacket.setTitle(title);
            bossBarPacket.setHealth(health);
            
            net.kyori.adventure.bossbar.BossBar.Color color;
            try {
                color = net.kyori.adventure.bossbar.BossBar.Color.valueOf(colorStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                color = net.kyori.adventure.bossbar.BossBar.Color.YELLOW;
            }
            
            net.kyori.adventure.bossbar.BossBar.Overlay overlay;
            try {
                overlay = net.kyori.adventure.bossbar.BossBar.Overlay.valueOf(overlayStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                overlay = net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS;
            }
            
            bossBarPacket.setColor(color);
            bossBarPacket.setOverlay(overlay);
            bossBarPacket.setFlags(java.util.EnumSet.noneOf(net.kyori.adventure.bossbar.BossBar.Flag.class));
            
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, bossBarPacket);
        } catch (Exception e) {
            getLogger().warning("Failed to send ECO BossBar to player " + player.getName() + ": " + e.getMessage());
        }

        // 延迟 1 tick 添加 AFK 标记（确保 BossBar 数据包先通过监听器）
        player.getScheduler().runDelayed(this, (task) -> {
            if (player.isOnline()) {
                AFK_PLAYERS.add(player.getUniqueId());
                ENTER_AFK_TIME.put(player.getUniqueId(), System.currentTimeMillis());
                getLogger().info("Player " + player.getName() + " (" + player.getUniqueId() + ") entered AFK mode");
            }
        }, null, 1L);
    }

    /**
     * 关闭节能模式并恢复玩家的正常网络通信
     */
    public void playerEcoDisable(Player player) {
        UUID playerUuid = player.getUniqueId();
        
        // 立即标记为非 AFK 状态，让后续数据包通过
        AFK_PLAYERS.remove(playerUuid);
        
        // 结算本次 AFK 时长
        Long enterTime = ENTER_AFK_TIME.remove(playerUuid);
        if (enterTime != null) {
            long duration = System.currentTimeMillis() - enterTime;
            TOTAL_AFK_TIME_MS.computeIfAbsent(playerUuid, k -> new java.util.concurrent.atomic.LongAdder()).add(duration);
        }

        // Folia 优化：确保所有状态修改都在玩家所在的区域线程执行
        player.getScheduler().run(this, regionTask -> {
            if (!player.isOnline()) return;

            // 视距恢复逻辑
            boolean modifyVD = getConfig().getBoolean("modifyPlayerViewDistance");
            if (interceptChunkPackets) {
                int currentVD = player.getSendViewDistance();
                int targetVD = currentVD > 0 ? currentVD : Bukkit.getServer().getViewDistance();
                
                if (compatibleWithPvdc) {
                    // 兼容 PVDC：延迟拉扯视距
                    player.getScheduler().runDelayed(this, task1 -> {
                        if (player.isOnline() && !AFK_PLAYERS.contains(playerUuid)) {
                            player.setSendViewDistance(targetVD + 1);
                            player.getScheduler().runDelayed(this, task2 -> {
                                if (player.isOnline() && !AFK_PLAYERS.contains(playerUuid)) {
                                    player.setSendViewDistance(modifyVD ? -1 : targetVD);
                                }
                            }, null, 20L);
                        }
                    }, null, 40L);
                } else {
                    // 普通模式：立即拉扯视距
                    player.setSendViewDistance(targetVD + 1);
                    player.getScheduler().runDelayed(this, task -> {
                        if (player.isOnline() && !AFK_PLAYERS.contains(playerUuid)) {
                            player.setSendViewDistance(modifyVD ? -1 : targetVD);
                        }
                    }, null, 20L);
                }
            } else if (modifyVD) {
                player.setSendViewDistance(-1);
            }

            player.resetPlayerTime();
            
            // 发送退出提示
            String message = getConfig().getString("message.playerEcoDisable", "");
            if(!message.isEmpty()){
                sendRichMessage(player, message);
            }
            
            // 移除 BossBar
            UUID ecoBarUuid = ECO_BAR_UUIDS.remove(playerUuid);
            if (ecoBarUuid != null) {
                try {
                    WrapperPlayServerBossBar removeBossBarPacket = new WrapperPlayServerBossBar(ecoBarUuid, WrapperPlayServerBossBar.Action.REMOVE);
                    PacketEvents.getAPI().getPlayerManager().sendPacket(player, removeBossBarPacket);
                } catch (Exception ignored) {}
            }
            
            // 刷新周围实体，修复“幽灵实体”问题
            // 性能优化：将半径从 48 缩小到 32，减少 Folia 区域扫描负担
            try {
                java.util.List<org.bukkit.entity.Entity> nearbyEntities = player.getNearbyEntities(32, 32, 32);
                for (org.bukkit.entity.Entity entity : nearbyEntities) {
                    if (entity.isValid()) {
                        if (player.isInsideVehicle() && entity.equals(player.getVehicle())) continue;
                        
                        if (entity instanceof Player) {
                            Player nearbyPlayer = (Player) entity;
                            player.hidePlayer(this, nearbyPlayer);
                            player.showPlayer(this, nearbyPlayer);
                        } else {
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
            } catch (Exception e) {
                getLogger().warning("刷新实体时出错: " + e.getMessage());
            }
        }, null);

        getLogger().info("玩家 " + player.getName() + " 退出省流模式");
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
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // 检查是否有绕过权限
        if (player.hasPermission("bandwidthsaver.bypass")) {
            if (AFK_PLAYERS.contains(playerId)) playerEcoDisable(player);
            return;
        }
        
        // 硬核 AFK 模式下不处理
        if (HARDCORE_AFK_PLAYERS.contains(playerId)) return;
        
        // 如果玩家使用了烟花火箭，强制唤醒并重置时间
        if (event.getItem() != null && event.getItem().getType() == org.bukkit.Material.FIREWORK_ROCKET) {
            if (AFK_PLAYERS.contains(playerId)) {
                playerEcoDisable(player);
            }
            LAST_HEAD_MOVEMENT_TIME.put(playerId, System.currentTimeMillis());
        }
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
        
        // 新增：清理玩家总计 AFK 时间
        TOTAL_AFK_TIME_MS.remove(playerId);
        
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

            // 硬核 AFK 模式保护
            if (HARDCORE_AFK_PLAYERS.contains(playerId)) {
                // 如果是手动输入的 /afk，被打时不退出 AFK
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

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // 忽略同区块内的微小移动（比如上下车）
        if (event.getFrom().getWorld() == event.getTo().getWorld() && 
            event.getFrom().distanceSquared(event.getTo()) < 16) { 
            return;
        }

        // 如果玩家在 AFK 期间被传送（如管理员强制 tp、死亡重生等）
        // 立刻打断 AFK，否则到了新地方加载不出区块会一直往下掉
        if (AFK_PLAYERS.contains(playerId)) {
            playerEcoDisable(player);
            // 更新最后头部移动时间，防止刚传过去又秒进 AFK
            LAST_HEAD_MOVEMENT_TIME.put(playerId, System.currentTimeMillis());
            
            // 如果是硬核模式，为了防止卡死，也建议暂时移除
            if (HARDCORE_AFK_PLAYERS.contains(playerId)) {
                HARDCORE_AFK_PLAYERS.remove(playerId);
                player.sendMessage(ChatColor.YELLOW + "检测到传送，已为您自动退出手动 AFK 模式以加载地形。");
            }
        }
    }

    // 修复：展开鞘翅时立刻解除挂机，恢复物理引擎数据包下发
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityToggleGlide(org.bukkit.event.entity.EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player && event.isGliding()) {
            Player player = (Player) event.getEntity();
            UUID uuid = player.getUniqueId();
            
            if (AFK_PLAYERS.contains(uuid)) {
                playerEcoDisable(player);
                if (HARDCORE_AFK_PLAYERS.contains(uuid)) {
                    HARDCORE_AFK_PLAYERS.remove(uuid);
                    player.sendMessage(ChatColor.YELLOW + "检测到展开鞘翅，已自动退出省流模式。");
                }
            }
            // 只要在飞，就重置发呆时间
            LAST_HEAD_MOVEMENT_TIME.put(uuid, System.currentTimeMillis());
        }
    }

    @Override
    public void onDisable() {
        // 取消所有玩家的专用任务
        // 取消所有玩家的检测任务
        for (io.papermc.paper.threadedregions.scheduler.ScheduledTask task : PLAYER_TASKS.values()) {
            task.cancel();
        }
        PLAYER_TASKS.clear();
        
        // 终止 PacketEvents
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
        // 处理 /afk 命令（手动 AFK 模式）
        if (command.getName().equalsIgnoreCase("afk")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "此命令只能由玩家执行！");
                return true;
            }
            
            Player player = (Player) sender;
            UUID playerId = player.getUniqueId();
            
            if (HARDCORE_AFK_PLAYERS.contains(playerId)) {
                HARDCORE_AFK_PLAYERS.remove(playerId);
                if (AFK_PLAYERS.contains(playerId)) {
                    playerEcoDisable(player);
                }
                player.sendMessage(ChatColor.GREEN + "您已退出手动 AFK 模式！");
            } else {
                HARDCORE_AFK_PLAYERS.add(playerId);
                if (!AFK_PLAYERS.contains(playerId)) {
                    playerEcoEnable(player);
                }
                player.sendMessage(ChatColor.YELLOW + "您已进入手动 AFK 模式！再次输入/afk 退出此模式。");
            }
            return true;
        }
        
        // 处理 /bandwidthsaver 命令
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
                sortedPlayerMap.entrySet().stream().limit(5).forEach(entry -> {
                    UUID u = entry.getKey();
                    String name = Bukkit.getOfflinePlayer(u).getName();
                    long pkts = entry.getValue().getPktCounter().longValue();
                    String size = humanReadableByteCount(entry.getValue().getPktSize().longValue(), false);
                    
                    // 计算该玩家的总AFK时间（如果正在挂机，则加上当前的挂机进度）
                    long currentSession = 0;
                    if (AFK_PLAYERS.contains(u) && ENTER_AFK_TIME.containsKey(u)) {
                        currentSession = System.currentTimeMillis() - ENTER_AFK_TIME.get(u);
                    }
                    long totalAfk = (TOTAL_AFK_TIME_MS.containsKey(u) ? TOTAL_AFK_TIME_MS.get(u).sum() : 0) + currentSession;
                    
                    sender.sendMessage(ChatColor.GRAY + name + " - " + pkts + " 个 (" + size + ")" + ChatColor.GREEN + " [挂机时长: " + formatAfkTime(totalAfk) + "]");
                });
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
    
    public static String formatAfkTime(long millis) {
        long seconds = millis / 1000;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) return String.format("%d小时%d分%d秒", h, m, s);
        if (m > 0) return String.format("%d分%d秒", m, s);
        return String.format("%d秒", s);
    }
    

    

    

    /**
     * 发送支持 MiniMessage 或传统颜色代码的消息
     */
    private void sendRichMessage(Player player, String message) {
        if (message == null || message.isEmpty()) return;
        
        if (message.contains("§") || message.contains("&")) {
            // 如果包含传统颜色代码，使用 Legacy 序列化器处理
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(ChatColor.translateAlternateColorCodes('&', message)));
        } else {
            // 否则使用 MiniMessage 处理现代文本格式
            try {
                player.sendMessage(MiniMessage.miniMessage().deserialize(message));
            } catch (Exception e) {
                // 如果 MiniMessage 解析失败（可能包含无法识别的标签），回退到普通文本
                player.sendMessage(message);
            }
        }
    }

}
