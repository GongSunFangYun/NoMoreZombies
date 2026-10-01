package cn.gsfy.nmz.client.config;

import cn.gsfy.nmz.NoMoreZombies;
import fi.dy.masa.malilib.config.options.ConfigString;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Map;

/**
 * Locale-aware default template config option (extends ConfigString) - the
 * AA chat template's default follows the client language (translation key
 * {@code nomorezombies.aaautocommand.defaultTemplate}). When the template is
 * empty/invalid, {@code AAAutoCommand.resolveTemplate()} falls back to the
 * same translation key; the config screen's "Reset" button fills the
 * current-language template here instead of an empty string.
 *
 * <p>Also owns "auto-rewrite on client language switch": as long as the
 * current value is still the <b>old language's</b> default template (the
 * user never customized it), a language switch rewrites it to the new
 * language's default and saves; a customized value is left alone. The old
 * default has to be looked up in the hardcoded map because at the moment of
 * the switch {@code Text.translatable} already returns the new text - only
 * this map still holds the old language's default.
 */
public class I18nTemplateConfig extends ConfigString {

    /**
     * Hardcoded per-language default templates. <b>Must stay in sync</b> with
     * the bilingual text of {@code nomorezombies.aaautocommand.defaultTemplate}
     * in the lang files (edit both together). The language-switch rewrite
     * depends on these values: at the moment of the switch
     * {@code Text.translatable} already returns the new language's text, so
     * the old language's default is only findable here.
     */
    private static final Map<String, String> LOCALE_DEFAULTS = new HashMap<>();

    static {
        LOCALE_DEFAULTS.put("zh_cn",
                "回合{round},推荐点位{point},刷新高危怪物{boss},难度{difficulty}");
        LOCALE_DEFAULTS.put("en_us",
                "Round {round}, Points {point}, Boss {boss}, Difficulty {difficulty}");
    }

    /** Client language from the last check; null = not yet initialized - the
     * first tick records the language and also migrates a default template
     * saved under another language to the current default (custom values
     * stay) */
    private String lastLanguage = null;

    /**
     * Builds the template option whose default follows the language - the
     * parent default is left empty; the real default is computed on read.
     *
     * @param name MaLiLib config key name
     */
    public I18nTemplateConfig(String name) {
        super(name, "", "");
    }

    /** Keeps chained {@code .apply(prefix)} returning I18nTemplateConfig so
     * the chain can continue */
    @Override
    public I18nTemplateConfig apply(String translationPrefix) {
        return (I18nTemplateConfig) super.apply(translationPrefix);
    }

    /** Default follows the language: config screen display and "Reset" both
     * use the current-language template */
    @Override
    public String getDefaultStringValue() {
        return defaultTemplate();
    }

    /** Reset: fills back the current-language default template (the
     * auto-rewrite only fires on an actual language switch) */
    @Override
    public void resetToDefault() {
        super.setValueFromString(defaultTemplate());
    }

    /** Whether the value was customized: anything differing from the
     * current-language default template counts as customized */
    @Override
    public boolean isModified() {
        return !this.getStringValue().equals(defaultTemplate());
    }

    /** Reset-button check: compares directly against the current-language
     * default; mask/typing state is irrelevant */
    @Override
    public boolean isModified(String newValue) {
        return !defaultTemplate().equals(newValue);
    }

    /**
     * Called every tick by {@code NoMoreZombiesClient}: watches the client
     * language. If the current value is still the <b>old language's</b>
     * default template (user never customized), it is rewritten to the new
     * language's default and saved; a customized template (matching no
     * language default) is untouched. The first tick records the language
     * and also migrates a default template saved under another language to
     * the current default (custom values stay).
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
            // First load: if the value is another language's default template,
            // migrate it to the current language
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
        // getStringValue() never returns null (MaLiLib ConfigString contract),
        // so only oldDefault needs a null check
        if (this.getStringValue().equals(oldDefault)) {
            rewriteTo(lang, oldLang);
        }
    }

    /** Rewrites the template to the new language's default and saves; skipped
     * when the new default equals the current value (already in the target
     * language) */
    private void rewriteTo(String lang, String oldLang) {
        String newDefault = LOCALE_DEFAULTS.get(lang);
        if (newDefault == null || newDefault.equals(this.getStringValue())) {
            return;
        }
        this.setValueFromString(newDefault);
        GlobalConfig.saveToFile();
        NoMoreZombies.LOGGER.info("[AA自动指挥] 客户端语言 {} → {}，AA 聊天模板已自动重写为 {} 语言默认版本",
                oldLang, lang, lang);
    }

    /** Current-language default template: reads the translation key directly;
     * AAAutoCommand falls back to the same place for empty/invalid templates */
    private static String defaultTemplate() {
        return Text.translatable("nomorezombies.aaautocommand.defaultTemplate").getString();
    }
}
