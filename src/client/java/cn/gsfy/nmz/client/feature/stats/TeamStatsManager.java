package cn.gsfy.nmz.client.feature.stats;

import cn.gsfy.nmz.client.shared.GameCache;
import cn.gsfy.nmz.client.util.LanguageUtils;
import cn.gsfy.nmz.client.util.PlayerUtils;
import cn.gsfy.nmz.client.util.StringUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * 队伍统计的事件接入层——把游戏事件接进来，翻译成对 {@link TeamStats} 的调用。
 *
 * <p>这里不掺任何状态机逻辑，只做「事件 → 调用」的编排：
 * <ol>
 *   <li>每 tick 扫一遍实体快照，交给 {@link TeamStats#tick}；</li>
 *   <li>维护倒地身体关联（Hypixel 随机名倒地实体按坐标关联，供黄色倒地框使用）；</li>
 *   <li>解析聊天消息，调 {@link TeamStats#transitionTo}；</li>
 *   <li>转发新回合/新游戏/游戏结束信号。</li>
 * </ol>
 *
 * <h3>聊天权威信号说明</h3>
 * <ul>
 *   <li><b>离开</b>（→ LEFT）：含「离开了游戏」/「退出了游戏」/ "left the game" 等；</li>
 *   <li><b>AFK 踢出</b>（→ LEFT）：AFK/挂机 与 踢出/kick 同时出现；</li>
 *   <li><b>被击倒</b>（→ DOWNED）：精确匹配 Hypixel Zombies 击倒提示；</li>
 *   <li><b>被杀死</b>（→ DEAD）：精确匹配死亡提示（由 DOWNED 迁移而来，白名单合法）；</li>
 *   <li><b>被救治</b>（→ IN_COMBAT）：精确匹配救治提示。</li>
 * </ul>
 *
 * @see TeamStats
 */
public final class TeamStatsManager {

    private static TeamStatsManager instance;

    /** 返回全局单例；{@link #init} 之前为 null。 */
    public static TeamStatsManager get() {
        return instance;
    }

    /** 初始化单例并注册客户端 tick 回调（Mod 入口只调一次，之后全靠它轮转）。 */
    public void init() {
        instance = this;
        ClientTickEvents.START_CLIENT_TICK.register(client -> onTick(client));
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    /**
     * 客户端每 tick 回调：只在 Zombies 局内干活，按固定顺序走
     * 缓存捕获 → 实体快照构建 → {@link TeamStats#tick} → 倒地身体维护 → 缓存落盘。
     *
     * <p>tick 流程说明：
     * <ol>
     *   <li><b>不在 Zombies</b>：清空全部数据并通知缓存离开对局，立刻返回；</li>
     *   <li><b>缓存捕获</b>：进场瞬间把磁盘缓存读成内存快照（必须在覆写文件之前，见下）；</li>
     *   <li><b>实体快照</b>：收集场上有效玩家名与血量，排除非法名与 SLEEPING 倒地实体；</li>
     *   <li><b>状态更新</b>：交给 {@link TeamStats#tick} 驱动计分板/实体迁移；</li>
     *   <li><b>倒地身体维护</b>：状态机迁移完成后做坐标关联；</li>
     *   <li><b>缓存落盘</b>：写入本局数据（1 秒节流），崩溃/退出重进时靠它恢复。</li>
     * </ol>
     */
    private void onTick(MinecraftClient client) {
        boolean inZombies = client.world != null && client.player != null && PlayerUtils.isInZombies();
        if (!inZombies) {
            TeamStats.clear();
            GameCache.onLeaveZombies();
            return;
        }

        // 进场瞬间（非 Zombies → Zombies 的上升沿）把磁盘缓存捕获成内存快照，
        // 必须在下方 GameCache.tick() 覆写文件之前：
        // 中途重进进行中的局时，round 标题出现前本 tick 会一直用「重进后的空数据」覆写文件，
        // 要是等标题出现再读，就会把总时长=0、金币=0 的污染数据当成本局数据恢复。
        GameCache.capture();

        // 构建本 tick 实体快照：排除无效玩家名（NPC、Hypixel 假人）
        // 和倒地身体（随机名 SLEEPING 实体）
        Map<String, Float> snapshot = new HashMap<>();
        for (PlayerEntity p : client.world.getPlayers()) {
            if (p.getGameProfile() == null) {
                continue;
            }
            String name = p.getGameProfile().getName();
            if (!TeamStats.isValidPlayerName(name)) {
                continue;
            }
            // 记下名单玩家的最后坐标（倒地瞬间靠它关联随机名身体；实体消失后保持冻结，防坐标漂移）
            if (TeamStats.getPlayers().containsKey(name)) {
                lastPos.put(name, p.getPos());
            }
            // 倒地身体/旁观实体是随机名 SLEEPING 玩家实体：不进名单（免得人数 < 4 的局被当真人混入）
            if (p.getPose() == EntityPose.SLEEPING) {
                continue;
            }
            snapshot.put(name, p.getHealth());
        }

        TeamStats.tick(snapshot);

        // 维护倒地身体关联（必须在 TeamStats.tick 之后：这里的 DOWNED 已经是权威值）
        maintainDownedBodies(client);

        // 顺带写本局数据缓存（1 秒节流）：崩溃重启 / 退出重进后的恢复兜底，不参与正常渲染
        GameCache.tick();
    }

    // ── 新回合信号（由游戏事件模块调用） ─────────────────────────────────────

    /**
     * 新回合开始时调用：把所有没退出的玩家复活并送进保护期。
     * GameEventBus 检测到 round > 1 的回合标题时就会调进来。
     */
    public static void onNewRound() {
        TeamStats.resetStatuses();
    }

    /**
     * 新游戏开始（回合 1）时调用：清空全部玩家数据，好重新收集本局名单；
     * 顺手删掉上一局的缓存文件——新局就是缓存的生命周期终点，退出重进只针对同一局。
     * GameEventBus 检测到 round == 1 的回合标题时调用。
     */
    public static void onNewGame() {
        TeamStats.clear();
        GameCache.reset();
    }

    /**
     * 游戏结束时调用（GameEventBus 检测到游戏结束标题时调用）。
     * 失败（全队覆灭）：没退出的玩家全部判死，兜底处理「最后一人被击杀但没经过倒地阶段」——
     * 不然游戏都结束了还挂着「战斗中」；胜利：存活玩家本就在战斗，不动。
     */
    public static void onGameEnd(boolean won) {
        if (won) {
            return;
        }
        for (TeamStats.PlayerStats st : TeamStats.getPlayers().values()) {
            if (st.status == TeamStats.Status.LEFT) {
                continue;
            }
            TeamStats.transitionTo(st, TeamStats.Status.DEAD, "game.end.defeat");
        }
    }

    // ── 聊天消息处理 ──────────────────────────────────────────────────────────

    /**
     * 收原始聊天字符串（含 § 颜色码），逐条解析并驱动状态机。
     * 调用方（Mixin 或 Fabric 事件）必须在渲染线程上调用。
     *
     * @param raw 未去色的原始聊天文本
     * @see #onNewRound
     * @see #onNewGame
     * @see #onGameEnd
     */
    public static void onChatReceived(String raw) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        String msg = StringUtils.trim(raw);
        if (msg.isEmpty()) {
            return;
        }

        // ── 本地击杀数（优先，避免后续误匹配） ─────────────────────────────
        // 格式：「你击杀了 N 个敌人！」/ 「You killed N enemies!」
        if (isSelfKillMessage(msg)) {
            String self = client.player.getGameProfile().getName();
            int n = StringUtils.getNumberInString(msg);
            if (n > 0) {
                TeamStats.PlayerStats st = TeamStats.getPlayers().get(self);
                // getPlayers() 返回的是不可修改视图，得走 package-visible 方法访问
                // tick 里的 computeIfAbsent 已保证它存在，但直接摸内部 map 不安全；
                // 所以走对外 API 触发击杀更新（还没追踪的玩家就忽略）
                // 注意：真正的击杀数以 TAB 列表计分板为准，这里只是兜底
                if (st != null) {
                    st.kills = n;
                }
            }
            return;
        }

        // ── 离开游戏（主动 / 强制 / 断线） → LEFT ───────────────────────────
        if (isLeftMessage(msg)) {
            transitionPlayer(msg, TeamStats.Status.LEFT);
            return;
        }

        // ── AFK 踢出 → LEFT ─────────────────────────────────────────────────
        if (isAfkKickMessage(msg)) {
            transitionPlayer(msg, TeamStats.Status.LEFT);
            return;
        }

        // ── 重新加入 → 由计分板决定真实状态 ─────────────────────
        // 消息：「X重新加入游戏。」/「X has rejoined the game」
        // 聊天只确认「玩家回到游戏里」，真实状态（存活/倒地/死亡/还没回归）由计分板权威给出：
        // 金币数→存活、等待救援→倒地、已死亡→死亡、已退出→还没正式回归（LEFT→IN_COMBAT/DOWNED/DEAD 白名单全合法）。
        // 所以这里不直接置 IN_COMBAT——不然会把已死亡/已退出的重连玩家误显示成「战斗中」；
        // 同时清掉退出残留窗口（leftAt）和旧保护期，让后面的计分板信号立刻生效。
        if (isRejoinMessage(msg)) {
            long now = System.currentTimeMillis();
            for (String name : TeamStats.getPlayers().keySet()) {
                if (msg.indexOf(name) < 0) {
                    continue;
                }
                TeamStats.PlayerStats st = TeamStats.getPlayers().get(name);
                if (st == null || st.status != TeamStats.Status.LEFT) {
                    continue;
                }
                st.leftAt = 0L;
                st.protectedUntil = 0L;
                st.rejoinAt = now;
            }
            return;
        }

        // ── 被击倒 → DOWNED（IN_COMBAT → DOWNED）───────────────────────────
        if (isDownedMessage(msg)) {
            transitionPlayer(msg, TeamStats.Status.DOWNED);
            return;
        }

        // ── 被杀死 → DEAD（DOWNED → DEAD）──────────────────────────────────
        if (isKilledMessage(msg)) {
            transitionPlayer(msg, TeamStats.Status.DEAD);
            return;
        }

        // ── 被救治 → IN_COMBAT（DOWNED/DEAD → IN_COMBAT）─────────────────────
        // 救治消息格式不固定（「X 救援了 Y！」/「Y 被 X 救起了！」/「X revived Y!」/「Y was revived by X」…），
        // 受害者可能出现在任意位置，所以把消息里提到的所有非战斗/非退出玩家都当被救者。
        // 「你复活了N名玩家！」这类不点名任何玩家的，由计分板数值恢复（scoreboard.statusWordGone）兜底。
        if (LanguageUtils.isReviveMessage(msg)) {
            long now = System.currentTimeMillis();
            for (String name : TeamStats.getPlayers().keySet()) {
                if (msg.indexOf(name) < 0) {
                    continue;
                }
                TeamStats.PlayerStats st = TeamStats.getPlayers().get(name);
                if (st == null || st.status == TeamStats.Status.IN_COMBAT || st.status == TeamStats.Status.LEFT) {
                    continue;
                }
                st.protectedUntil = now + TeamStats.REVIVE_PROTECT_MS;
                TeamStats.transitionTo(st, TeamStats.Status.IN_COMBAT, "chat.revive");
            }
            return;
        }

        // ── 重新加入（重连）→ 已在上方 isRejoinMessage 分支处理 ─────────────
        // 实测 Hypixel 中文消息是「X重新加入游戏。」（不是"重新连接"）；重进后的真实状态
        // 由计分板/实体兜底给出（TeamStats.tick 步骤 3 + parseScoreboard 各状态词分支）。
    }

    // ── 消息判断 ──────────────────────────────────────────────────────────────

    /**
     * 判断是不是本地玩家的击杀消息。
     * 精确匹配「你击杀了」+「敌人」，或 "You killed"+"enemies/enemy/zombies"（大小写不敏感）。
     */
    private static boolean isSelfKillMessage(String msg) {
        String lower = msg.toLowerCase();
        return (msg.contains("你击杀了") && msg.contains("敌人"))
                || (lower.contains("you killed") && (lower.contains("enemie") || lower.contains("zombie")));
    }

    /**
     * 离开/断线消息——只精确匹配已知的 Hypixel 离开提示，普通聊天别来沾边。
     */
    private static boolean isLeftMessage(String msg) {
        String lower = msg.toLowerCase();
        return msg.contains("离开了游戏")
                || msg.contains("退出了游戏")
                || lower.contains("left the game")
                || lower.contains("has left the game")
                || lower.contains("quit the game")
                || lower.contains("has disconnected");
    }

    /**
     * AFK 踢出消息——必须同时含 AFK/挂机 与 踢出/kick 两类关键词才认，防误判。
     */
    private static boolean isAfkKickMessage(String msg) {
        String lower = msg.toLowerCase();
        boolean hasAfk  = lower.contains("afk") || lower.contains("inactiv") || lower.contains("idle")
                || msg.contains("挂机") || msg.contains("未操作");
        boolean hasKick = lower.contains("kick") || lower.contains("removed") || lower.contains("booted")
                || msg.contains("踢出") || msg.contains("移除");
        return hasAfk && hasKick;
    }

    /**
     * 重新加入消息——实测 Hypixel 中文格式是「X重新加入游戏。」（简繁通用），
     * 英文是「X has rejoined the game」等。
     */
    private static boolean isRejoinMessage(String msg) {
        String lower = msg.toLowerCase();
        return msg.contains("重新加入") || msg.contains("重新進入")
                || msg.contains("重新进入") || msg.contains("重新连接") || msg.contains("重新連接")
                || lower.contains("rejoined") || lower.contains("reconnected");
    }

    /**
     * 被击倒消息——只匹配 Hypixel Zombies 的标准击倒格式。
     * 故意不碰泛用的 "knocked"，只认完整短语，不然随便一句含 knocked 的话都会误命中。
     */
    private static boolean isDownedMessage(String msg) {
        String lower = msg.toLowerCase();
        // 中文：「X 在 Y 被 Z 击倒了！」/ 「X 倒地了！」（不含局末总结「被击倒次数 - N」）
        if (msg.contains("击倒了") || msg.contains("倒地了")) {
            return true;
        }
        // 英文：「X was knocked down」/ 「X has been downed」
        return lower.contains("was knocked down")
                || lower.contains("has been knocked")
                || lower.contains("has been downed")
                || lower.contains("was downed");
    }

    /**
     * 被杀死消息（预期调用方：DOWNED 状态玩家）。
     * 只做精确匹配，普通 PvP / 环境伤害的死亡才不会误判进来。
     */
    private static boolean isKilledMessage(String msg) {
        String lower = msg.toLowerCase();
        // 中文：「X 被僵尸杀死了！」/ 「X 阵亡了！」
        if ((msg.contains("被") && msg.contains("杀死了")) || msg.contains("阵亡")) {
            return true;
        }
        // 英文：「X was killed by」/ 「X has been killed」/ 「X was slain」
        return lower.contains("was killed by")
                || lower.contains("has been killed")
                || lower.contains("was slain by")
                || lower.contains("has been slain");
    }

    // ── 玩家名查找工具 ────────────────────────────────────────────────────────

    /**
     * 在消息里按出现位置找玩家名，返回第 {@code occurrence} 个（0 = 最先出现）。
     *
     * <p>专门用来区分 "X 救援了 Y！" 里的施救者(0) 和被救者(1)。
     */
    private static String findPlayerInMessage(String msg, int occurrence) {
        // 收集每个玩家名在消息里的首次出现位置，存成 [消息位置, 玩家名] 对
        java.util.List<java.util.Map.Entry<Integer, String>> hits = new java.util.ArrayList<>();
        for (String name : TeamStats.getPlayers().keySet()) {
            int pos = msg.indexOf(name);
            if (pos >= 0) {
                hits.add(new java.util.AbstractMap.SimpleImmutableEntry<>(pos, name));
            }
        }
        if (occurrence >= hits.size()) {
            return null;
        }
        hits.sort(java.util.Comparator.comparingInt(java.util.Map.Entry::getKey));
        return hits.get(occurrence).getValue();
    }

    /**
     * 取消息里第 0 个出现的玩家名，若在名单中，就对他发起状态迁移。
     *
     * @param msg  原始聊天文本
     * @param next 目标状态（来源标记统一为 {@code "chat." + next}）
     * @see TeamStats#transitionTo
     */
    private static void transitionPlayer(String msg, TeamStats.Status next) {
        String target = findPlayerInMessage(msg, 0);
        if (target == null) {
            return;
        }
        // getPlayers() 返回不可修改视图，但 PlayerStats 对象本身还能写
        TeamStats.PlayerStats st = TeamStats.getPlayers().get(target);
        if (st == null) {
            return;
        }
        TeamStats.transitionTo(st, next, "chat." + next);
    }

    // ── 倒地身体关联（坐标关联随机名倒地实体，供黄色倒地框） ────────────────

    /** 倒地瞬间的关联扫描半径（格）：身体就生成在倒地玩家最后坐标附近（实测 0.1~0.9 格）。 */
    private static final double BODY_ASSOC_RADIUS = 3.0;
    /** 名单玩家名 → 最后已知坐标（战斗实体在场时更新；倒地后实体消失就保持冻结）。 */
    private static final Map<String, Vec3d> lastPos = new HashMap<>();

    /**
     * 每 tick 维护倒地身体关联（必须在 {@link TeamStats#tick} 之后调用，此刻 DOWNED 已是权威值）：
     * <ul>
     *   <li>非 DOWNED：清掉关联（救起/死亡/退出后身体被删，留着陈旧 ID 会误框）</li>
     *   <li>DOWNED 且已关联：校验身体还活着（没被删/ID 没复用），失效就清掉重扫</li>
     *   <li>DOWNED 且未关联：在最后坐标附近找「非名单 + SLEEPING」的玩家实体关联</li>
     * </ul>
     * 自己（self）不参与关联：反查表里没有 self → {@code EntityEsp.isTarget} 不会框自己的身体（只框队友）。
     */
    private static void maintainDownedBodies(MinecraftClient client) {
        if (client.world == null) {
            return;
        }
        // 跨局 / 换名单时自清理
        lastPos.keySet().retainAll(TeamStats.getPlayers().keySet());

        String self = client.player != null && client.player.getGameProfile() != null
                ? client.player.getGameProfile().getName() : null;

        for (String name : TeamStats.getPlayers().keySet()) {
            if (name.equals(self)) {
                continue; // 自己倒地不框：身体是自己的，不关联
            }
            if (TeamStats.getStatus(name) != TeamStats.Status.DOWNED) {
                TeamStats.removeDownedBody(name);
                continue;
            }
            Integer bodyId = TeamStats.getDownedBodyId(name);
            if (bodyId != null) {
                if (!isValidDownedBody(client, bodyId)) {
                    TeamStats.removeDownedBody(name); // 身体被删/ID 复用 → 清掉并重扫
                }
                continue;
            }
            Vec3d pos = lastPos.get(name);
            if (pos == null) {
                continue;
            }
            PlayerEntity body = findNearestDownedBody(client, pos);
            if (body != null) {
                TeamStats.setDownedBody(name, body.getId());
            }
        }
    }

    /** 在最后坐标附近找最近的「非名单 + SLEEPING」玩家实体——那就是倒地身体。 */
    private static PlayerEntity findNearestDownedBody(MinecraftClient client, Vec3d from) {
        PlayerEntity best = null;
        double bestDistSq = BODY_ASSOC_RADIUS * BODY_ASSOC_RADIUS;
        for (PlayerEntity p : client.world.getPlayers()) {
            if (p == client.player || p.getGameProfile() == null) {
                continue;
            }
            String pname = p.getGameProfile().getName();
            if (TeamStats.getPlayers().containsKey(pname)) {
                continue; // 名单里的 = 真人战斗实体，不是身体
            }
            if (p.getPose() != EntityPose.SLEEPING) {
                continue; // 只有睡觉姿态才算倒地身体（Phase A 实测 30/30 一致）
            }
            if (TeamStats.getDownedBodyOwner(p.getId()) != null) {
                continue; // 已经被别的倒地玩家关联走了
            }
            double distSq = p.getPos().squaredDistanceTo(from);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = p;
            }
        }
        return best;
    }

    /** 校验关联的身体实体还有效：在场上、还是「名单外 + SLEEPING」（防身体删除后 ID 被复用误框）。 */
    private static boolean isValidDownedBody(MinecraftClient client, int bodyId) {
        Entity e = client.world.getEntityById(bodyId);
        if (!(e instanceof PlayerEntity p) || p.getGameProfile() == null) {
            return false;
        }
        if (p.getPose() != EntityPose.SLEEPING) {
            return false;
        }
        return !TeamStats.getPlayers().containsKey(p.getGameProfile().getName());
    }
}
