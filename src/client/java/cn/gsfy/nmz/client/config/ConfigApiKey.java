package cn.gsfy.nmz.client.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import fi.dy.masa.malilib.config.options.ConfigString;

/**
 * Masked API Key config option (extends MaLiLib's inline STRING text field):
 * the plaintext never shows in the UI, only the mask does.
 *
 * <p>The text field always shows the mask - fixed length, all asterisks,
 * UUID-shaped (its length says nothing about the real value, which never
 * enters UI-readable text). The real plaintext is saved by
 * {@link #setValueFromString(String)}, which strips the mask characters from
 * the input (edit flow: {@code WidgetConfigOption.applyNewValueToConfig} ->
 * {@code setValueFromString(full text field content)}).
 * {@link #getAsJsonElement()} hands only the result of
 * {@link ApiKeyCrypto#encrypt(String)} to the config file, while
 * {@link #setValueFromJsonElement(JsonElement)} first restores via
 * {@link ApiKeyCrypto#decrypt(String)}; so with key material present the
 * config file never holds a bare key, but an encryption downgrade may store
 * the plaintext as-is, and a machine change or corrupted data reads back
 * empty.
 *
 * <p>Accidental-wipe guard: input that strips to empty while a real value
 * already exists is treated as unchanged, so losing focus cannot wipe the
 * key; clearing uses the "Reset" button next to the option.
 */
public class ConfigApiKey extends ConfigString {

    private static final char MASK_CHAR = '*'; // *
    private static final String MASK = "********-****-****-****-************";

    /** The real plaintext key - the text field only shows the mask; the
     * plaintext lives here and nowhere else */
    private String plainValue;

    /**
     * Builds the masked option - plaintext starts from the default value; the
     * display layer still only ever exposes the fixed-length mask.
     *
     * @param name MaLiLib config key name
     * @param defaultValue default plaintext
     */
    public ConfigApiKey(String name, String defaultValue) {
        super(name, defaultValue);
        this.plainValue = defaultValue;
    }

    /** Text field display value: empty shows empty, anything else shows the
     * mask - fixed length, not even the real length leaks */
    @Override
    public String getStringValue() {
        return this.plainValue.isEmpty() ? "" : MASK;
    }

    /** The real plaintext key (for the query logic), unmasked - the only
     * place to obtain the true value */
    public String getPlainValue() {
        return this.plainValue;
    }

    /**
     * Parses the plaintext from text field input (which may contain mask
     * characters) and saves it - while editing, the user only ever sees the
     * mask.
     *
     * @param value the full content of the text field (mask {@code *} plus
     *  any newly typed input)
     */
    @Override
    public void setValueFromString(String value) {
        String stripped = stripMask(value);
        // Input stripped to empty (all mask chars deleted) but a real value
        // exists: treat as unchanged, so losing focus cannot wipe the key
        if (stripped.isEmpty() && !this.plainValue.isEmpty()) {
            return;
        }
        this.plainValue = stripped;
        super.setValueFromString(stripped);
    }

    /** Reset: drops the plaintext and the display back to the default,
     * bypassing the accidental-wipe guard - otherwise Reset after typing a
     * key is swallowed by the empty-value guard and does nothing */
    @Override
    public void resetToDefault() {
        // Bypass the accidental-wipe guard by writing the default plaintext
        // directly: otherwise the guard would block Reset itself
        this.plainValue = this.getDefaultStringValue();
        super.setValueFromString(this.plainValue);
    }

    /** Whether the value drifted from the default: compared by real
     * plaintext - mask or length changes alone are not a difference
     * (called on every keybind change) */
    @Override
    public boolean isModified() {
        return !this.plainValue.equals(this.getDefaultStringValue());
    }

    /**
     * Reset-button state check: compares after stripping the mask, since the
     * mask itself is not a difference (called on every keystroke).
     *
     * @param newValue input text to compare (may contain mask characters)
     * @return true when it differs from the default value
     */
    @Override
    public boolean isModified(String newValue) {
        return !this.getDefaultStringValue().equals(stripMask(newValue));
    }

    /**
     * Serializes only the encryptor's result to disk; {@link #plainValue}
     * never goes straight to JSON.
     *
     * <p>Returns {@code enc:v1:...} when a machine or account key exists;
     * when no key material is available or encryption throws,
     * {@link ApiKeyCrypto#encrypt(String)} downgrades to the raw plaintext by
     * contract, so this is not an absolute secrecy boundary.
     *
     * @return ciphertext JSON writable to the config file; may carry the raw
     *  plaintext after an encryption downgrade
     */
    @Override
    public JsonElement getAsJsonElement() {
        return new JsonPrimitive(ApiKeyCrypto.encrypt(this.plainValue));
    }

    /**
     * Reads from disk: ciphertext is tried with the machine key first, then
     * the account UUID; plaintext is accepted as-is. Decryption failure
     * usually means a machine change, missing key material, or corrupted
     * data - the value is then cleared and the player re-enters it.
     *
     * @param element JSON element; must be a string (plaintext or
     *  ciphertext), anything else counts as empty
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

    /** Keeps chained {@code .apply(prefix)} returning ConfigApiKey: the
     * parent's generic T is fixed to ConfigString, so cast back to keep
     * chaining */
    @Override
    public ConfigApiKey apply(String translationPrefix) {
        return (ConfigApiKey) super.apply(translationPrefix);
    }

    /** Strips mask characters, keeping the plaintext the user actually
     * typed - the mask is display layer, not input */
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
