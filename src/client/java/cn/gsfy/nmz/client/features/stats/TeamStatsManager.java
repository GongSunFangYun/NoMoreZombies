package cn.gsfy.nmz.client.features.stats;

import cn.gsfy.nmz.client.shared.game.GameCache;
import cn.gsfy.nmz.client.utils.LanguageUtils;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import cn.gsfy.nmz.client.utils.StringUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;

/**
 * The team stats' event adapter - takes game events in and translates them
 * into calls on {@link TeamStats}.
 *
 * <p>No state machine logic here, only event-to-call orchestration:
 * <ol>
 *  <li>scan an entity snapshot each tick and hand it to {@link TeamStats#tick};</li>
 *  <li>maintain downed-body associations (Hypixel's random-name downed
 *  entities, associated by coordinates, for the yellow downed outline);</li>
 *  <li>parse chat messages and call {@link TeamStats#transitionTo};</li>
 *  <li>forward new-round / new-game / game-end signals</li>
 * </ol>
 *
 * <h2>Authoritative chat signals</h2>
 * <ul>
 *  <li><b>Left</b> (-> LEFT): "left the game" / "quit the game" etc.;</li>
 *  <li><b>AFK kick</b> (-> LEFT): AFK/idle plus kick wording together;</li>
 *  <li><b>Knocked down</b> (-> DOWNED): exact match of the Hypixel Zombies knockdown notice;</li>
 *  <li><b>Killed</b> (-> DEAD): exact match of the death notice (arrives from DOWNED, legal on the whitelist);</li>
 *  <li><b>Revived</b> (-> IN_COMBAT): exact match of the revive notice</li>
 * </ul>
 *
 * @see TeamStats
 */
public final class TeamStatsManager {

    private static TeamStatsManager instance;

    /** Returns the global singleton; {@code null} before {@link #init()}. */
    public static TeamStatsManager get() {
        return instance;
    }

    /** Initializes the singleton and registers the client tick callback (the mod entry calls it once; everything else rides on it). */
    public void init() {
        instance = this;
        ClientTickEvents.START_CLIENT_TICK.register(this::onTick);
    }

    // ──Tick──────────────────────────────────────────────────────────────────

    /**
     * The client's per-tick callback: works only inside a Zombies game, in a
     * fixed order: cache capture -> entity snapshot build ->
     * {@link TeamStats#tick} -> downed-body maintenance -> cache persist.
     *
     * <p>Tick flow:
     * <ol>
     *  <li><b>Not in Zombies</b>: clear all data, tell the cache the game was
     *  left, return immediately;</li>
     *  <li><b>Cache capture</b>: at the entry instant read the disk cache into
     *  an in-memory snapshot (before persisting, see GameCache);</li>
     *  <li><b>Entity snapshot</b>: collect valid player names and health on
     *  the field, excluding illegal names and SLEEPING downed bodies;</li>
     *  <li><b>Status update</b>: hand to {@link TeamStats#tick}, which drives
     *  scoreboard/entity transitions;</li>
     *  <li><b>Downed-body maintenance</b>: coordinate association after the
     *  state machine's transitions;</li>
     *  <li><b>Cache persist</b>: write this game's data (1s throttle) - the
     *  recovery fallback after a crash or exit-rejoin</li>
     * </ol>
     */
    private void onTick(MinecraftClient client) {
        boolean inZombies = client.world != null && client.player != null && PlayerUtils.isInZombies();
        if (!inZombies) {
            TeamStats.clear();
            GameCache.onLeaveZombies();
            resetSettlement();
            return;
        }

        // At the entry instant (the rising edge of not-Zombies -> Zombies) capture the disk
        // cache into an in-memory snapshot.
        // Must run before GameCache.tick() below - why is in the GameCache class comment
        GameCache.capture();

        // Build this tick's entity snapshot: exclude invalid player names (NPCs, Hypixel
        // dummies) and downed bodies (random-name SLEEPING entities)
        Map<String, Float> snapshot = new HashMap<>();
        for (PlayerEntity p : client.world.getPlayers()) {
            if (p.getGameProfile() == null) {
                continue;
            }
            String name = p.getGameProfile().getName();
            if (!TeamStats.isValidPlayerName(name)) {
                continue;
            }
            // Record roster players' last coordinates (used to associate the random-name body
            // at the downed instant; frozen once the entity disappears, to prevent drift)
            if (TeamStats.getPlayers().containsKey(name)) {
                lastPos.put(name, p.getPos());
            }
            // Downed bodies / spectator entities are random-name SLEEPING player entities: not
            // admitted to the roster (so a <4-player game does not get padded with fakes)
            if (p.getPose() == EntityPose.SLEEPING) {
                continue;
            }
            snapshot.put(name, p.getHealth());
        }

        TeamStats.tick(snapshot);

        // Maintain downed-body associations (must run after TeamStats.tick: DOWNED is already
        // authoritative here)
        maintainDownedBodies(client);

        // Also persist this game's data cache (1s throttle): the recovery fallback after a
        // crash restart / exit-rejoin; not part of normal rendering
        GameCache.tick();
    }

