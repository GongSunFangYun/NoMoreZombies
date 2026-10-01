package cn.gsfy.nmz.client.features.gamehud;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.data.model.MapId;
import cn.gsfy.nmz.client.utils.LanguageUtils;
import cn.gsfy.nmz.client.utils.PlayerUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.Arrays;
import cn.gsfy.nmz.client.config.hud.HUDEditor;

/**
 * Lightning Rod 4-slot charge cooldown HUD—draws "which slot is ready, which
 * is still cooling, how many seconds left" straight on screen.
 * (Alien Arcadium only; ported from the reference mod's LightningRodQueue
 * module.)
 *
 * <p>The signal source is pure client: zero packets, zero chat, zero
 * scoreboard. The only game signal is the {@link EntityType#LIGHTNING_BOLT}
 * entity loaded through {@link ClientEntityEvents#ENTITY_LOAD}—a bolt
 * arriving means "one charge was consumed". A local
 * {@code long[4] cooldownEndMs} wall-clock array plus
 * {@link System#currentTimeMillis()} advance the state; the server is not
 * touched at all.
 *
 * <p>Each slot has three states: EMPTY (never charged / reset; shows
 * "RDY") → COOLDOWN (blue border + ceil-second countdown + progress bar) →
 * READY (green "RDY" + full bar), decided by
 * {@code remainingMs = max(0, cooldownEndMs[slot]-now)}. Charge allocation
 * picks the first expired slot ({@code <=now}) and writes
 * {@code now + COOLDOWN_MS}; all four slots cooling silently drops the
 * strike (a known limitation). Entering/leaving the world and a
 * ≥3s grace after leaving both reset the queue. The lightning rod is an
 * AA-only mechanic, so rendering and tracking are both limited to an AA
 * game (other maps have no such signal). Position/scale go through the HUD
 * editor; inside an AA game it is force-shown and its independent
 * VISIBLE_LRQUEUE is bypassed, but the HUD_MASTER master switch and "placed
 * on the canvas by the player" cannot be bypassed, composed in
 * {@link GlobalConfig.Hud#lrQueueOn(boolean)}.
 */
public class LightningRodQueue extends TotalHUDRenderer {

    private static final int SLOT_COUNT = 4;
    private static final long COOLDOWN_MS = 20_000L;
    private static final long OUTSIDE_RESET_GRACE_MS = 3_000L;

    private static final int TILE_WIDTH = 26;
    private static final int TILE_HEIGHT = 34;
    private static final int TILE_GAP = 3;
    private static final int PROGRESS_HEIGHT = 2;

    // ----Colors (ARGB, values ported from the reference module as-is)----
    private static final int BACKGROUND = 0xBE0D1117;
    private static final int COOLDOWN_BORDER = 0xFF41A5FF;
    private static final int READY_BORDER = 0xFF46DC78;
    private static final int COOLDOWN_TEXT = 0xFFEBF5FF;
    private static final int READY_TEXT = 0xFF64FF91;
    private static final int SLOT_TEXT = 0xFFAFBECD;
    private static final int COOLDOWN_OVERLAY = 0x9B05080D;
    private static final int COOLDOWN_PROGRESS = 0xFF37B4FF;
    private static final int READY_PROGRESS = 0xFF46DC78;

    private final long[] cooldownEndMs = new long[SLOT_COUNT];
    private long lastZombiesSeenMs;

    /**
     * Lazily loaded icons—ItemStack depends on the bound item component, and
     * creating them statically during client entrypoint init steps on that, so
     * they are built only on first use.
     */
    private ItemStack lightningRodIcon; // Ready: Blaze Rod
    private ItemStack cooldownIcon;     // Cooldown: Gray Dye

