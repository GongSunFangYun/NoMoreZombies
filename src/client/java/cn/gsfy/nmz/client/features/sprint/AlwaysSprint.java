package cn.gsfy.nmz.client.features.sprint;

import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.utils.PlayerUtils;

/**
 * Permanent sprint—puts "the sprint key is held forever" into code: inside
 * {@code ClientPlayerEntity.tickMovement} (after the vanilla sprint test,
 * before the state settles), {@code setSprinting(true)} is called directly
 * whenever the vanilla sprint constraints hold (see
 * {@code AlwaysSprintMixin}). W must still be held forward
 * ({@code movementForward>=0.8}); the vanilla sprint-stop conditions
 * (hunger / blindness / water / holding a use item) all stay—otherwise the
 * server would read it as abnormal movement. It is not "sprint in place".
 *
 * <p>Gate = the QoL master switch + in a Zombies game (same convention as
 * zoom). Mixins query it statically; no wiring in NoMoreZombiesClient.
 */
public final class AlwaysSprint {

    private AlwaysSprint() {
    }

    /** Whether currently active: master switch on and in a Zombies game. */
    public static boolean isActive() {
        return GlobalConfig.QoL.ALWAYS_SPRINT_ENABLED.getBooleanValue()
                && PlayerUtils.isInZombies();
    }
}