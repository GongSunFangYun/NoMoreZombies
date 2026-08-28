package cn.gsfy.nmz.client.shared;

import cn.gsfy.nmz.NoMoreZombies;
import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.data.DataManager;
import cn.gsfy.nmz.client.data.model.MapId;
import cn.gsfy.nmz.client.feature.gamehud.AaCommander;
import cn.gsfy.nmz.client.feature.gamehud.TotalHUDRenderer;
import cn.gsfy.nmz.client.feature.playerquery.PlayerQueryManager;
import cn.gsfy.nmz.client.feature.powerups.PowerupDetect;
import cn.gsfy.nmz.client.feature.powerups.PowerupPredict;
import cn.gsfy.nmz.client.feature.spawntimes.CheckSpawnTimes;
import cn.gsfy.nmz.client.feature.spawntimes.SpawnNotice;
import cn.gsfy.nmz.client.feature.stats.TeamStatsManager;
import cn.gsfy.nmz.client.feature.recorder.TimeRecorder;
import cn.gsfy.nmz.client.util.LanguageUtils;
import cn.gsfy.nmz.client.util.PlayerUtils;
import cn.gsfy.nmz.client.util.StringUtils;

import java.util.ArrayList;

/**
 * 游戏状态总线——把「回合开始 / 游戏结束 / 世界声音」等状态信号统一收口到这里处理，
 * Mixin 只负责把原始信号转发进来，不含业务逻辑。
 *
 * <p>所有跨功能的状态推进（计时、玩家名单、道具、地图缓存、AA 指挥…）都在这一个入口
 * 按顺序编排：先记上一回合、再重置状态、最后触发各功能回调，谁先谁后就是因果顺序。
 * 时序坑（回合标题晚于计分板刷新、重进无新回合标题等）在各分支注释里标明。
 */
public final class GameEventBus {

