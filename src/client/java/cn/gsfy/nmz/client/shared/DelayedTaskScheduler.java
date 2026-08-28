package cn.gsfy.nmz.client.shared;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 基于客户端 tick 的延迟 / 周期任务调度器（功能上与 Forge DelayedTask、Bukkit 任务等效）。
 * 时间单位统一为游戏 tick（一个 tick 约为 50ms）。
 *
 * <p>任务在客户端 tick 末尾统一结算：先倒计时、筛选到期任务，再在执行动作（动作内新增 / 取消
 * 任务不会触发并发修改异常）。周期任务按固定 period 重复执行，一次性任务执行后从队列移除。
 */
public class DelayedTaskScheduler {

    private static DelayedTaskScheduler instance;
    private final List<Task> tasks = new ArrayList<>();

    /** 返回全局单例；未 {@link #init()} 时为 {@code null}。 */
    public static DelayedTaskScheduler get() {
        return instance;
    }

    /** 初始化单例并注册客户端 tick 回调，使调度器开始运行。 */
    public void init() {
        instance = this;
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    /** 每个客户端 tick 结算一次：倒计时已到期 / 已取消的任务出队并执行。 */
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
        // 任务动作在迭代完成后再执行：动作内部再次 runTaskLater/runTaskTimer 会修改 tasks，
        // 若在迭代中直接执行，下一次 it.remove() 会抛 ConcurrentModificationException（崩溃根因）。
        for (Task task : due) {
            task.run();
        }
    }

    /** 延迟 delay 个 tick 后执行一次。返回任务句柄，可通过 {@link Task#cancel()} 取消。 */
    public Task runTaskLater(int delay, Runnable action) {
        Task task = new Task(delay, 0, action);
        synchronized (tasks) {
            tasks.add(task);
        }
        return task;
    }

    /** 延迟 delay 个 tick 后，每隔 period 个 tick 重复执行一次。返回任务句柄。 */
    public Task runTaskTimer(int delay, int period, Runnable action) {
        Task task = new Task(delay, period, action);
        synchronized (tasks) {
            tasks.add(task);
        }
        return task;
    }

    /** 取消全部挂起任务（离开 Zombies / 断线时调用，防止跨局残留执行）。 */
    public void cancelAll() {
        synchronized (tasks) {
            for (Task t : tasks) {
                t.cancelled = true;
            }
            tasks.clear();
        }
    }

    /** 任务句柄：周期任务可调用 {@link #cancel()} 自行终止（如道具倒计时到期自取消，防止任务无限累积）。 */
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

        /** 标记取消：任务会在下一个 tick 从队列移除，不再执行。 */
        public void cancel() {
            this.cancelled = true;
        }

        void run() {
            try {
                action.run();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
