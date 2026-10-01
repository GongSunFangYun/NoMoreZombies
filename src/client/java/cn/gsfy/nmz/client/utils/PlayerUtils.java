package cn.gsfy.nmz.client.utils;

import cn.gsfy.nmz.NoMoreZombies;
import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.shared.game.ScoreboardManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Player-side helpers—in-game detection, chat send and sound playback
 * gathered in one static class; each feature uses what it needs without
 * rewriting the "get the client instance" boilerplate.
 *
 * <p>In-game detection carries a 200ms cache + a three-consecutive-
 * confirmation debounce: a sidebar flicker at a round boundary does not
 * misjudge a leave; chat send routes by output target (local / party /
 * public), and party/public strips the mod prefix first.
 */
public final class PlayerUtils {

    /** The last in-game check's timestamp: works with the 200ms throttle so
     *  the check does not rescan the scoreboard every frame. */
    private static long lastZombiesCheck;
    /** The cached result after the throttle: returned directly inside the
     *  window, avoiding a per-frame scoreboard rescan. */
    private static boolean cachedInZombies;
    /** Consecutive "not in Zombies" count: flips to false only after
     *  {@link #NOT_IN_ZOMBIES_CONFIRM} hits. */
    private static int notInZombiesStreak;
    /** 200ms×3 ≈ 600ms: a brief out-of-game (sidebar missing the "Zombies
     *  Left" row at a round switch) is not a leave. */
    private static final int NOT_IN_ZOMBIES_CONFIRM = 3;

    /**
     * Whether the player is in a Zombies game: the title hits and at least
     * one row is Zombies Left, with a 200ms cache throttle.
     * Entering recognizes immediately; leaving flips only after three
     * consecutive out-of-game results—guarding against a round switch's
     * momentary missing row being misjudged as a leave, which would reset
     * the whole state machine and suspend all tasks.
     */
    public static boolean isInZombies() {
        long now = System.currentTimeMillis();
        if (now - lastZombiesCheck > 200) {
            boolean in = computeInZombies();
            if (in) {
                cachedInZombies = true;
                notInZombiesStreak = 0;
            } else if (++notInZombiesStreak >= NOT_IN_ZOMBIES_CONFIRM) {
                // Flip only after three consecutive out-of-game results: the
                // sidebar drops rows at a round switch, and a misjudged leave
                // would reset the state machine and every suspended task -
                // better late than wrong
                cachedInZombies = false;
            }
            lastZombiesCheck = now;
        }
        return cachedInZombies;
    }

    /** Recomputes whether in-game in real time (no cache): the title is
     *  Zombies and the sidebar has a Zombies Left row. */
    private static boolean computeInZombies() {
        ScoreboardManager sm = ScoreboardManager.get();
        if (sm == null) {
            return false;
        }
        if (!LanguageUtils.isZombiesTitle(sm.getTitle())) {
            return false;
        }
        for (int i = 1; i <= sm.getSize(); i++) {
            if (LanguageUtils.isZombiesLeft(sm.getContent(i))) {
                return true;
            }
        }
        return false;
    }

    /** Whether in the Zombies lobby: only the title hits, no Zombies Left
     *  row—separating "in-game" from "waiting" states. */
    public static boolean isInZombiesTitle() {
        ScoreboardManager sm = ScoreboardManager.get();
        return sm != null && LanguageUtils.isZombiesTitle(sm.getTitle());
    }

    /**
     * Appends one message to the local chat HUD—silently skipped when the
     * client, player or HUD is not ready; display only, never sends. A
     * {@code null} {@code text} with a ready environment is rejected by the
     * chat HUD.
     *
     * @param text the text to append
     */
    public static void sendMessage(Text text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null && client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(text);
        }
    }

    /** The mod prefix ([NoMoreZombies]): kept only for local display, stripped
     *  before party/public sends. */
    private static final String MOD_PREFIX = "[" + NoMoreZombies.MOD_NAME + "] ";

    /** Strips the mod prefix (only when the string starts with
     *  [NoMoreZombies]): party/public messages go to others and must not
     *  pollute their chat. */
    private static String stripModPrefix(String s) {
        if (s != null && s.startsWith(MOD_PREFIX)) {
            return s.substring(MOD_PREFIX.length());
        }
        return s;
    }

    /**
     * Routes a message by output target: SELF goes to the local chat,
     * PARTY/CHAT go to the server command (/pc, /ac). Server-side sends
     * strip color codes then the mod prefix; a {@code null} in any parameter
     * is silently skipped.
     *
     * @param text the text to display or send; may be {@code null}
     * @param output the output target; may be {@code null}
     */
    public static void sendMessage(Text text, GlobalConfig.AlertOutput output) {
        if (text == null || output == null) {
            return;
        }
        switch (output) {
            case SELF -> sendMessage(text);
            case PARTY -> sendCommandToServer("pc " + stripModPrefix(StringUtils.trim(text.getString())));
            case CHAT -> sendCommandToServer("ac " + stripModPrefix(StringUtils.trim(text.getString())));
        }
    }

    /**
     * Sends one server command—without the leading slash; sendChatCommand
     * handles the signed /command itself. Silently skipped when the client,
     * player or network handler is not ready.
     *
     * @param command the command body; passed on to the network handler as is
     *   when the environment is ready
     */
    public static void sendCommandToServer(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendChatCommand(command);
        }
    }

    /**
     * Plays a sound at the player's position: cue sounds / whole-second
     * ticks and other feedback all go through this, saving a hand-rolled
     * packet.
     *
     * @param soundId the sound's resource location string
     * @param pitch the playback pitch
     */
    public static void playSound(String soundId, float pitch) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        SoundEvent soundEvent = SoundEvent.of(Identifier.of(soundId));
        client.getSoundManager().play(PositionedSoundInstance.master(soundEvent, pitch));
    }
}