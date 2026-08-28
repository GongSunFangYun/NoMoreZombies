package cn.gsfy.nmz.client.feature.gamehud;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.data.model.MapId;
import cn.gsfy.nmz.client.util.LanguageUtils;
import cn.gsfy.nmz.client.util.PlayerUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.Arrays;

/**
 * 电击棒（Lightning Rod）4 槽充能冷却 HUD（外星游乐园专属，移植自参考 mod 的
 * LightningRodQueue 模块）——把「哪一格充能好了、哪一格还在冷却、还剩几秒」直接画在屏幕上。
 *
 * <p>信号源是纯客户端的：零发包、零聊天、零记分板，唯一游戏信号是
 * {@link ClientEntityEvents#ENTITY_LOAD} 加载的 {@link EntityType#LIGHTNING_BOLT} 实体——
 * 实体到场即代表「有一格充能被消耗」，本地 {@code long[4] cooldownEndMs} 墙钟数组 +
 * {@link System#currentTimeMillis()} 推进状态，不动服务器一根汗毛。
 *
 * <p>每槽三态：EMPTY（从未充能/已重置，显示 RDY）→ COOLDOWN（蓝色边框 + ceil 秒倒计时 +
 * 进度条）→ READY（绿色 "RDY" + 满条），由 {@code remainingMs = max(0, cooldownEndMs[slot] - now)}
 * 判定。充能分配找第一个已过期（{@code <= now}）的槽写入 {@code now + COOLDOWN_MS}，
 * 4 槽全冷却则静默丢弃（已知限制）；进/出世界与离场 ≥3s 宽限都会重置队列。
 * 电击棒是 AA 专属机制，渲染与跟踪都限定在 AA 局内（其余地图无此信号），
 * 位置/缩放/可见性走 HUD 编辑器（Hud.X/Y/SCALE/VISIBLE_LRQUEUE）。
 */
public class LightningRodQueue extends TotalHUDRenderer {

    private static final int SLOT_COUNT = 4;
    private static final long COOLDOWN_MS = 20_000L;
    private static final long OUTSIDE_RESET_GRACE_MS = 3_000L;

    private static final int TILE_WIDTH = 26;
    private static final int TILE_HEIGHT = 34;
    private static final int TILE_GAP = 3;
    private static final int PROGRESS_HEIGHT = 2;

    // ---- 颜色（ARGB，数值原样移植自参考模块） ----
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

    /** 懒加载图标——ItemStack 依赖已绑定的物品组件，客户端入口初始化阶段静态创建会踩坑（参考红线），所以用到才建。 */
    private ItemStack lightningRodIcon; // 就绪：Blaze Rod
    private ItemStack cooldownIcon;     // 冷却：Gray Dye

