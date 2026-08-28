package cn.gsfy.nmz.client.feature.zoom;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.util.PlayerUtils;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeybindMulti;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;

import java.util.List;

/**
 * 平滑缩放（Zoomify 简化版）——缩放就是 FOV 除法，由 {@code ZoomMixin} 注入
 * {@code GameRenderer.getFov} 实现，世界与手持物品一起缩放、十字准星不动。
 *
 * <p>状态机移植自 Zoomify {@code TransitionInterpolator}：t ∈ [0,1] 表示缩放进度。
 * 每 tick 按「1/动画时长」匀速推进线性 {@code prev/current}；渲染帧先插值 t、再套当前
 * 方向的缓动曲线，最后算 divisor：{@code divisor = 1 / lerp(eased(t), 1, 1/zoom)}。
 * 方向中途反转时，用「新曲线 ∘ 原曲线⁻¹」把 prev/current 重投影回新曲线的线性空间
 * （{@code inverse()} 与 {@code apply()} 复合），渲染插值不跳变；缩小自动用所选方式的
 * 相反曲线（{@code GlobalConfig.ZoomEasing#opposite()}，如选缓出 → 放大缓出/缩小缓入）。
 *
 * <p>按键直接读 GLFW 物理键态（{@link KeybindMulti#isKeyDown(int)}，与 modifier 无关，
 * 等同原版 {@code KeyMapping.isDown}）——按住 Shift/Ctrl/WASD 等任意组合键都不干扰缩放：
 *   HOLD   读电平 → 按住放大、松开恢复；
 *   TOGGLE 读自维护边沿（keyDownPrev）→ 点按放大、再点恢复。
 * 不走 malilib 的 isKeybindHeld/isPressed 状态机：其默认 KeybindSettings.DEFAULT 的
 * allowExtraKeys=false 会在组合键下误判为没按下（蹲下 Shift+C 失效的根因）。
 *
 * <p>门控：总开关开 且 在 Zombies 局内（静态谓词，供 mixin 查询）。
 */
public final class ZoomHandler {

    public static final ZoomHandler INSTANCE = new ZoomHandler();

    /** 每 tick 的推进步进基数（20 TPS ≈ 0.05s/帧，对应 Zoomify 的 lastFrameDuration=0.05）。 */
    private static final double TICK_SECONDS = 0.05;

    /** 滚轮热调倍率范围（与配置 initialZoom 一致）与每格增量（±1 倍/格）。 */
    private static final double MIN_ZOOM = 1.0;
    private static final double MAX_ZOOM = 10.0;
    private static final double SCROLL_ZOOM_STEP = 1.0;
    /**
     * 滚轮热调平滑系数（Zoomify SmoothInterpolator 移植）：每 tick 推进量 = diff × SMOOTHNESS / 0.05 × tickDelta。
     * 默认 0.37 → 约 0.2s 收敛大半；值越小越「黏」（惯性大），越大越快到位。
     * 与旧版 ZOOM_SMOOTH_FACTOR=0.4 的固定比例不同，指数衰减在距离大时步进大、距离小时步进小，手感更自然。
     */
    private static final double SCROLL_SMOOTHNESS = 0.37;

    private final IKeybind keybind;

    // 按键状态
    private boolean zooming;
    private boolean keyDownPrev;

    // 滚轮热调：会话级临时倍率（0 = 未覆盖，用配置 INITIAL_ZOOM；>0 = 滚轮临时调整值，不写配置）。
    // 缩放会话完全收回后清零 → 下次激活从配置初始倍率重新开始（Zoomify 式「松开恢复原状」）。
    private double zoomOverride;
    /** 平滑后的当前倍率：每 tick 朝目标倍率（effectiveZoom）指数衰减逼近。
     * 滚轮热调时 target 每格 ±1 离散变化，若直接套用 divisor 会瞬间跳变（卡顿感）；
     * 平滑后 divisor 连续过渡，滚轮缩放如拖动滑条般顺滑。 */
    private double currentZoom = 1.0;
    /** 上一 tick 的 currentZoom，用于渲染帧子 tick 插值（与动画 prev/current 同理，消除 20Hz 阶梯感）。 */
    private double prevCurrentZoom = 1.0;

    // 动画状态（Zoomify TransitionInterpolator 移植）
    private double prev;
    private double current;
    private double prevTarget;
    private boolean justSwappedTransition;
    private GlobalConfig.ZoomEasing activeTransition;
    private GlobalConfig.ZoomEasing inactiveTransition;

