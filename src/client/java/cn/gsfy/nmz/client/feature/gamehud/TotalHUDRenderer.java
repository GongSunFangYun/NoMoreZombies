package cn.gsfy.nmz.client.feature.gamehud;

import cn.gsfy.nmz.client.util.PlayerUtils;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD 渲染基类——所有屏幕 HUD（波次 / 道具 / CPS / 电击棒队列等）的公共钩子与统一接入点，
 * 子类只需实现 {@link #onRender(DrawContext)} 画内容，其余注册、门控、异常都在这收口。
 *
 * <p>渲染按 {@link #renderAfterChat()} 归入两条通道：常规层（{@code ALL}）经
 * {@link HudRenderCallback#EVENT} 挂进 Fabric 每帧回调，画在聊天/其他覆盖层之下，
 * 适合画面中上部的 HUD；晚渲染层（{@code LATE}）不占每帧回调，由外部在聊天（含背景）
 * 画完后显式调 {@link #renderLateHud} 驱动，结果压在聊天背景之上，适合锚在下方聊天区的 HUD
 * （电击棒队列就覆写 {@link #renderAfterChat()} 返回 true）。
 *
 * <p>门控是三道闸叠出来的：实例开关 {@link #shouldRender}（外部统一驱动）、环境门控
 * {@link #shouldRenderHud()}（默认仅 Zombies 局内）、各子类自己的场景限制。入口捕获所有异常，
 * 单帧渲染失败只跳过自己，不拖累其余 HUD。每次渲染前重新取 {@link MinecraftClient} 与
 * {@link TextRenderer}——初始化阶段 textRenderer 尚未就绪，只在渲染时取才稳妥；
 * 子类在 onInitializeClient 之后调 {@link #init()} 注册接入。
 */
public abstract class TotalHUDRenderer {

    /** 常规层——经 {@link HudRenderCallback#EVENT} 注册、随每帧回调渲染的 HUD（波次 / 道具 / CPS 等）。 */
    private static final List<TotalHUDRenderer> ALL = new ArrayList<>();
    /** 晚渲染层——需压在聊天（含其背景）之上、由 {@link #renderLateHud} 显式驱动的 HUD（如电击棒队列）。 */
    private static final List<TotalHUDRenderer> LATE = new ArrayList<>();

    /** 全局显隐开关：由外部（如 {@link #setShouldRender}）统一驱动，false 时直接跳过渲染。 */
    public boolean shouldRender;
    protected MinecraftClient minecraft;
    protected TextRenderer textRenderer;

    /** 把本渲染器注册进对应通道并建立渲染入口。须在客户端初始化阶段调用一次，子类覆写时要记得调 super。 */
    public void init() {
        minecraft = MinecraftClient.getInstance();
        shouldRender = false;
        if (renderAfterChat()) {
            // 晚渲染层：不进 Fabric 每帧回调，由 renderLateHud 在聊天背景画完之后显式驱动
            LATE.add(this);
        } else {
            ALL.add(this);
            // 常规层：挂到每帧渲染回调，每次渲染都取最新实例——初始化阶段 textRenderer 还没就绪，只有渲染时才取才稳
            HudRenderCallback.EVENT.register((context, tickCounter) -> {
                this.minecraft = MinecraftClient.getInstance();
                this.textRenderer = this.minecraft.textRenderer;
                if (this.textRenderer == null) {
                    return;
                }
                try {
                    if (shouldRenderHud()) {
                        onRender(context);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }

    /** 单帧绘制入口：子类在这画具体内容；能走到这，说明 {@link #shouldRenderHud()} 那道闸已通过。 */
    public abstract void onRender(DrawContext context);

    /** 门控：本 HUD 现在能不能渲染。默认只在 Zombies 局内；子类覆写可叠加更严格的场景限制。 */
    protected boolean shouldRenderHud() {
        return PlayerUtils.isInZombies();
    }

    /** 要不要走晚渲染层（画在聊天背景之上）。默认否；锚在聊天区的 HUD（如电击棒队列）覆写返回 true。 */
    protected boolean renderAfterChat() {
        return false;
    }

    /** 批量驱动常规层：一次性给所有常规 HUD 设 {@link #shouldRender}，常用于全局显隐同步。 */
    public static void setShouldRender(boolean flag) {
        for (TotalHUDRenderer renderer : ALL) {
            renderer.shouldRender = flag;
        }
    }

    /** 晚渲染入口：由外部在聊天层画完后调用，逐帧驱动所有 renderAfterChat()==true 的 HUD——晚走一步，才不被聊天背景盖住。 */
    public static void renderLateHud(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        for (TotalHUDRenderer renderer : LATE) {
            renderer.minecraft = mc;
            renderer.textRenderer = mc.textRenderer;
            if (renderer.textRenderer == null) {
                continue;
            }
            try {
                if (renderer.shouldRenderHud()) {
                    renderer.onRender(context);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 以 (x,y) 为锚点、按 scale 倍数绘制，配合 HUD 编辑器的可移动 / 缩放。
     * scale≤0 一律当 1 处理（原尺寸）；|scale-1| 极小则跳过矩阵变换直接画，省一次无谓压栈。
     * 缩放用的是「先平移、再缩放、再平移回原锚点」的套路，锚点在缩放前后纹丝不动。
     *
     * @param context 绘制上下文
     * @param x       HUD 左上角锚点 X 坐标
     * @param y       HUD 左上角锚点 Y 坐标
     * @param scale   缩放倍数（≤0 或≈1 时按原尺寸处理）
     * @param draw    实际的绘制操作
     */
    public static void drawScaled(DrawContext context, int x, int y, float scale, Runnable draw) {
        if (scale <= 0) {
            scale = 1.0f;
        }
        if (Math.abs(scale - 1.0f) < 0.001f) {
            draw.run();
            return;
        }
        context.getMatrices().push();
        context.getMatrices().translate(x, y, 0);
        context.getMatrices().scale(scale, scale, 1.0f);
        context.getMatrices().translate(-x, -y, 0);
        draw.run();
        context.getMatrices().pop();
    }
}
