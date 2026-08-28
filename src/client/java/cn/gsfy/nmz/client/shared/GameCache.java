package cn.gsfy.nmz.client.shared;

import cn.gsfy.nmz.client.feature.gamehud.TotalHUDRenderer;
import cn.gsfy.nmz.client.feature.spawntimes.CheckSpawnTimes;
import cn.gsfy.nmz.client.feature.stats.TeamStats;
import cn.gsfy.nmz.client.util.LanguageUtils;
import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本局数据缓存（兜底）——把队伍统计 HUD + 游戏/回合时长「顺带」实时写一份到
 * {@code %temp%/nmz_gamecache.json}，专为重进进行中的局 / 崩溃重启兜底。
 *
 * <p>正常游戏时 HUD 实时追随游戏（计分板权威），这个文件只在重进进行中的局时被读一次，
 * 用于临时恢复：局末可审查全队总数据、时长从断点续上。新回合 {@code resetStatuses}
 * 走游戏强制同步，缓存数据随后被覆盖，不参与正常渲染。
 *
 * <p>生命周期：局内每 1s 写一次（有名单才写）；新局（round==1）{@link #reset()} 删除；
 * 重进时 {@link #restore} 用进场瞬间的内存快照 {@link #capture()}——超时（{@link #MAX_AGE_MS}）
 * 或回合回落（已非同一局）丢弃。
 */
public final class GameCache {

    /** 缓存文件路径：系统临时目录（%temp%）。 */
    private static final Path FILE = Paths.get(System.getProperty("java.io.tmpdir"), "nmz_gamecache.json");
    /** 缓存最大有效时长（ms）：重进时超过该时长视为已跨会话，丢弃不恢复（防把上一局数据串进新局）。 */
    private static final long MAX_AGE_MS = 10 * 60 * 1000L;
    /** 写盘节流（tick）：每 20 tick（1s）写一次——崩溃恢复粒度，1s 足够且磁盘开销可忽略。 */
    private static final int WRITE_INTERVAL_TICKS = 20;

    private static final Gson GSON = new Gson();
    private static int ticksSinceWrite = 0;
    /**
     * 进入 Zombies 局内瞬间从磁盘读入的内存快照：{@link #restore} 无条件优先用它。
     * 中途重进进行中的局时，round 标题出现前 onTick 会用「重进后的空数据」持续覆写文件，
     * 若 restore 直接读文件会把总时长=0、金币=0 的污染数据当成本局数据恢复；
     * 快照在覆写发生前取走旧局最后一次完整写入，绕开这个覆写窗口。
     */
    private static Dto snapshot;
    /** 本次「进入 Zombies 世界」是否已捕获过快照（离场→进场只捕获一次，防世界内瞬时 flicker 重读已被覆写的文件）。 */
    private static boolean capturedThisEntry;
    /** 本局是否已执行过缓存恢复（回合标题 restore 与进场探针共用，防重复覆盖计分板已纠正的值）。 */
    private static boolean restoreDone;
    /** 重进探针已尝试次数：进场瞬间计分板可能未刷新到 Zombies 局，最多重试几次再放弃。 */
    private static int rejoinProbeCount;
    /** 重进探针最大重试次数（每次 +3 tick，≈1s 内）。 */
    private static final int REJOIN_PROBE_MAX = 6;

    private GameCache() {
    }

    /** 每 tick 调用（仅 Zombies 局内）：攒够节流间隔且有名单数据时，顺手把本局数据写一份到盘上。 */
    public static void tick() {
        ticksSinceWrite++;
        if (ticksSinceWrite < WRITE_INTERVAL_TICKS) {
            return;
        }
        ticksSinceWrite = 0;
        if (TeamStats.getPlayers().isEmpty()) {
            return;
        }
        write();
    }

    private static void write() {
        try {
            Dto dto = new Dto();
            dto.writtenAt = System.currentTimeMillis();
            dto.round = CheckSpawnTimes.get().getCurrentRound();
            dto.totalGameTickMs = GameTickHandler.get().getTotalGameTick();
            dto.gameTickMs = GameTickHandler.get().getGameTick();
            for (Map.Entry<String, TeamStats.PlayerStats> e : TeamStats.getPlayers().entrySet()) {
                TeamStats.PlayerStats s = e.getValue();
                PlayerDto p = new PlayerDto();
                p.status = s.status.name();
                p.health = s.health;
                p.gold = s.gold;
                p.kills = s.kills;
                p.downed = s.downed;
                p.deaths = s.deaths;
                dto.players.put(e.getKey(), p);
            }
            Files.writeString(FILE, GSON.toJson(dto), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 缓存写失败静默：兜底不是关键路径，不影响游戏
        }
    }

    /**
     * 进入 Zombies 局内瞬间调用（false→true 沿，每 tick 轮询判定）：
     * 把磁盘缓存读入内存快照，供 {@link #restore} 使用。
     * 必须在 {@link #tick()} 覆写文件之前调用——重进进行中的局时，round 标题出现前
     * onTick 会持续用「重进后的空数据」覆写文件，直接读文件会读到污染数据。
     * 离场后（{@link #onLeaveZombies()}）重新武装，下次进场再捕获一次。
     */
    public static void capture() {
        if (capturedThisEntry) {
            return;
        }
        capturedThisEntry = true;
        snapshot = null;
        try {
            if (!Files.exists(FILE)) {
                return;
            }
            Dto dto = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), Dto.class);
            if (dto != null && dto.players != null) {
                snapshot = dto;
            }
            // 重进探针（Bug B 修复）：读到有效快照即尝试恢复。若本局尚未见过回合标题
            // （重进时 world-null 已复位 lastRoundSeen=0）且计分板已显示 Zombies 回合，
            // 说明是「重进进行中的局且无新回合标题」——回合标题 restore 永不触发，由探针接管。
            // 立即尝试：进场瞬间计分板缓存可能还是离场前本局数据（含回合行），可直接命中，
            // 无需额外延迟；若 content 尚未刷新到 Zombies 局则探针内部快速重试（短间隔轮询）。
            // 正常新局走回合标题 restore，restoreDone 保证两者互斥不重复。
            if (snapshot != null && !snapshot.players.isEmpty() && !restoreDone
                    && DelayedTaskScheduler.get() != null) {
                rejoinProbeCount = 0;
                tryRestoreOnRejoin();
            }
        } catch (Exception e) {
            // 读盘失败静默：快照只是兜底，不影响游戏
        }
    }

    /** 离开 Zombies（回大厅/世界切换）时调用：重置本次进场的捕获状态，下次进场重新捕获。
     * 同时解除恢复完成标志与探针计数——下次进场重新武装（重进同一局可再次恢复）。 */
    public static void onLeaveZombies() {
        capturedThisEntry = false;
        snapshot = null;
        restoreDone = false;
        rejoinProbeCount = 0;
    }

    /**
     * 本地中途重进进行中的局时调用：从进场瞬间的内存快照恢复本局累计数据与回合计时。
     * 必须在本回合计时清零之后调用（GameEventBus 在 {@code startOrSplit()} 清零 gameTick 后调）——
     * 回合时长恢复在其后生效；总时长不恢复，由计分板 Time 行权威同步。
     * 无快照（进场时本无有效缓存文件）直接返回，不退回读盘——覆写后的文件是重进后的空数据，读了更糟。
     *
     * @param joinedRound 重进时的回合号（用于「回合回落 = 已跨局」判定）
     */
    public static void restore(int joinedRound) {
        Dto dto = snapshot;
        if (dto == null || dto.players == null) {
            return;
        }
        // 进场探针已恢复过本局数据：跳过，防把计分板已纠正的金币再覆盖回缓存旧值
        if (restoreDone) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            long ageMs = now - dto.writtenAt;
            if (ageMs > MAX_AGE_MS) {
                return; // 超时：视为已跨会话，不恢复旧局数据
            }
            if (dto.round > 0 && joinedRound < dto.round) {
                return; // 回合回落：已非同一局（新局从低回合重来）
            }
            doRestore(dto, joinedRound);
        } catch (Exception e) {
            // 恢复异常静默：兜底不是关键路径，不影响游戏
        }
    }

    /**
     * 重进探针（Bug B 修复）：本地中途重进进行中的局时，若游戏已在回合中（无新回合标题），
     * 回合标题恢复永远不会触发；本探针在 capture 读到有效快照后延迟轮询计分板，
     * 检测「本局尚未见过回合标题 && 计分板已显示 Zombies 回合」→ 直接恢复玩家数据与回合计时。
     * 正常新局/本局（round==1 标题已到 → setLastRoundSeen）探针短路，交给回合标题 restore。
     * 不用 isTotalStarted() 判定：计分板 Time 行每 tick 权威同步会把 totalStarted 置 true
     * （syncTotalTimeFromScoreboard），重进时它也是 true，无法区分「重进进行中局」。
     */
    private static void tryRestoreOnRejoin() {
        Dto dto = snapshot;
        if (dto == null || dto.players == null || dto.players.isEmpty()) {
            return;
        }
        if (restoreDone) {
            return;
        }
        // 本局已见过回合标题（lastRoundSeen 在 world-null 重进时复位 0）：回合标题 restore 已接管，
        // 或即将接管，探针不插手。
        if (GameTickHandler.get().getLastRoundSeen() != 0) {
            return;
        }
        int sbRound = roundFromScoreboard();
        if (sbRound < 1) {
            // 计分板尚未刷新到 Zombies 局（进场瞬间可能还读着离场前/大厅数据）→ 短间隔重试
            // （3 tick=150ms，计分板每 5 tick 轮询，重试窗口内必覆盖一次刷新）
            if (++rejoinProbeCount <= REJOIN_PROBE_MAX) {
                DelayedTaskScheduler.get().runTaskLater(3, GameCache::tryRestoreOnRejoin);
            }
            return;
        }
        long ageMs = System.currentTimeMillis() - dto.writtenAt;
        if (ageMs > MAX_AGE_MS) {
            return;
        }
        if (dto.round > 0 && sbRound < dto.round) {
            return; // 回合回落：缓存是上局数据，已跨局
        }
        doRestore(dto, sbRound);
    }

    /** 从当前侧边栏内容解析回合号（"第N回合" / "Round N" 行）；未识别返回 0。 */
    private static int roundFromScoreboard() {
        ScoreboardManager sm = ScoreboardManager.get();
        if (sm == null) {
            return 0;
        }
        for (int i = 1; i <= sm.getSize(); i++) {
            String line = sm.getContent(i);
            if (line == null || line.isEmpty()) {
                continue;
            }
            int r = LanguageUtils.getRoundNumber(line);
            if (r > 0) {
                return r;
            }
        }
        return 0;
    }

    /**
     * 恢复快照数据（玩家 + 回合计时），并标记本局已恢复。两个触发方共用：
     * 回合标题 {@link #restore} 与进场探针 {@link #tryRestoreOnRejoin}，restoreDone 防重复。
     *
     * @param dto          内存快照
     * @param currentRound 当前回合号（标题场景=标题回合；探针场景=计分板回合），
     *                     用于「同回合才恢复 gameTick」判定
     */
    private static void doRestore(Dto dto, int currentRound) {
        restoreDone = true;
        // 恢复回合计时：总时长不恢复（由计分板 Time 行权威同步，见 restoreTimers javadoc）；
        // 回合时长仅当仍处于缓存记录的那个回合时恢复（否则该回合已结束，不适用）
        GameTickHandler.get().restoreTimers(dto.gameTickMs, dto.round == currentRound);
        // 恢复玩家数据（名单已有 → 只回填累计型数据；未重建 → 整体恢复）
        for (Map.Entry<String, PlayerDto> e : dto.players.entrySet()) {
            PlayerDto p = e.getValue();
            TeamStats.restoreCachedPlayer(e.getKey(), p.status, p.health, p.gold, p.kills, p.downed, p.deaths);
        }
        // 波次 HUD 为本回合恢复显示：currentRound/shouldRender 只在回合标题时更新（GameEventBus），
        // 重进进行中的局无新标题 → 用计分板回合号设置，波次 HUD 立即显示本回合表，
        // 且 getCurrentWave 依据恢复的 gameTick 每帧实时算 → 箭头自动停在当前波。
        // 标题 restore 场景（GameEventBus 已设相同值）幂等无害。
        if (currentRound > 0) {
            CheckSpawnTimes.get().setCurrentRound(currentRound);
            TotalHUDRenderer.setShouldRender(true);
        }
    }

    /** 新游戏开始（round==1）时调用：删除缓存文件 + 清空快照（上一局暂存的生命周期终点）。 */
    public static void reset() {
        // Bug A 修复：不再重置 capturedThisEntry。新局删文件后，若重置为 false，下一 tick capture()
        // 读到被删的文件（无快照）并置 true 锁死「无快照」——之后同一世界内任何重进都拿不到快照，
        // restore 永远无数据可恢复（正是「重进后自己金币=0」的根因之一）。保持 true：本世界已捕获过，
        // 新局无旧数据可恢复，交由 TeamStats.tick 每 1s 重新写盘重建缓存。
        snapshot = null;
        restoreDone = false;
        rejoinProbeCount = 0;
        try {
            Files.deleteIfExists(FILE);
        } catch (IOException e) {
            // 删除失败静默：下一局重新写盘重建
        }
    }

    // ── 序列化 DTO ────────────────────────────────────────────────────────────

    static class Dto {
        long writtenAt;
        int round;
        long totalGameTickMs;
        int gameTickMs;
        Map<String, PlayerDto> players = new LinkedHashMap<>();
    }

    static class PlayerDto {
        String status;
        int health;
        int gold;
        int kills;
        int downed;
        int deaths;
    }
}