    private ZoomHandler() {
        this.keybind = GlobalConfig.Zoom.ZOOM_KEY.getKeybind();
        // 首个 render 帧可能早于首次 tick 的安全兜底（tick 每帧都会按配置重新解析）
        this.activeTransition = GlobalConfig.ZoomEasing.EASE_OUT_EXP;
        this.inactiveTransition = GlobalConfig.ZoomEasing.EASE_IN_EXP;
    }

    /** 注册客户端 tick 事件（NoMoreZombiesClient 每次进游戏时调一次）。 */
    public void init() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    /** 当前是否生效：总开关开 且 在 Zombies 局内。 */
    public static boolean isActive() {
        return GlobalConfig.QoL.ZOOM_ENABLED.getBooleanValue()
                && PlayerUtils.isInZombies();
    }

    /** 当前生效倍率：滚轮热调临时值优先，否则用配置初始倍率（滚轮不写配置）。 */
    private double effectiveZoom() {
        if (this.zoomOverride > 0.0) {
            return MathHelper.clamp(this.zoomOverride, MIN_ZOOM, MAX_ZOOM);
        }
        return MathHelper.clamp(GlobalConfig.Zoom.INITIAL_ZOOM.getDoubleValue(), MIN_ZOOM, MAX_ZOOM);
    }

    /** 渲染帧的 FOV 除数（&gt;1 时放大）。纯读方法，绝不在里面写状态。 */
    public float getZoomDivisor(float partialTicks) {
        // 子 tick 插值：动画通道（prev/current）和滚轮通道（prevCurrentZoom/currentZoom）都做 lerp，
        // 消除 20Hz tick 率带来的阶梯感，与 Zoomify 双通道子 tick 插值一致。
        double zoom = Math.max(MathHelper.lerp(partialTicks, this.prevCurrentZoom, this.currentZoom), 1.0);
        // 先插值线性 t，再套当前方向缓动曲线，最后取倒数（先插值再算 divisor，避免非线性变加速）
        double t = MathHelper.clamp(MathHelper.lerp(partialTicks, this.prev, this.current), 0.0, 1.0);
        double tEased = this.activeTransition.apply(t);
        double mult = MathHelper.lerp(tEased, 1.0, 1.0 / zoom);
        return (float) (1.0 / mult);
    }

    /** 当前是否处于缩放激活：滚轮 mixin 靠它判断要不要吞掉滚轮事件。 */
    public boolean isZooming() {
        return this.zooming;
    }

    /**
     * 鼠标滚轮热调倍率（Zoomify 式会话级，不写配置）。缩放激活（zooming）期间每格 ±SCROLL_ZOOM_STEP 倍率，
     * 范围 [MIN_ZOOM, MAX_ZOOM]；返回 true 表示滚轮被缩放占用了（调用方应消费事件，别执行原版切热键栏）。
     * 未激活/未缩放返回 false（滚轮照常切热键栏）。
     */
    public boolean onMouseScroll(double vertical) {
        if (!isActive() || !this.zooming) {
            return false;
        }
        if (vertical == 0.0) {
            return false;
        }
        double base = effectiveZoom();
        this.zoomOverride = MathHelper.clamp(base + vertical * SCROLL_ZOOM_STEP, MIN_ZOOM, MAX_ZOOM);
        return true;
    }

    /** 当前 tick 的缩放除数（灵敏度换算用，不掺和渲染插值）。
     * partialTicks=1.0 → t 直接取 current（tick 级瞬时值）；Mouse.updateMouse 每 tick 调一次，20Hz 更新够平滑。 */
    public float getCurrentZoomDivisor() {
        return getZoomDivisor(1.0f);
    }

