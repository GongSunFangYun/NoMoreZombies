package cn.gsfy.nmz.client;

import cn.gsfy.nmz.NoMoreZombies;
import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.config.InitHandler;
import cn.gsfy.nmz.client.data.DataManager;
import cn.gsfy.nmz.client.feature.esp.EspRenderer;
import cn.gsfy.nmz.client.feature.freecam.FreeCameraHandler;
import cn.gsfy.nmz.client.feature.gamehud.AaCommander;
import cn.gsfy.nmz.client.feature.gamehud.CpsRenderer;
import cn.gsfy.nmz.client.feature.gamehud.LightningRodQueue;
import cn.gsfy.nmz.client.feature.gamehud.PowerupRenderer;
import cn.gsfy.nmz.client.feature.gamehud.SpawnTimeRenderer;
import cn.gsfy.nmz.client.feature.gamehud.TeamStatsRenderer;
import cn.gsfy.nmz.client.feature.gamehud.TimeHudRenderer;
import cn.gsfy.nmz.client.feature.gamehud.TotalHUDRenderer;
import cn.gsfy.nmz.client.feature.healthbar.HealthBarRenderer;
import cn.gsfy.nmz.client.feature.invisibility.HideNearbyPlayer;
import cn.gsfy.nmz.client.feature.playerquery.PlayerQueryManager;
import cn.gsfy.nmz.client.feature.powerups.PowerupDetect;
import cn.gsfy.nmz.client.feature.spawntimes.CheckSpawnTimes;
import cn.gsfy.nmz.client.feature.spawntimes.SpawnNotice;
import cn.gsfy.nmz.client.feature.stats.TeamStatsManager;
import cn.gsfy.nmz.client.feature.zoom.ZoomHandler;
import cn.gsfy.nmz.client.shared.DelayedTaskScheduler;
import cn.gsfy.nmz.client.shared.EspTargets;
import cn.gsfy.nmz.client.shared.GameEventBus;
import cn.gsfy.nmz.client.shared.GameTickHandler;
import cn.gsfy.nmz.client.shared.ScoreboardManager;
import cn.gsfy.nmz.client.util.LanguageUtils;
import cn.gsfy.nmz.client.util.StringUtils;
import fi.dy.masa.malilib.event.InitializationHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.minecraft.util.Identifier;

/**
 * 客户端模组入口（Fabric {@link ClientModInitializer}）——所有功能从这里站起来。
 *
 * <p>{@link #onInitializeClient()} 按「先基座、后功能」的顺序把整台机器点亮：
 * 先接 MaLiLib（配置与热键）、数据表与地图解析，再挂聊天 / 进服 / 断线三个事件入口，
 * 最后逐一把各功能（波次、道具、HUD、队伍统计、ESP…）初始化。注册顺序就是初始化
 * 优先级，功能间的依赖靠各单例的 {@code init()} 收口——谁先 init 谁先可用。
 */
public class NoMoreZombiesClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // MaLiLib：注册初始化处理器——配置加载与热键注册都等它在 onGameInitDone 统一收口
        InitializationHandler.getInstance().registerInitializationHandler(new InitHandler());

        // 数据表（F3+T 资源重载时一并重读）
        DataManager.init();

        // 地图识别完成时立刻重同步 AA 专属 HUD 的可见性：AA 自动启用、非 AA 自动禁用，
        // 补上「回合标题比地图识别更早、首轮同步还没跑完」那段窗口
        LanguageUtils.onMapResolved(GameEventBus::resyncAaHudVisibility);

        // 晚渲染层：把位于聊天区的 HUD（如电击棒队列）挂到聊天层之后，免得被聊天背景盖住
        HudLayerRegistrationCallback.EVENT.register(drawer ->
                drawer.attachLayerAfter(IdentifiedLayer.CHAT, IdentifiedLayer.of(
                        Identifier.of(NoMoreZombies.MOD_ID, "late_hud"),
                        (context, tickCounter) -> TotalHUDRenderer.renderLateHud(context, tickCounter))));

        // 核心基座：计分板轮询、墙钟、延迟任务
        new GameTickHandler().init();
        new DelayedTaskScheduler().init();
        new ScoreboardManager().init();

        // 聊天消息统一入口：按功能分发（道具 / 复活 / 难度 / 队伍统计）
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String raw = StringUtils.getRaw(message);
            if (PowerupDetect.get() != null) {
                PowerupDetect.get().onChatReceived(raw);
            }
            TeamStatsManager.onChatReceived(raw);
        });

        // 状态清理：进世界重置 / 断线复位
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (PowerupDetect.get() != null) {
                PowerupDetect.get().iniPowerupPatterns();
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ScoreboardManager.get().clear();
            GameTickHandler.get().setGameStarted(false);
            LanguageUtils.invalidateMapCache();
            TotalHUDRenderer.setShouldRender(false);
            PlayerQueryManager.get().clearCache();
            if (PowerupDetect.get() != null) {
                PowerupDetect.get().iniPowerupPatterns();
            }
            // 断线：挂起的延迟任务全取消——不取消的话，残留任务会在新局里乱跑
            if (DelayedTaskScheduler.get() != null) {
                DelayedTaskScheduler.get().cancelAll();
            }
            // 断线：自由视角立刻还原——否则相机残留会把视角卡死
            FreeCameraHandler.INSTANCE.forceDisable();
        });

        // 波次计时：核心逻辑 + HUD + 整秒音效
        new CheckSpawnTimes().init();
        new SpawnTimeRenderer().init();
        SpawnNotice.update(0);

        // 常驻计时 HUD（总游戏时长 + 本回合）
        new TimeHudRenderer().init();

        // 道具：检测引擎 + HUD
        new PowerupDetect().init();
        new PowerupRenderer().init();

        // 侧边栏 / 计时 / 电击棒队列（LR）
        new LightningRodQueue().init();

        // 外星游乐园自动指挥（HUD + 每回合聊天输出）
        new AaCommander().init();

        // AA 指挥：客户端语言切换时自动把模板配置重写为对应语言默认版本（未自定义才重写）
        ClientTickEvents.START_CLIENT_TICK.register(client -> GlobalConfig.AaCommand.TEMPLATE.checkLanguageChanged());

        // 左右键 CPS 统计 HUD
        new CpsRenderer().init();

        // 平滑缩放（FOV 除法，仅 Zombies 局内）
        ZoomHandler.INSTANCE.init();

        // 自由视角（替身相机实体，每 tick 轮询启停，仅 Zombies 局内）
        FreeCameraHandler.INSTANCE.init();

        // 战斗 / 视觉类 feature
        new HideNearbyPlayer().init();

        // 队伍统计：事件接入层（每 tick 扫描实体快照 + 聊天解析）→ 数据 / 状态机 → HUD
        new TeamStatsManager().init();
        new TeamStatsRenderer().init();

        // 玩家数据查询：局内缓存管理（开局自动请求 / 结束销毁缓存 / tick 自动清理）
        new PlayerQueryManager().init();

        // 实体 ESP（箱体线框）+ 共享目标扫描 + 怪物血条
        EspTargets.init();
        EspRenderer.init();
        HealthBarRenderer.init();
    }
}
