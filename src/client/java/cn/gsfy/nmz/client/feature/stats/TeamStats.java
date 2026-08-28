package cn.gsfy.nmz.client.feature.stats;

import cn.gsfy.nmz.client.shared.GameTickHandler;
import cn.gsfy.nmz.client.shared.ScoreboardManager;
import cn.gsfy.nmz.client.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 队伍统计数据模型 + 严格状态机——每个玩家的击杀/倒地/死亡/金币和实时状态
 * 都在这里沉淀，状态跳转只能走白名单，非法迁移一律静默拒绝。
 *
 * <h3>合法迁移白名单</h3>
 * <pre>
 *   IN_COMBAT → DOWNED   ：聊天击倒消息 / 计分板 DOWNED
 *   IN_COMBAT → LEFT     ：聊天离开消息 / AFK踢出 / 计分板 LEFT
 *   IN_COMBAT → DEAD     ：聊天死亡消息 / 计分板 DEAD / 游戏结束全队覆灭
 *          （正常对局死亡必经倒地；直接 IN_COMBAT→DEAD 只出现在错过倒地阶段的场景——
 *            最后一人被击杀即团灭、崩掉重连后计分板残留「已死亡」等，此时计分板/聊天是权威信号）
 *   DOWNED    → DEAD     ：聊天死亡消息 / 计分板 DEAD / 全员倒地团灭
 *   DOWNED    → LEFT     ：聊天离开消息 / 计分板 LEFT
 *   DOWNED    → IN_COMBAT：聊天救治消息 / 计分板数值恢复（确认过倒地后）
 *   DEAD      → LEFT     ：聊天离开消息 / 计分板 LEFT
 *   DEAD      → IN_COMBAT：新回合信号 / 聊天救治消息 / 计分板数值恢复
 *   LEFT      → IN_COMBAT：实体重连 / 计分板数值（重回到游戏中且存活）
 *   LEFT      → DOWNED   ：重连后计分板「等待救援」（重回游戏但已倒地）
 *   LEFT      → DEAD     ：重连后计分板「已死亡」（重回游戏但已死亡）
 * </pre>
 *
 * <p>白名单卡这么死，是因为 Hypixel 计分板有残留、聊天有先后差，信号互相打架
 * 是常态——白名单就是给冲突定唯一解：能发生的迁移都有明确理由，
 * 不能发生的（比如 DEAD 直接回 DOWNED）全当噪声丢掉。
 * 外部事件统一经 {@link TeamStatsManager} 转发进来，这里不接任何游戏事件，
 * 只管数据与迁移逻辑——入口收敛一处，状态才不会散落各处被改乱。
 */
public final class TeamStats {

    // ── 状态枚举 ──────────────────────────────────────────────────────────────

    /**
     * 玩家战斗状态——HUD 就按它给四种颜色，每种状态对应一句人话：
     * <ul>
     *   <li>{@link #IN_COMBAT}：战斗中（绿）——存活且可行动；</li>
     *   <li>{@link #DOWNED}：已倒地（黄）——等待救援，可被救起；</li>
     *   <li>{@link #DEAD}：已死亡（红）——需新回合复活；</li>
     *   <li>{@link #LEFT}：已退出（深红）——已离开对局，重连后由计分板/实体恢复真实状态。</li>
     * </ul>
     * 状态不能随便跳，只能按 {@link #isLegalTransition} 白名单里登记的路径走。
     */
    public enum Status {
        IN_COMBAT,  // 战斗中（绿）
        DOWNED,     // 已倒地（黄）
        DEAD,       // 已死亡（红）
        LEFT        // 已退出（深红）
    }

    // ── 玩家数据 ──────────────────────────────────────────────────────────────

    /**
     * 单个玩家的实时统计与状态机运行时字段——状态机每动一次，这里就跟着变。
     * 击杀/倒地/死亡/金币这类累计值是服务器权威信号直接覆盖的，其余字段由
     * {@link #transitionTo} 或计时窗口驱动；读写一律走 {@link TeamStats} 的
     * 静态方法，实例本身只是个数据袋子，不带任何业务逻辑。
     */
    public static final class PlayerStats {
        public Status status      = Status.IN_COMBAT;
        public int    health      = -1;  // <0 = 未知
        public int    gold        = 0;
        public int    kills       = 0;
        public int    downed      = 0;
        public int    deaths      = 0;
        /**
         * 新回合复活/救治后的保护期截止时间（毫秒）。
         * 保护期里计分板残留的 DOWNED/DEAD 关键词一律忽略——不遮一下的话，
         * 上一回合的旧数据会把刚复活的玩家误打回倒地/死亡。
         */
        public long protectedUntil = 0L;
        /** 进入当前状态的时间戳（毫秒）——倒地/死亡恢复要靠它去抖，别把刚换状态当成旧状态。 */
        public long statusSince = 0L;
        /** 计分板是否已确认过这段倒地/死亡（状态词出现过）；数值恢复前必须先确认，
         *  不然倒地瞬间残留的金币数会把「还没救起来」误判成「已救援」。 */
        public boolean scoreboardDownedSeen = false;
        /** 聊天确认退出时间（毫秒），0 = 没从聊天里离开过。退出后的短窗口里，
         *  计分板/实体信号都当离开前的残留数据看。 */
        public long leftAt = 0L;
        /** 聊天重进时间（毫秒），0 = 没走聊天重进。重进后的短窗口里，计分板还写着
         *  「已退出」很可能只是重进前的残留，先忽略等它刷新。 */
        public long rejoinAt = 0L;
    }

