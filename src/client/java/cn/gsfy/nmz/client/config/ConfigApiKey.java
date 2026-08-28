package cn.gsfy.nmz.client.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import fi.dy.masa.malilib.config.options.ConfigString;

/**
 * 掩码 API Key 配置项（继承 MaLiLib 的 STRING 内联文本框）——明文不露脸，只显示掩码。
 *
 * <p>文本框里始终显示掩码 {@code ••••••••}（真实值不进 UI 可读文本）；实际明文在
 * {@link #setValueFromString} 时从输入内容里剥离掩码字符后保存
 * （编辑流程：{@code WidgetConfigOption.applyNewValueToConfig} → {@code setValueFromString(文本框全文)}）。
 * 落盘经 {@link ApiKeyCrypto} 加密；换账号/离线导致解密失败时返回空（需重填）。
 *
 * <p>误清空保护：输入被清空（剥离后为空）且已有真实值 → 视为未改动，防止焦点移开误清空；
 * 清空请使用配置项右侧的「重置」按钮。
 */
public class ConfigApiKey extends ConfigString {

    private static final char MASK_CHAR = '•'; // •
    private static final String MASK = "••••••••";

    /** 真实明文密钥——文本框里只显示掩码，明文全存这里，别的地方都不放。 */
    private String plainValue = "";

    public ConfigApiKey(String name, String defaultValue) {
        super(name, defaultValue);
        this.plainValue = defaultValue;
    }

    /** 文本框显示值：空则显示空，非空一律掩码——掩码定长，连长度也不泄露。 */
    @Override
    public String getStringValue() {
        return this.plainValue.isEmpty() ? "" : MASK;
    }

    /** 真实密钥明文（供查询逻辑使用），不带掩码——只有这一处能拿到真值。 */
    public String getPlainValue() {
        return this.plainValue;
    }

    /**
     * 从文本框输入（可能含掩码字符）解析明文并保存——编辑时用户看到的永远是掩码。
     *
     * @param value 文本框的完整内容（可能包含掩码 {@code •} 与用户新输入）
     */
    @Override
    public void setValueFromString(String value) {
        String stripped = stripMask(value);
        // 输入被清空（掩码被删光）但已有真实值 → 视为未改动：否则光标一移开密钥就没了；清空请用重置按钮
        if (stripped.isEmpty() && !this.plainValue.isEmpty()) {
            return;
        }
        this.plainValue = stripped;
        super.setValueFromString(stripped);
    }

    @Override
    public void resetToDefault() {
        // 重置必须绕过「误清空保护」：直接落默认明文——否则输过密钥后点重置，会被上面的空值保护挡住不生效
        this.plainValue = this.getDefaultStringValue();
        super.setValueFromString(this.plainValue);
    }

    @Override
    public boolean isModified() {
        return !this.plainValue.equals(this.getDefaultStringValue());
    }

    /**
     * 重置按钮状态判定：按剥离掩码后的明文比，掩码本身不算差异（每次键盘输入都会调用）。
     *
     * @param newValue 待比较的输入文本（可能含掩码字符）
     * @return 与默认值不同则为 true
     */
    @Override
    public boolean isModified(String newValue) {
        return !this.getDefaultStringValue().equals(stripMask(newValue));
    }

    /** 落盘：把明文经 {@link ApiKeyCrypto} 加密成 {@code enc:v1:...} 形式的 JSON 字符串，配置里不存裸钥。 */
    @Override
    public JsonElement getAsJsonElement() {
        return new JsonPrimitive(ApiKeyCrypto.encrypt(this.plainValue));
    }

    /**
     * 读盘：解密（旧版明文自动兼容）；解密失败（换账号等）→ 空，等玩家重填。
     *
     * @param element JSON 元素，须为字符串（明文或密文），否则视为空值
     */
    @Override
    public void setValueFromJsonElement(JsonElement element) {
        try {
            this.plainValue = (element != null && element.isJsonPrimitive())
                    ? ApiKeyCrypto.decrypt(element.getAsString())
                    : "";
        } catch (Exception e) {
            this.plainValue = "";
        }
    }

    /** 保持链式 {@code .apply(prefix)} 返回 ConfigApiKey：父类泛型 T 固定为 ConfigString，这里强转回来才能继续链式。 */
    @Override
    public ConfigApiKey apply(String translationPrefix) {
        return (ConfigApiKey) super.apply(translationPrefix);
    }

    /** 剥离掩码字符，留下用户实际输入的明文——掩码是显示层，不算真输入。 */
    private static String stripMask(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c != MASK_CHAR) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