    // ──New round signals (called by the game event module)──────────────────

    /**
     * Called when a new round begins: revives every player who has not left
     * and puts them into the protection window.
     * GameEventBus calls in when it detects a round title with {@code round>1}.
     */
    public static void onNewRound() {
        TeamStats.resetStatuses();
    }

    /**
     * Called when a new game starts (round 1): clears all player data so this
     * game's roster can be collected fresh;
     * also deletes the previous game's cache file - a new game is the cache's
     * end of life; exit-rejoin applies only to the same game.
     * Called by GameEventBus when it detects a round==1 round title.
     */
    public static void onNewGame() {
        TeamStats.clear();
        GameCache.reset();
        resetSettlement();
    }

    /**
     * Clears the settlement flags on a new game / leaving, so the next game's
     * settlement signal can land in {@link #onGameEnd} again.
     * The flags carry one-way semantics (after a win, defeat conclusions are
     * refused - defeat evidence often comes from an unreliable title default),
     * and only a game change may reset them wholesale.
     */
    private static void resetSettlement() {
        gameEndSettled = false;
        gameEndWon = false;
    }

    /**
     * Called when the game ends (GameEventBus detects a game-end title, the
     * ender-dragon death sound, or a chat settlement banner).
     *
     * <p>Defeat (team wipe): everyone who has not left is marked dead,
     * covering "the last player was killed without passing through the downed
     * stage" - otherwise the game is over yet the HUD still says "in combat".
     *
     * <p>Victory: everyone returns to "in combat + full health". At the
     * victory instant the server revives the whole team (downed and dead
     * teammates included), but this mod's victory signal has never been
     * obtainable (see {@link #classifySettlement}), so {@code won} would fall
     * to the default false and the settlement would mark everyone dead - it
     * would look like "won yet all dead". Here the post-settlement real state
     * is applied directly, plus the protection window to block settlement-time
     * scoreboard residue.
     *
     * <p>Idempotence and one-way override: a repeated conclusion does not
     * rerun (title + sound + chat banner may all arrive); "mis-judged as
     * defeat first, then judged a win" may rerun to correct, but <b>not the
     * reverse</b> - once settled as a win, later defeat conclusions are all
     * refused. Defeat conclusions often come from the unreliable title
     * evidence ({@code isWinTitle} defaults to false when it cannot recognize
     * victory wording); letting it overwrite the chat banner's reliable
     * {@code SURVIVED!} would mark revived teammates dead again - exactly the
     * bug this guards against.
     */
    public static void onGameEnd(boolean won) {
        if (gameEndSettled && (gameEndWon == won || gameEndWon)) {
            return;
        }
        gameEndSettled = true;
        gameEndWon = won;
        for (TeamStats.PlayerStats st : TeamStats.getPlayers().values()) {
            if (st.status == TeamStats.Status.LEFT) {
                continue; // players who left mid-game neither revive nor die at settlement
            }
            if (won) {
                reviveTo(st, "game.end.victory");
            } else {
                TeamStats.transitionTo(st, TeamStats.Status.DEAD, "game.end.defeat");
            }
        }
    }