    // ── 常量 ──────────────────────────────────────────────────────────────────

    /** 新回合/救治后的保护期（ms）——这期间计分板旧状态词不作数。 */
    public static final long  REVIVE_PROTECT_MS  = 6_000L;
    /** 倒地/死亡恢复去抖（ms）：刚进 DOWNED/DEAD 的那一瞬间，计分板可能还挂着
     *  旧金币数，短窗口内忽略「变数值=存活」，免得把刚倒地的当成刚救起。 */
    static final long  REVIVE_DEBOUNCE_MS = 1_000L;
    /** 聊天退出后的残留数据窗口（ms）：此窗口内忽略旧状态词与旧金币数，
     *  等计分板自己刷新成「已退出」再认。 */
    static final long  LEAVE_SCOREBOARD_SETTLE_MS = 1_500L;
    /** 聊天重进后的计分板「已退出」残留窗口（ms）：重进后短窗口内忽略这个词，
     *  等计分板刷新出玩家真实状态。 */
    static final long  REJOIN_LEFT_SETTLE_MS = 2_000L;
    /** 最多追踪 4 名真人玩家。 */
    public static final int MAX_PLAYERS = 4;

    // ── 内部状态 ──────────────────────────────────────────────────────────────

    /** 按加入顺序存储，最多 {@value MAX_PLAYERS} 条。 */
    private static final Map<String, PlayerStats> players = new LinkedHashMap<>();

    /**
     * 本地玩家是否处于死亡旁观（伪旁观）——死亡瞬间置 true，复活/新回合置 false。
     * 旁观期间玩家 ESP 只框「本地死亡瞬间还在战斗」的队友，不然服务器把尸体
     * 实体发回来时会把尸体也框上。
     */
    private static boolean selfSpectating = false;
    /** 本地玩家死亡瞬间仍在战斗中的队友名集合——死亡旁观期间玩家 ESP 只框这些人。 */
    private static final Set<String> aliveWhenSelfDied = new HashSet<>();

    /**
     * 倒地身体关联：名单玩家名 → 倒地身体实体 ID。
     * Hypixel 倒地瞬间会在玩家坐标生成一个「随机名」玩家实体顶替战斗实体，
     * 这时按玩家名根本认不出是谁，所以由 TeamStatsManager 在倒地瞬间用
     * 「最后已知坐标 + 最近的非名单 SLEEPING 玩家实体」把两者对上，
     * 之后改按实体 ID 追踪；一旦救起/死亡/退出（离开 DOWNED）就把关联清掉。
     */
    private static final Map<String, Integer> downedBodyId = new HashMap<>();
    /** 倒地身体反查：实体 ID → 名单玩家名（ESP 渲染时 O(1) 直查，不来回翻表）。 */
    private static final Map<Integer, String> downedBodyOwner = new HashMap<>();

    // ── 状态迁移（唯一入口） ──────────────────────────────────────────────────