    private void onClientTick(MinecraftClient mc) {
        boolean active = isActive();
        if (!active) {
            // 非激活强制回空闲：离场/关开关不留残留，下次激活从 0 起步、无跳变
            this.zooming = false;
            this.keyDownPrev = false;
            this.prev = 0.0;
            this.current = 0.0;
            this.prevTarget = 0.0;
            this.justSwappedTransition = false;
            this.zoomOverride = 0.0;
            this.currentZoom = 1.0;
            this.prevCurrentZoom = 1.0;
        }
        if (active) {
            GlobalConfig.ZoomKeyBehaviour kb = GlobalConfig.Zoom.KEY_BEHAVIOUR.getOptionListValue()
                    instanceof GlobalConfig.ZoomKeyBehaviour k ? k : GlobalConfig.ZoomKeyBehaviour.HOLD;
            GlobalConfig.ZoomEasing ease = GlobalConfig.Zoom.EASING.getOptionListValue()
                    instanceof GlobalConfig.ZoomEasing e ? e : GlobalConfig.ZoomEasing.EASE_OUT_EXP;
            float timeIn = MathHelper.clamp((float) GlobalConfig.Zoom.ZOOM_IN_TIME.getDoubleValue(), 0.1f, 5.0f);
            float timeOut = MathHelper.clamp((float) GlobalConfig.Zoom.ZOOM_OUT_TIME.getDoubleValue(), 0.1f, 5.0f);

            if (mc.currentScreen != null) {
                // 打开界面（背包/配置）时强制退出缩放；同步键态，防界面关闭瞬间误触 TOGGLE 边沿
                this.zooming = false;
                this.keyDownPrev = isZoomKeyDown();
            } else {
                boolean down = isZoomKeyDown();
                if (kb == GlobalConfig.ZoomKeyBehaviour.HOLD) {
                    // 直接读物理键态（同原版 KeyMapping.isDown）：组合键（Shift/Ctrl/WASD）不干扰
                    this.zooming = down;
                } else if (down && !this.keyDownPrev) {
                    // TOGGLE：自维护边沿检测（keyDownPrev 记录上一 tick 键态）
                    this.zooming = !this.zooming;
                }
                this.keyDownPrev = down;
            }

            tickInterpolation(this.zooming ? 1.0 : 0.0, ease, timeIn, timeOut);

            // 滚轮热调平滑（Zoomify SmoothInterpolator 指数衰减移植）：
            // 每 tick 推进量 = diff × smoothness（距离大步进大、距离小步进小），比固定比例更自然。
            // prevCurrentZoom 在推进前捕获，供渲染帧子 tick 插值用。
            this.prevCurrentZoom = this.currentZoom;
            if (this.zoomOverride > 0.0) {
                double diff = effectiveZoom() - this.currentZoom;
                this.currentZoom += diff * SCROLL_SMOOTHNESS;
            } else {
                this.currentZoom = effectiveZoom();
            }

            // 缩放会话完全收回（未缩放且动画已归零）→ 清滚轮热调并重置平滑倍率，下次激活从初始倍率重新开始。
            // 注意不能在「松开瞬间」就清：缩小动画要按当前热调倍率平滑缩回，清早了会瞬间跳回初始倍率。
            if (!this.zooming && this.current == 0.0) {
                this.zoomOverride = 0.0;
                this.currentZoom = 1.0;
                this.prevCurrentZoom = 1.0;
            }
        }
    }

    /** 缩放键是否被按住：遍历所有绑定键，有一个没按下就算没按住（额外组合键不干扰判定）。 */
    private boolean isZoomKeyDown() {
        List<Integer> keys = this.keybind.getKeys();
        if (keys.isEmpty()) {
            return false;
        }
        for (int keyCode : keys) {
            if (!KeybindMulti.isKeyDown(keyCode)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 推进一帧动画（Zoomify TransitionInterpolator 移植）。
     * 先捕获 prev=current，再按方向选 active 曲线（放大=所选、缩小=其 opposite）；
     * 方向反转且曲线有反函数时，把 current 经 inverse(apply()) 重投影后再线性推进，
     * 最后把捕获的 prev 也一并重投影——渲染插值在方向切换那一瞬不跳变。
     */
    private void tickInterpolation(double target, GlobalConfig.ZoomEasing ease,
                                   float timeIn, float timeOut) {
        GlobalConfig.ZoomEasing transitionIn = ease;
        GlobalConfig.ZoomEasing transitionOut = ease.opposite();

        this.prev = this.current;
        double currentMod = this.current;

        if (target > this.current) {
            this.activeTransition = transitionIn;
            this.inactiveTransition = transitionOut;
            if (this.prevTarget < target && this.activeTransition.hasInverse()) {
                this.justSwappedTransition = true;
                currentMod = this.activeTransition.inverse(this.inactiveTransition.apply(currentMod));
            }
        } else if (target < this.current) {
            this.activeTransition = transitionOut;
            this.inactiveTransition = transitionIn;
            if (this.prevTarget > target && this.activeTransition.hasInverse()) {
                this.justSwappedTransition = true;
                currentMod = this.activeTransition.inverse(this.inactiveTransition.apply(currentMod));
            }
        }
        this.prevTarget = target;

        if (this.activeTransition == GlobalConfig.ZoomEasing.INSTANT) {
            // 瞬间跳变：prev 同步 current，渲染 lerp 无中间帧
            this.current = target;
            this.prev = target;
            return;
        }

        if (target > currentMod) {
            this.current = Math.min(currentMod + TICK_SECONDS / timeIn, target);
        } else if (target < currentMod) {
            this.current = Math.max(currentMod - TICK_SECONDS / timeOut, target);
        } else {
            this.current = target;
        }

        if (this.justSwappedTransition) {
            this.justSwappedTransition = false;
            this.prev = this.activeTransition.inverse(this.inactiveTransition.apply(this.prev));
        }
    }
}