    /** Whether this game has been settled (settlement signals may repeat; the same conclusion does not rerun). */
    private static boolean gameEndSettled;
    /** The settled conclusion: true = win. Used for idempotence and for the rerun on a win/loss flip. */
    private static boolean gameEndWon;

    /**
     * Pulls a player back to "in combat": into the protection window, with
     * health staged at full.
     *
     * <p>Health must be given here: step 5 of {@link TeamStats#tick} presses
     * every non-IN_COMBAT player's health to 0, and teammate entities are
     * often absent (far away / not loaded, invisible to
     * {@code world.getPlayers()}), so with only a status change the health
     * would stick at 0 - "rescued + 0 HP" is worse than unknown.
     * Full health here is only a fallback display; the next tick's entity
     * snapshot overwrites it with the real value when the entity is present.
     * When the whitelist rejects the transition (e.g. LEFT), neither the
     * protection window nor the health is touched.
     */
    private static void reviveTo(TeamStats.PlayerStats st, String src) {
        if (!TeamStats.transitionTo(st, TeamStats.Status.IN_COMBAT, src)
                && st.status != TeamStats.Status.IN_COMBAT) {
            return;
        }
        st.protectedUntil = System.currentTimeMillis() + TeamStats.REVIVE_PROTECT_MS;
        st.health = TeamStats.MAX_HEALTH;
    }

    // ──Chat message handling────────────────────────────────────────────────

