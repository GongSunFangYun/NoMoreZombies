package cn.gsfy.nmz.client.shared;

import cn.gsfy.nmz.client.util.LanguageUtils;
import net.minecraft.entity.decoration.ArmorStandEntity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * 强化道具模型（对应源 Powerup）。offsetTime 单位 = 游戏 tick（1200 = 60 秒）。
 *
 * <p>世界内每个已刷出的道具 = 一个盔甲架实体 + 一个倒计时：{@code powerups} 按实体索引，
 * 倒计时归零即移除并进黑名单；预测中的道具不绑定实体，只挂在 {@code incPowerups} 里；
 * 已拾取生效的另走 {@link ActivePowerup} 的墙钟倒计时。三者互不混淆。
 */
public class Powerup {

    private final PowerupType powerupType;
    private final ArmorStandEntity armorStand;

    /** 世界内已刷出的道具：盔甲架 -> Powerup（倒计时归零或拾取后移除）。 */
    public static final LinkedHashMap<ArmorStandEntity, Powerup> powerups = new LinkedHashMap<>();
    /** 本回合将刷出的道具（预测中，尚无实体），仅用于展示「已掉落」状态与掉落时刻。 */
    public static final List<Powerup> incPowerups = new ArrayList<>();
    /** 已过期的盔甲架——短暂黑名单，防同一实体被重复注册成两个 Powerup。 */
    public static final List<ArmorStandEntity> expiredPowerups = new ArrayList<>();
    /** 已拾取且生效中的道具：每个含「类型 + 到期时刻」（wall-clock ms，与服务器真实时长一致）。 */
    public static final List<ActivePowerup> activePowerups = new ArrayList<>();
    /** 本回合已拾取的道具类型（聊天激活命中时写入，回合开始清空）。
     *  HUD 据此在生效结束后不再回退显示「已掉落」（状态机只前进不回溯）。 */
    public static final Set<PowerupType> pickedUpRound = EnumSet.noneOf(PowerupType.class);

    private int offsetTime;

    /** 本回合是否已掉落（仅预测条目 incPowerups 使用）；dropGameTickMs = 掉落时刻（墙钟 ms since round start）。 */
    private boolean dropped;
    private long dropGameTickMs;

    /**
     * 已拾取且生效中的道具。剩余时间用 wall-clock 计算（NEZ PowerUpHud 同款）：
     * 精确到真实秒数，也避免为每个生效道具各挂一个永不终止的 ticker 造成泄漏。
     */
    public static class ActivePowerup {
        private final PowerupType powerupType;
        private final long expireMs;
        /** true=真实时长倒计时；false=瞬时道具（如 Max Ammo）的「已生效」确认闪窗。 */
        private final boolean timed;

        ActivePowerup(PowerupType type, long expireMs, boolean timed) {
            this.powerupType = type;
            this.expireMs = expireMs;
            this.timed = timed;
        }

        public PowerupType getPowerupType() {
            return powerupType;
        }

        public long getExpireMs() {
            return expireMs;
        }

        /** 是否为真实时长倒计时（瞬时道具为 false，仅作拾取确认闪窗）。 */
        public boolean isTimed() {
            return timed;
        }

        /** 相对 now 的剩余毫秒（已过期返回 0）。 */
        public long getRemainingMs(long now) {
            return Math.max(0L, expireMs - now);
        }
    }

    /** 记录一个已拾取且生效中的道具（同类重复激活时替换旧条目）。durationSeconds 来自聊天激活消息。 */
    public static void addActivePowerup(PowerupType type, int durationSeconds) {
        // 瞬时道具（durationSeconds<=0，如 Max Ammo）用 3s 确认闪窗：只提示「已生效」，不带倒计时，
        // 避免暗示还有几秒效果；真实时长道具按秒计时。
        boolean timed = durationSeconds > 0;
        long expireMs = System.currentTimeMillis() + Math.max(durationSeconds, 3) * 1000L;
        // 同类重复激活时替换旧条目（NEZ 留有 TODO，此处直接实现：只保留最近一次）
        activePowerups.removeIf(a -> a.getPowerupType() == type);
        activePowerups.add(new ActivePowerup(type, expireMs, timed));
    }

    /** 世界内已刷出的道具注册：盔甲架不在黑名单里就登记并返回新 Powerup；已注册/黑名单则返回 null。 */
    public static Powerup deserialize(PowerupType type, ArmorStandEntity stand) {
        if (!powerups.containsKey(stand) && !expiredPowerups.contains(stand)) {
            Powerup powerup = new Powerup(type, stand);
            // 不再移除 inc 条目：上段「本回合」需保留到回合结束，以显示「已掉落」状态与掉落时刻
            powerups.put(stand, powerup);
            return powerup;
        }
        return null;
    }

