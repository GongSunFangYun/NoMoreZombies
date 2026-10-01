package cn.gsfy.nmz.client.config.hud;

import cn.gsfy.nmz.client.data.model.MapId;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The full description of one editable HUD--what the registry
 * ({@link RegisterHUD}) collects
 *
 * <p>Adding a HUD only means one more {@code register(...)} call in
 * {@link RegisterHUD#registerBuiltins()}: the editor, component library,
 * config panel, and canvas all pick it up automatically. All read/write
 * access is centralized in this class instead of scattered through the
 * {@link HUDEditor} constructor, so adding a HUD touches none of the
 * editor, the config UI, or the previews
 *
 * <p><b>Not this class's job</b>: it holds no workspace state (the
 * position / scale / visibility being dragged are the editor's temporaries,
 * written back only on save), and it does no live rendering (each
 * renderer's own {@code onRender} stays untouched). It is only a
 * declaration of which accessors one HUD has, where sizes come from, and
 * how the preview draws
 *
 * <p><b>Coordinate semantics</b>: position is an <b>anchor ratio</b>
 * {@code 0.0-1.0}--{@code 0.0} = leading edge (left/top), {@code 0.5} =
 * centered, {@code 1.0} = trailing edge (right/bottom); in-between values
 * walk the matching percentage of the available travel; scale is 0.5-2.0
 *
 * <p>Travel is determined by {@link #reserveX}/{@link #reserveY} together
 * with screen size and content size; see
 * {@code TotalHUDRenderer#anchorPixels}. There is no "never dragged"
 * sentinel: the three levels themselves are the factory defaults, and any
 * value lands at the same relative position at any resolution
 */
public final class HudEntry {

    /**
     * Context for preview factories--when a preview needs the state of
     * <b>other HUDs on the editor canvas</b> (the scoreboard preview uses
     * it to decide live whether to absorb sidebar rows for team stats and
     * time), it must read through this, not from config
     */
    public interface PreviewContext {
        /**
         * Whether a HUD is actually on duty in the <b>editor canvas</b>--
         * on the canvas <b>and</b> enabled
         *
         * <p>The test is "on the canvas and enabled", not "drawn in game"--
         * the latter is {@code VISIBLE_*}'s business. The canvas is a
         * <b>self-consistent picture</b>: it shows what I placed on it, not
         * what my config has switched on. A HUD removed from the canvas
         * does not exist in that picture, so its partner (the scoreboard)
         * must compose as if it were absent. If this read visibility and
         * ignored placement, dragging team stats back into the library
         * would leave the scoreboard still slimmed as if it were there--
         * a composition that contradicts the canvas content.
         *
         * <p>It serves exactly one consumer (the scoreboard preview);
         * in-game rendering goes through
         * {@code GlobalConfig.Hud.teamStatsOn()}/{@code gameTimeOn()},
         * and the two paths deliberately <b>do not share</b> this
         * accessor--"placed" means different things on each: live gating
         * wants "the player really put it on the canvas and left it on"
         * (see the predicate group in {@code GlobalConfig.Hud}), while this
         * asks "is it on duty on the editor canvas right now"
         *
         * @param id the target HUD's {@link #id}
         * @return whether that HUD is currently on the canvas and enabled;
         * false for unknown ids
         */
        boolean workspaceActive(String id);
    }

    /** Preview factory: builds one preview from the context (size measured by the renderer, see {@link HudPreview}) */
    public interface PreviewFactory {
        /**
         * Builds the preview
         *
         * @param ctx preview context
         * @return the preview
         */
        HudPreview create(PreviewContext ctx);
    }

    /** Stable identifier: used for config key prefixes, logs, and drag-drop markers--<b>do not change it</b> (changing it means a different HUD) */
    public final String id;
    /** Translation key of the display name */
    public final String nameKey;

    /** X anchor read side (anchor ratio) */
    public final DoubleSupplier getX;
    /** X anchor write side (anchor ratio) */
    public final DoubleConsumer setX;
    /** Y anchor read side (anchor ratio) */
    public final DoubleSupplier getY;
    /** Y anchor write side (anchor ratio) */
    public final DoubleConsumer setY;
    /** Scale read side (0.5-2.0) */
    public final DoubleSupplier getScale;
    /** Scale write side (0.5-2.0) */
    public final DoubleConsumer setScale;
    /** In-game visibility read side ({@code VISIBLE_*}) */
    public final BooleanSupplier getVisible;
    /** In-game visibility write side */
    public final Consumer<Boolean> setVisible;
    /**
     * Editor "is on the canvas" read side ({@code PLACED_*})
     *
     * <p>Two independent states, persisted separately from
     * {@link #getVisible}: "visible" governs whether the HUD draws in game
     * (its life or death), "placed" governs whether it sits on the editor
     * canvas (whether it occupies my workspace). So "not placed + visible
     * in game" is a legal and useful combination--that HUD is alive in my
     * game but does not take up my canvas
     */
    public final BooleanSupplier getPlaced;
    /** Editor "is on the canvas" write side */
    public final Consumer<Boolean> setPlaced;

    /** Preview factory */
    public final PreviewFactory preview;
    /**
     * This HUD's own <b>extra config widgets</b> (nullable)--the config
     * panel stacks them vertically below the "show / remove from canvas"
     * buttons and the scale slider, and grows to fit them
     *
     * <p>A supplier rather than a widget list: these widgets are
     * <b>stateful</b> vanilla widgets (buttons carry hover / focus, sliders
     * carry drag state) and must be held and reused by the editor; this
     * only declares which ones the HUD needs--when to read and how to lay
     * them out is the editor's call. No HUD uses it today--it exists so
     * that "this HUD has its own switches" never forces a change to the
     * editor's layout code (same extension goal as {@link RegisterHUD})
     */
    public final Supplier<List<ClickableWidget>> extraConfigWidgets;
    /**
     * Availability predicate (null = usable on any map); only the two AA
     * entries use it today. From it the editor adds an orange note line to
     * the tooltip instead of graying the entry out
     */
    public final Predicate<MapId> availableIn;
    /**
     * Whether this HUD <b>brings its own background</b> (default false)
     *
     * <p>The editor canvas backs every preview with a 25% opaque black
     * layer, so the HUD's content separates at a glance from the canvas,
     * which is not part of the HUD (a pure editor visual layer: never
     * persisted, no effect in game). The scoreboard is the one exception--
     * it already paints its two background segments itself via
     * {@code getTextBackgroundColor}, and a second black layer would only
     * muddy its own colors
     *
     * <p>The declaration lives here rather than in an editor-side
     * {@code id.equals("scoreboard")} check: the editor <b>knows no
     * concrete HUD</b>, and branching on id would punch a hole in that
     * contract. With the declaration, "back me or not" travels with the
     * HUD, and a future self-backgrounded HUD only adds one word on its
     * own registration line
     */
    public final boolean ownBackground;

    /**
     * Live reserve declaration--<b>X axis</b> (virtual screen pixels,
     * default 0)
     *
     * <p>Anchor ratio {@code 1.0} lands at
     * {@code screenWidth - contentWidth - reserve}, {@code 0.0} lands at
     * {@code reserve}. So the reserve is
     * "how many pixels from the screen edge when pinned to that edge",
     * a constant independent of resolution
     *
     * <p>A non-zero reserve is for HUDs that must dodge vanilla HUD space
     * or align with a native edge; only the scoreboard uses one today
     * (1px to align pixel-for-pixel with the native right edge). Every
     * other HUD reserves 0 and touches the screen edge exactly on both ends
     */
    public final int reserveX;
    /** Live reserve declaration--<b>Y axis</b> (virtual screen pixels, default 0); same semantics as {@link #reserveX} */
    public final int reserveY;

    private HudEntry(Builder b) {
        this.id = b.id;
        this.nameKey = b.nameKey;
        this.getX = b.getX;
        this.setX = b.setX;
        this.getY = b.getY;
        this.setY = b.setY;
        this.getScale = b.getScale;
        this.setScale = b.setScale;
        this.getVisible = b.getVisible;
        this.setVisible = b.setVisible;
        this.getPlaced = b.getPlaced;
        this.setPlaced = b.setPlaced;
        this.reserveX = b.reserveX;
        this.reserveY = b.reserveY;
        this.preview = b.preview;
        this.ownBackground = b.ownBackground;
        this.extraConfigWidgets = b.extraConfigWidgets;
        this.availableIn = b.availableIn;
    }

    /**
     * How many rows of extra config this HUD needs--the config panel grows by it
     *
     * @return number of extra widgets; 0 when unconfigured
     */
    public int extraConfigRowCount() {
        return this.extraWidgets().size();
    }

    /**
     * The extra config widgets (empty list when unconfigured)--the supplier
     * is queried fresh on every call; the caller holds and reuses the
     * widgets
     *
     * @return the extra widgets; empty when unconfigured or when the
     * supplier returns {@code null}
     */
    public List<ClickableWidget> extraWidgets() {
        if (this.extraConfigWidgets == null) {
            return List.of();
        }
        List<ClickableWidget> widgets = this.extraConfigWidgets.get();
        return widgets == null ? List.of() : widgets;
    }

    /** Display name (via the translation key, follows the client language) */
    public String name() {
        return Text.translatable(this.nameKey).getString();
    }

    /**
     * Whether this HUD is usable on the current map--no {@link #availableIn} means always usable
     *
     * @param map the currently detected map (may be {@link MapId#NULL})
     * @return whether it is usable
     */
    public boolean isAvailable(MapId map) {
        return this.availableIn == null || this.availableIn.test(map);
    }

    /**
     * Whether this HUD is <b>map-restricted</b> (true only when
     * {@link #availableIn} is non-null)
     *
     * <p>The difference from {@link #isAvailable(MapId)} is the question
     * asked: that one asks "usable on the current map", this one asks "is
     * this a HUD that only works on specific maps". The editor tooltip
     * wants the latter--a player hovering the lightning rod queue while
     * inside AA sees {@code isAvailable} true, yet "AA only" is exactly the
     * line worth saying at that moment; judging by the former would drop
     * the note right when it matters most
     *
     * <p>{@link #availableIn} is an opaque predicate with no map name
     * available, so the tooltip text hardcodes the currently single
     * restricted map (Alien Arcadium). If a second restricted map ever
     * appears, that key must switch to a placeholder form
     *
     * @return whether the HUD is map-restricted
     */
    public boolean isMapRestricted() {
        return this.availableIn != null;
    }

    /**
     * Opens a builder--each HUD in the registry reads as a declaration of
     * one to ten lines
     *
     * @param id stable identifier (lowercase, no spaces, same source as the
     * {@code GlobalConfig.Hud} key names)
     * @param nameKey display name translation key
     * @return the builder
     */
    public static Builder of(String id, String nameKey) {
        return new Builder(id, nameKey);
    }

    /** Builder for {@link HudEntry}--turns a dozen positional parameters into readable named calls */
    public static final class Builder {

        private final String id;
        private final String nameKey;
        private DoubleSupplier getX, getY, getScale;
        private DoubleConsumer setX, setY, setScale;
        private BooleanSupplier getVisible;
        private Consumer<Boolean> setVisible;
        private BooleanSupplier getPlaced;
        private Consumer<Boolean> setPlaced;
        private int reserveX;
        private int reserveY;
        private PreviewFactory preview;
        private Supplier<List<ClickableWidget>> extraConfigWidgets;
        private Predicate<MapId> availableIn;
        private boolean ownBackground;

        private Builder(String id, String nameKey) {
            this.id = id;
            this.nameKey = nameKey;
        }

        /**
         * Binds the coordinate accessors
         *
         * @param gx X read side
         * @param sx X write side
         * @param gy Y read side
         * @param sy Y write side
         * @return this builder
         */
        public Builder position(DoubleSupplier gx, DoubleConsumer sx, DoubleSupplier gy, DoubleConsumer sy) {
            this.getX = gx;
            this.setX = sx;
            this.getY = gy;
            this.setY = sy;
            return this;
        }

        /**
         * Binds the scale accessors
         *
         * @param gs scale read side
         * @param ss scale write side
         * @return this builder
         */
        public Builder scale(DoubleSupplier gs, DoubleConsumer ss) {
            this.getScale = gs;
            this.setScale = ss;
            return this;
        }

        /**
         * Binds the independent visibility accessors (drawn in game or not,
         * persisted as {@code VISIBLE_*})
         *
         * @param gv visibility read side
         * @param sv visibility write side
         * @return this builder
         */
        public Builder visible(BooleanSupplier gv, Consumer<Boolean> sv) {
            this.getVisible = gv;
            this.setVisible = sv;
            return this;
        }

        /**
         * Binds the "is on the editor canvas" accessors (persisted as
         * {@code PLACED_*})--independent of
         * {@link #visible(BooleanSupplier, Consumer)}
         *
         * @param gp placed read side
         * @param sp placed write side
         * @return this builder
         */
        public Builder placed(BooleanSupplier gp, Consumer<Boolean> sp) {
            this.getPlaced = gp;
            this.setPlaced = sp;
            return this;
        }

        /**
         * Declares that this HUD brings its own background (optional)--the
         * editor canvas no longer backs it with the 25% black layer; see
         * {@link HudEntry#ownBackground}
         *
         * @return this builder
         */
        public Builder ownBackground() {
            this.ownBackground = true;
            return this;
        }

        /**
         * Binds this HUD's own extra config widgets (optional)--the config
         * panel grows by the returned count and stacks them vertically
         *
         * @param widgets supplier of the extra widgets ({@code null} counts
         * as none)
         * @return this builder
         */
        public Builder extraConfigWidgets(Supplier<List<ClickableWidget>> widgets) {
            this.extraConfigWidgets = widgets;
            return this;
        }

        /**
         * Declares the live reserve (optional, default 0/0)--"how many
         * pixels from the screen edge when pinned to it"
         *
         * <p>Only the <b>two ends</b> move: {@code ratio=1} lands at
         * {@code screenWidth - contentWidth - reserveX}, {@code ratio=0}
         * lands at {@code reserveX}; in-between values interpolate linearly
         * across the travel, so the reserve also shrinks the placeable
         * range--that is intended: a reserve says "this edge is off
         * limits", not "just shift the origin"
         *
         * <p>Without a call, both ends touch the screen edge exactly; only
         * HUDs that must dodge vanilla HUD space or align with a native
         * edge need one (today only the scoreboard: 1px to align
         * pixel-for-pixel with the native right edge)
         *
         * @param reserveX X reserve (virtual screen pixels, negatives are
         * treated as 0)
         * @param reserveY Y reserve (virtual screen pixels, negatives are
         * treated as 0)
         * @return this builder
         */
        public Builder reserve(int reserveX, int reserveY) {
            this.reserveX = Math.max(0, reserveX);
            this.reserveY = Math.max(0, reserveY);
            return this;
        }

        /**
         * Binds the preview factory
         *
         * @param factory preview factory
         * @return this builder
         */
        public Builder preview(PreviewFactory factory) {
            this.preview = factory;
            return this;
        }

        /**
         * Restricts the usable maps (optional)
         *
         * @param predicate availability test
         * @return this builder
         */
        public Builder availableIn(Predicate<MapId> predicate) {
            this.availableIn = predicate;
            return this;
        }

        /**
         * Finalizes the entry
         *
         * @return the immutable HUD description
         * @throws IllegalStateException when a required accessor is missing
         *  (position / scale / visible / placed / preview)
         */
        public HudEntry build() {
            if (this.getX == null || this.setX == null || this.getY == null || this.setY == null) {
                throw new IllegalStateException("HudEntry[" + this.id + "] 缺少坐标读写口");
            }
            if (this.getScale == null || this.setScale == null) {
                throw new IllegalStateException("HudEntry[" + this.id + "] 缺少缩放读写口");
            }
            if (this.getVisible == null || this.setVisible == null) {
                throw new IllegalStateException("HudEntry[" + this.id + "] 缺少显隐读写口");
            }
            if (this.getPlaced == null || this.setPlaced == null) {
                throw new IllegalStateException("HudEntry[" + this.id + "] 缺少放置读写口");
            }
            if (this.preview == null) {
                throw new IllegalStateException("HudEntry[" + this.id + "] 缺少样张工厂");
            }
            return new HudEntry(this);
        }
    }
}