    /**
     * Receives the raw chat string (with § color codes), parses each message
     * and drives the state machine.
     * Callers (a mixin or Fabric event) must invoke on the render thread.
     *
     * @param raw raw chat text, colors not yet stripped
     * @see #onNewRound()
     * @see #onNewGame()
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

        // ──Settlement banner (highest priority) -> team settlement──────────
        // First, deliberately: the settlement row shares no evidence with any other branch, and
        // judging it first keeps nameless texts like "SURVIVED!" from spinning through the
        // branches below. It must also come before knocked-down/killed - at the settlement
        // instant the server marks downed teammates dead, and if those two messages ran first,
        // the victory revival would be overwritten by the subsequent deaths
        if (trySettlement(msg)) {
            return;
        }

        // ──Local kill count (early, to avoid later mismatches)──────────────
        // Format: "你击杀了N个敌人！" / "You killed N enemies!"
        if (isSelfKillMessage(msg)) {
            String self = client.player.getGameProfile().getName();
            int n = StringUtils.getNumberInString(msg);
            if (n > 0) {
                TeamStats.PlayerStats st = TeamStats.getPlayers().get(self);
                // getPlayers()'s map structure is read-only, but PlayerStats is a mutable element
                // shared with the state machine;
                // tick's computeIfAbsent guarantees it exists - the chat value here is only a
                // fallback, still overwritten by the TAB authoritative value afterwards
                if (st != null) {
                    st.kills = n;
                }
            }
            return;
        }

        // ──Left the game (voluntary / forced / disconnect) -> LEFT──────────
        if (isLeftMessage(msg)) {
            transitionPlayer(msg, TeamStats.Status.LEFT);
            return;
        }

        // ──AFK kick -> LEFT─────────────────────────────────────────────────
        if (isAfkKickMessage(msg)) {
            transitionPlayer(msg, TeamStats.Status.LEFT);
            return;
        }

        // ──Rejoin -> the scoreboard decides the real state──────────────────
        // Message: "X重新加入游戏." / "X has rejoined the game"
        // Chat only confirms "the player is back in the game"; the real state (alive / downed /
        // dead / not yet back) is given authoritatively by the scoreboard:
        // a gold number -> alive, waiting for revive -> downed, dead -> dead, left -> not
        // officially back yet (LEFT -> IN_COMBAT/DOWNED/DEAD are all whitelisted).
        // So IN_COMBAT is not set directly here - it would display reconnected players who were
        // dead/left as "in combat";
        // the leave-residue window (leftAt) and the old protection window are cleared so later
        // scoreboard signals apply immediately
        if (isRejoinMessage(msg)) {
            long now = System.currentTimeMillis();
            for (String name : TeamStats.getPlayers().keySet()) {
                if (!msg.contains(name)) {
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

        // ──Knocked down -> DOWNED (IN_COMBAT -> DOWNED)─────────────────────
        if (isDownedMessage(msg)) {
            transitionPlayer(msg, TeamStats.Status.DOWNED);
            return;
        }

        // ──Killed -> DEAD (DOWNED -> DEAD)──────────────────────────────────
        if (isKilledMessage(msg)) {
            transitionPlayer(msg, TeamStats.Status.DEAD);
            return;
        }

        // ──Team Machine full revive -> all downed players revived together──
        // This must precede the generic revive branch: the activation broadcast and
        // "you revived N players!" name nobody, so the name-lookup generic branch is useless for
        // them and only the scoreboard fallback would remain - but the Team Machine revive is
        // instantaneous, and when the scoreboard has not yet polled "Waiting for revive",
        // scoreboardDownedSeen stays false, the fallback never fires, and the HUD sticks at
        // "downed + 0 HP". Here the broadcast itself revives every downed player
        if (isTeamMachineReviveMessage(msg)) {
            for (TeamStats.PlayerStats st : TeamStats.getPlayers().values()) {
                if (st.status == TeamStats.Status.DOWNED) {
                    reviveTo(st, "chat.teamMachine");
                }
            }
            return;
        }

        // ──Revived -> IN_COMBAT (DOWNED/DEAD -> IN_COMBAT)──────────────────
        // Revive messages have no fixed format ("X救援了Y！" / "Y被X救起了！" / "X revived Y!" /
        // "Y was revived by X"...), the victim may appear anywhere, so every non-combat, non-left
        // player mentioned in the message is treated as rescued.
        // Nameless ones like "you revived N players!" are caught by the Team Machine branch above
        if (LanguageUtils.isReviveMessage(msg)) {
            for (String name : TeamStats.getPlayers().keySet()) {
                if (!msg.contains(name)) {
                    continue;
                }
                TeamStats.PlayerStats st = TeamStats.getPlayers().get(name);
                if (st == null || st.status == TeamStats.Status.IN_COMBAT || st.status == TeamStats.Status.LEFT) {
                    continue;
                }
                reviveTo(st, "chat.revive");
            }
        }

        // ──Rejoin (reconnect) -> already handled by the isRejoinMessage branch above──
        // The Hypixel Chinese message is "X重新加入游戏." in testing (not "重新连接"); the real
        // state after rejoining comes from the scoreboard/entity fallbacks (TeamStats.tick step 3
        // plus parseScoreboard's status-word branches)
    }

    // ──Message matchers──────────────────────────────────────────────────────

    /**
     * Whether this is the local player's kill message.
     * Exact match of "你击杀了" + "敌人", or "You killed" +
     * "enemies/enemy/zombies" (case-insensitive).
     */
    private static boolean isSelfKillMessage(String msg) {
        String lower = msg.toLowerCase();
        return (msg.contains("你击杀了") && msg.contains("敌人"))
                || (lower.contains("you killed") && (lower.contains("enemie") || lower.contains("zombie")));
    }

    /**
     * Leave/disconnect message - exact match only of known Hypixel leave
     * notices; ordinary chat must not match.
     * A false positive would mark the player named in the message as left.
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
     * AFK kick message - requires both an AFK/idle keyword and a kick
     * keyword together, to prevent misfires.
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
     * Rejoin message - the Hypixel Chinese format is "X重新加入游戏." in
     * testing (traditional Chinese variants included); English is
     * "X has rejoined the game" etc.
     */
    private static boolean isRejoinMessage(String msg) {
        String lower = msg.toLowerCase();
        return msg.contains("重新加入") || msg.contains("重新進入")
                || msg.contains("重新进入") || msg.contains("重新连接") || msg.contains("重新連接")
                || lower.contains("rejoined") || lower.contains("reconnected");
    }

