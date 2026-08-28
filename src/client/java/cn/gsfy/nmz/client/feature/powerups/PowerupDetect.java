package cn.gsfy.nmz.client.feature.powerups;

import cn.gsfy.nmz.NoMoreZombies;
import cn.gsfy.nmz.client.config.GlobalConfig;
import cn.gsfy.nmz.client.data.DataManager;
import cn.gsfy.nmz.client.data.model.PowerupPattern;
import cn.gsfy.nmz.client.feature.spawntimes.CheckSpawnTimes;
import cn.gsfy.nmz.client.shared.DelayedTaskScheduler;
import cn.gsfy.nmz.client.shared.GameTickHandler;
import cn.gsfy.nmz.client.shared.Powerup;
import cn.gsfy.nmz.client.util.LanguageUtils;
import cn.gsfy.nmz.client.util.PlayerUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 道具检测与规律提交（对应源 PowerupDetect，NEZ 化改造）。
 * 三条通道共同识别道具事件、互相兜底：通道 A 是 {@code onEntityTrackerUpdate} mixin
 * 的元数据通道，盔甲架一进场就有名字；通道 A' 每 4 tick 扫一遍场上盔甲架名字
 * （{@link #scanArmorStands()}），专治 Hypixel 新实体名字随 spawn 包下发、不进
 * onEntityTrackerUpdate 的漏网之鱼；通道 B 是聊天激活消息（{@link #onChatReceived(String)}），
 * 在盔甲架彻底漏检时做最后兜底，还承担生效倒计时与「已拾取」状态。
 *
 * <p>两条盔甲架通道共用 {@link #detectArmorstand} 与 {@link #seenStandIds} 去重，
 * 同一次掉落不会记两遍。规律（pattern）提交是 commit-once：首次观测（盔甲架或聊天）
 * 就把该类型命中的规律索引定死、整局锁定，之后 {@link #nextRound} 引擎按
 * 「显式轮次 + 个位数字推演」预测后续回合，中途不因第二次观测改弦更张。
 */
public class PowerupDetect {

    /** 全局单例，{@link #init()} 后才有值；外部一律用 {@link #get()} 判空取用。 */
    private static PowerupDetect instance;

    /** 本局已提交的道具规律：类型 -> 规律，首次观测即锁定、整局不改。
     *  命中数据表时取用按索引查到的 {@link PowerupPattern}；数据表为空（该地图无此类型规律）时
     *  提交合成单点规律 {@code {rounds:[round], digits:[round%10]}}——
     *  让「本回合(已掉落)」能上 HUD，同个位数字的后续回合也能继续推演。 */
    private final Map<Powerup.PowerupType, PowerupPattern> committedPattern = new EnumMap<>(Powerup.PowerupType.class);

    /** 本局已注册过的盔甲架实体 ID——mixin 元数据通道与每 tick 扫描通道共用这一份去重，同一具盔甲架只记一次。 */
    private final Set<Integer> seenStandIds = new HashSet<>();

    /** 扫描节流计数：凑满 4 tick 才扫一次场上盔甲架，避免每 tick 全量遍历实体列表。 */
    private int scanCounter;

    public static PowerupDetect get() {
        return instance;
    }

    public void init() {
        instance = this;
        // NEZ 同款兜底通道：每 4 tick 扫一遍场上盔甲架名字——Hypixel 新实体出生时名字随 spawn 包
        // 下发，不走 onEntityTrackerUpdate，不主动扫世界就漏检，这条通道专门补这个洞
        ClientTickEvents.END_CLIENT_TICK.register(client -> scanArmorStands());
    }

    /** 进入新游戏时清空全部规律与缓存状态——跨局不残留，旧局的规律不能带进新局。 */
    public void iniPowerupPatterns() {
        Powerup.powerups.clear();
        Powerup.incPowerups.clear();
        Powerup.expiredPowerups.clear();
        Powerup.activePowerups.clear();
        Powerup.pickedUpRound.clear();
        committedPattern.clear();
        seenStandIds.clear();
        scanCounter = 0;
    }

    /** 每 4 tick 扫一次场上盔甲架（NEZ LivingUpdateEventHandler 同款思路）；非 Zombies 世界直接跳过，省遍历。 */
    private void scanArmorStands() {
        if (!PlayerUtils.isInZombies()) {
            return;
        }
        // 生效列表每 tick 到期清理——HUD 关了也照清，列表最多 6 条，全量过滤开销极低
        Powerup.activePowerups.removeIf(a -> a.getExpireMs() <= System.currentTimeMillis());
        if (++scanCounter % 4 != 0) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return;
        }
        for (Entity entity : client.world.getEntities()) {
            if (entity instanceof ArmorStandEntity stand && stand.hasCustomName()) {
                String standName = stand.getCustomName().getString();
                detectArmorstand(standName, stand.getId());
            }
        }
    }

    // ==================== 通道 A：盔甲架名字 ====================

    /**
     * 检测一具盔甲架的名字——mixin 元数据通道与每 tick 扫描通道的公共入口，两个源头汇到这一处。
     * 名字能解析出道具类型、实体还在场、本局又没登记过，才算一次有效观测：
     * 记录掉落时刻、提交规律、播报提示，三步一起做完。
     */
    public void detectArmorstand(String armorStandName, int entityId) {
        int round = CheckSpawnTimes.get().getCurrentRound();
        if (round == 0) {
            return;
        }
        Powerup.PowerupType type = Powerup.PowerupType.fromName(armorStandName);
        if (type == Powerup.PowerupType.NULL) {
            return;
        }
        Entity entity = MinecraftClient.getInstance().world.getEntityById(entityId);
        if (!(entity instanceof ArmorStandEntity stand)) {
            return;
        }
        if (seenStandIds.contains(entityId)) {
            return; // 已登记过——mixin 通道与扫描通道共用去重，防同一具盔甲架被记两遍
        }
        Powerup powerup = Powerup.deserialize(type, stand);
        if (powerup == null) {
            return;
        }
        seenStandIds.add(entityId);
        NoMoreZombies.LOGGER.info("[道具] 盔甲架检测：{}（entity {}）round {} 名字=\"{}\"", type, entityId, round, armorStandName);
        // 记下本回合掉落时刻（回合开始起的墙钟 ms），顺手把预测条目标为已掉落——之后 HUD 才能显示「已掉落·X.X秒」
        markDropped(type);
        learnPattern(type, round);
        notifyPowerupDropped(type);
    }

    /** 把本回合该类型的预测条目标为已掉落，并记下掉落时刻（墙钟 ms，从回合开始起算）。 */
    private void markDropped(Powerup.PowerupType type) {
        long dropMs = GameTickHandler.get().getGameTick();
        for (Powerup p : new ArrayList<>(Powerup.incPowerups)) {
            if (p.getPowerupType() == type && !p.isDropped()) {
                p.markDropped(dropMs);
                return;
            }
        }
        // 回填：可预测类型（dataKey != null）在回合开始没建预测条目时——比如中途加入、或规律
        // 到回合中才提交——补一条，保证 HUD 显示「本回合(已掉落·X.X秒)」。
        // 非预测类型（双倍金钱等）不回填，它们另有「已刷出 00:XX」的展示通道。
        if (dataKey(type) != null) {
            Powerup entry = Powerup.deserialize(type);
            if (entry != null) {
                entry.markDropped(dropMs);
            }
        }
    }

    /** 道具掉落聊天提示（NEZ PowerUpAlert 同款）：盔甲架一检测到就报「XX 已掉落」，由 POWERUP_PREDICT 开关门控。 */
    private void notifyPowerupDropped(Powerup.PowerupType type) {
        if (!GlobalConfig.Powerups.POWERUP_PREDICT.getBooleanValue()) {
            return;
        }
        Text powerupText = Text.literal(type.getColorCode() + Text.translatable(Powerup.keyFor(type)).getString());
        Text message = Text.literal("[NoMoreZombies] ").formatted(Formatting.GOLD)
                .copy()
                .append(powerupText)
                .append(Text.literal(" ").formatted(Formatting.WHITE))
                .append(Text.translatable("nomorezombies.msg.powerup.dropped").formatted(Formatting.WHITE));
        PlayerUtils.sendMessage(message,
                GlobalConfig.Powerups.ALERT_OUTPUT.getOptionListValue() instanceof GlobalConfig.AlertOutput o
                        ? o : GlobalConfig.AlertOutput.SELF);
    }

    // ==================== 通道 B：聊天激活消息 ====================

    /** 聊天消息监听回调：message 是已去格式化的纯文本。引擎常开（与盔甲架通道一致），只负责识别，输出由各自开关门控。 */
    public void onChatReceived(String message) {
        if (message.contains(":") || message.contains("：")) {
            return;
        }
        if (!PlayerUtils.isInZombies()) {
            return;
        }
        if (!LanguageUtils.isActivatedMessage(message)) {
            return;
        }
        int round = CheckSpawnTimes.get().getCurrentRound();
        if (round == 0) {
            return;
        }

        Powerup.PowerupType type = detectFromMessage(message);
        if (type == Powerup.PowerupType.NULL) {
            return;
        }
        int duration = LanguageUtils.extractPowerupDuration(message);
        NoMoreZombies.LOGGER.info("[道具] 聊天激活：{}（{} 秒）round {} 消息=\"{}\"", type, duration, round, message);
        // 聊天拾取是盔甲架漏检时的兜底（NEZ onPowerUpPickup 同款）；规律提交走 commit-once，已提交就不重复
        learnPattern(type, round);
        // 记下本回合已拾取：生效结束 HUD 不回退到「已掉落」，状态机只前进不回溯
        Powerup.pickedUpRound.add(type);
        // 生效倒计时：非瞬时道具（双倍金币/瞬间击杀/购物狂欢等）按聊天给的真实时长走；
        // 瞬时道具（Max Ammo，duration<=0）走 3s「已生效」确认闪窗
        Powerup.addActivePowerup(type, duration);

        Powerup.PowerupType finalType = type;
        DelayedTaskScheduler.get().runTaskLater(5, () -> {
            // 这里只回收已消失（被拾取/过期）的盔甲架条目，刻意不 markDropped：
            // ① 拾取时刻 ≠ 掉落时刻，误标会污染「已掉落·X.X秒」；
            // ② 跨回合拾到上一回合残留的道具时，会误把本回合预测条目标成已掉落。
            // 掉落时刻只认盔甲架检测通道（detectArmorstand → markDropped）一家。
            for (Map.Entry<ArmorStandEntity, Powerup> entry : new ArrayList<>(Powerup.powerups.entrySet())) {
                if (entry.getValue().getPowerupType() == finalType) {
                    ArmorStandEntity stand = entry.getKey();
                    if (stand == null || stand.isRemoved()) {
                        entry.getValue().claim();
                    }
                }
            }
        });
    }

    private Powerup.PowerupType detectFromMessage(String message) {
        String lower = message.toLowerCase();
        if (lower.contains("insta kill") || message.contains("秒杀") || message.contains("一擊必殺")
                || message.contains("瞬间击杀") || message.contains("瞬間擊殺")) {
            return Powerup.PowerupType.INSTA_KILL;
        }
        if (lower.contains("max ammo") || message.contains("满弹药") || message.contains("滿彈藥")
                || message.contains("弹药满载") || message.contains("彈藥滿載")) {
            return Powerup.PowerupType.MAX_AMMO;
        }
        if (lower.contains("shopping spree") || lower.contains("shop spree")
                || message.contains("购物狂潮") || message.contains("購物狂潮")) {
            return Powerup.PowerupType.SHOPPING_SPREE;
        }
        if (lower.contains("carpenter") || message.contains("木匠")) {
            return Powerup.PowerupType.CARPENTER;
        }
        if (lower.contains("bonus gold") || message.contains("额外金币") || message.contains("額外金幣")) {
            return Powerup.PowerupType.BONUS_GOLD;
        }
        if (lower.contains("double gold") || message.contains("双倍金币") || message.contains("雙倍金幣")
                || message.contains("双倍金钱") || message.contains("雙倍金錢")) {
            return Powerup.PowerupType.DOUBLE_GOLD;
        }
        return Powerup.PowerupType.NULL;
    }

    // ==================== 规律提交（NEZ commit-once） ====================

    /** 首次观测该类型时定下命中哪条规律并锁定；已提交的直接忽略——commit-once 的第二半。 */
    private void learnPattern(Powerup.PowerupType type, int round) {
        if (committedPattern.containsKey(type)) {
            return;
        }
        List<PowerupPattern> pats = patterns(type);
        int idx = patternIndexFor(type, round);
        if (idx >= 0) {
            committedPattern.put(type, pats.get(idx));
            NoMoreZombies.LOGGER.info("[道具] 规律提交：{} 命中规律 #{}（round {}）", type, idx, round);
            return;
        }
        // 跨回合归属修正（镜像参考 PowerupPredictor.match 的 early 逻辑）：回合刚开（<=1s）时
        // 观测到的道具可能属上一回合——回合标题先翻，盔甲架扫描/聊天激活稍晚才到——
        // 于是按 round-1 再试一次，把迟到的事件归到正确的回合。
        if (GameTickHandler.get() != null && GameTickHandler.get().getGameTick() <= 1000L) {
            int prevIdx = patternIndexFor(type, round - 1);
            if (prevIdx >= 0) {
                committedPattern.put(type, pats.get(prevIdx));
                NoMoreZombies.LOGGER.info("[道具] 规律提交：{} 跨回合命中规律 #{}（检测 round {}，实际 round {}）",
                        type, prevIdx, round, round - 1);
                return;
            }
        }
        // 表为空（该地图无此类型的规律，如 DE/BB/Lab/Prison 无 shopping_spree）时，
        // 首次观测就提交一条合成的单点规律 {rounds:[round], digits:[round%10]}：
        // 让「本回合(已掉落)」立刻上 HUD，同个位数字的后续回合也能被预测到。
        // 合成只在表为空时发生，表存在但本轮没命中仍保持未提交，不污染真实规律表。
        if (pats.isEmpty()) {
            committedPattern.put(type, new PowerupPattern(new int[]{round}, new int[]{round % 10}));
            NoMoreZombies.LOGGER.info("[道具] 规律提交：{} 无规律表，合成单点规律（round {}）", type, round);
        }
    }

    /** 该类型规律表里第一个 rounds 含 round 的索引；全都不含返回 -1，表示这轮没命中任何规律。 */
    private int patternIndexFor(Powerup.PowerupType type, int round) {
        List<PowerupPattern> pats = patterns(type);
        for (int i = 0; i < pats.size(); i++) {
            if (contains(pats.get(i).getRounds(), round)) {
                return i;
            }
        }
        return -1;
    }

    private List<PowerupPattern> patterns(Powerup.PowerupType type) {
        String key = dataKey(type);
        if (key == null) {
            return List.of();
        }
        return DataManager.get().getPowerupPatterns(LanguageUtils.getMap(), key);
    }

    private static String dataKey(Powerup.PowerupType type) {
        return switch (type) {
            case INSTA_KILL -> "insta_kill";
            case MAX_AMMO -> "max_ammo";
            case SHOPPING_SPREE -> "shopping_spree";
            default -> null;
        };
    }

    // ==================== 预测引擎（NEZ getNextPowerUpRound） ====================

    /**
     * 该类型在 currentRound 及之后的下一次刷新回合（>= 语义，含「本回合」）；
     * 显式表用尽后按个位数字逐年推演。无已提交规律、或推演不出时返回 -1。
     */
    public int nextRound(Powerup.PowerupType type, int currentRound) {
        PowerupPattern p = committedPattern.get(type);
        if (p == null) {
            return -1;
        }

        // 显式阶段：在 rounds 里找最小且 >= currentRound 的回合——表里还有就先用表
        int best = -1;
        for (int r : p.getRounds()) {
            if (r >= currentRound && (best < 0 || r < best)) {
                best = r;
            }
        }
        if (best >= 0) {
            return best;
        }

        // 推演阶段：显式表用尽但有个位数字 → 从当前十位起，(tensDown + digit) 逐十年往后扫，跨出显式表也能预测
        int[] digits = p.getDigits();
        if (digits.length == 0) {
            return -1;
        }
        int tensDown = currentRound - currentRound % 10;
        for (int i = 0; i < 10; i++) {
            for (int digit : digits) {
                int res = tensDown + digit;
                if (res >= currentRound) {
                    return res;
                }
            }
            tensDown += 10;
        }
        return -1;
    }

    /** 当前回合是不是该类型的刷新回合——显式命中和个位数推演命中都算。 */
    public boolean isPowerupRound(Powerup.PowerupType type, int round) {
        return nextRound(type, round) == round;
    }

    /** 该类型是否已提交规律——预测 / 聊天输出在动手前都先问这一句。 */
    public boolean hasCommitted(Powerup.PowerupType type) {
        return committedPattern.containsKey(type);
    }

    private boolean contains(int[] array, int value) {
        for (int i : array) {
            if (i == value) {
                return true;
            }
        }
        return false;
    }
}
