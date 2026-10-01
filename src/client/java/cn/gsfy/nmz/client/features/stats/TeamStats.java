package cn.gsfy.nmz.client.features.stats;

import cn.gsfy.nmz.client.shared.game.GameTickHandler;
import cn.gsfy.nmz.client.shared.game.ScoreboardManager;
import cn.gsfy.nmz.client.utils.StringUtils;
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
 * Team stats data model plus a strict state machine - each player's kills,
 * downs, deaths, gold and live status settle here; transitions may only walk
 * the whitelist, and any illegal move is silently rejected.
 *
 * <h2>Legal transition whitelist</h2>
 * <pre>
 *   IN_COMBAT -> DOWNED   : chat knockdown message / scoreboard DOWNED
 *   IN_COMBAT -> LEFT     : chat leave message / AFK kick / scoreboard LEFT
 *   IN_COMBAT -> DEAD     : chat death message / scoreboard DEAD / team wipe at game end
 *         (a normal in-round death always passes through DOWNED; a direct
 *          IN_COMBAT->DEAD only appears when the downed stage was skipped - the
 *          last player killed is a wipe, or the scoreboard still says "Dead"
 *          after a crash reconnect - in those cases the scoreboard/chat is the
 *          authoritative signal)
 *   DOWNED    -> DEAD     : chat death message / scoreboard DEAD / all-downed wipe
 *   DOWNED    -> LEFT     : chat leave message / scoreboard LEFT
 *   DOWNED    -> IN_COMBAT: chat revive message / scoreboard value restored (after downed was confirmed)
 *   DEAD      -> LEFT     : chat leave message / scoreboard LEFT
 *   DEAD      -> IN_COMBAT: new round signal / chat revive message / scoreboard value restored
 *   LEFT      -> IN_COMBAT: entity reconnect / scoreboard value (back in game and alive)
 *   LEFT      -> DOWNED   : post-reconnect scoreboard "Waiting for revive" (back in game but downed)
 *   LEFT      -> DEAD     : post-reconnect scoreboard "Dead" (back in game but dead)
 * </pre>
 *
 * <p>The whitelist is this strict because the Hypixel scoreboard leaves
 * residue, chat messages arrive out of order, and clashing signals are the
 * norm - the whitelist gives every conflict one resolution: transitions that
 * can happen each have a clear reason, and the rest (say DEAD straight back
 * to DOWNED) are discarded as noise. External events all arrive via
 * {@link TeamStatsManager}; this class hooks no game events and owns data
 * and transition logic only - one funnel is what keeps the state from being
 * scattered and corrupted.
 *
 * <p>All state is read and written on the Minecraft client thread only; no
 * internal locks, no cross-thread guarantees. {@link #getPlayers()} exposes
 * a live read-only view of the underlying roster, not a snapshot: callers
 * cannot modify the map structure, but the {@link PlayerStats} values are
 * mutable objects shared with the state machine, and their fields keep
 * changing with ticks, chat and round events.
 */
public final class TeamStats {

    // ──Status enum──────────────────────────────────────────────────────────

    /**
     * A player's combat status - the HUD colors by it, one plain sentence
     * each:
     * <ul>
     *  <li>{@link #IN_COMBAT}: in combat (green) - alive and acting;</li>
     *  <li>{@link #DOWNED}: downed (yellow) - waiting for a revive;</li>
     *  <li>{@link #DEAD}: dead (red) - needs a new round to come back;</li>
     *  <li>{@link #LEFT}: left (dark red) - out of the game; the real state
     *  is restored from the scoreboard/entities after reconnecting</li>
     * </ul>
     * Statuses do not jump freely - only along the whitelisted paths of
     * {@link TeamStats#transitionTo(PlayerStats, Status, String)}.
     */
    public enum Status {
        IN_COMBAT,  // in combat (green)
        DOWNED,     // downed (yellow)
        DEAD,       // dead (red)
        LEFT        // left (dark red)
    }

    // ──Player data──────────────────────────────────────────────────────────

    /**
     * One player's live stats plus the state machine's runtime fields - they
     * move every time the machine moves. Cumulative values (kills, downs,
     * deaths, gold) are overwritten directly by authoritative server signals;
     * the rest are driven by
     * {@link #transitionTo(PlayerStats, Status, String)} or by timing
     * windows. The instance is a public mutable data carrier: the manager
     * and renderers read fields directly, and the event layer writes
     * authoritative values back. Callers who want to change status must go
     * through the transition entry point - never write
     * {@link PlayerStats#status} around the whitelist.
     */
    public static final class PlayerStats {
        /** Current combat status; migrates only via {@link TeamStats#transitionTo} along the whitelist. */
        public Status status      = Status.IN_COMBAT;
        /** Currently displayed health, rounded up; negative means unknown, and non-combat statuses zero it at tick end. */
        public int    health      = -1;  // <0 = unknown
        /** Current gold as given by the scoreboard; the server value overwrites it when the numeric row appears. */
        public int    gold        = 0;
        // Kills this game; the TAB list / scoreboard server value takes priority.
        public int    kills       = 0;
        /** Downs this game; +1 on every successful transition into {@link Status#DOWNED}. */
        public int    downed      = 0;
        /** Deaths this game; +1 on every successful transition into {@link Status#DEAD}. */
        public int    deaths      = 0;
        /**
         * End of the post-revive/revive protection window (epoch ms, 0 = none).
         * Residue DOWNED/DEAD keywords on the scoreboard are ignored inside
         * the window - without the cover, last round's stale data would knock
         * a freshly revived player straight back to downed/dead.
         */
        public long protectedUntil = 0L;
        /**
         * Timestamp of entering the current status (epoch ms, 0 = not yet
         * transitioned). Downed/dead recovery needs it for debouncing - don't
         * mistake a just-changed status for the old one.
         */
        public long statusSince = 0L;
        /** Whether the scoreboard has confirmed this downed/dead stretch (the status word was seen); required before value-based recovery,
         *  otherwise the gold number left over at the downed moment reads "not rescued yet" as "already rescued". */
        public boolean scoreboardDownedSeen = false;
        /** When chat confirmed the leave (epoch ms, 0 = never left via chat). In the short window after leaving,
         *  scoreboard/entity signals are treated as pre-leave residue. */
        public long leftAt = 0L;
        /** When chat saw the rejoin (epoch ms, 0 = no chat rejoin). In the short window after rejoining, a scoreboard still
         *  saying "Left" is most likely pre-rejoin residue - ignore it and wait for a refresh. */
        public long rejoinAt = 0L;
    }

    // ──Constants────────────────────────────────────────────────────────────

    /** Protection window after a new round or a revive (ms) - old scoreboard status words don't count during it. */
    public static final long  REVIVE_PROTECT_MS  = 6_000L;
    /** Downed/dead recovery debounce (ms): in the instant after entering DOWNED/DEAD the scoreboard may still
     *  carry the old gold number, so "value changed = alive" is ignored for a short window - a fresh down must
     *  not read as a fresh rescue. */
    static final long  REVIVE_DEBOUNCE_MS = 1_000L;
    /** Residue window after a chat leave (ms): old status words and old gold are ignored inside it,
     *  until the scoreboard itself refreshes to "Left". */
    static final long  LEAVE_SCOREBOARD_SETTLE_MS = 1_500L;
    /** Residue window for the scoreboard's "Left" after a chat rejoin (ms): the word is ignored briefly,
     *  until the scoreboard refreshes the player's real state. */
    static final long  REJOIN_LEFT_SETTLE_MS = 2_000L;
    /** Track at most 4 real players. */
    public static final int MAX_PLAYERS = 4;
    /** Full health: on the victory settlement every player's health snaps here (the server has revived everyone,
     *  but the local entity snapshot may not cover every teammate - health must not stick at 0). */
    public static final int MAX_HEALTH = 20;

    // ──Internal state───────────────────────────────────────────────────────

    /** Stored in join order, at most {@value #MAX_PLAYERS} entries. */
    private static final Map<String, PlayerStats> players = new LinkedHashMap<>();

    /**
     * Whether the local player is in death spectating (pseudo-spectator) -
     * true from the death instant, false on revive/new round. During it the
     * player ESP frames only teammates who "were still in combat when the
     * local player died"; otherwise the corpse entities the server sends
     * back would get framed too.
     */
    private static boolean selfSpectating = false;
    /** Teammates still in combat at the local player's death instant - the player ESP frames only these during death spectating. */
    private static final Set<String> aliveWhenSelfDied = new HashSet<>();

    /**
     * Downed-body association: roster player name -> downed-body entity ID.
     * At the downed instant Hypixel spawns a "random-name" player entity at
     * the player's coordinates to replace the combat entity, and by name
     * alone there is no telling who it is. TeamStatsManager therefore pairs
     * them at the downed instant via "last known coordinates + nearest
     * non-roster SLEEPING player entity", and tracking switches to entity ID;
     * once revived/dead/left (out of DOWNED) the association is cleared.
     */
    private static final Map<String, Integer> downedBodyId = new HashMap<>();
    // Downed-body reverse lookup: entity ID -> roster player name (O(1) direct lookup at ESP render time, no table walking).
    private static final Map<Integer, String> downedBodyOwner = new HashMap<>();

    // ──Status transitions (the single entry point)──────────────────────────

    /**
     * Attempts to move {@code st} to {@code next}; silently fails when the
     * target equals the current status or violates the whitelist. On
     * success it also maintains the down/death counters, the status
     * timestamp and the local player's spectating flag.
     *
     * @param st target player stats (not {@code null}; must come from this class's live roster)
     * @param next target status (not {@code null})
     * @param src call-site identifier (not {@code null}; a {@code "chat."} prefix marks an authoritative chat transition,
     *  used to start the leave-residue window)
     * @return whether a transition actually happened
     * @see #isLegalTransition
     */
    public static boolean transitionTo(PlayerStats st, Status next, String src) {
        if (st.status == next) {
            return false;
        }
        if (!isLegalTransition(st.status, next)) {
            return false;
        }
        // Maintain the cumulative counters on the way: downed +1 into DOWNED, deaths +1 into DEAD
        if (next == Status.DOWNED) {
            st.downed++;
        } else if (next == Status.DEAD) {
            st.deaths++;
        }
        st.status = next;
        st.statusSince = System.currentTimeMillis();
        if (next == Status.DOWNED || next == Status.DEAD) {
            st.scoreboardDownedSeen = false; // a new downed/dead stretch starts; wait for the scoreboard status word to confirm
        }
        if (next == Status.LEFT && src.startsWith("chat.")) {
            st.leftAt = System.currentTimeMillis(); // chat-authoritative leave confirmed; the residue window starts here
        }
        // Local player special care: dead -> enter spectating and record the teammates
        // "still in combat at the death instant" (for the ESP filter);
        // revive/new round -> leave spectating. Note st.status was already assigned to
        // next above, so the capture does not count the self as in-combat
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
     * Whitelist check: where each status may go is fixed here.
     * The signal and reasoning per transition live in the class Javadoc's
     * table - only the code remains here.
     *
     * @param from current status
     * @param to target status
     * @return whether {@code to} is a legal transition from {@code from}
     */
    private static boolean isLegalTransition(Status from, Status to) {
        return switch (from) {
            case IN_COMBAT -> to == Status.DOWNED || to == Status.LEFT || to == Status.DEAD;
            case DOWNED    -> to == Status.DEAD   || to == Status.LEFT || to == Status.IN_COMBAT;
            case DEAD      -> to == Status.LEFT   || to == Status.IN_COMBAT;
            // Reconnect after leaving: the real state is left to the scoreboard/entities
            // (IN_COMBAT = alive; DOWNED/DEAD = back in game but downed/dead)
            case LEFT      -> to == Status.IN_COMBAT || to == Status.DOWNED || to == Status.DEAD;
        };
    }

    // ──Lifecycle API────────────────────────────────────────────────────────

    /**
     * A new round begins (triggered by
     * {@link TeamStatsManager#onNewRound()}); revive every player who has not
     * left this game.
     * <ul>
     *  <li>DEAD/DOWNED -> IN_COMBAT (legal): enters the revive protection
     *  window, ignoring last round's residue status words;</li>
     *  <li>IN_COMBAT: already fighting, left alone;</li>
     *  <li>LEFT: the whitelist blocks it outright, no extra check - a player
     *  who left does not come back with a new round</li>
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
                // Revived from DEAD/DOWNED: enter the protection window
                if (!wasInCombat) {
                    st.protectedUntil = now + REVIVE_PROTECT_MS;
                }
            }
        }
    }

    // ──Cache restore (local rejoin into the same game)──────────────────────

    /**
     * Restores one player's data from the cache file (called per player after
     * {@code GameCache} reads {@code %temp%/nmz_gamecache.json}). Two paths,
     * by whether the roster has been rebuilt:
     * <ul>
     *  <li>roster already has the player: backfill cumulative data only
     *  (kills/downs/deaths/gold); status/health/timing stay with the running
     *  state machine;</li>
     *  <li>roster not rebuilt yet: full restore (status/health display the
     *  cached values temporarily, then get corrected by scoreboard/entity
     *  snapshots)</li>
     * </ul>
     * Kills/gold are overwritten by the scoreboard's authoritative value
     * right away (the server accumulates across the whole game and does not
     * reset on rejoin); the scoreboard has no down/death counts, so those
     * keep accumulating on top of the restored base - both legs together is
     * what keeps the end-of-game "totals" complete.
     *
     * @param name player name
     * @param status cached status name ({@link Status} name; invalid values fall back to IN_COMBAT)
     * @param health cached health (shown temporarily after rejoin, then refreshed by the entity snapshot)
     * @param gold cached gold
     * @param kills cached kills
     * @param downed cached down count
     * @param deaths cached death count
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
     * Clears all data (called on a new game / disconnect-reconnect). The
     * cache file is not this class's business - {@code GameCache} runs it
     * separately: a new game (round==1) deletes it via
     * {@code GameCache.reset()}, while exiting and rejoining the same game
     * restores from the file.
     */
    public static void clear() {
        players.clear();
        selfSpectating = false;
        aliveWhenSelfDied.clear();
        downedBodyId.clear();
        downedBodyOwner.clear();
    }

    /** Whether the given PlayerStats is the local player. */
    private static boolean isLocalPlayer(PlayerStats st) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return false;
        }
        return st == players.get(client.player.getGameProfile().getName());
    }

    /**
     * Whether the local player is in death spectating (pseudo-spectator).
     * The state machine signal (a confirmed death transition) wins; the
     * native entity signal is the fallback - local health 0 counts as dead.
     */
    public static boolean isSelfSpectating() {
        if (selfSpectating) {
            return true;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null && !client.player.isAlive();
    }

    /**
     * Whether this player was still in combat at the local player's death
     * instant. During death spectating the player ESP frames only these -
     * otherwise the corpse entities the server sends back get framed too.
     */
    public static boolean wasAliveWhenSelfDied(String name) {
        return aliveWhenSelfDied.contains(name);
    }

    // ──Per-tick update (called by TeamStatsManager)────────────────────────

    /**
     * The full per-tick update, in a fixed order:
     * <ol>
     *  <li>refresh entity health (display only, never drives transitions)</li>
     *  <li>parse the scoreboard, firing status-word/value-driven transitions
     *  (downed/dead/left/revive)</li>
     *  <li>reconnect detection (LEFT -> IN_COMBAT: entity back in the snapshot = returned and alive)</li>
     *  <li>all-downed wipe check</li>
     *  <li>zero the displayed health of non-combat players</li>
     *  <li>trim the roster over limit in insertion order (keeps out-of-game entities out)</li>
     * </ol>
     *
     * @param entitySnapshot this tick's (name -> entity health) snapshot of alive, roster-validated players
     */
    public static void tick(Map<String, Float> entitySnapshot) {
        long now = System.currentTimeMillis();

        // ──Step 1: health refresh + auto-admit────────────────────────────
        for (Map.Entry<String, Float> entry : entitySnapshot.entrySet()) {
            String name = entry.getKey();
            float  hp   = entry.getValue();

            PlayerStats st = players.get(name);
            if (st == null) {
                // Roster full: no new faces - otherwise a spectator/misc 5th entity
                // joins and gets kicked every tick, flapping forever
                if (players.size() >= MAX_PLAYERS) {
                    continue;
                }
                st = new PlayerStats();
                players.put(name, st);
            }

            // Note: neither downed nor revive is judged from entity signals - entity
            // presence and heal amounts are unreliable across modes (teammate entities
            // are often briefly absent in testing).
            // Downed trusts the chat knockdown / scoreboard "Waiting for revive";
            // revive trusts the scoreboard value / chat revive.
            st.health = (hp > 0f) ? (int) Math.ceil(hp) : 0;
        }

        // ──Step 2: scoreboard-driven transitions──────────────────────────
        parseScoreboard(entitySnapshot.keySet());

        // ──Step 3: reconnect LEFT -> IN_COMBAT (entity back in the snapshot = returned and alive)──
        // If the scoreboard gave a DOWNED/DEAD word, step 2 already restored the status
        // (LEFT -> DOWNED/DEAD); still LEFT here with the entity present means the player
        // really is back in the game - treat as alive
        for (Map.Entry<String, PlayerStats> e : players.entrySet()) {
            // In the short window right after a chat-confirmed leave, the lingering
            // entity is just delayed removal, not a reconnect - ignore
            if (e.getValue().status == Status.LEFT && entitySnapshot.containsKey(e.getKey())
                    && now - e.getValue().leftAt >= LEAVE_SCOREBOARD_SETTLE_MS) {
                e.getValue().protectedUntil = System.currentTimeMillis() + REVIVE_PROTECT_MS;
                transitionTo(e.getValue(), Status.IN_COMBAT, "entity.reconnect");
            }
        }

        // ──Step 4: all-downed wipe check──────────────────────────────────
        tickWipeCheck();

        // ──Step 5: zero displayed health of non-combat players────────────
        for (PlayerStats st : players.values()) {
            if (st.status != Status.IN_COMBAT) {
                st.health = 0;
            }
        }

        // ──Step 6: roster cap (keeps out-of-game entities out)────────────
        while (players.size() > MAX_PLAYERS) {
            // Drop the most recently added one (LinkedHashMap iterates in insertion
            // order, so the last key is the newest)
            String last = null;
            for (String k : players.keySet()) {
                last = k;
            }
            if (last != null) {
                players.remove(last);
            }
        }
    }

    // ──Wipe check───────────────────────────────────────────────────────────

    /**
     * All-downed wipe check: nobody still fighting and at least one downed ->
     * mark everyone dead immediately.
     *
     * <p>Downed trusts only chat/scoreboard authoritative signals, and with
     * everyone downed there is nobody left to do the reviving (with a living
     * IN_COMBAT player this branch is never reached), so the game is lost for
     * sure - no grace wait; switching to dead right away is what keeps the
     * HUD from hanging "downed" as false hope.
     */
    private static void tickWipeCheck() {
        // Conditions: no IN_COMBAT and at least one DOWNED
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
            // Nobody alive to revive -> all DOWNED flip to DEAD together
            for (PlayerStats st : players.values()) {
                if (st.status == Status.DOWNED) {
                    transitionTo(st, Status.DEAD, "wipe.allDowned");
                }
            }
        }
    }

    // ──Scoreboard parsing───────────────────────────────────────────────────

    /**
     * Chews through the sidebar scoreboard line by line - status words drive
     * transitions, numeric values drive gold updates.
     *
     * <p>Scoreboard format (colors stripped):
     * <pre>
     *   PlayerName: StatusWord -> fires a transition (including LEFT reconnect recovery; a new entry initializes straight to that status)
     *   PlayerName: Number     -> updates gold; a downed/dead player whose status word vanished gets revived; a numeric row on a LEFT player = returned alive
     *   Kills: N               -> updates local kills
     *   击杀: N                -> same
     * </pre>
     *
     * @param presentNames player names whose entities are present this tick (used to ignore residue LEFT status words)
     */
    private static void parseScoreboard(java.util.Set<String> presentNames) {
        ScoreboardManager sm = ScoreboardManager.get();
        if (sm == null) {
            return;
        }

        long now = System.currentTimeMillis();

        // Also read the TAB list objective for kill counts
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

            // ──Total time row (the scoreboard's authoritative game clock; joiners/rejoiners show real elapsed time)──
            // Local accumulation counts from the join moment and runs low, so the server's
            // whole-game clock directly overwrites totalGameTick.
            // No continue here: a merged row "Time:0:44Kills:3" must still fall through to
            // the kills match
            syncTotalTimeFromLine(line);

            // ──Kills row──────────────────────────────────────────────────
            // Format: "Kills:3" / "击杀:3" / "Time:0:44Kills:3"
            int lineKills = parseKillsFromLine(line);
            if (lineKills >= 0) {
                if (client.player != null) {
                    PlayerStats self = players.computeIfAbsent(
                            client.player.getGameProfile().getName(), k -> new PlayerStats());
                    self.kills = lineKills;
                }
                continue;
            }

            // ──Generic "name:value" row───────────────────────────────────
            int colonIdx = indexOfColon(line);
            if (colonIdx < 0) {
                continue;
            }
            String label = StringUtils.trim(line.substring(0, colonIdx));
            String value = StringUtils.trim(line.substring(colonIdx + 1));

            if (label.isEmpty() || value.isEmpty()) {
                continue;
            }

            // A Hypixel sidebar player row may carry a rank prefix (e.g. "[VIP]Bilishenxds_:1200"),
            // while self/teammates enter the map under plain player names (self is created by the
            // kills row's computeIfAbsent(profileName)).
            // Strip the prefix before matching, or the rank prefix makes the whole row skip - when
            // self happens to be the only ranked player, the symptom is "after rejoining a running
            // map only my own gold is lost, teammates are fine". Stripping is a no-op without a prefix
            String strippedLabel = stripRankPrefix(label);

            PlayerStats st = players.get(strippedLabel);
            if (st == null) {
                // A far teammate's entity has not loaded: the scoreboard row is the authoritative
                // roster source (cached fully).
                // Only rows of "ASCII player name + number/status word" are admitted; misc rows
                // cannot sneak in
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
                // A dead player must not be pulled back to downed by "Waiting for revive": the chat
                // death message (DOWNED->DEAD) always arrives first, and the scoreboard status word
                // lags ~1s before changing to "Dead". Residue DOWNED words in that window are ignored
                // outright - otherwise the whitelist rejection (DEAD->DOWNED) spams every tick
                if (scoreStatus == Status.DOWNED && st.status == Status.DEAD) {
                    continue;
                }
                if (scoreStatus == Status.LEFT) {
                    // In the short window after a chat rejoin, "Left" is only pre-rejoin residue -
                    // ignore; the real status comes from later words/values
                    if (now - st.rejoinAt < REJOIN_LEFT_SETTLE_MS) {
                        continue;
                    }
                    // With the entity present, or inside the revive/new-round protection window,
                    // "LEFT" may be residue - ignore
                    // (presentNames holds plain entity names; label may carry a rank prefix, so
                    // compare with the stripped name)
                    if (!presentNames.contains(strippedLabel) && now >= st.protectedUntil) {
                        transitionTo(st, Status.LEFT, "scoreboard.LEFT");
                    }
                } else {
                    // In the short window right after a chat-confirmed leave, the scoreboard's old
                    // status word is pre-leave data - ignore
                    if (now - st.leftAt < LEAVE_SCOREBOARD_SETTLE_MS) {
                        continue;
                    }
                    // Seeing a DOWNED/DEAD status word: record "the scoreboard confirmed this
                    // downed/dead stretch" (used by value-based recovery)
                    st.scoreboardDownedSeen = true;
                    // Ignore residue DOWNED/DEAD words during the protection window (guards against
                    // mis-judgment after revive/new round).
                    // Why IN_COMBAT->DEAD is whitelisted: a normal in-round death passes through
                    // downed (DOWNED->DEAD); a direct IN_COMBAT->DEAD only appears when the downed
                    // stage was skipped (last player killed = wipe, or the scoreboard still says
                    // "Dead" after a crash reconnect) - then the scoreboard is authoritative and is
                    // taken directly
                    if (now >= st.protectedUntil) {
                        transitionTo(st, scoreStatus, "scoreboard." + scoreStatus);
                    }
                }
            } else if (isNumericValue(value)) {
                // Numeric row = the player is alive, and the number is gold
                int newGold = StringUtils.getNumberInString(value);
                if (newGold != st.gold) {
                    st.gold = newGold;
                }
                if (st.status == Status.LEFT) {
                    if (now - st.leftAt >= LEAVE_SCOREBOARD_SETTLE_MS) {
                        // A left player back in the game and alive (scoreboard went from "Left" back
                        // to a gold number) -> back to combat
                        st.protectedUntil = now + REVIVE_PROTECT_MS;
                        transitionTo(st, Status.IN_COMBAT, "scoreboard.reconnect.numeric");
                    }
                } else if (st.status != Status.IN_COMBAT) {
                    // Downed/dead recovery (after rescue/respawn the scoreboard goes from the status
                    // word back to a number).
                    // scoreboardDownedSeen is required first: the scoreboard must have confirmed the
                    // downed/dead status word before a number counts as "the word vanished";
                    // otherwise the old gold left over at the downed instant reads as rescued
                    // (in testing Hagebub got falsely rescued and his downed count went +2)
                    if (st.scoreboardDownedSeen && now - st.statusSince >= REVIVE_DEBOUNCE_MS) {
                        st.protectedUntil = now + REVIVE_PROTECT_MS;
                        transitionTo(st, Status.IN_COMBAT, "scoreboard.statusWordGone");
                    }
                }
            }
        }

        // ──TAB list kills sync────────────────────────────────────────────
        // board already passed the board == null early return above and tabObj derives
        // from it, so only tabObj is tested here
        if (tabObj != null) {
            for (Map.Entry<String, PlayerStats> e : players.entrySet()) {
                net.minecraft.scoreboard.ReadableScoreboardScore score =
                        board.getScore(ScoreHolder.fromName(e.getKey()), tabObj);
                if (score != null) {
                    e.getValue().kills = score.getScore();
                }
            }
        }
    }

    // ──Utilities────────────────────────────────────────────────────────────

    /**
     * Recognizes a status word in a scoreboard row - language-aware, exact,
     * and not allowed to misfire on a single plain word.
     *
     * <p>Chinese keywords use contains matching; English keywords are pinned
     * to word boundaries - otherwise "down" picked out of a sentence like
     * "knocked down" would misfire as DOWNED.
     */
    private static Status parseStatusWord(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        // ──Left──
        if (value.contains("退出") || value.contains("离开")) {
            return Status.LEFT;
        }
        String lower = value.toLowerCase();
        if (lower.equals("left") || lower.equals("quit") || lower.equals("gone") || lower.equals("offline")) {
            return Status.LEFT;
        }
        // ──Downed──
        // The Chinese scoreboard's downed value is "等待救援" in testing; "倒地" is accepted for
        // good measure. English recognizes "Downed" / "Waiting for revive" etc.
        if (value.contains("倒地") || value.contains("等待救援") || value.contains("待救援")) {
            return Status.DOWNED;
        }
        if (lower.equals("downed") || lower.equals("down") || lower.equals("knocked")
                || lower.contains("waiting for revive") || lower.contains("awaiting revive")
                || lower.contains("waiting for rescue") || lower.contains("waiting to be revived")) {
            return Status.DOWNED;
        }
        // ──Dead──
        if (value.contains("死亡")) {
            return Status.DEAD;
        }
        if (lower.equals("dead") || lower.equals("died") || lower.equals("eliminated")) {
            return Status.DEAD;
        }
        return null;
    }

    /**
     * Pulls the kill count out of a scoreboard row, one regex per language.
     * Matches {@code "Kills: N"} / {@code "击杀: N"} / {@code "... Kills: N ..."}.
     *
     * Returns the kill count on a match, {@code -1} otherwise.
     */
    private static final java.util.regex.Pattern KILLS_PATTERN =
            java.util.regex.Pattern.compile("(?i)[Kk]ills\\s*[:：]\\s*(\\d+)");
    private static final java.util.regex.Pattern KILLS_PATTERN_ZH =
            java.util.regex.Pattern.compile("击杀\\s*[:：]\\s*(\\d+)");

    /** Scoreboard total-time row (the authoritative game clock): {@code "Time:23:45"} / merged
     *  {@code "Time:0:44Kills:3"} / Chinese {@code "时间:23:45"}. */
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
     * Parses the scoreboard total-time row and syncs it to GameTickHandler
     * (the server's whole-game clock, authoritative).
     * Matches {@code "Time: 23:45"} / {@code "时间:23:45"} / merged
     * {@code "Time: 0:44 Kills: 3"}.
     * After a mid-game join/rejoin, local accumulation counts from the join
     * moment and runs low, so the scoreboard value is taken directly; inert
     * while the end-of-game freeze is active.
     * Called only in the Zombies scoreboard parsing context - lobbies and
     * other places without a "Time:mm:ss" row never trigger it.
     *
     * <p>Note: only {@code totalGameTick} is synced (RKPM's authoritative
     * round differencing uses it); the time HUD's game-duration row reads the
     * local stopwatch and is unaffected here.
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
                return; // seconds past 59 means this is not a clock row; skip to avoid a bad sync
            }
            long totalMs = (minutes * 60 + seconds) * 1000L;
            GameTickHandler handler = GameTickHandler.get();
            if (handler != null) {
                handler.syncTotalTimeFromScoreboard(totalMs);
            }
        } catch (NumberFormatException ignored) {
            // Number parse failure just skips (the row did not match; only guards
            // against pathological characters on very old clients)
        }
    }

    /**
     * Finds the first English or Chinese colon in the row - the Chinese
     * scoreboard's separator is the full-width colon, and without handling it
     * the whole row goes unrecognized.
     */
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

    /**
     * Whether the string is purely numeric - both the English and Chinese
     * thousands separators ("," and "，") pass.
     * Gold numbers often carry thousands separators (e.g. 1,200); without
     * accepting commas the numeric row would go unrecognized.
     */
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
     * Whether a scoreboard "name:value" row can count as a roster member
     * (the authoritative source, cached fully): the name must be an ASCII
     * player name (1-16 letters/digits/underscores), the value a number or a
     * status word.
     * The heuristic reuses logic proven in {@code SidebarEnhancer.isPlayerRow};
     * kills rows continue earlier and never reach here.
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
     * Strips Hypixel rank prefixes (e.g. "[VIP]", possibly stacked). Safe
     * because Minecraft player names contain no '[' or ']'.
     * Color codes are already stripped by {@link StringUtils#trim}; without a
     * prefix the input is returned as is (a no-op).
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

    /**
     * Validates a Minecraft player name (1-16 letters/digits/underscores) -
     * the official name rule.
     * NPC/Hypixel dummy decorated names fail it and are filtered right at the
     * entity-snapshot stage.
     */
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

    // ──Public query API─────────────────────────────────────────────────────

    /**
     * Returns a live read-only view of the underlying player table - not a
     * snapshot; every query and iteration sees later roster changes.
     *
     * <p>Callers cannot add or remove entries through the return value, but
     * the values are still shared mutable {@link PlayerStats} whose fields
     * keep being updated by ticks, chat and round events on the client
     * thread. If a structural modification is triggered on the same thread
     * during iteration, the underlying {@link LinkedHashMap}'s fail-fast
     * semantics still apply; this API offers no cross-thread concurrency
     * guarantees.
     *
     * @return a live read-only map view in join order; neither keys nor values are copied
     */
    public static Map<String, PlayerStats> getPlayers() {
        return Collections.unmodifiableMap(players);
    }

    /** Queries a player's current status; {@code null} when not on the roster. */
    public static Status getStatus(String name) {
        PlayerStats st = players.get(name);
        return st != null ? st.status : null;
    }

    // ──Downed-body association API──────────────────────────────────────────

    /** The downed-body entity ID of a roster player; {@code null} when not associated. */
    public static Integer getDownedBodyId(String name) {
        return downedBodyId.get(name);
    }

    /** Reverse lookup: whether an entity ID is some roster player's downed body; returns that player's name or {@code null}. */
    public static String getDownedBodyOwner(int entityId) {
        return downedBodyOwner.get(entityId);
    }

    /** Associates a roster player's downed body (entity ID). */
    public static void setDownedBody(String name, int entityId) {
        removeDownedBody(name);
        downedBodyId.put(name, entityId);
        downedBodyOwner.put(entityId, name);
    }

    /** Clears a roster player's downed-body association (revive/death/leave, or the body going invalid). */
    public static void removeDownedBody(String name) {
        Integer id = downedBodyId.remove(name);
        if (id != null) {
            // Auto-unboxing: remove(Object) and remove(int) are two overloads; writing
            // id.intValue() just states "delete by entity ID" - semantically equivalent
            downedBodyOwner.remove(id);
        }
    }

    /** The local player's cumulative kills; -1 when not on the roster. */
    public static int getLocalKills() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return -1;
        }
        PlayerStats st = players.get(client.player.getGameProfile().getName());
        return st != null ? st.kills : -1;
    }

}