    /** InGameHud.setTitle 钩子——回合开始 / 游戏结束标题都从这里进来。 */
    public static void onSetTitle(String title) {
        String trimmed = StringUtils.trim(title);
        boolean gameEnd = LanguageUtils.isGameEnd(trimmed) || LanguageUtils.isGameEndSummary(trimmed);
        boolean isRound = !gameEnd && LanguageUtils.isRoundTitle(trimmed);
        if (isRound || gameEnd) {
            // 注意顺序：先记录用时（此时 gameTick 尚未清零，currentRound 仍是上一轮）
            TimeRecorder.recordGameTime();

            if (gameEnd) {
                // 游戏结束：冻结计时器（通关/团灭都是回合 + 总计时冻结在当前值）。
                // 不调 startOrSplit()——不重置 gameTick，保留冻结的数值。gameStarted 保持 true 供 HUD 继续渲染冻结值。
                // won 幂等：龙死亡音效（onWorldPlaySound）已先置 gameWon=true 时，标题不再覆盖胜利标志。
                boolean won = GameTickHandler.get().isGameOver()
                        ? GameTickHandler.get().isGameWon()
                        : LanguageUtils.isWinTitle(trimmed);
                GameTickHandler.get().setGameStarted(true);
                if (!GameTickHandler.get().isGameOver()) {
                    GameTickHandler.get().onGameEnd(won);
                }
                // 失败（全队覆灭）：未退出玩家全部判死，兜底防止游戏结束后仍显示「战斗中」
                TeamStatsManager.onGameEnd(won);
                // 本局结束：销毁玩家数据查询缓存（下一局重新请求）
                if (PlayerQueryManager.get() != null) {
                    PlayerQueryManager.get().clearCache();
                }
                // 本局结束：计分板缓存随游戏结束清空（下一局重新轮询）
                if (ScoreboardManager.get() != null) {
                    ScoreboardManager.get().clear();
                }
                // 本局结束：地图缓存随游戏结束失效（下一局/换图重新识别，防止 AA 缓存串到非 AA 图）
                LanguageUtils.invalidateMapCache();
            } else {
                GameTickHandler.get().setGameStarted(true);
                GameTickHandler.get().startOrSplit();
            }
            int round = gameEnd ? 0 : LanguageUtils.getRoundNumber(trimmed);

            // 这些必须在 isInZombiesTitle 检查之前运行，确保跨局/回合的玩家状态正确重置
            if (round > 0) {
                // 本轮是否为「本局首个回合标题」：lastRoundSeen 在确认离场（out-of-zombies）与
                // 世界切换（world null）时复位为 0；首个回合标题后的正常新回合均为非 0。
                // 用于区分「本地中途重进进行中的局」（round>1 且首次标题）与普通新回合——
                // 不依赖 isTotalStarted()：重进瞬间世界切换会把它复位，导致重进检测错位到下个回合
                // （正是「重进不显示、开新回合反而显示」的根因）。
                boolean firstRoundTitle = GameTickHandler.get().getLastRoundSeen() == 0;
                if (round == 1) {
                    TeamStatsManager.onNewGame(); // 新游戏：清空旧名单，重置全员数据（含缓存文件）
                    // 新局开始：地图缓存随新局失效（2026-08-19 实战铁证——地图缓存只在上局结束/断线时
                    // 失效，新局开始若不失效会把上一张图串进新局：DE 局结束后进 AA 局，getMap() 整局返回
                    // 缓存 DEAD_END，AA 指挥 HUD / 电击棒 HUD / 波次表 / 道具预测全错）。round==1 恒为新局
                    // （同图续局也重新识别，正确无害）。
                    LanguageUtils.invalidateMapCache();
                } else {
                    TeamStatsManager.onNewRound(); // 新回合：复活非退出玩家，进入保护期
                }
                GameTickHandler.get().setLastRoundSeen(round);
                // 第1回合=新游戏从0起算；中途加入=从加入时刻起算（round 可能>1）
                if (!GameTickHandler.get().isTotalStarted()) {
                    // 中途加入（round>1）：本客户端首次回合标题即新局，同样失效地图缓存重新识别
                    LanguageUtils.invalidateMapCache();
                    GameTickHandler.get().startGameTimer();
                }
                // 本地中途重进进行中的局（round>1 且本轮是本局首个回合标题）：从 %temp%/nmz_gamecache.json
                // 恢复本局累计数据（队伍统计 + 游戏/回合时长），局末可审查全队总数据、崩溃重启数据不丢。
                // 缓存只是兜底——正常游戏 HUD 实时追随游戏（计分板权威），新回合 resetStatuses 走游戏
                // 强制同步。队友单独重进不触发（不产生本地回合标题）；本地自己加入没有「XXX重新加入游戏」
                // 消息，不走 chat 检测。注意在 startGameTimer() 之后调用：让恢复的总时长不被清零覆盖。
                if (round > 1 && firstRoundTitle) {
                    GameCache.restore(round);
                }
            }

            // 波次 HUD 依赖 currentRound/shouldRender，这两者不依赖计分板标题（标题可能延迟更新）：
            // 回合标题有效即推进，避免「断线重进后计分板标题晚于首回合标题」导致波次 HUD 整局不亮。
            CheckSpawnTimes.get().setCurrentRound(round);
            TotalHUDRenderer.setShouldRender(round > 0);
            // 外星游乐园自动指挥：每回合开始触发（内部延迟 20 tick 等待 AA 地图识别，开关/地图自行门控）
            AaCommander.onRoundStarted(round);
            // 智能同步 AA 专属 HUD（AA 指挥 + 电击棒序列）可见性：AA 自动启用，非 AA 自动禁用。
            // 地图识别可能晚于回合标题，故延迟 + 未识别时重试。
            if (round > 0) {
                scheduleAaHudVisibilitySync(0);
            }

            // 以下只对 Zombies 生效（计分板标题可能延迟更新，不能阻塞上面的重置）
            if (!PlayerUtils.isInZombiesTitle()) {
                return;
            }
            SpawnNotice.update(round);
            handlePowerupsOnRound(round);
            // 玩家数据查询（网络请求）仅在 Zombies 局内调度：每回合延迟 3 秒触发，onGameStart 幂等
            // （已缓存/请求中自动跳过），自愈第 1 回合查询任务被离场误判取消的竞态，并补查中途加入的队友。
            // 移入 isInZombiesTitle 门控内，防止非 Zombies 出现「Round」标题时局外发网络请求。
            if (round > 0 && PlayerQueryManager.get() != null) {
                DelayedTaskScheduler.get().runTaskLater(60, PlayerQueryManager.get()::fetchInGamePlayers);
            }
        }
    }

