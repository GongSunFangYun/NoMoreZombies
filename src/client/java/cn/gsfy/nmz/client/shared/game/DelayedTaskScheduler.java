package cn.gsfy.nmz.client.shared.game;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import cn.gsfy.nmz.NoMoreZombies;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A client-tick-based delayed / periodic task scheduler (functionally
 * equivalent to Forge's DelayedTask, Bukkit tasks). Time units are unified
 * as game ticks (one tick ≈ 50ms).
 *
 * <p>Tasks settle at the end of each client tick: countdown first, filter
 * the due ones, then run the actions (adding/cancelling tasks inside an
 * action cannot throw a concurrent-modification exception).
 * A periodic task repeats at a fixed period; a one-shot task is removed from
 * the queue after running.
 *
 * <p>Boundary: every action runs inside try/catch, and one throwing action
 * loses only itself—the scheduler and the same tick's other tasks carry on.
 * On leaving / disconnect, GameEventBus calls {@code cancelAll()} to clear
 * the queue; a suspended task must not survive across games—
 * without clearing, the previous game's suspended power-up prediction /
 * query tasks would fire randomly in the new game.
 * When the game is not running the tick does not settle and tasks stay
 * suspended naturally.
 */
public class DelayedTaskScheduler {

    private static DelayedTaskScheduler instance;
    private final List<Task> tasks = new ArrayList<>();

    /** Returns the global singleton; {@code null} before {@link #init()}. */
    public static DelayedTaskScheduler get() {
        return instance;
    }

    /** Initializes the singleton and registers the client tick callback, starting the scheduler. */
    public void init() {
        instance = this;
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    /** Settles once per client tick: due / cancelled tasks leave the queue and run. */
    private void tick() {
        List<Task> due = new ArrayList<>();
        synchronized (tasks) {
            Iterator<Task> it = tasks.iterator();
            while (it.hasNext()) {
                Task task = it.next();
                if (task.cancelled) {
                    it.remove();
                    continue;
                }
                task.delay--;
                if (task.delay <= 0) {
                    if (task.period > 0) {
                        task.delay = task.period;
                    } else {
                        it.remove();
                    }
                    due.add(task);
                }
            }
        }
        // Task actions run after iteration completes: an action that calls
        // runTaskLater/runTaskTimer again modifies tasks, and running it mid-iteration would
        // make the next it.remove() throw ConcurrentModificationException
        for (Task task : due) {
            task.run();
        }
    }

    /**
     * Adds an action to the client tick queue; it runs once after the
     * countdown expires. The handle can be cancelled before it is due.
     *
     * @param delay the number of client ticks to wait; values ≤0 still wait
     *   for the next settlement
     * @param action the action to run on the client tick thread when due
     * @return a cancellable task handle
     */
    public Task runTaskLater(int delay, Runnable action) {
        Task task = new Task(delay, 0, action);
        synchronized (tasks) {
            tasks.add(task);
        }
        return task;
    }

    /**
     * Adds a periodic action to the client tick queue: it repeats at a fixed
     * period after the first delay, until the handle is cancelled.
     *
     * @param delay the number of client ticks to wait before the first run
     * @param period the subsequent period (client ticks); values ≤0 degrade
     *   to a one-shot task
     * @param action the action to run on the client tick thread when due
     * @return a cancellable task handle
     */
    public Task runTaskTimer(int delay, int period, Runnable action) {
        Task task = new Task(delay, period, action);
        synchronized (tasks) {
            tasks.add(task);
        }
        return task;
    }

    /** Cancels every suspended task (called on leaving Zombies / disconnect, preventing cross-game leftovers from running). */
    public void cancelAll() {
        synchronized (tasks) {
            for (Task t : tasks) {
                t.cancelled = true;
            }
            tasks.clear();
        }
    }

    /**
     * A task handle: a periodic task can cancel itself via {@link #cancel()}
     * (e.g. a power-up countdown cancelling itself on expiry, preventing
     * unbounded task accumulation).
     *
     * <p>Exceptions thrown by an action are swallowed inside {@code run()}
     * (logged through the shared LOGGER): the scheduler lives on the client
     * tick callback chain, and one task's exception blowing up here would
     * break the whole tick chain, leaving every later task unsettled.
     */
    public static final class Task {
        int delay;
        final int period;
        final Runnable action;
        boolean cancelled;

        Task(int delay, int period, Runnable action) {
            this.delay = delay;
            this.period = period;
            this.action = action;
        }

        /** Marks the task cancelled: it leaves the queue next tick and no longer runs. */
        public void cancel() {
            this.cancelled = true;
        }

        void run() {
            try {
                action.run();
            } catch (Exception e) {
                // A throwing task action must not blow up the whole tick queue; go through
                // the shared LOGGER instead of a bare stack, so the log at least shows the
                // failure at "the scheduler task" layer
                NoMoreZombies.LOGGER.error("[DelayedTask] 任务动作抛出异常", e);
            }
        }
    }
}