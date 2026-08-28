package cn.gsfy.nmz.client.feature.freecam;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.util.PlayerUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.entity.Entity;

/**
 * 自由视角（Free Camera）处理器——仿 {@code ZoomHandler} 的每 tick 三段式轮询启停
 * （没有现成的 value-change 回调可抄），只在 Zombies 局内且配置开启时干活。
 *
 * <p>生命周期：{@code wantActive} 由「配置 + 局内门控」算出来；启用时创建替身相机、
 * 把渲染与准星切给它，停用时恢复原相机实体。玩家冻结与否、相机飞不飞，
 * 由两个正交子选项决定：
 * <ul>
 *   <li>{@code playerMovement} 关（默认）：玩家完全冻结，相机随 WASD/鼠标自由飞；</li>
 *   <li>{@code playerMovement} 开：玩家照常走动，相机原地当静止观察点；</li>
 *   <li>{@code playerInputs} 关（默认）：相机视角下攻击/挖掘/使用/挥动全封禁，防盲视误操作。</li>
 * </ul>
 */
public final class FreeCameraHandler {

    public static final FreeCameraHandler INSTANCE = new FreeCameraHandler();

    /** 自由视角当前是否已生效：相机实体已创建并接管渲染。 */
    private boolean applied;

    /** 冻结输入：1.21.4 里 {@code Input} 是具体类，ctor 就置 {@code playerInput=PlayerInput.DEFAULT}（全 false）、
     * 各 boolean 全 false、floats 全 0——一个 {@code new Input()} 恰好是完美的空输入，玩家 tick 读到它纹丝不动。 */
    private static final Input FROZEN_INPUT = new Input();

    /** 冻结输入实例：FreeCameraPlayerMixin 在玩家 tick 期间拿它替换 {@code this.input}。 */
    public static Input getFrozenInput() {
        return FROZEN_INPUT;
    }

    /** 玩家真实输入实例（freeze 前捕获；KeyboardInput 无状态、每 tick 现读 options，跨玩家实例复用无害）。
     * mixin 的对称恢复（tick HEAD 换冻结 / RETURN 还原）一旦被异常/中断打断会残留冻结实例，
     * 这个字段保证「真输入」永远能找回来，不被残留污染。 */
    private static Input playerRealInput;

    /** 捕获玩家真实输入：只在当前输入不是冻结实例时更新——残留状态下 input 已是冻结实例，
     * 若再存进「真输入」就永久污染了（以后恢复出来的永远是冻结）。 */
    public static void captureRealInput(Input current) {
        if (current != FROZEN_INPUT) {
            playerRealInput = current;
        }
    }

    /** 玩家真实输入（freeze 恢复用；首次 freeze 前必已捕获，正常非 null）。 */
    public static Input getRealInput() {
        return playerRealInput != null ? playerRealInput : FROZEN_INPUT;
    }

    /** 兜底恢复：玩家 input 游离成冻结实例（tick 中途异常、RETURN 恢复没执行到）时还原成真输入。 */
    public static void restoreRealInput(MinecraftClient mc) {
        if (mc.player != null && mc.player.input == FROZEN_INPUT && playerRealInput != null) {
            mc.player.input = playerRealInput;
        }
    }

    private FreeCameraHandler() {
    }

    public void init() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    /** 自由视角当前是否生效（供 mixin / HUD 查询）。 */
    public static boolean isActive() {
        return INSTANCE.applied;
    }

    /** 玩家应冻结移动（含鼠标转向转发给相机）= 生效 且 没开「允许玩家移动」。 */
    public static boolean shouldPreventMovement() {
        return isActive() && !GlobalConfig.FreeCam.PLAYER_MOVEMENT.getBooleanValue();
    }

    /** 玩家交互应被封禁 = 生效 且 没开「允许玩家输入」。 */
    public static boolean shouldPreventInputs() {
        return isActive() && !GlobalConfig.FreeCam.PLAYER_INPUTS.getBooleanValue();
    }

    /**
     * freecam 生效时，目标实体是否为本地玩家本体。正常游戏准星永远指不到自己（射线从眼睛出发向前，
     * 命中自己的判定几何上不存在），但 freecam 下相机与身体分离，准星可以指到自己冻结的身体——
     * 此时攻击/交互目标=本地玩家，客户端会发出指向自身的交互包，服务器直接硬拒
     * （Hypixel「Cannot interact with self!」踢出，涉嫌反作弊误判，有封号风险）。
     * 这里无条件封禁（与「允许玩家输入」无关，开着也封）：目标是自身 → 源头掐断，包根本发不出去。
     */
    public static boolean isSelfTarget(Entity target) {
        if (!isActive() || target == null) {
            return false;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && target == mc.player;
    }

    /** 鼠标转向增量转发给相机实体（LookMixin 调进来）。 */
    public static void rotateCamera(float yawChange, float pitchChange) {
        CameraEntity.rotateCamera(yawChange, pitchChange);
    }

    /** 强制停用（断线/离场安全网）：立即还原相机，防残留。 */
    public static void forceDisable() {
        if (INSTANCE.applied) {
            CameraEntity.removeCamera(MinecraftClient.getInstance());
            INSTANCE.applied = false;
        }
    }

    /** 每 tick 轮询：wantActive / applied 三段式启停，顺带驱动相机飞行。 */
    private void onClientTick(MinecraftClient mc) {
        boolean wantActive = GlobalConfig.QoL.FREE_CAMERA_ENABLED.getBooleanValue()
                && PlayerUtils.isInZombies()
                && mc.world != null && mc.player != null;

        if (wantActive && !this.applied) {
            CameraEntity.setCameraState(mc);
            this.applied = true;
        } else if (!wantActive && this.applied) {
            CameraEntity.removeCamera(mc);
            this.applied = false;
        }

        // 相机仅在「允许玩家移动」关闭时飞行（玩家冻结）；开启时相机是静止观察点
        if (this.applied && !GlobalConfig.FreeCam.PLAYER_MOVEMENT.getBooleanValue()) {
            CameraEntity.movementTick();
        }

        // 兜底：输入冻结只应在 freecam 生效期间存在。freeze 对称恢复因异常/中断失败时玩家 input
        // 会残留冻结实例，导致关闭自由视角后 WASD/蹲跳全部失效（立正）——这里无条件清理游离冻结。
        if (!this.applied) {
            restoreRealInput(mc);
        }
    }
}
