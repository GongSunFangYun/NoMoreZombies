package cn.gsfy.nmz.client.features.gamehud;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.Sprite;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import cn.gsfy.nmz.client.config.hud.HUDEditor;

/**
 * Status-effect HUD—a text-list potion-effect overview, replacing the
 * vanilla top-right icon grid. (Zombies games only; the vanilla rendering is
 * cancelled by {@code InGameHudEffectOverlayMixin} under the same switch
 * set.)
 *
 * <p>Each effect takes two rows: "[icon] effect name level" on the first,
 * "remaining duration" on the second. The icon reuses the vanilla
 * mob_effects atlas (9px, vertically centered on the left of the two-row
 * block); levels use Roman numerals (ASCII I/II/III..., level I is hidden,
 * matching the vanilla tooltip convention); durations are M:SS (infinite
 * effects show ∞)—the time number is the main value-add over the vanilla
 * HUD. The countdown gets its own row instead of hanging off the name's
 * right side and stretching the whole block rightward—anchoring to the
 * right then never pushes a long name plus a long countdown off screen.
 *
 * <p>Timing data is read live each frame from
 * {@code player.getStatusEffects()}; soonest-to-expire on top, and
 * remaining ≤3s turns red. Gate =
 * {@link GlobalConfig.Hud#statusEffectsOn()} (master switch + placed + this
 * HUD's own switch) + the base class's isInZombies. With no active effect,
 * the whole block is not rendered. Layout width comes from
 * {@link #hudWidth}; editor preview and default right-hug resolution share
 * one accounting.
 */
public class StatusEffectHudRenderer extends TotalHUDRenderer {

    /** Icon edge (px): the two-row block is only 18px tall, so the icon
     *  shrinks to 9px on the left. */
    private static final int ICON_SIZE = 9;
    /** Gap between icon and text (px). */
    private static final int ICON_GAP = 3;
    /** Rows per effect: one for the name, one for the countdown. */
    private static final int ROWS_PER_EFFECT = 2;
    /** Colors: effect name white / duration light blue (same source as the
     *  powerup HUD separator) / expiring red / no separate label (the first
     *  row is the content). */
    private static final int COLOR_NAME = 0xFFFFFF;
    private static final int COLOR_TIME = 0x99CCFF;
    private static final int COLOR_EXPIRING = 0xFF5555;
    /** Remaining duration at or below this many seconds turns the time red. */
    private static final int EXPIRING_SECONDS = 3;

    /** The preview sample's effect table (real rendering, editor preview,
     *  width estimation all share one line structure): each entry
     *  {name lang key, level, countdown}. The name is stored as a lang key
     *  and translated by {@link #previewName} at render time—Chinese/English
     *  follow the client language. */
    public static final String[][] PREVIEW_ROWS = {
            {"nomorezombies.statuseffect.preview.miningFatigue", "V", "∞"},
            {"nomorezombies.statuseffect.preview.regeneration", "III", "∞"},
            {"nomorezombies.statuseffect.preview.speed", "", "∞"}
    };

    /** The sample's i-th effect name (translated). */
    public static String previewName(int i) {
        return Text.translatable(PREVIEW_ROWS[i][0]).getString();
    }

    /** The sample's i-th countdown (editor sample and real rendering share one source). */
    public static String previewTime(int i) {
        return PREVIEW_ROWS[i][2];
    }

    /** Rows taken by one effect—the sample drawing and real rendering share
     *  this number; writing a separate constant in each would make the
     *  sample frame miss the actual strokes by a layer whenever the row
     *  count changes. */
    public static int rowsPerEffect() {
        return ROWS_PER_EFFECT;
    }

    /** HUD total width = the widest row across the current effects (icon
     *  area + text); falls back to the sample width when there is no player
     *  or no effect. Dynamic measuring makes <em>default right-hug
     *  anchoring</em> hug the real content—long effect names no longer push
     *  the visible rendering off screen. The editor collision box does not
     *  go through here (it follows the sample, see {@link #previewWidth}).
     *  Both include the shadow overhang. */
    public static int hudWidth(TextRenderer tr) {
        return effectsWidth(tr, currentEffects());
    }

