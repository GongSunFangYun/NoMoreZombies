package cn.gsfy.nmz.client.config;

import cn.gsfy.nmz.NoMoreZombies;
import fi.dy.masa.malilib.config.options.ConfigString;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Map;

/**
 * 本地化默认模板配置项（继承 ConfigString）——AA 聊天模板的默认值随客户端语言走
 * （翻译键 {@code nomorezombies.aacommand.defaultTemplate}）。模板为空/非法时由
 * {@code AaCommander.resolveTemplate()} 回退到同一翻译键；这里让配置界面「重置」按钮
 * 填入当前语言模板，而不是填个空串。
 *
 * <p>同时扛起「客户端语言切换自动重写」：只要当前值还是<b>旧语言</b>的默认模板
 * （说明用户没自定义过），语言一切就自动改写成新语言默认模板并落盘；用户自定义过则不动。
 * 之所以要在这份硬编码默认值里查旧语言，是因为切换瞬间 {@code Text.translatable} 已返回新文案，
 * 旧语言默认值只有这里还留着。
 */
public class I18nTemplateConfig extends ConfigString {

    /**
     * 各语言默认模板的硬编码副本。与 lang 文件 {@code nomorezombies.aacommand.defaultTemplate} 的
     * 双语文案<b>必须保持同步</b>（改 lang 需同步此处，反之亦然）——语言切换重写依赖这份值，
     * 因为切换瞬间 {@code Text.translatable} 已经返回新语言文案，旧语言默认值只能在这查到。
     */
    private static final Map<String, String> LOCALE_DEFAULTS = new HashMap<>();

    static {
        LOCALE_DEFAULTS.put("zh_cn",
                "回合 {round}, 推荐点位 {point}, 刷新高危怪物 {boss}, 难度 {difficulty}");
        LOCALE_DEFAULTS.put("en_us",
                "Round {round}, Points {point}, Boss {boss}, Difficulty {difficulty}");
    }

    /** 上次处理时的客户端语言；null = 尚未初始化——首个 tick 只记录、不重写，避免刚启动就乱动配置。 */
    private String lastLanguage = null;

    /** 构造：父类默认值给空串，真正默认值由 {@link #defaultTemplate()} 按当前语言现算。 */
    public I18nTemplateConfig(String name) {
        super(name, "", "");
    }

    /** 保持链式 {@code .apply(prefix)} 返回 I18nTemplateConfig，才能一路链式调下去。 */
    @Override
    public I18nTemplateConfig apply(String translationPrefix) {
        return (I18nTemplateConfig) super.apply(translationPrefix);
    }

    /** 默认值随语言走：配置界面显示与「重置」都拿当前语言模板。 */
    @Override
    public String getDefaultStringValue() {
        return defaultTemplate();
    }

    /** 重置：把当前语言默认模板填回去（自动重写只发生在语言切换时）。 */
    @Override
    public void resetToDefault() {
        super.setValueFromString(defaultTemplate());
    }

    /** 是否改过默认值：与当前语言默认模板比，不同即自定义过。 */
    @Override
    public boolean isModified() {
        return !this.getStringValue().equals(defaultTemplate());
    }

    /** 重置按钮判定：直接与当前语言默认模板比对，掩码/输入过程无关。 */
    @Override
    public boolean isModified(String newValue) {
        return !defaultTemplate().equals(newValue);
    }

    /**
     * 每 tick 由 {@code NoMoreZombiesClient} 调用：盯客户端语言变化。
     * 若当前值仍是<b>旧语言</b>的默认模板（用户未自定义），自动重写为新语言默认模板并落盘；
     * 用户自定义模板（≠ 任一语言默认）不受影响。首帧只记录当前语言，不重写。
     */
    public void checkLanguageChanged() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) {
            return;
        }
        String lang = client.options.language;
        if (lang == null || lang.isEmpty()) {
            return;
        }

        if (lastLanguage == null) {
            lastLanguage = lang;
            // 首次加载：若值恰是「非当前语言」的默认模板（上次在旧语言下保存的默认），顺带迁到当前语言
            for (Map.Entry<String, String> e : LOCALE_DEFAULTS.entrySet()) {
                if (!e.getKey().equals(lang) && this.getStringValue().equals(e.getValue())) {
                    rewriteTo(lang, e.getKey());
                    return;
                }
            }
            return;
        }

        if (lang.equals(lastLanguage)) {
            return;
        }
        String oldLang = lastLanguage;
        lastLanguage = lang;
        String oldDefault = LOCALE_DEFAULTS.get(oldLang);
        if (oldDefault != null && this.getStringValue().equals(oldDefault)) {
            rewriteTo(lang, oldLang);
        }
    }

    /** 把模板值改写为新语言默认并落盘；新默认与当前值相同（已在目标语言）则跳过。 */
    private void rewriteTo(String lang, String oldLang) {
        String newDefault = LOCALE_DEFAULTS.get(lang);
        if (newDefault == null || newDefault.equals(this.getStringValue())) {
            return;
        }
        this.setValueFromString(newDefault);
        GlobalConfig.saveToFile();
        NoMoreZombies.LOGGER.info("[AA指挥] 客户端语言 {} → {}，AA 聊天模板已自动重写为 {} 语言默认版本",
                oldLang, lang, lang);
    }

    /** 当前语言默认模板：直接读翻译键，模板为空/非法时由 AaCommander 回退到同一处。 */
    private static String defaultTemplate() {
        return Text.translatable("nomorezombies.aacommand.defaultTemplate").getString();
    }
}