    /** 预测条目：不绑定实体，直接挂到 inc 列表，回合内展示「将掉落」。 */
    public static Powerup deserialize(PowerupType type) {
        Powerup powerup = new Powerup(type, null);
        incPowerups.add(powerup);
        return powerup;
    }

    /** 构造：绑定盔甲架实体时挂一个每秒倒计时，归零自取消并从地图移除。 */
    public Powerup(PowerupType type, ArmorStandEntity armorStand) {
        this.powerupType = type;
        this.armorStand = armorStand;
        this.offsetTime = 1200;
        if (armorStand != null) {
            // 每秒计数，到期自取消：防止计时器永不终止（每道具一个 period=1 任务无限累积）+
            // expiredPowerups 黑名单每 20 tick 重开守卫导致 delay=20 无限重排（日志刷屏/调度膨胀）。
            // 用数组持有者捕获任务句柄（lambda 不能引用未初始化的局部变量）。
            DelayedTaskScheduler.Task[] self = new DelayedTaskScheduler.Task[1];
            self[0] = DelayedTaskScheduler.get().runTaskTimer(0, 1, () -> {
                if (offsetTime <= 0) {
                    if (self[0] != null) {
                        self[0].cancel();
                    }
                    onDeleteArmorStandFromExpiredList(armorStand);
                    powerups.remove(armorStand);
                } else {
                    offsetTime--;
                }
            });
        }
    }

    public void claim() {
        this.offsetTime = 0;
        if (armorStand != null) {
            onDeleteArmorStandFromExpiredList(armorStand);
            powerups.remove(armorStand);
        }
    }

    private void onDeleteArmorStandFromExpiredList(ArmorStandEntity stand) {
        if (expiredPowerups.contains(stand)) {
            return;
        }
        expiredPowerups.add(stand);
        DelayedTaskScheduler.get().runTaskLater(20, () -> expiredPowerups.remove(stand));
    }

    /** 标记本回合已掉落并记录掉落时刻（GameTickHandler.gameTick，墙钟 ms since round start）。 */
    public void markDropped(long dropGameTickMs) {
        this.dropped = true;
        this.dropGameTickMs = dropGameTickMs;
    }

    public boolean isDropped() {
        return dropped;
    }

    public long getDropGameTickMs() {
        return dropGameTickMs;
    }

    public PowerupType getPowerupType() {
        return powerupType;
    }

    public int getOffsetTime() {
        return offsetTime;
    }

    /** 道具名 lang 键（PowerupRenderer HUD / PowerupDetect 掉落聊天提示共用）。 */
    public static String keyFor(PowerupType type) {
        return switch (type) {
            case INSTA_KILL -> "nomorezombies.powerup.instaKill";
            case MAX_AMMO -> "nomorezombies.powerup.maxAmmo";
            case DOUBLE_GOLD -> "nomorezombies.powerup.doubleGold";
            case CARPENTER -> "nomorezombies.powerup.carpenter";
            case BONUS_GOLD -> "nomorezombies.powerup.bonusGold";
            case SHOPPING_SPREE -> "nomorezombies.powerup.shoppingSpree";
            case NULL -> "";
        };
    }

    /** 道具类型枚举：显示名 + 聊天颜色码，配 lang 键做多语言。 */
    public enum PowerupType {
        NULL("", ""),
        INSTA_KILL("INSTA KILL", "§c"),
        MAX_AMMO("MAX AMMO", "§9"),
        DOUBLE_GOLD("DOUBLE GOLD", "§6"),
        CARPENTER("CARPENTER", "§1"),
        BONUS_GOLD("BONUS GOLD", "§e"),
        SHOPPING_SPREE("SHOP SPREE", "§5");

        private final String displayName;
        private final String colorCode;

        PowerupType(String displayName, String colorCode) {
            this.displayName = displayName;
            this.colorCode = colorCode;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getColorCode() {
            return colorCode;
        }

        /** 从盔甲架名字（中英）识别道具类型。 */
        public static PowerupType fromName(String name) {
            if (LanguageUtils.isInstaKill(name)) return INSTA_KILL;
            if (LanguageUtils.isMaxAmmo(name)) return MAX_AMMO;
            if (LanguageUtils.isDoubleGold(name)) return DOUBLE_GOLD;
            if (LanguageUtils.isCarpenter(name)) return CARPENTER;
            if (LanguageUtils.isBonusGold(name)) return BONUS_GOLD;
            if (LanguageUtils.isShoppingSpree(name)) return SHOPPING_SPREE;
            return NULL;
        }
    }
}