    /**
     * 尝试把 {@code st} 迁到 {@code next}；目标状态跟当前一样或违反白名单时静默失败。
     * 一旦迁成功，会顺手维护倒地/死亡计数、状态时间戳和本地玩家旁观标记。
     *
     * @param st   目标玩家统计（不可为 null）
     * @param next 目标状态
     * @param src  调用来源标识（带 {@code "chat."} 前缀 = 聊天权威迁移，用于记录退出残留窗口起点）
     * @return 是否真的发生了迁移
     * @see #isLegalTransition
     */
    public static boolean transitionTo(PlayerStats st, Status next, String src) {
        if (st.status == next) {
            return false;
        }
        if (!isLegalTransition(st.status, next)) {
            return false;
        }
        // 顺带把累计计数记上：进倒地就 +1 倒地，进死亡就 +1 死亡
        if (next == Status.DOWNED) {
            st.downed++;
        } else if (next == Status.DEAD) {
            st.deaths++;
        }
        st.status = next;
        st.statusSince = System.currentTimeMillis();
        if (next == Status.DOWNED || next == Status.DEAD) {
            st.scoreboardDownedSeen = false; // 新一段倒地/死亡重新开始，等计分板状态词确认
        }
        if (next == Status.LEFT && src.startsWith("chat.")) {
            st.leftAt = System.currentTimeMillis(); // 聊天权威确认退出，残留窗口从这里起算
        }
        // 本地玩家特殊照顾：死亡 → 进旁观，同时记下「死亡瞬间还在战斗」的队友（给 ESP 过滤用）；
        // 复活/新回合 → 退出旁观。注意 st.status 上面已经赋成 next，所以捕获时自我不会被计入战斗。
        if (isLocalPlayer(st)) {
            if (next == Status.DEAD) {
                selfSpectating = true;
                aliveWhenSelfDied.clear();
                for (Map.Entry<String, PlayerStats> e : players.entrySet()) {
                    if (e.getValue().status == Status.IN_COMBAT) {
                        aliveWhenSelfDied.add(e.getKey());
                    }
                }
            } else if (next == Status.IN_COMBAT) {
                selfSpectating = false;
                aliveWhenSelfDied.clear();
            }
        }
        return true;
    }

    /**
     * 白名单校验：一个状态能往哪几个状态走，全在这里定死（完整定义见类级 Javadoc）。
     * <ul>
     *   <li>IN_COMBAT → DOWNED / LEFT / DEAD</li>
     *   <li>DOWNED → DEAD / LEFT / IN_COMBAT</li>
     *   <li>DEAD → LEFT / IN_COMBAT</li>
     *   <li>LEFT → IN_COMBAT / DOWNED / DEAD（重连后按计分板/实体恢复真实状态）</li>
     * </ul>
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return {@code to} 是否为从 {@code from} 的合法迁移
     */
    private static boolean isLegalTransition(Status from, Status to) {
        return switch (from) {
            case IN_COMBAT -> to == Status.DOWNED || to == Status.LEFT || to == Status.DEAD;
            case DOWNED    -> to == Status.DEAD   || to == Status.LEFT || to == Status.IN_COMBAT;
            case DEAD      -> to == Status.LEFT   || to == Status.IN_COMBAT;
            // 退出后重连：真实状态交给计分板/实体定（IN_COMBAT=存活，DOWNED/DEAD=回了游戏但已倒地/死亡）
            case LEFT      -> to == Status.IN_COMBAT || to == Status.DOWNED || to == Status.DEAD;
        };
    }

    // ── 生命周期 API ──────────────────────────────────────────────────────────

    /**
     * 新回合开始（由 {@link TeamStatsManager#onNewRound()} 触发），把本局没退出的玩家全复活。
     * <ul>
     *   <li>DEAD / DOWNED → IN_COMBAT（合法）：进复活保护期，忽略计分板残留的上一回合状态词；</li>
     *   <li>IN_COMBAT：本来就在打，保持不动；</li>
     *   <li>LEFT：白名单直接拦下，不用额外判断——退出去的玩家不会随新回合回来。</li>
     * </ul>
     *
     * @see TeamStatsManager#onNewRound()
     */
    public static void resetStatuses() {
        long now = System.currentTimeMillis();
        for (PlayerStats st : players.values()) {
            if (st.status == Status.LEFT) {
                continue;
            }
            boolean wasInCombat = st.status == Status.IN_COMBAT;
            if (transitionTo(st, Status.IN_COMBAT, "resetStatuses")) {
                // 从 DEAD/DOWNED 复活，进保护期
                if (!wasInCombat) {
                    st.protectedUntil = now + REVIVE_PROTECT_MS;
                }
            }
        }
    }

    // ── 缓存恢复（本地重进同一局的数据留存） ──────────────────────────────────

