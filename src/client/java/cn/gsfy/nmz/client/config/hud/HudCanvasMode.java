package cn.gsfy.nmz.client.config.hud;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

/**
 * How the HUD editor canvas sources its picture--where the shrunken game
 * view in the middle actually comes from
 *
 * <p>The canvas currently uses a static backdrop image, so this enum is
 * consumed only by the retained implementation in {@link HudCanvas}; the
 * editor has no switching UI. All four modes share the same interface
 * (all driven by {@link HudCanvas}), so if live playback is ever
 * reconnected and the GPU path misrenders on some machine, the snapshot
 * path remains a fallback--no code changes, no game restart
 */
public enum HudCanvasMode implements IConfigOptionListEntry {

    /**
     * Blit the main framebuffer into an owned framebuffer and sample that
     * texture: GPU sampling, zero CPU readback, fresh image every frame.
     * Default.
     */
    LIVE_GPU("live_gpu"),
    /**
     * Screenshot + downsample + dynamic texture: one synchronous readback
     * per cycle, expensive but the most reliable, the fallback when the GPU
     * path misbehaves
     */
    LIVE_SNAPSHOT("live_snapshot"),
    /**
     * Capture once when the editor opens, then freeze: cheapest, fits
     * placement-only work where a live backdrop does not matter
     */
    FROZEN("frozen"),
    /** No picture at all, just a dark backdrop--pure layout work, zero overhead */
    OFF("off");

    /** UI dropdown set--a full {@link #values()} snapshot, shared by the GUI and config parsing */
    public static final ImmutableList<HudCanvasMode> VALUES = ImmutableList.copyOf(values());

    private final String configString;

    HudCanvasMode(String configString) {
        this.configString = configString;
    }

    /** MaLiLib serialization value (the lowercase configString) */
    @Override
    public String getStringValue() {
        return this.configString;
    }

    /**
     * Display name: looked up in the current language from translation key
     * {@code nomorezombies.hudeditor.canvas.mode.<configString>}
     */
    @Override
    public String getDisplayName() {
        return StringUtils.translate("nomorezombies.hudeditor.canvas.mode." + this.configString);
    }

    /** Config file string to enum: case-insensitive match; unknown values fall back to {@link #LIVE_GPU} */
    @Override
    public HudCanvasMode fromString(String value) {
        for (HudCanvasMode v : VALUES) {
            if (value.compareToIgnoreCase(v.getStringValue()) == 0) {
                return v;
            }
        }
        return LIVE_GPU;
    }

    /** {@link IConfigOptionListEntry} cycling: forward to the next entry, wrapping at the ends; backward mirrors it */
    @Override
    public IConfigOptionListEntry cycle(boolean forward) {
        int id = this.ordinal();
        if (forward) {
            if (++id >= values().length) {
                id = 0;
            }
        } else {
            if (--id < 0) {
                id = values().length - 1;
            }
        }
        return values()[id];
    }

    /** Same as getStringValue: some MaLiLib paths use toString() as the option value */
    @Override
    public String toString() {
        return this.getStringValue();
    }
}
