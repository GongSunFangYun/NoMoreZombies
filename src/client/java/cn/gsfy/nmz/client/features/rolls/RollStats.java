package cn.gsfy.nmz.client.features.rolls;

import cn.gsfy.nmz.client.utils.LanguageUtils;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import cn.gsfy.nmz.client.utils.StringUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lucky chest roll stats - counts only the rewards the local player rolled
 * in lucky chests, accumulated per item, and records "which roll of the game
 * this was" for every hit.
 *
 * <p>Data flow: the chat text (formatting already stripped) hits one of
 * {@link #match(String)}'s eight full-string rules, then ownership is judged,
 * then {@link LanguageUtils#rollItemKey(String)} maps the localized item name
 * to its canonical key, and finally the hit aggregates into the count and
 * roll-number tables. Teammates' rolls stay out of the stats, but the OTHER
 * rules must stay - they carry the "this one is not me" duty and are the
 * future hook for teammate stats.
 *
 * <p><b>One roll may arrive as two messages</b>: first "found {ITEM}" (with a
 * 10s claim window), then the "claimed {ITEM}" receipt. Both belong to the
 * same roll; counting each would turn one chest into two - so a hit on
 * "found" registers a pending record, and a hit on "claimed" first tries to
 * consume that pending record; consumed, it is not counted again (see
 * {@link #record}).
 *
 * <p>What this class guards against is three kinds of <b>silent</b> failure:
 * a mistyped punctuation, an item name off by one letter, wording drifting
 * from the rules - none of them errors, just numbers quietly short or wrong.
 * The mod emits no diagnostic logs, so there is no runtime warning for a
 * silent mismatch: changing any rule or item name can only be calibrated by
 * watching in game whether the counts move.
 *
 * <p><b>This game's stats persist with the fallback cache</b> (see
 * {@link #encode()}/{@link #decode(CachedRolls)}): when the player exits by
 * mistake and rejoins the same game, counts restore from {@code GameCache}
 * instead of resetting to zero.
 * Clearing memory and deleting the file are two separate things - leaving /
 * disconnect clears memory only, and the file is deleted only when the next
 * game starts ({@code GameCache.reset()}); that is exactly why "rejoin
 * restores" and "a new game never mixes data" each hold.
 */
public final class RollStats {

    /** Pairing window for pending records (ms): the normal gap between "found" and "claimed" is far smaller;
     *  past the timeout the two are unrelated rolls, so an old pending record cannot swallow a later claim. */
    private static final long PENDING_WINDOW_MS = 60_000L;

    /**
     * The 11 reward types the mapping table recognizes - <b>enum order is the
     * render row order</b>.
     *
     * <p>The row order is deliberately this catalog order, not by count: the
     * counts change with every roll, so ordering by count would make rows hop
     * up and down and an arranged HUD harder to read.
     */
    public enum Item {
        ZOMBIE_ZAPPER("zombie_zapper"),
        GOLD_DIGGER("gold_digger"),
        RAINBOW_RIFLE("rainbow_rifle"),
        DOUBLE_BARREL("double_barrel"),
        ELDER_GUN("elder_gun"),
        ZOMBIE_SOAKER("zombie_soaker"),
        BLOW_DART("blow_dart"),
        FLAMETHROWER("flamethrower"),
        THE_PUNCHER("the_puncher"),
        LIGHTNING_ROD_SKILL("lightning_rod_skill"),
        HEAL_SKILL("heal_skill");

        /**
         * Canonical key - the return value of
         * {@link LanguageUtils#rollItemKey(String)}; the lang key suffix, the
         * icon table and this field share one string, and a rename invalidates
         * all three at once.
         */
        public final String key;

        Item(String key) {
            this.key = key;
        }

        /** The item name's translation key: parsing matches the Chinese/English originals, rendering shows the client language - the two do not interfere. */
        public String nameKey() {
            return "nomorezombies.rollstats.item." + key;
        }

        /** The item name (translated to the client language). */
        public String displayName() {
            return Text.translatable(nameKey()).getString();
        }
    }

    /**
     * Which stage of a roll a message belongs to.
     *
     * <p>Only "found" starts a new roll; "claimed" is its receipt - consume
     * the pending record first, and a consumed one is not counted (see
     * {@link #record}).
     */
    public enum Flavor {
        /** Rolled (possibly with a claim window still open) */
        FOUND,
        /** The claim receipt */
        CLAIMED
    }

    /**
     * One matching rule.
     *
     * @param name rule name (for logs and self-checks)
     * @param pattern for full-string matching; the pattern must not contain a bare {@code \d} or bare {@code .} (see the class comment)
     * @param self a hit means "this one is mine"
     * @param itemGroup capture group of the item name
     * @param nameGroup capture group of the player name; 0 means a SELF rule has no such group
     * @param soft whether this is a low-confidence fallback rule (hits would log confidence)
     * @param flavor which stage of the roll this message belongs to
     */
    private record Rule(String name, Pattern pattern, boolean self, int itemGroup, int nameGroup,
                        boolean soft, Flavor flavor) {
    }

    /**
     * The eight rules - <b>order is priority, and every SELF rule must come
     * before the OTHER ones</b>.
     *
     * <p>Three spellings that must not be dropped:
     * <ul>
     *  <li>digits are written {@code [0-9]}, never {@code \d}: Java's
     *  {@code \d} already equals {@code [0-9]}, but writing it explicitly
     *  makes the JS and Java runtimes fully equivalent so this rule set can
     *  run in the Node regression forever
     *  (Chinese full-width digits are covered by the {@code [0-9０-９]} spot);</li>
     *  <li>OTHER_ZH's player-name class must stay the ASCII
     *  {@code [A-Za-z0-9_]{3,16}}, <b>not</b> be widened to {@code (.+?)} -
     *  widened, the Chinese SELF rule would be eaten by OTHER, capturing a
     *  phantom teammate named "你";</li>
     *  <li>item captures are always lazy {@code (.+?)}; greedy would swallow
     *  the separator (e.g. the full-width "！") into the item name, and one
     *  item would end up with two keys</li>
     * </ul>
     *
     * <p>Verbs are written as <em>non-capturing</em> alternations like
     * {@code (?:获取到|领取了)}: group numbers belong to the item and player
     * names; one extra capture group would shift every {@code itemGroup}.
     *
     * <p>The English claim verbs (claimed/have claimed/received/got) are not
     * yet confirmed on a live client - the Chinese
     * {@code 你在幸运箱中领取了…} is a measured original and the English side
     * is inferred symmetrically from it. A wrong guess only means never
     * matching (nothing is ever recorded onto the wrong item), so the symptom
     * of a wrong guess is "numbers too small", not "numbers wrong".
     *
     * <p>Input must pass {@link StringUtils#trim} first, or full-width digits
     * and trailing whitespace would silently miss.
     */
    private static final List<Rule> RULES = List.of(
            new Rule("SELF_EN_FOUND",
                    Pattern.compile("^You found (.+?) in the Lucky Chest! You have [0-9]+s to claim it before it disappears!$"),
                    true, 1, 0, false, Flavor.FOUND),
            new Rule("SELF_EN_FOUND_SOFT",
                    Pattern.compile("^You found (.+?) in the Lucky Chest!\\s+You have .*$"),
                    true, 1, 0, true, Flavor.FOUND),
            new Rule("SELF_EN_CLAIM",
                    Pattern.compile("^You (?:claimed|have claimed|received|got) (.+?) (?:from|in) the Lucky Chest!?$"),
                    true, 1, 0, false, Flavor.CLAIMED),
            new Rule("SELF_ZH_FOUND",
                    Pattern.compile("^你在幸运箱中(?:获取到|獲取到)(.+?)[！!]?你有[0-9０-９]+[ ]?秒的时间领取[，,][ ]?否则它将消失[！!]$"),
                    true, 1, 0, false, Flavor.FOUND),
            new Rule("SELF_ZH_FOUND_SOFT",
                    Pattern.compile("^你在幸运箱中(?:获取到|獲取到)(.+?)[！!]?你有.*$"),
                    true, 1, 0, true, Flavor.FOUND),
            new Rule("SELF_ZH_CLAIM",
                    Pattern.compile("^你在幸运箱中(?:领取了|領取了|获得了|獲得了)(.+?)[！!]?$"),
                    true, 1, 0, false, Flavor.CLAIMED),
            new Rule("OTHER_EN",
                    Pattern.compile("^([A-Za-z0-9_]{3,16}) found (.+?) in the Lucky Chest!$"),
                    false, 2, 1, false, Flavor.FOUND),
            new Rule("OTHER_ZH",
                    Pattern.compile("^([A-Za-z0-9_]{3,16})在幸运箱中(?:获取到|獲取到|领取了|領取了|获得了|獲得了)(.+?)[！!]?$"),
                    false, 2, 1, false, Flavor.FOUND));

    /** Rolls counted this game (both the roll-number table and the "total rolls" readout number from it). */
    private static int rollSeq;
    /** Item -> cumulative count. */
    private static final Map<Item, Integer> COUNTS = new EnumMap<>(Item.class);
    /** Item -> the roll numbers when it was rolled (ascending, deduplicated: rolled twice at roll 5 records a single 5). */
    private static final Map<Item, TreeSet<Integer>> ROLLS = new EnumMap<>(Item.class);
    /** Item -> "found" hits still waiting for their "claimed" receipt. */
    private static final Map<Item, Pending> PENDING = new EnumMap<>(Item.class);

    /**
     * One pending record: how many "found" hits for this item have not yet
     * seen their "claimed" receipt.
     *
     * @param count unpaired hits
     * @param atMs wall-clock time of the most recent "found"
     */
    private record Pending(int count, long atMs) {
    }

    /**
     * The pure result of one hit - this record is decided by the input text
     * alone and carries no runtime state, so self-checks can ask
     * {@link #match(String)} directly without a game environment.
     *
     * @param rule the hit rule's name
     * @param self a SELF rule hit (the name-identity check happens separately in {@link #isSelf(Hit, String)})
     * @param soft a low-confidence fallback rule
     * @param flavor rolled or claim receipt
     * @param itemName the captured localized item name
     * @param player the captured player name; empty for SELF rules
     */
    public record Hit(String rule, boolean self, boolean soft, Flavor flavor, String itemName, String player) {
    }

    /**
     * One item's aggregate.
     *
     * @param item the item
     * @param count rolls this game
     * @param rolls the roll numbers when it was rolled (ascending, deduplicated, from 1)
     */
    public record Entry(Item item, int count, List<Integer> rolls) {
    }

    /** Construction is stateless and only hooks one leave-reset: the tick callback clears this game's stats once no longer inside a Zombies game. */
    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean inZombies = client.world != null && client.player != null && PlayerUtils.isInZombies();
            if (!inZombies && !COUNTS.isEmpty()) {
                reset();
            }
        });
    }

    /**
     * The chat entry point (dispatched from the client chat callback, the same
     * entry as power-up detection / team stats).
     *
     * <p>Only records with {@code self} true are written; rolls by others
     * never enter the stats but still pass through the matching layer - the
     * matching layer is the only judge of "whose roll was this"; filtering
     * belongs to the aggregation layer.
     *
     * @param raw chat text with formatting already stripped
     */
    public static void onChatReceived(String raw) {
        String input = StringUtils.trim(raw);
        if (input.isEmpty()) {
            return;
        }
        Hit hit = match(input);
        if (hit == null) {
            return;
        }
        if (!isSelf(hit, localPlayerName())) {
            return;
        }
        String key = LanguageUtils.rollItemKey(hit.itemName);
        if (key == null) {
            return;
        }
        Item item = byKey(key);
        if (item == null) {
            return;
        }
        record(item, hit.flavor);
    }

    /**
     * Folds one hit into the aggregate - <b>pairing is this method's only
     * job</b>.
     *
     * <p>"Found" registers a pending record and counts once; "claimed" first
     * tries to consume the same item's pending record - consumed, it is the
     * second message of the same roll and is <b>not counted again</b>;
     * unconsumable (the "found" message was not recognized, or it is simply
     * different wording), it counts as a new roll.
     *
     * <p>Pending records are stored per item, so interleavings like "found A,
     * found B, claim A, claim B" pair correctly on their own.
     *
     * @param item the item
     * @param flavor which stage this message belongs to
     */
    private static void record(Item item, Flavor flavor) {
        long now = System.currentTimeMillis();
        if (flavor == Flavor.FOUND) {
            Pending pending = PENDING.get(item);
            int count = pending == null ? 0 : pending.count();
            PENDING.put(item, new Pending(count + 1, now));
            countRoll(item);
            return;
        }
        Pending pending = PENDING.get(item);
        if (pending != null && pending.count() > 0 && now - pending.atMs() <= PENDING_WINDOW_MS) {
            if (pending.count() == 1) {
                PENDING.remove(item);
            } else {
                PENDING.put(item, new Pending(pending.count() - 1, pending.atMs()));
            }
            return;
        }
        PENDING.remove(item); // timed out or never existed: this claim is its own roll
        countRoll(item);
    }

    /** Counts one roll: advances the sequence, accumulates the count, and records the number in the item's roll table. */
    private static void countRoll(Item item) {
        rollSeq++;
        COUNTS.merge(item, 1, Integer::sum);
        ROLLS.computeIfAbsent(item, k -> new TreeSet<>()).add(rollSeq);
    }

    /** Clears this game's stats: called from all three sites - a new game (the round-1 title), leaving, and disconnect. */
    public static void reset() {
        rollSeq = 0;
        COUNTS.clear();
        ROLLS.clear();
        PENDING.clear();
    }

    /** Total rolls with a mapped reward (the sum over the 11 types; unmapped rewards are not counted). */
    public static int totalRolls() {
        int total = 0;
        for (int c : COUNTS.values()) {
            total += c;
        }
        return total;
    }

    /**
     * The items rolled this game (enum order, only entries with count > 0).
     *
     * @return an immutable copy; empty when nothing mapped was rolled
     */
    public static List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        for (Item item : Item.values()) {
            Integer count = COUNTS.get(item);
            if (count == null || count <= 0) {
                continue;
            }
            TreeSet<Integer> rolls = ROLLS.get(item);
            out.add(new Entry(item, count, rolls == null ? List.of() : List.copyOf(rolls)));
        }
        return List.copyOf(out);
    }

    /**
     * The pure matching layer - full-string matching ({@code matches()}, not
     * substring search), SELF rules before OTHER.
     *
     * <p>Full-string anchoring is required: the player-name class
     * {@code [A-Za-z0-9_]{3,16}} matches the 3-character {@code You}, and a
     * substring search would judge "you rolled it" as a teammate named
     * {@code You} - silently and completely wrong.
     *
     * @param raw the plain text, formatting stripped and {@code strip()}ed
     * @return the first rule's hit; {@code null} when nothing matches
     */
    public static Hit match(String raw) {
        String input = StringUtils.trim(raw);
        if (input.isEmpty()) {
            return null;
        }
        for (Rule rule : RULES) {
            Matcher m = rule.pattern.matcher(input);
            if (m.matches()) {
                String itemName = StringUtils.trim(m.group(rule.itemGroup));
                String player = rule.nameGroup == 0 ? "" : StringUtils.trim(m.group(rule.nameGroup));
                return new Hit(rule.name, rule.self, rule.soft, rule.flavor, itemName, player);
            }
        }
        return null;
    }

    /**
     * Ownership - <b>by name identity, not a {@code You}/{@code 你} prefix</b>.
     *
     * <p>At least three other messages start with {@code You}, and "You have
     * fully repaired this window!" even shares the {@code You have} prefix
     * with the roll message, so even prefix matching collides.
     * IGNs are globally unique, so "a teammate who happens to be named You"
     * dissolves naturally: the local player cannot also be named You.
     *
     * @param hit the match result
     * @param localName the local player's IGN; when empty only the rule's own self flag can be trusted
     * @return whether this roll belongs to the local player
     */
    static boolean isSelf(Hit hit, String localName) {
        if (hit.self) {
            return true;
        }
        return !localName.isEmpty() && localName.equalsIgnoreCase(hit.player);
    }

    /** The local player's IGN: empty when the player is not ready, in which case everything outside SELF rules is uncounted. */
    private static String localPlayerName() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.player.getGameProfile() == null) {
            return "";
        }
        return client.player.getGameProfile().getName();
    }

    /** Canonical key -> enum; a mistyped key returns {@code null} rather than throwing. */
    private static Item byKey(String key) {
        for (Item item : Item.values()) {
            if (item.key.equals(key)) {
                return item;
            }
        }
        return null;
    }

    /** Whether not a single roll happened this game (judged by the roll sequence {@code rollSeq}, independent of the per-item count sum). */
    public static boolean isEmpty() {
        return rollSeq <= 0;
    }

    /**
     * Encodes this game's stats into a persistable snapshot (plain
     * Gson-serializable data, without wall-clock middle states like
     * {@link Pending}).
     *
     * <p>Only "counted items" are exported: {@link #PENDING} is the pairing
     * middle state of "found but no claim receipt yet" - restoring it across
     * sessions is meaningless, since that receipt either already happened or
     * belongs to a roll that is over.
     *
     * @return the snapshot; {@code null} when nothing was rolled this game (no empty shells written)
     */
    public static CachedRolls encode() {
        if (isEmpty()) {
            return null;
        }
        CachedRolls cached = new CachedRolls();
        cached.rollSeq = rollSeq;
        for (Item item : Item.values()) {
            Integer count = COUNTS.get(item);
            if (count == null || count <= 0) {
                continue;
            }
            TreeSet<Integer> rolls = ROLLS.get(item);
            CachedItem entry = new CachedItem();
            entry.key = item.key;
            entry.count = count;
            entry.rolls = rolls == null ? new int[0]
                    : rolls.stream().mapToInt(Integer::intValue).toArray();
            cached.items.put(item.key, entry);
        }
        return cached.items.isEmpty() ? null : cached;
    }

    /**
     * Restores this game's stats from a snapshot (rejoining a game in
     * progress) - a <b>wholesale replacement</b>, never clear-then-add,
     * otherwise rejoining twice would stack the same data twice.
     *
     * <p>Unrecognizable item keys are skipped: the cache file may come from an
     * older version with a different mapping table; skipping only undercounts
     * that one item, leaves the rest intact, and never throws to break the
     * whole restore.
     *
     * @param cached the snapshot; {@code null} or an empty table clears this game's stats
     */
    public static void decode(CachedRolls cached) {
        reset();
        if (cached == null || cached.items == null) {
            return;
        }
        rollSeq = Math.max(0, cached.rollSeq);
        for (CachedItem entry : cached.items.values()) {
            if (entry == null) {
                continue;
            }
            if (entry.key == null) {
                continue;
            }
            Item item = byKey(entry.key);
            if (item == null || entry.count <= 0) {
                continue;
            }
            COUNTS.put(item, entry.count);
            TreeSet<Integer> rolls = new TreeSet<>();
            if (entry.rolls != null) {
                for (int roll : entry.rolls) {
                    rolls.add(roll);
                }
            }
            ROLLS.put(item, rolls);
        }
    }

    /** The persisted roll-stats snapshot (serialized by Gson directly): total rolls + per-item detail. */
    public static class CachedRolls {
        /** Total rolls counted this game (both the roll-number table and the "total rolls" readout number from it). */
        public int rollSeq;
        /** Item canonical key -> detail; a {@link LinkedHashMap} only so the file reads in order. */
        public Map<String, CachedItem> items = new LinkedHashMap<>();
    }

    /** One item's persisted detail. */
    public static class CachedItem {
        /** {@link Item#key} (not the enum name) - renaming an enum must not invalidate the whole old cache. */
        public String key;
        /** Rolls this game. */
        public int count;
        /** The roll numbers when it was rolled (ascending, deduplicated). */
        public int[] rolls;
    }

    private RollStats() {
    }
}