    /**
     * Knocked-down message - matches only Hypixel Zombies' standard knockdown
     * formats. The generic "knocked" is deliberately avoided; only full
     * phrases count, otherwise any sentence containing "knocked" would fire.
     */
    private static boolean isDownedMessage(String msg) {
        String lower = msg.toLowerCase();
        // Chinese: "X在Y被Z击倒了！" / "X倒地了！" (not the end-of-game summary "被击倒次数-N")
        if (msg.contains("击倒了") || msg.contains("倒地了")) {
            return true;
        }
        // English: "X was knocked down" / "X has been downed"
        return lower.contains("was knocked down")
                || lower.contains("has been knocked")
                || lower.contains("has been downed")
                || lower.contains("was downed");
    }

    /**
     * Killed message (the expected caller: a DOWNED player).
     * Exact matching only, so ordinary PvP/environmental deaths never fire it.
     */
    private static boolean isKilledMessage(String msg) {
        String lower = msg.toLowerCase();
        // Chinese: "X被僵尸杀死了！" / "X阵亡了！"
        if ((msg.contains("被") && msg.contains("杀死了")) || msg.contains("阵亡")) {
            return true;
        }
        // English: "X was killed by" / "X has been killed" / "X was slain"
        return lower.contains("was killed by")
                || lower.contains("has been killed")
                || lower.contains("was slain by")
                || lower.contains("has been slain");
    }

    /**
     * Team Machine full-revive message - the activation broadcast and "you
     * revived N players!" <b>name nobody</b>, so the name-lookup generic
     * revive branch is useless for them; they must be recognized separately,
     * then filtered by status (revive all DOWNED).
     *
     * <p>Two English broadcasts in testing: {@code X activated Full Revive
     * from the Team Machine!} and {@code You revived N players!}; the Chinese
     * client's counterpart is "你复活了N名玩家！".
     * The evidence takes only full-revive's collective wording -
     * "全体复活 / N名玩家 / revived N players" - one-player revives ("X救援了Y！" /
     * {@code X revived Y!}) always carry player names, lack this wording, and
     * are never swallowed.
     * The English one pins a number ({@code revived\s+\d+\s+players?}) -
     * otherwise a teammate named {@code Player123} revived solo would match
     * {@code revived}+{@code player} and read as a full revive.
     * "团队机器/Team Machine" alone is equally unsafe - the same machine also
     * broadcasts Ammo Supply and Dragon's Wrath, which are not revives, so a
     * revive-wording layer is stacked on top.
     * The end-of-game summary's "复活玩家数-N" contains none of these words and stays safe.
     */
    private static boolean isTeamMachineReviveMessage(String msg) {
        String lower = msg.toLowerCase();
        // English: "...activated Full Revive from the Team Machine!" / "You revived N players!"
        if (lower.contains("full revive") || BULK_REVIVE_EN.matcher(lower).find()) {
            return true;
        }
        // Chinese: "...从团队机器激活了全体复活！" / "你复活了N名玩家！"
        if (msg.contains("全体复活") || msg.contains("全體復活")) {
            return true;
        }
        if ((msg.contains("团队机器") || msg.contains("團隊機器"))
                && (msg.contains("复活") || msg.contains("復活"))) {
            return true;
        }
        return (msg.contains("复活了") || msg.contains("復活了")) && msg.contains("名玩家");
    }

    /** The English bulk-revive broadcast: {@code You revived 1 players!}. */
    private static final java.util.regex.Pattern BULK_REVIVE_EN =
            java.util.regex.Pattern.compile("revived\\s+\\d+\\s+players?");

