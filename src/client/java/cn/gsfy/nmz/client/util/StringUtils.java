package cn.gsfy.nmz.client.util;

import net.minecraft.text.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 字符串处理工具——去色去 emoji、数字提取、颜色符号常量收在静态类里，
 * 聊天文案 / 计分板解析的调用方统一走这里，保证判定口径一致。
 *
 * <p>Hypixel 的聊天与计分板带 § 颜色码和各种 emoji，直接比对原文会漏判，
 * 所以所有文案判定先过 {@link #trim} 洗成纯文本；数字提取额外处理千分位逗号，
 * 击杀数「3,119」才能正确解析成 3119。
 */
public final class StringUtils {

    /** 匹配整数或小数的正则：带可选前导负号，击杀 / 时长数字都能抠出来。 */
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(\\.\\d+)?");

    /**
     * 洗出可判定的纯文本：去掉首尾空白、颜色符号（含 Hypixel 自定义 §p/§q 等）
     * 与 emoji；{@code null} 输入返回空串，调用方不用再判空。
     */
    public static String trim(String string) {
        if (string == null) {
            return "";
        }
        return string.replaceAll("§[0-9a-zA-Z]", "")
                .replaceAll(Emoji.REGEX, "")
                .trim();
    }

    /** 取字符串里第一个数字：先去千分位逗号（"3,119" → 3119），无数字返回 0，
     * 回合号 / 计分板数值提取用。 */
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

    /** 取字符串里最后一个数字：TAB 列表击杀数在玩家名与图标之间，后面还跟着别的东西，
     * 所以只认最后一个；无数字返回 0。 */
    public static int getLastNumberInString(String string) {
        if (string == null) {
            return 0;
        }
        String cleaned = string.replace(",", "").replace("，", "");
        Matcher matcher = NUMBER_PATTERN.matcher(cleaned);
        int last = 0;
        while (matcher.find()) {
            try {
                last = (int) Math.floor(Double.parseDouble(matcher.group()));
            } catch (NumberFormatException ignored) {
            }
        }
        return last;
    }

    /** 判断 string 是否含 key：两者都非空才算，null 输入不会误报。 */
    public static boolean contains(String string, String key) {
        return string != null && key != null && string.contains(key);
    }

    /** 取 Text 的原始文本：经 {@link #trim} 洗过，去格式与 emoji，聊天分发统一用它。 */
    public static String getRaw(Text text) {
        return trim(text.getString());
    }

    /** 常用颜色符号常量：拼聊天 / HUD 字符串时直接引用，免手写 § 码。 */
    public static final class Formatting {
        public static final String BLACK = "§0";
        public static final String DARK_BLUE = "§1";
        public static final String DARK_GREEN = "§2";
        public static final String DARK_AQUA = "§3";
        public static final String DARK_RED = "§4";
        public static final String DARK_PURPLE = "§5";
        public static final String GOLD = "§6";
        public static final String GRAY = "§7";
        public static final String DARK_GRAY = "§8";
        public static final String BLUE = "§9";
        public static final String GREEN = "§a";
        public static final String AQUA = "§b";
        public static final String RED = "§c";
        public static final String LIGHT_PURPLE = "§d";
        public static final String YELLOW = "§e";
        public static final String WHITE = "§f";
        public static final String RESET = "§r";
    }

    /** emoji 正则：取自源项目 ShowSpawnTime.EMOJI_REGEX，覆盖聊天里常见表情区段。 */
    private static final class Emoji {
        private static final String REGEX =
                "(?:[🌀-🗿]|[🤀-🧿]|[😀-🙏]|[🚀-🛿]|[☀-⛿]️?|[✀-➿]️?|Ⓜ️?|[🇦-🇿]{1,2}|[🅰🅱🅾🅿🆎🆑-🆚]️?|[#*0-9]️?⃣|[↔-↙↩-↪]️?|[⬅-⬇⬛⬜⭐⭕]️?|[⤴⤵]️?|[〰〽]️?|[㊗㊙]️?|[🈁🈂🈚🈯🈲-🈺🉐🉑]️?|[‼⁉]️?|[▪▫▶◀◻-◾]️?|[©®]️?|[™ℹ]️?|🀄️?|🃏️?|[⌚⌛⌨⏏⏩-⏳⏸-⏺]️?)";
    }

    private StringUtils() {
    }
}