    /**
     * 延迟同步 AA 专属 HUD 可见性：AA 自动启用，非 AA 自动禁用（编辑器勾选随之联动，即「智能切换」）。
     * 地图未识别时最多重试 30 次（≈10s）；重试耗尽仍未知时跳过本次同步——保持现值，
     * 绝不把 NULL 当非 AA 处理。若随后地图才解析出来，LanguageUtils 的地图解析回调会重新触发本同步。
     */
    private static void scheduleAaHudVisibilitySync(int attempt) {
        DelayedTaskScheduler.get().runTaskLater(20, () -> {
            MapId map = LanguageUtils.getMap();
            if (map == MapId.NULL) {
                if (attempt < 30) {
                    scheduleAaHudVisibilitySync(attempt + 1);
                } else {
                    NoMoreZombies.LOGGER.info("[HUD切换] 地图仍未识别（已重试 {} 次），保持 AA HUD 可见性现值 {}",
                            attempt, GlobalConfig.Hud.VISIBLE_AA_COMMAND.getBooleanValue());
                }
                return;
            }
            boolean aa = map == MapId.ALIEN_ARCADIUM;
            GlobalConfig.Hud.VISIBLE_AA_COMMAND.setBooleanValue(aa);
            GlobalConfig.Hud.VISIBLE_LRQUEUE.setBooleanValue(aa);
            NoMoreZombies.LOGGER.info("[HUD切换] 地图={} → AA指挥HUD/电击棒HUD 可见性={}（AA={}）", map, aa, aa);
        });
    }

    /** 地图解析完成时由 LanguageUtils 回调：重新同步 AA HUD 可见性（覆盖「识别太晚」的窗口）。 */
    public static void resyncAaHudVisibility() {
        scheduleAaHudVisibilitySync(0);
    }

    /** 回合开始时的道具逻辑：清空上回合预测/拾取记录，预测本回合道具、BOSS 回合回收在场道具。 */
    private static void handlePowerupsOnRound(int round) {
        if (PowerupDetect.get() == null) {
            return;
        }
        if (round <= 0) {
            return;
        }
        Powerup.incPowerups.clear();
        Powerup.pickedUpRound.clear();

        if (isBossRound(round)) {
            new ArrayList<>(Powerup.powerups.values()).forEach(Powerup::claim);
        }
        PowerupDetect detect = PowerupDetect.get();
        boolean insta = detect.isPowerupRound(Powerup.PowerupType.INSTA_KILL, round);
        boolean maxAmmo = detect.isPowerupRound(Powerup.PowerupType.MAX_AMMO, round);
        boolean ss = detect.isPowerupRound(Powerup.PowerupType.SHOPPING_SPREE, round);
        if (insta) {
            Powerup.deserialize(Powerup.PowerupType.INSTA_KILL);
        }
        if (maxAmmo) {
            Powerup.deserialize(Powerup.PowerupType.MAX_AMMO);
        }
        if (ss) {
            Powerup.deserialize(Powerup.PowerupType.SHOPPING_SPREE);
        }
        if (GlobalConfig.Powerups.POWERUP_PREDICT.getBooleanValue()) {
            DelayedTaskScheduler.get().runTaskLater(40, PowerupPredict::detectNextPowerupRound);
        }
    }

    private static boolean isBossRound(int round) {
        MapId map = LanguageUtils.getMap();
        if (map == MapId.NULL) {
            return false;
        }
        int[] bossRounds = DataManager.get().getBossRounds(map);
        for (int r : bossRounds) {
            if (r == round) {
                return true;
            }
        }
        return false;
    }

    /** ClientWorld.playSound 钩子——用声音识别游戏状态（凋灵出生 / 龙死亡）。 */
    public static void onWorldPlaySound(String path, float pitch) {
        // 仅 Zombies 局内：防止单机/普通服务器的凋灵生成、末影龙死亡音效污染游戏状态机
        if (!PlayerUtils.isInZombies()) {
            return;
        }
        if (path.equals("entity.wither.spawn")) {
            GameTickHandler.get().setGameStarted(true);
        } else if (path.equals("entity.ender_dragon.death")) {
            // 最终 BOSS 死亡 = 通关：冻结计时器（保留数值，不再清零重置，与游戏结束标题分支行为一致）
            GameTickHandler.get().setGameStarted(true);
            GameTickHandler.get().onGameEnd(true);
        }
    }

    private GameEventBus() {
    }
}