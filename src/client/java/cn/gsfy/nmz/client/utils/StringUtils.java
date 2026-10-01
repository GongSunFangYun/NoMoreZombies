package cn.gsfy.nmz.client.utils;

import net.minecraft.text.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * String helpers—color/emoji stripping, number extraction and color-symbol
 * constants gathered in one static class; callers parsing chat text or
 * scoreboards all go through here, keeping one accounting.
 *
 * <p>Hypixel's chat and scoreboard carry § color codes and various emoji;
 * comparing raw text would miss hits, so every wording check runs through
 * {@link #trim} first to wash down to plain text; number extraction
 * additionally handles the thousands separator, so a kill count like "3,119"
 * parses to 3119.
 */
public final class StringUtils {

    /** Pattern matching an integer or decimal: optional leading minus; kill
     *  counts and duration numbers are both extracted. */
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(\\.\\d+)?");

    /**
     * Washes down to plain text suitable for checking: strips leading /
     * trailing whitespace, color codes (including Hypixel's custom §p/§q
     * etc.) and emoji; a {@code null} input returns an empty string, so
     * callers need no null check.
     */
    public static String trim(String string) {
        if (string == null) {
            return "";
        }
        return string.replaceAll("§[0-9a-zA-Z]", "")
                .replaceAll(Emoji.REGEX, "")
                .trim();
    }

    /** Fetches the first number in the string: thousands separators (both
     *  the half-width "," and the full-width "，") are stripped first
     *  ("3,119"→3119); 0 when there is no number. Used for round numbers /
     *  scoreboard value extraction; swallowing NumberFormatException is just
     *  a defensive second line—the regex already guarantees the matched
     *  substring is a legal numeric literal. */
    public static int getNumberInString(String string) {
        if (string == null) {
            return 0;
        }
        String cleaned = string.replace(",", "").replace("，", "");
        Matcher matcher = NUMBER_PATTERN.matcher(cleaned);
        if (matcher.find()) {
            try {
                return (int) Math.floor(Double.parseDouble(matcher.group()));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /** Whether {@code string} contains {@code key}: both must be non-null; a
     *  null input never reports a false hit. */
    public static boolean contains(String string, String key) {
        return string != null && key != null && string.contains(key);
    }

    /**
     * Takes {@link Text#getString}, then de-formats and de-emojis through
     * {@link #trim}; the unified entry for chat dispatch.
     *
     * @param text a non-{@code null} text
     * @return the washed plain text
     * @throws NullPointerException when {@code text} is {@code null}
     */
    public static String getRaw(Text text) {
        return trim(text.getString());
    }

    /** Emoji regex: taken from the source project's ShowSpawnTime.EMOJI_REGEX,
     *  covering the emoji blocks common in chat. */
    private static final class Emoji {
        private static final String REGEX =
                "(?:[🌀-🗿]|[🤀-🧿]|[😀-🙏]|[🚀-🛿]|[☀-⛿]️?|[✀-➿]️?|Ⓜ️?|[🇦-🇿]{1,2}|[🅰🅱🅾🅿🆎🆑-🆚]️?|[#*0-9]️?⃣|[↔-↙↩-↪]️?|[⬅-⬇⬛⬜⭐⭕]️?|[⤴⤵]️?|[〰〽]️?|[㊗㊙]️?|[🈁🈂🈚🈯🈲-🈺🉐🉑]️?|[‼⁉]️?|[▪▫▶◀◻-◾]️?|[©®]️?|[™ℹ]️?|🀄️?|🃏️?|[⌚⌛⌨⏏⏩-⏳⏸-⏺]️?)";
    }

    private StringUtils() {
    }
}