    /**
     * 从缓存文件恢复单个玩家数据（{@code GameCache} 读完 {@code %temp%/nmz_gamecache.json}
     * 后逐玩家调进来）。按名单是否已重建分两种路子：
     * <ul>
     *   <li>名单已有该玩家：只回填累计型数据（击杀/倒地/死亡/金币），状态/血量/时序交给现有状态机；</li>
     *   <li>名单还没重建：整体恢复（状态/血量先用缓存值临时显示，随后由计分板/实体快照校正）。</li>
     * </ul>
     * 击杀/金币马上会被计分板权威值盖掉（服务器整局累计，重进不归零）；倒地/死亡计数计分板上没有，
     * 恢复后继续在本局基础上累加——两条腿一起走，局末「总数据」才不会缺。
     *
     * @param name   玩家名
     * @param status 缓存的状态名（{@link Status} name；非法值回落 IN_COMBAT）
     * @param health 缓存的血量（重进后旧血量作临时显示，随后被实体快照刷新）
     * @param gold   缓存的金币
     * @param kills  缓存的击杀
     * @param downed 缓存的倒地次数
     * @param deaths 缓存的死亡次数
     */
    public static void restoreCachedPlayer(String name, String status, int health, int gold, int kills, int downed, int deaths) {
        PlayerStats cur = players.get(name);
        if (cur == null) {
            PlayerStats st = new PlayerStats();
            try {
                st.status = Status.valueOf(status);
            } catch (Exception ex) {
                st.status = Status.IN_COMBAT;
            }
            st.health = health;
            st.gold = gold;
            st.kills = kills;
            st.downed = downed;
            st.deaths = deaths;
            players.put(name, st);
        } else {
            cur.kills = kills;
            cur.downed = downed;
            cur.deaths = deaths;
            cur.gold = gold;
        }
    }

    /**
     * 清空所有数据（新游戏 / 断线重连时调用）。缓存文件不归这里管，是 {@code GameCache}
     * 单独打理：新局（round==1）由 {@code GameCache.reset()} 删掉，退出重进同一局则从文件恢复。
     */
    public static void clear() {
        players.clear();
        selfSpectating = false;
        aliveWhenSelfDied.clear();
        downedBodyId.clear();
        downedBodyOwner.clear();
    }

