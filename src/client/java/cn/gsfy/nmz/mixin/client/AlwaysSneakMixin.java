package cn.gsfy.nmz.mixin.client;

import cn.gsfy.nmz.client.feature.sneak.AlwaysSneak;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 永久潜行——把潜行键焊死，省得一直按着 Ctrl。
 *
 * <p>注入 {@code KeyboardInput.tick()V}（无参方法，输入状态落定阶段），{@code @Inject} 挂在
 * {@code @At("RETURN")}：每次键盘输入结算完毕、且 {@link AlwaysSneak#isActive()} 生效时，
 * 重建 {@code playerInput} 并置 sneak true。之所以要整份重建：1.21.4 的 {@code Input} 没有独立
 * {@code sneaking} 字段，潜行标志收进 {@code PlayerInput} record——下蹲动画、防坠落边缘、
 * 缩小碰撞箱、潜行数据包全走 vanilla 状态机（与按住潜行键等效），纯客户端输入层，不发额外数据包。
 *
 * <p>实现注意：{@code playerInput} 声明在父类 {@code Input} 上，<b>不能 {@code @Shadow}</b>——
 * loom 编译期对无 owner 的 {@code @Shadow} 字段名会映射到错误类（实测映射成 PlayerInput 类的
 * field_54155），运行时在 KeyboardInput 继承链上找不到该字段会直接启动崩溃
 * （InvalidMixinException，2026-08-19 实战触发）。因此改用 {@code (Input)(Object)this} 直取
 * 公开字段：owner 显式，loom 按 owner 精确 remap。
 */
@Mixin(KeyboardInput.class)
public abstract class AlwaysSneakMixin {

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void nmz$alwaysSneak(CallbackInfo ci) {
        if (AlwaysSneak.isActive()) {
            Input input = (Input) (Object) this;
            PlayerInput p = input.playerInput;
            if (p != null && !p.sneak()) {
                input.playerInput = new PlayerInput(
                        p.forward(), p.backward(), p.left(), p.right(), p.jump(), true, p.sprint());
            }
        }
    }
}
