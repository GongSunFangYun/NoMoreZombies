package cn.gsfy.nmz.client.feature.cps;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 左右键 CPS（每秒点击次数）统计——CPS HUD 那两个格子的数值来源。
 *
 * <p>用滚动 1 秒窗口接住每一次点击：mixin 按下瞬间把墙钟时间戳入队，
 * 读取计数时先剪掉超出窗口（&gt;1s）的旧戳，再数剩下几个。只由渲染线程读写，
 * 不存在跨线程竞争，所以不加锁也不会错。
 */
public final class CpsTracker {

    private static final long WINDOW_MS = 1000L;

    private static final Deque<Long> LEFT_CLICKS = new ArrayDeque<>();
    private static final Deque<Long> RIGHT_CLICKS = new ArrayDeque<>();

    private CpsTracker() {
    }

    /** 左键（攻击/开枪）点击：CpsMixin 按下瞬间调进来，把这 1 次记入滚动窗口。 */
    public static void onLeftClick() {
        recordClick(LEFT_CLICKS);
    }

    /** 右键（使用物品）点击：CpsMixin 按下瞬间调进来，把这 1 次记入滚动窗口。 */
    public static void onRightClick() {
        recordClick(RIGHT_CLICKS);
    }

    /** 左键 CPS（近 1 秒点击次数）：HUD 渲染时读它，先剪旧戳再报数。 */
    public static int getLeftCps() {
        return cpsOf(LEFT_CLICKS);
    }

    /** 右键 CPS（近 1 秒点击次数）：HUD 渲染时读它，先剪旧戳再报数。 */
    public static int getRightCps() {
        return cpsOf(RIGHT_CLICKS);
    }

    /** 清空计数：滚动窗口虽会自动衰减，断线/新开局仍显式归零，免得带上上一局的点击量。 */
    public static void reset() {
        LEFT_CLICKS.clear();
        RIGHT_CLICKS.clear();
    }

    /** 记录一次点击：写入当前墙钟时间戳，顺手剪掉超出窗口的旧记录，窗口保持最新。 */
    private static void recordClick(Deque<Long> clicks) {
        long now = System.currentTimeMillis();
        clicks.addLast(now);
        prune(clicks, now);
    }

    /** 统计近 1 秒点击次数：先按当前时刻裁剪过期时间戳，再返回剩余数量。 */
    private static int cpsOf(Deque<Long> clicks) {
        long now = System.currentTimeMillis();
        prune(clicks, now);
        return clicks.size();
    }

    /** 从队头移除早于 {@code now - WINDOW_MS} 的时间戳——时间戳按入队顺序排，队头必然最旧，从队头剪即可。 */
    private static void prune(Deque<Long> clicks, long now) {
        long cutoff = now - WINDOW_MS;
        while (!clicks.isEmpty() && clicks.peekFirst() < cutoff) {
            clicks.removeFirst();
        }
    }
}