    /** 判断给定 PlayerStats 是否为本地玩家。 */
    private static boolean isLocalPlayer(PlayerStats st) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return false;
        }
        return st == players.get(client.player.getGameProfile().getName());
    }

    /**
     * 本地玩家是否处于死亡旁观（伪旁观）。
     * 状态机信号（死亡迁移已确认）优先，原生实体信号兜底——本地玩家血量 0 就按已死亡算。
     */
    public static boolean isSelfSpectating() {
        if (selfSpectating) {
            return true;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null && !client.player.isAlive();
    }

    /**
     * 本地玩家死亡瞬间，该玩家是否还在战斗中。
     * 死亡旁观期间玩家 ESP 只框这些人——不然服务器把尸体实体发回来，会把尸体也框上。
     */
    public static boolean wasAliveWhenSelfDied(String name) {
        return aliveWhenSelfDied.contains(name);
    }

    // ── 每 tick 更新（由 TeamStatsManager 调用） ──────────────────────────────

    /**
     * 每 tick 的完整更新入口，按固定顺序走：
     * <ol>
     *   <li>刷新实体血量（只供显示，不驱动状态迁移）</li>
     *   <li>解析计分板，触发状态词/数值驱动的迁移（倒地/死亡/退出/复活）</li>
     *   <li>重连检测（LEFT → IN_COMBAT：实体重新出现 = 回归且存活）</li>
     *   <li>全员倒地团灭判定</li>
     *   <li>非战斗中玩家血量归零显示</li>
     * </ol>
     *
     * @param entitySnapshot 本 tick 存活且通过名单校验的 (名字 → 实体血量) 快照
     */
    public static void tick(Map<String, Float> entitySnapshot) {
        long now = System.currentTimeMillis();

        // ── 步骤 1：血量驱动的状态迁移 ─────────────────────────────────────
        for (Map.Entry<String, Float> entry : entitySnapshot.entrySet()) {
            String name = entry.getKey();
            float  hp   = entry.getValue();

            PlayerStats st = players.get(name);
            if (st == null) {
                // 名单满了就不再收新面孔：不然旁观/杂项第 5 个实体每 tick 加入又被踢，来回抖
                if (players.size() >= MAX_PLAYERS) {
                    continue;
                }
                st = new PlayerStats();
                players.put(name, st);
            }

            // 注意：倒地/复活都不靠实体信号判——不同模式下实体在场情况/回血量不可靠（实测队友实体常短暂缺席）。
            // 倒地认聊天击倒 / 计分板「等待救援」；复活认计分板数值 / 聊天救治。
            st.health = (hp > 0f) ? (int) Math.ceil(hp) : 0;
        }

        // ── 步骤 2：计分板驱动的状态迁移 ───────────────────────────────────
        parseScoreboard(entitySnapshot.keySet());

        // ── 步骤 3：重连检测 LEFT → IN_COMBAT（实体重新出现在快照中 = 回归且存活） ──
        // 计分板若给出 DOWNED/DEAD 词，步骤 2 已先把状态恢复成对应值（LEFT→DOWNED/DEAD）；
        // 走到这里还是 LEFT 且实体在场，说明玩家确实回到游戏了，按存活处理。
        for (Map.Entry<String, PlayerStats> e : players.entrySet()) {
            // 聊天刚确认退出后的短窗口内，残留实体只是移除有延迟，不是重连，忽略
            if (e.getValue().status == Status.LEFT && entitySnapshot.containsKey(e.getKey())
                    && now - e.getValue().leftAt >= LEAVE_SCOREBOARD_SETTLE_MS) {
                e.getValue().protectedUntil = System.currentTimeMillis() + REVIVE_PROTECT_MS;
                transitionTo(e.getValue(), Status.IN_COMBAT, "entity.reconnect");
            }
        }

        // ── 步骤 4：全员倒地团灭判定 ────────────────────────────────────────
        tickWipeCheck();

        // ── 步骤 5：非战斗中血量归零显示 ────────────────────────────────────
        for (PlayerStats st : players.values()) {
            if (st.status != Status.IN_COMBAT) {
                st.health = 0;
            }
        }

        // ── 步骤 6：名单上限（防止游戏外实体混入） ──────────────────────────
        while (players.size() > MAX_PLAYERS) {
            // 去掉最后加入的那个（LinkedHashMap 遍历顺序 = 插入顺序，最后一个就是最新）
            String last = null;
            for (String k : players.keySet()) {
                last = k;
            }
            if (last != null) {
                players.remove(last);
            }
        }
    }

    // ── 团灭判定 ──────────────────────────────────────────────────────────────

    /**
     * 全员倒地团灭判定：没人还在战斗、且至少一人倒地 → 立刻全判死。
     *
     * <p>倒地只认聊天/计分板的权威信号，全员都倒地时已经没有能救的人（要有存活的
     * IN_COMBAT 玩家根本不会走到这分支），这局必败无疑，所以不用宽限等待——马上切
     * 到死亡，HUD 才不会一直挂着「已倒地」给玩家假希望。
     */
    private static void tickWipeCheck() {
        // 判定条件：没有任何 IN_COMBAT，同时至少有一个 DOWNED
        boolean anyInCombat = false;
        boolean anyDowned   = false;
        for (PlayerStats st : players.values()) {
            if (st.status == Status.IN_COMBAT) {
                anyInCombat = true;
                break;
            }
            if (st.status == Status.DOWNED) {
                anyDowned = true;
            }
        }

        if (!anyInCombat && anyDowned) {
            // 没活人能救 → 所有 DOWNED 一起转 DEAD
            for (PlayerStats st : players.values()) {
                if (st.status == Status.DOWNED) {
                    transitionTo(st, Status.DEAD, "wipe.allDowned");
                }
            }
        }
    }

    // ── 计分板解析 ────────────────────────────────────────────────────────────

    /**
     * 逐行啃侧边栏计分板——状态词和数值分别驱动状态迁移与金币更新。
     *
     * <p>计分板格式（去色后）：
     * <pre>
     *   玩家名: 状态词    → 触发迁移（含 LEFT 玩家重连恢复；新条目直接初始化为该状态）
     *   玩家名: 数值      → 更新金币；倒地/死亡玩家状态词消失则救治；LEFT 玩家数值=回归存活
     *   Kills: N         → 更新本地击杀
     *   击杀: N          → 同上
     * </pre>
     *
     * @param presentNames 本 tick 实体在场的玩家名（用于忽略残留的 LEFT 状态词）
     */
    private static void parseScoreboard(java.util.Set<String> presentNames) {
        ScoreboardManager sm = ScoreboardManager.get();
        if (sm == null) {
            return;
        }

        long now = System.currentTimeMillis();

        // 顺带读 TAB 列表的 objective 拿击杀数
        net.minecraft.client.MinecraftClient client = MinecraftClient.getInstance();
        net.minecraft.scoreboard.Scoreboard board = (client.world != null) ? client.world.getScoreboard() : null;
        ScoreboardObjective tabObj = (board != null)
                ? board.getObjectiveForSlot(ScoreboardDisplaySlot.LIST)
                : null;

        for (int i = 1; i <= sm.getSize(); i++) {
            String line = sm.getContent(i);
            if (line == null || line.isEmpty()) {
                continue;
            }

            // ── 总时长行（计分板权威游戏时钟，中途加入/重进显示真实经过时长） ──
            // 本地累计从加入时刻起算会偏低，所以直接用服务器整局时钟覆盖 totalGameTick。
            // 这里不能 continue：合并行 "Time: 0:44 Kills: 3" 还得继续往下匹配击杀行。
            syncTotalTimeFromLine(line);

            // ── 击杀行 ──────────────────────────────────────────────────────
            // 格式："Kills: 3" / "击杀: 3" / "Time: 0:44 Kills: 3"
            int lineKills = parseKillsFromLine(line);
            if (lineKills >= 0) {
                if (client.player != null) {
                    PlayerStats self = players.computeIfAbsent(
                            client.player.getGameProfile().getName(), k -> new PlayerStats());
                    self.kills = lineKills;
                }
                continue;
            }

            // ── 通用 "name: value" 行 ────────────────────────────────────────
            int colonIdx = indexOfColon(line);
            if (colonIdx < 0) {
                continue;
            }
            String label = StringUtils.trim(line.substring(0, colonIdx));
            String value = StringUtils.trim(line.substring(colonIdx + 1));

            if (label.isEmpty() || value.isEmpty()) {
                continue;
            }

            // Hypixel 侧边栏玩家行可能带 rank 前缀（如 "[VIP] Bilishenxds_: 1200"），
            // 而 self/队友都以纯玩家名进 map（self 由 Kills 行 computeIfAbsent(profileName) 建立）。
            // 先剥前缀再匹配，不然 rank 前缀会让本行被整个跳过——self 恰好是唯一带 rank 的
            // 玩家时，症状就是「重进已开局图后只有自己金币丢、队友正常」。无前缀时剥离是 no-op。
            String strippedLabel = stripRankPrefix(label);

            PlayerStats st = players.get(strippedLabel);
            if (st == null) {
                // 远处队友实体还没加载：计分板行就是权威成员来源（全缓存），
                // 只收「ASCII 玩家名 + 数值/状态词」的行，杂项行混不进来
                if (!isScoreboardPlayerRow(strippedLabel, value)) {
                    continue;
                }
                if (players.size() >= MAX_PLAYERS) {
                    continue;
                }
                st = new PlayerStats();
                players.put(strippedLabel, st);
            }

            Status scoreStatus = parseStatusWord(value);
            if (scoreStatus != null) {
                // 已死的玩家不能被「等待救援」拉回倒地：聊天死亡消息(DOWNED->DEAD)总是先到，
                // 计分板状态词要滞后 ~1s 才改成「已死亡」，这段窗口里残留的 DOWNED 词直接忽略，
                // 不然每 tick 都在白名单拒绝(DEAD->DOWNED)上刷屏
                if (scoreStatus == Status.DOWNED && st.status == Status.DEAD) {
                    continue;
                }
                if (scoreStatus == Status.LEFT) {
                    // 聊天重进后的短窗口里，「已退出」只是重进前的残留，忽略；真实状态由后续词/数值给出
                    if (now - st.rejoinAt < REJOIN_LEFT_SETTLE_MS) {
                        continue;
                    }
                    // 实体还在场、或在复活/新回合保护期内时，"LEFT"可能是残留数据，忽略
                    // （presentNames 是纯实体名，label 可能带 rank 前缀，用剥离后的名比较）
                    if (!presentNames.contains(strippedLabel) && now >= st.protectedUntil) {
                        transitionTo(st, Status.LEFT, "scoreboard.LEFT");
                    }
                } else {
                    // 聊天刚确认退出后的短窗口里，计分板残留的旧状态词是离开前的数据，忽略
                    if (now - st.leftAt < LEAVE_SCOREBOARD_SETTLE_MS) {
                        continue;
                    }
                    // 见到 DOWNED/DEAD 状态词：记下「计分板已确认这段倒地/死亡」（供数值恢复判定）
                    st.scoreboardDownedSeen = true;
                    // 保护期内忽略残留的 DOWNED/DEAD 状态词（防复活/新回合后被误判）。
                    // IN_COMBAT→DEAD 之所以进白名单：正常对局死亡必经倒地（DOWNED→DEAD），
                    // 直接 IN_COMBAT→DEAD 只出现在错过倒地阶段的场景（最后一人被击杀团灭、
                    // 崩掉重连后计分板残留「已死亡」），这时计分板就是权威信号，直接采用。
                    if (now >= st.protectedUntil) {
                        transitionTo(st, scoreStatus, "scoreboard." + scoreStatus);
                    }
                }
            } else if (isNumericValue(value)) {
                // 数值行 = 玩家还活着，这数字是金币。
                int newGold = StringUtils.getNumberInString(value);
                if (newGold != st.gold) {
                    st.gold = newGold;
                }
                if (st.status == Status.LEFT) {
                    if (now - st.leftAt >= LEAVE_SCOREBOARD_SETTLE_MS) {
                        // 退出玩家回到游戏且存活（计分板从「已退出」变回金币数）→ 恢复战斗
                        st.protectedUntil = now + REVIVE_PROTECT_MS;
                        transitionTo(st, Status.IN_COMBAT, "scoreboard.reconnect.numeric");
                    }
                } else if (st.status != Status.IN_COMBAT) {
                    // 倒地/死亡玩家恢复（救援/重生后计分板从状态词变回数值）
                    // 必须先满足 scoreboardDownedSeen：计分板确认过倒地/死亡状态词，数值才算「状态词消失」；
                    // 不然倒地瞬间残留的旧金币数会被误判成已救（实测 Hagebub 被误救后 downed 计数 +2）。
                    if (st.scoreboardDownedSeen && now - st.statusSince >= REVIVE_DEBOUNCE_MS) {
                        st.protectedUntil = now + REVIVE_PROTECT_MS;
                        transitionTo(st, Status.IN_COMBAT, "scoreboard.statusWordGone");
                    }
                }
            }
        }

        // ── TAB 列表击杀同步 ───────────────────────────────────────────────
        if (tabObj != null && board != null) {
            for (Map.Entry<String, PlayerStats> e : players.entrySet()) {
                net.minecraft.scoreboard.ReadableScoreboardScore score =
                        board.getScore(ScoreHolder.fromName(e.getKey()), tabObj);
                if (score != null) {
                    e.getValue().kills = score.getScore();
                }
            }
        }
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    /**
     * 从计分板行里认出状态词——区分语言、精确匹配，普通单词一个都不许误伤。
     *
     * <p>中文关键词用包含匹配；英文关键词卡单词边界，不然 "knocked down" 这种
     * 句子里抠出个 "down" 就会被误判成 DOWNED。
     */
    private static Status parseStatusWord(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        // ── 退出 ──
        if (value.contains("退出") || value.contains("离开")) {
            return Status.LEFT;
        }
        String lower = value.toLowerCase();
        if (lower.equals("left") || lower.equals("quit") || lower.equals("gone") || lower.equals("offline")) {
            return Status.LEFT;
        }
        // ── 倒地 ──
        // 中文计分板倒地值实测是「等待救援」，顺手兼容「倒地」；英文认 "Downed"/"Waiting for revive" 等
        if (value.contains("倒地") || value.contains("等待救援") || value.contains("待救援")) {
            return Status.DOWNED;
        }
        if (lower.equals("downed") || lower.equals("down") || lower.equals("knocked")
                || lower.contains("waiting for revive") || lower.contains("awaiting revive")
                || lower.contains("waiting for rescue") || lower.contains("waiting to be revived")) {
            return Status.DOWNED;
        }
        // ── 死亡 ──
        if (value.contains("死亡")) {
            return Status.DEAD;
        }
        if (lower.equals("dead") || lower.equals("died") || lower.equals("eliminated")) {
            return Status.DEAD;
        }
        return null;
    }

    /**
     * 从计分板行里抠击杀数，中英文各一张正则。
     * 匹配格式：{@code "Kills: N"} / {@code "击杀: N"} / {@code "... Kills: N ..."}
     *
     * @return 击杀数，没匹配上返回 -1
     */
    private static final java.util.regex.Pattern KILLS_PATTERN =
            java.util.regex.Pattern.compile("(?i)[Kk]ills\\s*[:：]\\s*(\\d+)");
    private static final java.util.regex.Pattern KILLS_PATTERN_ZH =
            java.util.regex.Pattern.compile("击杀\\s*[:：]\\s*(\\d+)");

    /** 计分板总时长行（游戏时钟，权威）：{@code "Time: 23:45"} / 合并行
     *  {@code "Time: 0:44 Kills: 3"} / 中文 {@code "时间：23:45"}。 */
    private static final java.util.regex.Pattern TIME_PATTERN =
            java.util.regex.Pattern.compile("(?i)(?:Time|时间)\\s*[:：]\\s*(\\d{1,3}):(\\d{1,2})");

    private static int parseKillsFromLine(String line) {
        java.util.regex.Matcher m = KILLS_PATTERN.matcher(line);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        m = KILLS_PATTERN_ZH.matcher(line);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    /**
     * 解析计分板总时长行并同步给 GameTickHandler（服务器整局时钟，权威）。
     * 匹配 {@code "Time: 23:45"} / {@code "时间：23:45"} / 合并行 {@code "Time: 0:44 Kills: 3"}。
     * 中途加入/重进后本地累计从加入时刻起算会偏低，直接采用计分板值；游戏结束冻结时不生效。
     * 只在 Zombies 计分板解析上下文里调用，大厅等没有 "Time: mm:ss" 行的地方不会误触发。
     */
    private static void syncTotalTimeFromLine(String line) {
        java.util.regex.Matcher m = TIME_PATTERN.matcher(line);
        if (!m.find()) {
            return;
        }
        try {
            long minutes = Long.parseLong(m.group(1));
            long seconds = Long.parseLong(m.group(2));
            if (seconds >= 60) {
                return; // 秒值超过 59 说明不是时钟行，忽略以防误同步
            }
            long totalMs = (minutes * 60 + seconds) * 1000L;
            GameTickHandler handler = GameTickHandler.get();
            if (handler != null) {
                handler.syncTotalTimeFromScoreboard(totalMs);
            }
        } catch (NumberFormatException ignored) {
            // 数字解析失败就跳过（行没匹配上，只是防极老客户端字符异常）
        }
    }

    /** 找到行中第一个英文冒号或中文冒号的位置。 */
    private static int indexOfColon(String line) {
        int en = line.indexOf(':');
        int zh = line.indexOf('：');
        if (en < 0) {
            return zh;
        }
        if (zh < 0) {
            return en;
        }
        return Math.min(en, zh);
    }

    /** 判断字符串（可含千分位逗号）是否为纯数字。 */
    private static boolean isNumericValue(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (char c : s.toCharArray()) {
            if (!Character.isDigit(c) && c != ',' && c != '，') {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断计分板 "name: value" 行能不能当名单成员（全缓存的权威来源）：
     * 名字得是 ASCII 玩家名（1–16 位字母/数字/下划线），值得是数字或状态词。
     * 启发式复用了 {@code SidebarEnhancer.isPlayerRow} 里验证过的逻辑；击杀行早已 continue，到不了这。
     */
    private static boolean isScoreboardPlayerRow(String label, String value) {
        if (label.isEmpty() || value.isEmpty()) {
            return false;
        }
        if (!label.matches("[A-Za-z0-9_]{1,16}")) {
            return false;
        }
        if (label.equals("Kills") || label.equals("Kill") || label.equals("击杀") || label.equals("杀敌")) {
            return false;
        }
        return isNumericValue(value) || parseStatusWord(value) != null;
    }

    /**
     * 剥离 Hypixel rank 前缀（如 "[VIP] "，可多个叠加）。Minecraft 玩家名不含 '[' ']'，所以安全。
     * 色码已由 {@link StringUtils#trim} 剥掉；没有前缀时原样返回（no-op）。
     */
    private static String stripRankPrefix(String label) {
        String s = label;
        while (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close < 0) {
                break;
            }
            s = s.substring(close + 1).stripLeading();
        }
        return s;
    }

    /** 校验 Minecraft 玩家名（1–16 位字母/数字/下划线）。 */
    public static boolean isValidPlayerName(String name) {
        if (name == null || name.isEmpty() || name.length() > 16) {
            return false;
        }
        for (char c : name.toCharArray()) {
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    // ── 公开查询 API ──────────────────────────────────────────────────────────

    /** 返回玩家统计快照（不可修改视图）。 */
    public static Map<String, PlayerStats> getPlayers() {
        return Collections.unmodifiableMap(players);
    }

    /** 查询某玩家的当前状态；不在名单返回 null。 */
    public static Status getStatus(String name) {
        PlayerStats st = players.get(name);
        return st != null ? st.status : null;
    }

    // ── 倒地身体关联 API ────────────────────────────────────────────────────

    /** 某名单玩家的倒地身体实体 ID；未关联返回 null。 */
    public static Integer getDownedBodyId(String name) {
        return downedBodyId.get(name);
    }

    /** 反查：某实体 ID 是否为某名单玩家的倒地身体；是则返回该玩家名，否则返回 null。 */
    public static String getDownedBodyOwner(int entityId) {
        return downedBodyOwner.get(entityId);
    }

    /** 关联某名单玩家的倒地身体（实体 ID）。 */
    public static void setDownedBody(String name, int entityId) {
        removeDownedBody(name);
        downedBodyId.put(name, entityId);
        downedBodyOwner.put(entityId, name);
    }

    /** 清除某名单玩家的倒地身体关联（救起/死亡/退出，或身体失效时）。 */
    public static void removeDownedBody(String name) {
        Integer id = downedBodyId.remove(name);
        if (id != null) {
            downedBodyOwner.remove(id.intValue());
        }
    }

    /** 本地玩家的累计击杀数；未加入名单返回 -1。 */
    public static int getLocalKills() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return -1;
        }
        PlayerStats st = players.get(client.player.getGameProfile().getName());
        return st != null ? st.kills : -1;
    }

}