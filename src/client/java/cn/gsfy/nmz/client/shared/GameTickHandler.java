package cn.gsfy.nmz.client.shared;

import cn.gsfy.nmz.client.feature.gamehud.TotalHUDRenderer;
import cn.gsfy.nmz.client.feature.spawntimes.CheckSpawnTimes;
import cn.gsfy.nmz.client.feature.spawntimes.SpawnNotice;
import cn.gsfy.nmz.client.util.LanguageUtils;
import cn.gsfy.nmz.client.util.PlayerUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

/**
 * 游戏计时器——以「1000 单位 = 1 秒」的刻度累计真实墙钟时间。
 *
 * <p>原版用 ScheduledExecutorService 每 10ms +10，这里改为在客户端 tick 里用
 * nanoTime 增量换算：既绕开跨线程竞态，又保持相同刻度。每整秒触发一次
 * SpawnNotice.onSpawn(tick) 检查波次出生。
 */
public class GameTickHandler {

    private static GameTickHandler instance;

    private int gameTick;
    private boolean gameStarted;
    private long lastNano;
    private int lastSecond;
    /** 本局总时长（ms，自进入本局起，跨回合累计不清零；新局/断线复位）。 */
    private long totalGameTick;
    private boolean totalStarted;
    /** 权威回合起点：本回合开始时计分板服务器总时长（ms）的快照。权威回合时长 =
     * 当前总时长 − 该快照，两端都来自服务器时钟；0 = 未就绪（总时长尚未同步 / 刚重进），
     * 此时权威差分不可用，回退到本地回合计时器 gameTick。 */
    private long roundStartTotalMs;
    /** 本局已结束（通关或团灭）：回合/总计时冻结（保留当前值，停止走秒）。 */
    private boolean gameOver;
    /** 通关胜利标志（区分胜利/团灭，供 TeamStats 结束兜底判定；HUD 不再据此区分显示）。 */
    private boolean gameWon;
    /** 上一轮是否已确认在局内。仅当「曾确认在局内 → 现在确认离开」才执行离场复位，
     * 防止回合切换瞬间 isInZombies() 瞬时 false 触发全状态复位并取消挂起任务（如玩家查询）。 */
    private boolean wasInZombies;
    /**
     * 本局最近一次回合标题的回合号；0 = 尚未见任何回合标题。
     * 在确认离场（out-of-zombies）与世界切换（world null）时复位为 0，
     * 用于区分「本地中途重进进行中的局」（round>1 且首次回合标题）与普通新回合——
     * 不再依赖 isTotalStarted()（重进瞬间 world 切换会把它复位，导致重进检测错位到下个回合）。
     */
    private int lastRoundSeen;

    public static GameTickHandler get() {
        return instance;
    }

    public void init() {
        instance = this;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null) {
                // 不在世界中：全部计时与状态归零
                gameTick = 0;
                gameStarted = false;
                lastNano = 0;
                lastSecond = 0;
                totalGameTick = 0;
                totalStarted = false;
                roundStartTotalMs = 0;
                gameOver = false;
                gameWon = false;
                wasInZombies = false;
                lastRoundSeen = 0;
                return;
            }
            boolean inZombies = PlayerUtils.isInZombies();
            // 退出 Zombies（回大厅/其他游戏）时重置全部计时与状态。
            // 仅当曾确认在局内、现在确认离开才复位（回合切换瞬间的瞬时 false 不触发）。
            if (!inZombies) {
                if (wasInZombies && (gameStarted || gameTick != 0 || totalStarted)) {
                    gameTick = 0;
                    gameStarted = false;
                    lastSecond = 0;
                    totalGameTick = 0;
                    totalStarted = false;
                    roundStartTotalMs = 0;
                    gameOver = false;
                    gameWon = false;
                    CheckSpawnTimes.get().setCurrentRound(0);
                    TotalHUDRenderer.setShouldRender(false);
                    cn.gsfy.nmz.client.feature.spawntimes.SpawnNotice.update(0);
                    // 离开 Zombies：地图缓存随离场失效——防上一张图缓存串进下一张图
                    // （2026-08-19 实战铁证：DE 局结束后缓存带 DE 进 AA 局，新局开头失效才是根治）
                    LanguageUtils.invalidateMapCache();
                    // 离开 Zombies：挂起延迟任务全部取消（防跨局残留执行）
                    if (DelayedTaskScheduler.get() != null) {
                        DelayedTaskScheduler.get().cancelAll();
                    }
                }
                wasInZombies = false;
                lastRoundSeen = 0;
                lastNano = System.nanoTime();
                return;
            }
            wasInZombies = true;
            long now = System.nanoTime();
            if (lastNano != 0) {
                int elapsedMs = (int) ((now - lastNano) / 1_000_000L);
                // 游戏结束（gameOver）：回合/总计时一并冻结，停在当前值（通关或团灭都是游戏结束信号）
                if (elapsedMs > 0 && !gameOver) {
                    gameTick += elapsedMs;
                    if (totalStarted) {
                        totalGameTick += elapsedMs;
                    }
                }
            }
            lastNano = now;

