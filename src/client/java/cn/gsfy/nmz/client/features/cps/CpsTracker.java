package cn.gsfy.nmz.client.features.cps;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Left/right CPS (clicks per second) tracking—the value source for the
 * two CPS HUD cells.
 *
 * <p>A rolling 1-second window catches each click: the mixin enqueues a
 * wall-clock timestamp the moment a button goes down, and a read first
 * drops timestamps older than the window, then counts what's left. Only
 * the client main thread reads and writes (both the mixin event and the
 * HUD render sit on it), so there is no cross-thread race and no lock is
 * needed.
 */
public final class CpsTracker {

    /** Window length in ms. A click stamp older than this counts as
     *  expired and gets pruned. */
    private static final long WINDOW_MS = 1000L;

    /** Left-click rolling 1s window: wall-clock timestamps in ms. */
    private static final Deque<Long> LEFT_CLICKS = new ArrayDeque<>();
    /** Right-click rolling 1s window: wall-clock timestamps in ms. */
    private static final Deque<Long> RIGHT_CLICKS = new ArrayDeque<>();

    private CpsTracker() {
    }

    /** Left-click (attack/shoot). Called by CpsTrackerMixin the moment
     *  the button goes down. */
    public static void onLeftClick() {
        recordClick(LEFT_CLICKS);
    }

    /** Right-click (use item). Called by CpsTrackerMixin the moment the
     *  button goes down. */
    public static void onRightClick() {
        recordClick(RIGHT_CLICKS);
    }

    /** Left CPS over the last second; the HUD reads this while rendering. */
    public static int getLeftCps() {
        return cpsOf(LEFT_CLICKS);
    }

    /** Right CPS over the last second; the HUD reads this while rendering. */
    public static int getRightCps() {
        return cpsOf(RIGHT_CLICKS);
    }

    /**
     * Clears both counters. Reserved for a disconnect / new-game reset:
     * the rolling window decays on its own, but an explicit clear keeps
     * the previous game's clicks from bleeding over. No caller wires it
     * up yet.
     */
    public static void reset() {
        LEFT_CLICKS.clear();
        RIGHT_CLICKS.clear();
    }

    private static void recordClick(Deque<Long> clicks) {
        long now = System.currentTimeMillis();
        clicks.addLast(now);
        prune(clicks, now);
    }

    private static int cpsOf(Deque<Long> clicks) {
        long now = System.currentTimeMillis();
        prune(clicks, now);
        return clicks.size();
    }

    /**
     * Drops timestamps older than {@code now-WINDOW_MS} from the head.
     * Stamps are enqueued in time order, so the head is always the oldest;
     * checking the head alone is enough.
     */
    private static void prune(Deque<Long> clicks, long now) {
        long cutoff = now - WINDOW_MS;
        while (!clicks.isEmpty() && clicks.peekFirst() < cutoff) {
            clicks.removeFirst();
        }
    }
}