    /** The editor sample width: measured from the three rounds of
     *  {@link #PREVIEW_ROWS} (two rows per round, taking the wider of the
     *  two), the same accounting as the preview actually draws. The
     *  collision box follows the sample, not the player's live effects—on
     *  a player with one live effect the box would be two rows tall and
     *  could not grab the last two sample rounds, and with five effects it
     *  would have extra blank space; box and visible sample would not line
     *  up. */
    public static int previewWidth(TextRenderer tr) {
        int iconCol = ICON_SIZE + ICON_GAP;
        int w = 0;
        for (int i = 0; i < PREVIEW_ROWS.length; i++) {
            String[] row = PREVIEW_ROWS[i];
            w = Math.max(w, iconCol + Math.max(
                    tr.getWidth(effectTitle(previewName(i), row[1])),
                    tr.getWidth(row[2])));
        }
        return w + TEXT_SHADOW;
    }

    /** The editor sample height = rounds × rows per round × row height +
     *  the last row's shadow overhang (same accounting as
     *  {@link #previewWidth}). */
    public static int previewHeight(TextRenderer tr) {
        return PREVIEW_ROWS.length * ROWS_PER_EFFECT * tr.fontHeight + TEXT_SHADOW;
    }

    /** The current player's icon-bearing active effects, sorted by ascending
     *  remaining duration (infinite last); no player → empty list. */
    public static List<StatusEffectInstance> currentEffects() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return List.of();
        }
        List<StatusEffectInstance> effects = new ArrayList<>();
        for (StatusEffectInstance e : client.player.getStatusEffects()) {
            if (e.shouldShowIcon()) {
                effects.add(e);
            }
        }
        effects.sort(Comparator.comparingInt(e -> e.isInfinite() ? Integer.MAX_VALUE : e.getDuration()));
        return effects;
    }

    /** Effect name (translated). */
    private static String effectName(StatusEffectInstance e) {
        return Text.translatable(e.getEffectType().value().getTranslationKey()).getString();
    }

    /** Remaining duration: M:SS; infinite: ∞. */
    private static String formatTime(StatusEffectInstance e) {
        if (e.isInfinite()) {
            return "∞";
        }
        long seconds = e.getDuration() / 20L;
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    /**
     * Draws the effect list each frame: reads live effects from the player,
     * ascending duration, lays out icon and text row by row; with no effect
     * the whole block is not rendered. Gating has three layers: the editor
     * IS_OPEN short-circuit + {@link GlobalConfig.Hud#statusEffectsOn()}
     * (master switch + placed + this element's visibility) + isInZombies.
     * The isInZombies check here duplicates the base class's
     * {@code shouldRenderHud()} but is harmless.
     */
    @Override
    public void onRender(DrawContext context) {
        if (HUDEditor.IS_OPEN) return;
        if (!GlobalConfig.Hud.statusEffectsOn()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || !PlayerUtils.isInZombies()) {
            return;
        }

        // Read live from the client each frame: ascending remaining duration
        // (infinite last), only icon-bearing effects.
        List<StatusEffectInstance> effects = new ArrayList<>();
        for (StatusEffectInstance e : client.player.getStatusEffects()) {
            if (e.shouldShowIcon()) {
                effects.add(e);
            }
        }
        if (effects.isEmpty()) {
            return;
        }
        effects.sort(Comparator.comparingInt(e -> e.isInfinite() ? Integer.MAX_VALUE : e.getDuration()));

        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        float scale = (float) GlobalConfig.Hud.SCALE_STATUS_EFFECTS.getDoubleValue();
        // Content size measured from this frame's one effect table: right-hug
        // won't be pushed off screen with a long effect name or many rounds.
        int hudW = visibleSize(effectsWidth(textRenderer, effects), scale);
        int hudH = visibleSize(effects.size() * ROWS_PER_EFFECT * textRenderer.fontHeight
                + TEXT_SHADOW, scale);
        int absoluteX = anchorPixels(GlobalConfig.getXStatusEffects(), screenWidth, hudW, 0);
        int absoluteY = anchorPixels(GlobalConfig.getYStatusEffects(), screenHeight, hudH, 0);

        drawScaled(context, absoluteX, absoluteY, scale,
                () -> drawEffects(context, client, absoluteX, absoluteY, effects));
    }

    /**
     * The pixel width of a given effect table—the explicit version of
     * {@link #hudWidth}, where the render path already has this frame's
     * table and reuses it rather than fetching again. Each effect takes two
     * rows; take the wider of the name row and the countdown row.
     *
     * @param tr the text renderer
     * @param effects this frame's effect table
     * @return pixel width (shadow overhang included)
     */
    public static int effectsWidth(TextRenderer tr, List<StatusEffectInstance> effects) {
        if (effects.isEmpty()) {
            return previewWidth(tr);
        }
        int iconCol = ICON_SIZE + ICON_GAP;
        int w = 0;
        for (StatusEffectInstance e : effects) {
            String title = effectTitle(effectName(e), romanNumeral(e.getAmplifier() + 1));
            w = Math.max(w, iconCol + Math.max(tr.getWidth(title), tr.getWidth(formatTime(e))));
        }
        return w + TEXT_SHADOW;
    }

    /** Draws two rows per round: "icon name level" on one row, countdown on the next. */
    private void drawEffects(DrawContext context, MinecraftClient client, int x, int y,
                             List<StatusEffectInstance> effects) {
        TextRenderer tr = textRenderer;
        int fh = tr.fontHeight;
        int textX = x + ICON_SIZE + ICON_GAP;
        int row = 0;
        for (StatusEffectInstance effect : effects) {
            int cy = y + fh * row * ROWS_PER_EFFECT;
            RegistryEntry<StatusEffect> entry = effect.getEffectType();

            // Icon: the vanilla mob_effects atlas shrunk to fit in 9px
            // (vanilla's 18px grid is unusable); vertically centered on the
            // two-row block (9px centered = 4px offset), not competing with
            // either row for space.
            Sprite sprite = client.getStatusEffectSpriteManager().getSprite(entry);
            context.drawSpriteStretched(RenderLayer::getGuiTextured, sprite,
                    x, cy + (ROWS_PER_EFFECT * fh - ICON_SIZE) / 2, ICON_SIZE, ICON_SIZE);

            // Remaining duration: M:SS; infinite ∞; ≤3s turns red.
            String time = formatTime(effect);
            int timeColor = COLOR_TIME;
            if (!effect.isInfinite()) {
                long seconds = effect.getDuration() / 20L;
                if (seconds <= EXPIRING_SECONDS) {
                    timeColor = COLOR_EXPIRING;
                }
            }

            // Name and countdown drawn on separate rows: the countdown gets its
            // own line and its room is no longer squeezed by the name's length.
            String title = effectTitle(effectName(effect), romanNumeral(effect.getAmplifier() + 1));
            context.drawTextWithShadow(tr, title, textX, cy, COLOR_NAME);
            context.drawTextWithShadow(tr, time, textX, cy + fh, timeColor);
            row++;
        }
    }

    /** The name-row text "name level": when level is empty (level I) only the
     *  name remains. Real rendering, sample, and width estimation all share
     *  one structure. */
    private static String effectTitle(String name, String level) {
        return level.isEmpty() ? name : name + " " + level;
    }

    /** Level number → Roman numeral (1→I...10→X; 1 is shown as empty, matching
     *  the vanilla tooltip convention). Levels outside the table fall back to
     *  Arabic digits. ASCII letters, best compatibility. */
    private static String romanNumeral(int level) {
        String[] numerals = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return level >= 1 && level < numerals.length ? numerals[level] : String.valueOf(level);
    }
}