            int second = gameTick / 1000;
            if (gameStarted && second != lastSecond) {
                lastSecond = second;
                if (second > 0 && MinecraftClient.getInstance().world != null) {
                    // 传入对齐到整秒的刻度，保证 SpawnNotice 的精确匹配命中
                    SpawnNotice.onSpawn(second * 1000);
                }
            }
        });
    }

    /** 回合开始 / 新回合（split）时调用：若未启动则等待启动，否则把计时清零重计。
     * 新回合出现即解除游戏结束冻结（新游戏/续局）。 */
    public void startOrSplit() {
        gameStarted = true;
        gameTick = 0;
        lastSecond = 0;
        lastNano = System.nanoTime();
        gameOver = false;
        gameWon = false;
        // 快照当前计分板权威总时长作为本回合起点——权威回合时长用它做差分，避免本地墙钟抖动
        roundStartTotalMs = totalGameTick;
    }

    /** 设置游戏是否已开始；置 false 时顺带把全部计时与状态复位（断线 / 离场清理用）。 */
    public void setGameStarted(boolean flag) {
        this.gameStarted = flag;
        if (!flag) {
            gameTick = 0;
            lastSecond = 0;
            totalGameTick = 0;
            totalStarted = false;
            roundStartTotalMs = 0;
            gameOver = false;
            gameWon = false;
        }
    }

    /** 游戏结束（通关胜利或全队覆灭）：冻结回合/总计时（保留当前值，停止走秒）。
     * 由 GameEventBus 在游戏结束标题 / 最终 BOSS 死亡音效时调用。 */
    public void onGameEnd(boolean won) {
        gameOver = true;
        gameWon = won;
    }

    public boolean isGameOver() {
        return gameOver;
    }

    public boolean isGameWon() {
        return gameWon;
    }

    /** 本局开始/中途加入时调用：总时长从此刻起算并清零（不动回合 gameTick）。 */
    public void startGameTimer() {
        totalStarted = true;
        totalGameTick = 0;
    }

    /** 本局总时长（ms，自进入本局起跨回合累计）。 */
    public long getTotalGameTick() {
        return totalGameTick;
    }

    public boolean isTotalStarted() {
        return totalStarted;
    }

    /** 权威回合时长（ms）：计分板服务器总时长在「本回合开始」与「当前」的差值。
     * 两端都来自服务器时钟，免疫本地标题接收抖动；未就绪（总时长还没同步过 / 刚重进导致
     * roundStartTotalMs 为 0）返回 -1，由调用方回退到本地回合计时器 gameTick。 */
    public long getRoundElapsedFromTotal() {
        if (!totalStarted || roundStartTotalMs <= 0) {
            return -1;
        }
        long elapsed = totalGameTick - roundStartTotalMs;
        return elapsed > 0 ? elapsed : 0;
    }

    /**
     * 计分板权威同步总时长（每 5 tick 轮询）：侧边栏 {@code Time: mm:ss} 行是服务器整局时钟，
     * 中途加入/重进显示的是游戏真实经过时长，本地累计从加入时刻起算会偏低——直接采用计分板值。
     * 游戏结束后冻结，不再随计分板漂移（保留通关/团灭冻结语义）。
     *
     * @param totalMs 计分板解析出的总时长（ms）
     */
    public void syncTotalTimeFromScoreboard(long totalMs) {
        if (gameOver) {
            return; // 游戏结束冻结：回合/总计时停在当前值
        }
        totalGameTick = totalMs;
        totalStarted = true;
    }

    public int getGameTick() {
        return gameTick;
    }

    public boolean isGameStarted() {
        return gameStarted;
    }

    /** 本局最近一次回合标题的回合号；0 = 尚未见任何回合标题（GameEventBus 每回合标题更新）。 */
    public int getLastRoundSeen() {
        return lastRoundSeen;
    }

    public void setLastRoundSeen(int round) {
        this.lastRoundSeen = round;
    }

    /**
     * 重进恢复回合计时（由 GameCache 恢复缓存数据后调用）。总时长不再从缓存恢复——
     * 由计分板 {@code Time: mm:ss} 行权威同步（{@link #syncTotalTimeFromScoreboard}，
     * 中途加入/重进显示服务器整局时钟，文件缓存值反而会与计分板值打架造成短暂回跳）。
     * 回合时长仅当仍处于缓存记录的那个回合时才恢复（否则该回合已结束，缓存值是上一回合
     * 的剩余用时，不适用）。
     *
     * @param roundMs   缓存的回合用时（ms）
     * @param sameRound 重进时是否仍在缓存记录的那个回合
     */
    public void restoreTimers(int roundMs, boolean sameRound) {
        if (sameRound) {
            gameTick = roundMs;
        }
    }
}