    /**
     * Subscribes four kinds of event: a lightning entity load consumes one
     * slot; a per-tick keep-alive / grace reset; entering or leaving the world
     * clears the queue. The lifecycle all closes here.
     */
    public void init() {
        super.init();

        // Lightning entity load = one charge consumed. Client-thread visible,
        // same path in singleplayer and multiplayer; the signal is reliable.
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity.getType() == EntityType.LIGHTNING_BOLT) {
                recordLightningStrike();
            }
        });

        // Per-tick keep-alive / grace reset—the queue's lifecycle maintenance
        // is all in this callback.
        ClientTickEvents.END_CLIENT_TICK.register(client -> onClientTick());

        // Entering or leaving the world clears the queue: a new game must start
        // from an empty queue, or the previous game's leftover cooldowns would
        // pollute the new one.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> resetQueue());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetQueue());
    }

    /**
     * Gate: rendering and tracking are both AA-only—the lightning rod is an
     * AA-only mechanic, and other maps have no lightning entity signal at all,
     * so tracking them would be pointless.
     */
    @Override
    protected boolean shouldRenderHud() {
        return isInAlienArcadium();
    }

    /** The lightning rod queue defaults to anchoring in the screen's lower
     *  chat area—without a late render layer it would be covered by the chat
     *  background, so it goes through the late layer. */
    @Override
    protected boolean renderAfterChat() {
        return true;
    }

    /**
     * AA-only check: in a Zombies game and the map identified as Alien
     * Arcadium. Before identification / before chunks load, getMap is not AA,
     * so this returns false naturally.
     */
    private boolean isInAlienArcadium() {
        return PlayerUtils.isInZombies() && LanguageUtils.getMap() == MapId.ALIEN_ARCADIUM;
    }

    /**
     * Draws the 4-slot lightning rod queue: each slot lays out its icon and
     * progress bar by the three-state scheme (cooling blue border + countdown
     * / ready green border with "RDY" / uncharged).
     */
    @Override
    public void onRender(DrawContext context) {
        if (HUDEditor.IS_OPEN) return;
        // Force-shown inside AA: "AA mode auto-enables" is hard semantics, so
        // this HUD's independent visibility switch is bypassed in AA—but the
        // master switch and "placed on the canvas by the player" cannot be
        // bypassed, composed into one predicate (see GlobalConfig.Hud#lrQueueOn).
        if (!GlobalConfig.Hud.lrQueueOn(LanguageUtils.getMap() == MapId.ALIEN_ARCADIUM)) {
            return;
        }
        if (minecraft.player == null || minecraft.world == null) {
            return;
        }

        long now = System.currentTimeMillis();
        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        float scale = (float) GlobalConfig.Hud.SCALE_LRQUEUE.getDoubleValue();
        // The geometry is constant (4 slots x 26px + 3px gaps, 34px tall), used
        // directly as the content size.
        int slotW = visibleSize(hudWidth(), scale);
        int slotH = visibleSize(hudHeight(), scale);
        // Reserve 0: the hotbar only occupies the screen's lower middle, and
        // reserving the whole edge would keep this queue out of the bottom
        // corners; whether it overlaps the hotbar is left to the player's eyes.
        int startX = anchorPixels(GlobalConfig.getXLRQueue(), screenWidth, slotW, 0);
        int y = anchorPixels(GlobalConfig.getYLRQueue(), screenHeight, slotH, 0);

        // Block left anchor; scaling anchors on the block's top-left corner,
        // matching the HUD editor's drag/zoom.
        drawScaled(context, startX, y, scale, () -> {
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                int x = startX + slot * (TILE_WIDTH + TILE_GAP);
                drawSlot(context, x, y, slot, now);
            }
        });
    }

    /**
     * A lightning entity load consumes one charge: finds the first expired
     * slot ({@code <=now}), writes a 20s cooldown and returns. All four slots
     * cooling silently drops the strike (a known limitation).
     */
    private void recordLightningStrike() {
        if (minecraft.player == null || minecraft.world == null || !isInAlienArcadium()) {
            return;
        }

        long now = System.currentTimeMillis();
        lastZombiesSeenMs = now; // The bolt itself is strong evidence of being in-game.
        for (int slot = 0; slot < cooldownEndMs.length; slot++) {
            if (cooldownEndMs[slot] <= now) {
                cooldownEndMs[slot] = now + COOLDOWN_MS;
                return;
            }
        }
    }

    /**
     * Per-tick keep-alive: refresh lastZombiesSeenMs while inside AA; only
     * reset the queue after the 3s grace window has passed—the grace prevents
     * a spurious clear on a round transition. It mitigates but cannot root out
     * every false positive.
     */
    private void onClientTick() {
        if (minecraft.player == null || minecraft.world == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (isInAlienArcadium()) {
            lastZombiesSeenMs = now;
            return;
        }
        if (lastZombiesSeenMs != 0L && now - lastZombiesSeenMs >= OUTSIDE_RESET_GRACE_MS) {
            resetQueue();
        }
    }

    /**
     * Component pixel width: 4 slots x 26px + 3 x 3px gaps = 113px. The
     * default center-hug resolution ({@code GlobalConfig.getXLRQueue}) and
     * the editor preview both go through this, so neither side writes its own
     * 113.
     */
    public static int hudWidth() {
        return SLOT_COUNT * TILE_WIDTH + (SLOT_COUNT - 1) * TILE_GAP;
    }

    /** Component pixel height: one tile's height (slot text/icon both fit
     *  within 34px, no overhang). */
    public static int hudHeight() {
        return TILE_HEIGHT;
    }

    /**
     * Draws one slot, layered top to bottom: background → border → icon →
     * cooldown overlay → slot number → status text (ceil seconds / RDY) →
     * bottom progress bar.
     */
    private void drawSlot(DrawContext context, int x, int y, int slot, long now) {
        long remainingMs = Math.max(0L, cooldownEndMs[slot] - now);
        boolean coolingDown = remainingMs > 0L;
        int border = coolingDown ? COOLDOWN_BORDER : READY_BORDER;

        context.fill(x, y, x + TILE_WIDTH, y + TILE_HEIGHT, BACKGROUND);
        drawOutline(context, x, y, border);
        context.drawItem(getSlotIcon(coolingDown), x + (TILE_WIDTH - 16) / 2, y + 2);

        if (coolingDown) {
            context.fill(x + 4, y + 2, x + TILE_WIDTH - 4, y + 20, COOLDOWN_OVERLAY);
        }

        String slotLabel = Integer.toString(slot + 1);
        context.drawTextWithShadow(textRenderer, slotLabel, x + 2, y + 2, SLOT_TEXT);

        String status;
        int statusColor;
        float progress;
        if (coolingDown) {
            status = Long.toString((remainingMs + 999L) / 1_000L); // ceil seconds = (ms+999)/1000
            statusColor = COOLDOWN_TEXT;
            progress = Math.clamp(remainingMs / (float) COOLDOWN_MS, 0.0F, 1.0F);
        } else {
            status = Text.translatable("nomorezombies.lrqueue.ready").getString();
            statusColor = READY_TEXT;
            progress = 1.0F;
        }

        int textX = x + (TILE_WIDTH - textRenderer.getWidth(status)) / 2;
        context.drawTextWithShadow(textRenderer, status, textX, y + 21, statusColor);

        int innerWidth = TILE_WIDTH - 2;
        int progressWidth = Math.round(innerWidth * progress);
        context.fill(x + 1, y + TILE_HEIGHT - PROGRESS_HEIGHT - 1,
                x + 1 + progressWidth, y + TILE_HEIGHT - 1,
                coolingDown ? COOLDOWN_PROGRESS : READY_PROGRESS);
    }

    /**
     * 1px border—DrawContext has no ready-made outline primitive, so four
     * fill lines are assembled by hand (the same fallback the reference
     * module uses).
     *
     * <p>Width and height come straight from this class's tile constants:
     * all four slots are the same size, and collapsing them into two "always
     * the same value" parameters would just make call sites write
     * {@code TILE_*} twice.
     */
    private void drawOutline(DrawContext context, int x, int y, int color) {
        context.fill(x, y, x + TILE_WIDTH, y + 1, color);
        context.fill(x, y + TILE_HEIGHT - 1, x + TILE_WIDTH, y + TILE_HEIGHT, color);
        context.fill(x, y, x + 1, y + TILE_HEIGHT, color);
        context.fill(x + TILE_WIDTH - 1, y, x + TILE_WIDTH, y + TILE_HEIGHT, color);
    }

    /** Clears all four slots' cooldowns and the in-game timestamp: entering
     *  or leaving the world and the 3s grace after leaving all call it, so a
     *  new game starts from an empty queue. */
    private void resetQueue() {
        Arrays.fill(cooldownEndMs, 0L);
        lastZombiesSeenMs = 0L;
    }

    /** Lazily loaded icons: Gray Dye while cooling, Blaze Rod when ready.
     *  Built on first use (see the field comment above). */
    private ItemStack getSlotIcon(boolean coolingDown) {
        if (coolingDown) {
            if (cooldownIcon == null) {
                cooldownIcon = new ItemStack(Items.GRAY_DYE);
            }
            return cooldownIcon;
        }
        if (lightningRodIcon == null) {
            lightningRodIcon = new ItemStack(Items.BLAZE_ROD);
        }
        return lightningRodIcon;
    }
}