    public void init() {
        super.init();

        // 闪电实体加载 = 一格充能被消耗——客户端线程可见，单人与多服走同一条检测路径，信号可靠
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity.getType() == EntityType.LIGHTNING_BOLT) {
                recordLightningStrike();
            }
        });

        // 每 tick 在场保活 / 离场宽限重置——队列的生命周期维护全在这条回调里
        ClientTickEvents.END_CLIENT_TICK.register(client -> onClientTick());

        // 进/出世界都清空队列——新游戏必须从空队列起算，残留的上一局冷却会污染新局
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> resetQueue());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetQueue());
    }

    /** 门控：渲染与跟踪都只在 AA 局内——电击棒是 AA 专属机制，其余地图根本没有闪电实体信号，跟了也白跟。 */
    @Override
    protected boolean shouldRenderHud() {
        return isInAlienArcadium();
    }

    /** 电击棒队列默认锚在屏幕下方聊天区——不压到聊天背景之上就会被挡住，所以走晚渲染层。 */
    @Override
    protected boolean renderAfterChat() {
        return true;
    }

    /** AA 专属检测：Zombies 局内且地图识别为外星游乐园；识别前/区块未加载时 getMap 不是 AA，自然返回 false。 */
    private boolean isInAlienArcadium() {
        return PlayerUtils.isInZombies() && LanguageUtils.getMap() == MapId.ALIEN_ARCADIUM;
    }

    @Override
    public void onRender(DrawContext context) {
        if (cn.gsfy.nmz.client.config.HUDEditor.IS_OPEN) return;
        // AA 图内强制显示：「AA 模式自动启用」是硬性语义，总开关照听，该 HUD 自身的开关
        // 在 AA 图内被绕过。可见性同步由 GameEventBus 按地图驱动，但同步事件可能晚于
        // 首帧渲染才到，这里再兜一道底：同步前先按地图自己判一遍，窗口期不会闪没
        if (!GlobalConfig.QoL.HUD_MASTER.getBooleanValue()
                || (!GlobalConfig.Hud.VISIBLE_LRQUEUE.getBooleanValue()
                    && LanguageUtils.getMap() != MapId.ALIEN_ARCADIUM)) {
            return;
        }
        if (minecraft.player == null || minecraft.world == null) {
            return;
        }

        long now = System.currentTimeMillis();
        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        int totalWidth = SLOT_COUNT * TILE_WIDTH + (SLOT_COUNT - 1) * TILE_GAP;
        int startX = (int) (GlobalConfig.getXLRQueue(screenWidth) * screenWidth);
        int y = (int) (GlobalConfig.getYLRQueue(screenHeight) * screenHeight);
        float scale = (float) GlobalConfig.Hud.SCALE_LRQUEUE.getDoubleValue();

        // 块左锚；缩放以块左上角为锚，与 HUD 编辑器拖动/缩放一致
        drawScaled(context, startX, y, scale, () -> {
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                int x = startX + slot * (TILE_WIDTH + TILE_GAP);
                drawSlot(context, x, y, slot, now);
            }
        });
    }

    /** 闪电实体加载 → 消耗一格充能：找第一个已过期（{@code <= now}）的槽写入 20s 冷却并返回；4 槽全冷却则静默丢弃（已知限制）。 */
    private void recordLightningStrike() {
        if (minecraft.player == null || minecraft.world == null || !isInAlienArcadium()) {
            return;
        }

        long now = System.currentTimeMillis();
        lastZombiesSeenMs = now; // 闪电本身是在场的强证据
        for (int slot = 0; slot < cooldownEndMs.length; slot++) {
            if (cooldownEndMs[slot] <= now) {
                cooldownEndMs[slot] = now + COOLDOWN_MS;
                return;
            }
        }
    }

    /** 每 tick 保活：在 AA 场刷新 lastZombiesSeenMs；离场超过 3s 宽限才重置队列——宽限是防回合切换时误清，能缓解但根除不了全部误判。 */
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

    /** 单槽绘制，从上到下分层：底 → 边框 → 图标 → 冷却遮罩 → 槽号 → 状态文本（ceil 秒 / RDY）→ 底部进度条。 */
    private void drawSlot(DrawContext context, int x, int y, int slot, long now) {
        long remainingMs = Math.max(0L, cooldownEndMs[slot] - now);
        boolean coolingDown = remainingMs > 0L;
        int border = coolingDown ? COOLDOWN_BORDER : READY_BORDER;

        context.fill(x, y, x + TILE_WIDTH, y + TILE_HEIGHT, BACKGROUND);
        drawOutline(context, x, y, TILE_WIDTH, TILE_HEIGHT, border);
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
            status = Long.toString((remainingMs + 999L) / 1_000L); // ceil 秒
            statusColor = COOLDOWN_TEXT;
            progress = Math.max(0.0F, Math.min(1.0F, remainingMs / (float) COOLDOWN_MS));
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

    /** 1px 边框——DrawContext 没有现成的 outline 原语，只能用 4 条 fill 线拼（参考模块同款回退方案）。 */
    private void drawOutline(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x, y, x + width, y + 1, color);
        context.fill(x, y + height - 1, x + width, y + height, color);
        context.fill(x, y, x + 1, y + height, color);
        context.fill(x + width - 1, y, x + width, y + height, color);
    }

    private void resetQueue() {
        Arrays.fill(cooldownEndMs, 0L);
        lastZombiesSeenMs = 0L;
    }

    /** 懒加载图标：冷却用 Gray Dye、就绪用 Blaze Rod，首次用到才 new（见上方字段红线）。 */
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