    /**
     * Settlement banner classification - the game's outcome can only be told
     * from the banner itself; every other settlement text stays silent.
     *
     * <p>The evidence is <b>normalized full equality</b>, not containment:
     * keep letters only (stripping whitespace, punctuation, digits and color
     * codes), then compare against the word lists. The strictness comes from
     * two counterexamples in real logs - another minigame (Murder Mystery)
     * sends {@code +100tokens! Survived30seconds} and
     * {@code YOU DIED! A Murderer stabbed you!}; with
     * {@code contains("survived")} / {@code contains("you died")} this game
     * would be misjudged as a Zombies settlement.
     * Normalized they are {@code tokenssurvivedseconds} /
     * {@code youdiedamurdererstabbedyou}, which cannot equal the settlement
     * words {@code survived} / {@code youdied} and are excluded naturally.
     *
     * <p>The end-of-game summary ("Zombies-12:34 (Round 15)" style) is
     * deliberately <b>not</b> counted as a win: it appears in both outcomes,
     * and accepting it would misreport a wipe as a clear - which is exactly
     * the misreport that got downed teammates marked dead after a victory.
     *
     * @param msg chat text with colors stripped
     * @return {@code TRUE} = victory settlement, {@code FALSE} = defeat settlement, {@code null} = not a settlement
     */
    private static Boolean classifySettlement(String msg) {
        if (msg.length() > SETTLEMENT_MAX_LEN) {
            return null;
        }
        String letters = msg.replaceAll("[^\\p{L}]", "").toLowerCase();
        if (letters.isEmpty()) {
            return null;
        }
        if (VICTORY_BANNERS.contains(letters)) {
            return Boolean.TRUE;
        }
        if (DEFEAT_BANNERS.contains(letters)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** Longest banner length in characters; beyond it the row is basically not a settlement (tested banners are far shorter). */
    private static final int SETTLEMENT_MAX_LEN = 48;

    /**
     * Victory banners (normalized full equality): {@code SURVIVED!} /
     * {@code You Win!} / {@code Victory} / the Chinese spellings.
     */
    private static final java.util.Set<String> VICTORY_BANNERS = java.util.Set.of(
            "survived", "youwin", "youwon", "victory",
            "存活", "幸存", "倖存", "你赢", "胜利", "勝利");

    /** Defeat banners (normalized full equality): {@code GAME OVER} / {@code YOU DIED} / the Chinese spellings. */
    private static final java.util.Set<String> DEFEAT_BANNERS = java.util.Set.of(
            "gameover", "youdied",
            "游戏结束", "遊戲結束", "你死了", "全队覆灭", "全隊覆滅");

    /**
     * Settles the team stats by the settlement banner (one fallback path per
     * outcome).
     *
     * <p>Why the banner is the fallback: the mod's existing
     * {@code GameEventBus.onSetTitle} and the ender-dragon death sound are
     * both <b>unreliable</b> in practice - a 30-round clear went through with
     * neither a recognizable settlement title nor the dragon sound, so
     * {@code won} fell to the default false and the victory marked the whole
     * team dead; and treating the "end-of-game summary" as a win would in
     * turn misreport a wipe as a clear.
     * So the settlement banner is read directly: {@code SURVIVED!} -> win,
     * {@code GAME OVER!} -> loss, and all other settlement text stays silent.
     *
     * @param msg chat text with colors stripped
     * @return whether a settlement was recognized (on success {@link #onGameEnd} has already run)
     */
    private static boolean trySettlement(String msg) {
        Boolean won = classifySettlement(msg);
        if (won == null) {
            return false;
        }
        onGameEnd(won);
        return true;
    }

    // ──Player-name lookup utilities──────────────────────────────────────────

    /**
     * Finds the roster player name appearing earliest in the message - in
     * "X救援了Y！" the rescuer X is wanted, i.e. the name earliest in the
     * text; {@code null} when no roster player is mentioned.
     */
    private static String findPlayerInMessage(String msg) {
        // Collect each player name's first occurrence position and take the smallest
        String best = null;
        int bestPos = Integer.MAX_VALUE;
        for (String name : TeamStats.getPlayers().keySet()) {
            int pos = msg.indexOf(name);
            if (pos >= 0 && pos < bestPos) {
                bestPos = pos;
                best = name;
            }
        }
        return best;
    }

    /**
     * Takes the earliest-mentioned player name and, if they are on the
     * roster, fires the status transition for them.
     *
     * @param msg raw chat text
     * @param next target status (the source marker is uniformly {@code "chat." + next})
     * @see TeamStats#transitionTo
     */
    private static void transitionPlayer(String msg, TeamStats.Status next) {
        String target = findPlayerInMessage(msg);
        if (target == null) {
            return;
        }
        // getPlayers() is a live read-only view of the underlying map: entries cannot be added
        // or removed, but the PlayerStats element can still go through the state machine
        TeamStats.PlayerStats st = TeamStats.getPlayers().get(target);
        if (st == null) {
            return;
        }
        TeamStats.transitionTo(st, next, "chat." + next);
    }

    // ──Downed-body association (associating random-name downed entities by coordinates, for the yellow outline)──

    /** Association scan radius at the downed instant (blocks): the body spawns near the downed player's last coordinates (0.1-0.9 blocks in testing). */
    private static final double BODY_ASSOC_RADIUS = 3.0;
    /** Roster player name -> last known coordinates (updated while the combat entity is present; frozen once it disappears at the downed instant). */
    private static final Map<String, Vec3d> lastPos = new HashMap<>();

    /**
     * Maintains downed-body associations each tick (must run after
     * {@link TeamStats#tick}, when DOWNED is already authoritative):
     * <ul>
     *  <li>not DOWNED: clear the association (the body is deleted on
     *  revive/death/leave; a stale ID would frame nothing meaningful)</li>
     *  <li>DOWNED and associated: verify the body is still valid (not deleted
     *  / ID not reused); on failure clear and rescan</li>
     *  <li>DOWNED and unassociated: find a "non-roster + SLEEPING" player
     *  entity near the last coordinates to associate</li>
     * </ul>
     * The self player takes no part: the reverse table holds no self entry,
     * so {@code EntityEsp.isTarget} never frames one's own body (teammates
     * only).
     */
    private static void maintainDownedBodies(MinecraftClient client) {
        if (client.world == null) {
            return;
        }
        // Self-clean across games / roster changes
        lastPos.keySet().retainAll(TeamStats.getPlayers().keySet());

        String self = client.player != null && client.player.getGameProfile() != null
                ? client.player.getGameProfile().getName() : null;

        for (String name : TeamStats.getPlayers().keySet()) {
            if (name.equals(self)) {
                continue; // one's own downed body is not framed: it is one's own, not associated
            }
            if (TeamStats.getStatus(name) != TeamStats.Status.DOWNED) {
                TeamStats.removeDownedBody(name);
                continue;
            }
            Integer bodyId = TeamStats.getDownedBodyId(name);
            if (bodyId != null) {
                if (!isValidDownedBody(client, bodyId)) {
                    TeamStats.removeDownedBody(name); // body deleted / ID reused -> clear and rescan
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

    /** Finds the nearest "non-roster + SLEEPING" player entity near the last coordinates - that is the downed body. */
    private static PlayerEntity findNearestDownedBody(MinecraftClient client, Vec3d from) {
        World world = client.world;
        if (world == null) {
            return null;
        }
        PlayerEntity best = null;
        double bestDistSq = BODY_ASSOC_RADIUS * BODY_ASSOC_RADIUS;
        for (PlayerEntity p : world.getPlayers()) {
            if (p == client.player || p.getGameProfile() == null) {
                continue;
            }
            String pname = p.getGameProfile().getName();
            if (TeamStats.getPlayers().containsKey(pname)) {
                continue; // on the roster = a real combat entity, not a body
            }
            if (p.getPose() != EntityPose.SLEEPING) {
                continue; // only the sleeping pose counts as a downed body (30/30 consistent in Phase A testing)
            }
            if (TeamStats.getDownedBodyOwner(p.getId()) != null) {
                continue; // already associated to another downed player
            }
            double distSq = p.getPos().squaredDistanceTo(from);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = p;
            }
        }
        return best;
    }

    /** Verifies the associated body entity is still valid: on the field and still "non-roster + SLEEPING" (guards against a reused ID after body deletion). */
    private static boolean isValidDownedBody(MinecraftClient client, int bodyId) {
        // maintainDownedBodies already checked world != null at its top; a defensive fetch here
        Entity e = client.world != null ? client.world.getEntityById(bodyId) : null;
        if (!(e instanceof PlayerEntity p) || p.getGameProfile() == null) {
            return false;
        }
        if (p.getPose() != EntityPose.SLEEPING) {
            return false;
        }
        return !TeamStats.getPlayers().containsKey(p.getGameProfile().getName());
    }
}
