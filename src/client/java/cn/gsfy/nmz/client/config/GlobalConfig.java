package cn.gsfy.nmz.client.config;

import cn.gsfy.nmz.NoMoreZombies;
import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.config.IConfigValue;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.JsonUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 全局配置持有者（MaLiLib）——所有设置的唯一仓库：按功能分组存成静态配置常量，
 * 界面由 {@link GlobalConfigGui} 编辑，持久化到 config/nomorezombies.json
 * （MaLiLib 格式，按分组写）。
 *
 * <p>凡是「开/关」语义的项都做成 {@link ConfigBooleanHotkeyed}，绑上热键就能在游戏内
 * 一键切换（自带提示消息），不用每次进菜单翻开关；音效 ID / HUD 坐标 / 缩放这类没有
 * 开关语义的项，则分别用 ConfigString / ConfigDouble / ConfigOptionList 各归其位。
 */
public class GlobalConfig implements IConfigHandler {

    private static final String CONFIG_FILE_NAME = NoMoreZombies.MOD_ID + ".json";

    // ---- 翻译 key 前缀：.apply(prefix) 会把 prefix 接到 name/prettyName/comment 前 ----
    // 拼成翻译键（prefix.name/prettyName/comment.<cleanName>），界面按客户端语言取词。
    private static final String PREFIX_SST = "nomorezombies.config.sst";
    private static final String PREFIX_POWERUP = "nomorezombies.config.powerup";
    private static final String PREFIX_RECORD = "nomorezombies.config.record";
    private static final String PREFIX_AA_COMMAND = "nomorezombies.config.aacommand";
    private static final String PREFIX_QOL = "nomorezombies.config.qol";
    private static final String PREFIX_HIDE = "nomorezombies.config.hide";
    private static final String PREFIX_ZOOM = "nomorezombies.config.zoom";
    private static final String PREFIX_SNEAK = "nomorezombies.config.sneak";
    private static final String PREFIX_GAMMA = "nomorezombies.config.gamma";
    private static final String PREFIX_FREECAM = "nomorezombies.config.freecamera";

    // ======================= 波次计时 (spawntimes) =======================

    /**
     * 波次计时提示的配置组：波次出生 / 末波的提示音效（ID 与音高）、
     * 末波倒计时与整场变色提示，全收在这一个屋里。
     *
     * <p>这里只摆「怎么播」，不碰「播不播」——全局总开关见
     * {@link QoL#WAVE_SOUND_ENABLED}。地图生效范围由下面 4 个开关各管一张图，
     * 全开 = 所有地图、按需关闭即可，比原来的三档下拉更细。开 / 关由
     * {@link QoL#WAVE_SOUND_ENABLED} 总开关统一承担，这里不再单设「关闭」。
     */
    public static class Spawntimes {
        /** 每波刷怪音效在「外星游乐园」是否生效。默认开。 */
        public static final ConfigBoolean WAVE_SOUND_AA =
                new ConfigBoolean("waveSoundAA", true).apply(PREFIX_SST);
        /** 每波刷怪音效在「穷途末路」是否生效。默认开。 */
        public static final ConfigBoolean WAVE_SOUND_DE =
                new ConfigBoolean("waveSoundDE", true).apply(PREFIX_SST);
        /** 每波刷怪音效在「坏血之宫」是否生效。默认开。 */
        public static final ConfigBoolean WAVE_SOUND_BB =
                new ConfigBoolean("waveSoundBB", true).apply(PREFIX_SST);
        /** 每波刷怪音效在「监狱」是否生效。默认开。 */
        public static final ConfigBoolean WAVE_SOUND_PRISON =
                new ConfigBoolean("waveSoundPrison", true).apply(PREFIX_SST);
        public static final ConfigString PRECEDED_WAVE_SOUND =
                new ConfigString("precededWaveSound", "minecraft:block.note_block.pling").apply(PREFIX_SST);
        public static final ConfigDouble PRECEDED_WAVE_PITCH =
                new ConfigDouble("precededWavePitch", 2.0, 0.0, 2.0, true).apply(PREFIX_SST);
        public static final ConfigString LAST_WAVE_SOUND =
                new ConfigString("lastWaveSound", "minecraft:entity.experience_orb.pickup").apply(PREFIX_SST);
        public static final ConfigDouble LAST_WAVE_PITCH =
                new ConfigDouble("lastWavePitch", 0.5, 0.0, 2.0, true).apply(PREFIX_SST);
        /** 末波倒计时音效（可绑热键，全地图生效，不限于穷途末路/坏血之宫）：
         * 每回合末波前播放 3-2-1 倒计时，提示玩家「这波打完就到收尾关」。 */
        public static final ConfigBooleanHotkeyed FINAL_WAVE_COUNTDOWN =
                new ConfigBooleanHotkeyed("finalWaveCountDown", false, "").apply(PREFIX_SST);
        public static final ConfigString COUNTDOWN_SOUND =
                new ConfigString("countDownSound", "minecraft:block.note_block.pling").apply(PREFIX_SST);
        public static final ConfigDouble COUNTDOWN_PITCH =
                new ConfigDouble("countDownPitch", 1.5, 0.0, 2.0, true).apply(PREFIX_SST);
        public static final ConfigBooleanHotkeyed COLOR_ALERT =
                new ConfigBooleanHotkeyed("colorAlert", false, "").apply(PREFIX_SST);

        // 界面展示顺序刻意排成「按钮（开关/热键）> 滑动条（音高）> 文本输入（音效 ID）」：
        // 最常碰的开关放最前，音高滑条居中，音效 ID 这种要打字的压到最底。
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                WAVE_SOUND_AA,
                WAVE_SOUND_DE,
                WAVE_SOUND_BB,
                WAVE_SOUND_PRISON,
                FINAL_WAVE_COUNTDOWN,
                COLOR_ALERT,
                PRECEDED_WAVE_PITCH,
                LAST_WAVE_PITCH,
                COUNTDOWN_PITCH,
                PRECEDED_WAVE_SOUND,
                LAST_WAVE_SOUND,
                COUNTDOWN_SOUND
        );
    }

    // ======================= 道具系统 (powerups) =======================

    /**
     * 道具系统配置组：道具预警与提醒的输出方式。
     *
     * <p>全组只有两项，且都不提供「关闭」选项——预警 / 提醒的开关合在一把钥匙上：
     * {@link #POWERUP_PREDICT}（已绑热键）一关，下面这两个输出项自然就没人干活了。
     */
    public static class Powerups {
        public static final ConfigBooleanHotkeyed POWERUP_PREDICT =
                new ConfigBooleanHotkeyed("powerupPredict", false, "").apply(PREFIX_POWERUP);
        /** 道具提醒输出方式：自己 / 队伍(/pc) / 公聊(/ac) / 关闭——决定提醒发去哪个频道，
         * 选「自己」最安静，选公聊则整局都看得见。 */
        public static final ConfigOptionList ALERT_OUTPUT =
                new ConfigOptionList("alertOutput", AlertOutput.SELF).apply(PREFIX_POWERUP);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                POWERUP_PREDICT,
                ALERT_OUTPUT
        );
    }

    // ======================= 计时记录 (recorder) =======================

    /**
     * 计时记录配置组：回合数据统计聊天播报的频率，所有地图共用一档。
     *
     * <p>这里只挑播报频率，不管开与关——开关由 {@link QoL#RECORD_ENABLED}（已绑热键）
     * 统一承担，所以这个选项不带「关闭」，避免同一个语义上两把锁。
     * 单档不分图：AA 是 105 回合的马拉松图，短图最多 40 回合，同一档在两图上每场录的
     * 条数自然不同——想均衡节奏，短图挑「每 5 回合」、AA 挑「每 10 回合」即可。
     */
    public static class Record {
        public static final ConfigOptionList ROUNDS_RECORD =
                new ConfigOptionList("roundsRecord", RecordTiming.ALL).apply(PREFIX_RECORD);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                ROUNDS_RECORD
        );
    }

    // ======================= 外星游乐园自动指挥 (AA auto commander) =======================

    /**
     * 外星游乐园（AA）自动指挥配置组：播报输出方式与信息模板。
     *
     * <p>开与关由 {@link QoL#AA_COMMAND_ENABLED}（已绑热键）统一承担，这里只管
     * 「往哪说、说什么」；模板还支持本地化默认值，语言切换时自动重写，见
     * {@link I18nTemplateConfig}。
     */
    public static class AaCommand {
        /** 输出方式：仅自己 / 队伍(/pc) / 公聊(/ac)。直接复用 {@link AlertOutput}
         * （同为 SELF/PARTY/CHAT，无「关闭」——开关由 AA_COMMAND_ENABLED 承担）。 */
        public static final ConfigOptionList OUTPUT =
                new ConfigOptionList("output", AlertOutput.SELF).apply(PREFIX_AA_COMMAND);
        /** 输出信息模板（输入框）。支持 {round}/{point}/{boss}/{difficulty} 四个变量；
         * 空串或含未知变量都算非法，自动回退默认模板——宁可说通用话也不报错。
         * 默认 / 重置随客户端语言走（nomorezombies.aacommand.defaultTemplate），
         * 语言切换自动重写，见 {@link I18nTemplateConfig}。 */
        public static final I18nTemplateConfig TEMPLATE =
                new I18nTemplateConfig("template").apply(PREFIX_AA_COMMAND);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                OUTPUT,
                TEMPLATE
        );
    }

    // ======================= HUD 坐标与缩放（仅 HUDEditor 编辑，不进配置界面） =======================

    /**
     * HUD 布局配置组：各 HUD 元素的位置（屏幕比例 0.0~1.0）、缩放（0.5~2.0）
     * 与独立可见性。
     *
     * <p>这一整组都不进配置界面，只由 {@link HUDEditor} 拖拽编辑；坐标记成负值
     * （< 0）代表「用户还没手动调过」，此时渲染端走 {@link GlobalConfig#getXSpawnTime(int)}
     * 这类解析方法按默认贴边自动排布（见下文解析区）——手动拖过才写死坐标。
     */
    public static class Hud {
        public static final ConfigDouble X_SPAWN_TIME = new ConfigDouble("xSpawnTime", 0.883, -1.0, 1.0);
        public static final ConfigDouble Y_SPAWN_TIME = new ConfigDouble("ySpawnTime", 0.799, -1.0, 1.0);
        public static final ConfigDouble X_POWERUP = new ConfigDouble("xPowerup", 0.004, -1.0, 1.0);
        public static final ConfigDouble Y_POWERUP = new ConfigDouble("yPowerup", 0.256, -1.0, 1.0);
        public static final ConfigDouble X_TEAM_STATS = new ConfigDouble("xTeamStats", 0.004, -1.0, 1.0);
        public static final ConfigDouble Y_TEAM_STATS = new ConfigDouble("yTeamStats", 0.007, -1.0, 1.0);
        public static final ConfigDouble SCALE_SPAWN_TIME = new ConfigDouble("scaleSpawnTime", 1.0, 0.5, 2.0, true);
        public static final ConfigDouble SCALE_POWERUP = new ConfigDouble("scalePowerup", 1.0, 0.5, 2.0, true);
        public static final ConfigDouble SCALE_TEAM_STATS = new ConfigDouble("scaleTeamStats", 1.001, 0.5, 2.0, true);
        public static final ConfigDouble X_GAME_TIME = new ConfigDouble("xGameTime", 0.829, -1.0, 1.0);
        public static final ConfigDouble Y_GAME_TIME = new ConfigDouble("yGameTime", 0.007, -1.0, 1.0);
        public static final ConfigDouble SCALE_GAME_TIME = new ConfigDouble("scaleGameTime", 1.0, 0.5, 2.0, true);
        public static final ConfigDouble X_LRQUEUE = new ConfigDouble("xLrQueue", 0.004, -1.0, 1.0);
        public static final ConfigDouble Y_LRQUEUE = new ConfigDouble("yLrQueue", 0.871, -1.0, 1.0);
        public static final ConfigDouble SCALE_LRQUEUE = new ConfigDouble("scaleLrQueue", 1.0, 0.5, 2.0, true);
        public static final ConfigDouble X_AA_COMMAND = new ConfigDouble("xAaCommand", 0.004, -1.0, 1.0);
        public static final ConfigDouble Y_AA_COMMAND = new ConfigDouble("yAaCommand", 0.455, -1.0, 1.0);
        public static final ConfigDouble SCALE_AA_COMMAND = new ConfigDouble("scaleAaCommand", 1.0, 0.5, 2.0, true);
        public static final ConfigDouble X_CPS = new ConfigDouble("xCps", 0.695, -1.0, 1.0);
        public static final ConfigDouble Y_CPS = new ConfigDouble("yCps", 0.928, -1.0, 1.0);
        public static final ConfigDouble SCALE_CPS = new ConfigDouble("scaleCps", 1.0, 0.5, 2.0, true);

        // 各 HUD 独立可见性（仅 HUDEditor 编辑，不进配置界面）。
        // 默认全开：总开关 HUD_MASTER 默认关，把它打开后所有 HUD 一起显示，
        // 谁想藏哪块，进 HUD 编辑器单独关对应项。
        // 渲染条件 = 总开关(QoL.HUD_MASTER) && 本项可见性，缺一不可。
        public static final ConfigBoolean VISIBLE_SPAWN_TIME = new ConfigBoolean("visibleSpawnTime", true);
        public static final ConfigBoolean VISIBLE_POWERUP = new ConfigBoolean("visiblePowerup", true);
        public static final ConfigBoolean VISIBLE_TEAM_STATS = new ConfigBoolean("visibleTeamStats", true);
        public static final ConfigBoolean VISIBLE_GAME_TIME = new ConfigBoolean("visibleGameTime", true);
        public static final ConfigBoolean VISIBLE_LRQUEUE = new ConfigBoolean("visibleLrQueue", false);
        public static final ConfigBoolean VISIBLE_AA_COMMAND = new ConfigBoolean("visibleAaCommand", false);
        public static final ConfigBoolean VISIBLE_CPS = new ConfigBoolean("visibleCps", true);

        public static final ImmutableList<IConfigValue> OPTIONS = ImmutableList.of(
                X_SPAWN_TIME,
                Y_SPAWN_TIME,
                X_POWERUP,
                Y_POWERUP,
                X_TEAM_STATS,
                Y_TEAM_STATS,
                X_GAME_TIME,
                Y_GAME_TIME,
                X_LRQUEUE,
                Y_LRQUEUE,
                X_AA_COMMAND,
                Y_AA_COMMAND,
                X_CPS,
                Y_CPS,
                SCALE_SPAWN_TIME,
                SCALE_POWERUP,
                SCALE_TEAM_STATS,
                SCALE_GAME_TIME,
                SCALE_LRQUEUE,
                SCALE_AA_COMMAND,
                SCALE_CPS,
                VISIBLE_SPAWN_TIME,
                VISIBLE_POWERUP,
                VISIBLE_TEAM_STATS,
                VISIBLE_GAME_TIME,
                VISIBLE_LRQUEUE,
                VISIBLE_AA_COMMAND,
                VISIBLE_CPS
        );
    }

    // ======================= QoL =======================

    /**
     * 实用功能（QoL）配置组：各功能的总开关（大多绑了热键）、ESP / 血条 / 隐身等
     * 视觉效果开关，外加打开配置界面 / HUD 编辑器的快捷键。
     *
     * <p>此组的 {@link ConfigBooleanHotkeyed} 都支持游戏内热键一键开/关——把最常见的
     * 操作放到键上，是这套配置的默认姿势，进菜单只是备选路径。
     */
    public static class QoL {
        /** 所有 HUD 的总开关（默认关，同 Tweakeroo）：一旦关闭，所有 HUD 一律隐藏，
         * 权重压过编辑器里各 HUD 的独立显示开关。各 HUD 可见性默认全开——把总开关
         * 打开后全部显示，要藏哪块再进 HUD 编辑器单独关。 */
        public static final ConfigBooleanHotkeyed HUD_MASTER =
                new ConfigBooleanHotkeyed("utilityHud", false, "").apply(PREFIX_QOL);
        /** 波次出生音效总开关（可绑热键）：只管「出声或不出声」，涵盖波次出生提示音
         * 与末波 3-2-1 倒计时；地图生效范围（4 个地图开关）与音效 ID / 音高这些细节
         * 留在「全局配置」页慢慢调。默认关。 */
        public static final ConfigBooleanHotkeyed WAVE_SOUND_ENABLED =
                new ConfigBooleanHotkeyed("waveSoundEnabled", false, "").apply(PREFIX_QOL);
        /** 回合用时录制总开关（可绑热键，默认关，与其余功能开关一致）：只管播或不播
         * 回合用时统计；播报密度（每回合 / 每 5 / 每 10）在「全局配置」页按地图挑。 */
        public static final ConfigBooleanHotkeyed RECORD_ENABLED =
                new ConfigBooleanHotkeyed("recordEnabled", false, "").apply(PREFIX_QOL);
        /** 队友 ESP：为名单内战斗中（绿）/ 倒地身体（黄）的队友绘制线框——实时掌握队友状态。
         * 渲染机制（{@link #TEAMMATE_ESP_RENDER_MODE}）控制是否穿墙。 */
        public static final ConfigBooleanHotkeyed TEAMMATE_ESP =
                new ConfigBooleanHotkeyed("teammateEsp", false, "").apply(PREFIX_QOL);
        /** 僵尸 ESP：为僵尸 / 狼 / 烈焰人等敌对生物绘制红色线框——扫图时一眼看清威胁位置。
         * 渲染机制（{@link #ZOMBIE_ESP_RENDER_MODE}）控制是否穿墙。 */
        public static final ConfigBooleanHotkeyed ZOMBIE_ESP =
                new ConfigBooleanHotkeyed("zombieEsp", false, "").apply(PREFIX_QOL);
        /** 道具 ESP：为已刷出的强化道具盔甲架绘制白色线框——别让道具刷在角落看不见。
         * 渲染机制（{@link #POWERUP_ESP_RENDER_MODE}）控制是否穿墙。 */
        public static final ConfigBooleanHotkeyed POWERUP_ESP =
                new ConfigBooleanHotkeyed("powerupEsp", false, "").apply(PREFIX_QOL);
        public static final ConfigBooleanHotkeyed ENTITY_HEALTH_BAR =
                new ConfigBooleanHotkeyed("entityHealthBar", false, "").apply(PREFIX_QOL);
        /** 队友 ESP 渲染机制：常规（仅深度层，墙后不可见）/ 穿墙（深度+穿墙双层，墙后可见）。默认常规。 */
        public static final ConfigOptionList TEAMMATE_ESP_RENDER_MODE =
                new ConfigOptionList("teammateEspRenderMode", EspRenderMode.NORMAL).apply(PREFIX_QOL);
        /** 僵尸 ESP 渲染机制：常规（仅深度层，墙后不可见）/ 穿墙（深度+穿墙双层，墙后可见）。默认常规。 */
        public static final ConfigOptionList ZOMBIE_ESP_RENDER_MODE =
                new ConfigOptionList("zombieEspRenderMode", EspRenderMode.NORMAL).apply(PREFIX_QOL);
        /** 道具 ESP 渲染机制：常规（仅深度层，墙后不可见）/ 穿墙（深度+穿墙双层，墙后可见）。默认常规。 */
        public static final ConfigOptionList POWERUP_ESP_RENDER_MODE =
                new ConfigOptionList("powerupEspRenderMode", EspRenderMode.NORMAL).apply(PREFIX_QOL);
        /** 僵尸血条渲染机制：常规（深度测试，墙后不可见）/ 穿墙（始终可见）。默认常规。 */
        public static final ConfigOptionList HEALTH_BAR_RENDER_MODE =
                new ConfigOptionList("healthBarRenderMode", EspRenderMode.NORMAL).apply(PREFIX_QOL);
        /** 总穿墙渲染距离（滑动条，5 ~ 200 格，默认 100）：穿墙渲染（穿墙模式下的 ESP 穿墙层、
         * 血条的穿透显示）的最大生效距离。超出该距离的实体不再穿墙显示——ESP 线框退回仅深度层、
         * 血条退回深度测试（墙后不可见）。单一总开关，对所有 ESP 类型与血条统一生效。 */
        public static final ConfigDouble THROUGH_WALL_RENDER_DISTANCE =
                new ConfigDouble("throughWallRenderDistance", 100.0, 5.0, 200.0, true).apply(PREFIX_QOL);
        public static final ConfigBooleanHotkeyed PLAYER_INVISIBLE =
                new ConfigBooleanHotkeyed("playerInvisible", false, "").apply(PREFIX_QOL);
        /** 拦截原生 Boss 血条（屏幕顶部）：开启后不再渲染 BossBarHud，让屏幕顶部干净些。 */
        public static final ConfigBooleanHotkeyed HIDE_BOSS_BAR =
                new ConfigBooleanHotkeyed("hideBossBar", false, "").apply(PREFIX_QOL);
        /** 隐藏原生计分板（Hypixel 右侧侧边栏）：只是藏起渲染，mod 读 / 改计分板数据
         * 照常走（吃独食机制），所以隐藏不伤任何依赖计分板的逻辑。 */
        public static final ConfigBooleanHotkeyed HIDE_SCOREBOARD =
                new ConfigBooleanHotkeyed("hideScoreboard", false, "").apply(PREFIX_QOL);
        /** 屏蔽除开火外的右键操作：准星射线无视隐形盔甲架（全息字：门价 / 机器提示 /
         * 道具字样），交互方块的右键反应也一并跳过——右键只开枪，避免误触。
         * 代价是开启期间没法操作机器 / 门等交互方块（买枪 / 开门前得临时关掉）；
         * 仅 Zombies 生效。默认关。 */
        public static final ConfigBooleanHotkeyed RIGHT_CLICK_FIRE_ONLY =
                new ConfigBooleanHotkeyed("rightClickFireOnly", false, "").apply(PREFIX_QOL);
        /** 无发射粒子：从收包源头取消玩家附近（约 5.1 格）的枪口火焰 / 烟雾 / 火花粒子，
         * 仅 Zombies 且主手持僵尸枪时生效。cancel 的是入站粒子包，零发包、只影响本地
         * 显示——别人看我们照样开枪有火光。默认关。 */
        public static final ConfigBooleanHotkeyed NO_GUN_FIRE =
                new ConfigBooleanHotkeyed("noGunFire", false, "").apply(PREFIX_QOL);
        /** 无火焰效果：着火时完全移除屏幕火焰遮罩（单一开关，取消渲染不重绘，不打断
         * 别的画面）。仅 Zombies 生效。默认关。 */
        public static final ConfigBooleanHotkeyed NO_FIRE_EFFECT =
                new ConfigBooleanHotkeyed("noFireEffect", false, "").apply(PREFIX_QOL);
        /** 外星游乐园自动指挥：AA 图每回合开始自动按「全局配置」页的输出方式 / 信息模板
         * 播报回合指挥（推荐点位 / 首领 / 难度）。仅外星游乐园（AA）生效；HUD 显示由
         * HUD 总开关 + HUD 编辑器里的该 HUD 开关独立控制。默认关。 */
        public static final ConfigBooleanHotkeyed AA_COMMAND_ENABLED =
                new ConfigBooleanHotkeyed("aaCommandEnabled", false, "").apply(PREFIX_QOL);
        /** 平滑缩放功能总开关（可绑热键，默认关）：打开后，在 Zombies 局内按「全局配置」页
         * 的缩放快捷键（默认 C）即可缩放视角。倍率 / 时长 / 动画 / 按键行为与快捷键绑定
         * 都放在「全局配置」页，这里只管开不开这门功能。 */
        public static final ConfigBooleanHotkeyed ZOOM_ENABLED =
                new ConfigBooleanHotkeyed("zoomEnabled", false, "").apply(PREFIX_QOL);
        /** 永久潜行总开关（可绑热键，默认关）：开启后在 Zombies 局内强制潜行——下蹲动画、
         * 防掉落边缘、缩小碰撞箱，走完整 vanilla 潜行状态机，不掉进奇怪状态。
         * 默认在 GUI 界面（背包等）里不生效，想改去「全局配置」页。 */
        public static final ConfigBooleanHotkeyed ALWAYS_SNEAK_ENABLED =
                new ConfigBooleanHotkeyed("alwaysSneakEnabled", false, "").apply(PREFIX_QOL);
        /** 永久疾跑总开关（可绑热键，默认关）：开启后在 Zombies 局内按 W 前进即自动疾跑，
         * 等价于疾跑键永远按住——但饥饿 / 失明 / 泡水 / 手持使用物品这些原版疾跑约束
         * 一条不删，游戏该不让你跑就不让你跑。 */
        public static final ConfigBooleanHotkeyed ALWAYS_SPRINT_ENABLED =
                new ConfigBooleanHotkeyed("alwaysSprintEnabled", false, "").apply(PREFIX_QOL);
        /** 伽马覆写总开关（可绑热键，默认关）：开启后在 Zombies 局内把游戏亮度强制设为
         * 「全局配置」页的覆写值（默认 16，夜视级，不受原版亮度滑条 0~1 限制）；
         * 关闭即还原玩家原亮度。纯客户端视觉，只改显示不发包。 */
        public static final ConfigBooleanHotkeyed GAMMA_OVERRIDE_ENABLED =
                new ConfigBooleanHotkeyed("gammaOverrideEnabled", false, "").apply(PREFIX_QOL);
        /** 自由视角总开关（可绑热键，默认关）：开启后在 Zombies 局内把渲染视角切到替身
         * 相机实体——相机脱离身体自由飞行（WASD 移动 + 鼠标转向），玩家本体冻结原地。
         * 相机飞不飞、能不能交互，由「全局配置」页的 playerMovement / playerInputs
         * 子选项决定；关闭瞬间还原，玩家零残留。 */
        public static final ConfigBooleanHotkeyed FREE_CAMERA_ENABLED =
                new ConfigBooleanHotkeyed("freeCameraEnabled", false, "").apply(PREFIX_QOL);
        /** 打开配置界面（默认组合键 Z+X）——按一下直达 MaLiLib 配置页，最常用的入口之一。 */
        public static final ConfigHotkey OPEN_GUI_CONFIGS =
                new ConfigHotkey("openConfigGui", "Z,X").apply(PREFIX_QOL);
        /** 打开 HUD 编辑器——HUD 坐标 / 缩放 / 独立可见性的拖拽入口，见 {@link HUDEditor}。 */
        public static final ConfigHotkey OPEN_HUD_EDITOR =
                new ConfigHotkey("openHudEditor", "").apply(PREFIX_QOL);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                HUD_MASTER,
                WAVE_SOUND_ENABLED,
                RECORD_ENABLED,
                TEAMMATE_ESP,
                ZOMBIE_ESP,
                POWERUP_ESP,
                ENTITY_HEALTH_BAR,
                TEAMMATE_ESP_RENDER_MODE,
                ZOMBIE_ESP_RENDER_MODE,
                POWERUP_ESP_RENDER_MODE,
                HEALTH_BAR_RENDER_MODE,
                THROUGH_WALL_RENDER_DISTANCE,
                PLAYER_INVISIBLE,
                HIDE_BOSS_BAR,
                HIDE_SCOREBOARD,
                RIGHT_CLICK_FIRE_ONLY,
                NO_GUN_FIRE,
                NO_FIRE_EFFECT,
                AA_COMMAND_ENABLED,
                ZOOM_ENABLED,
                ALWAYS_SNEAK_ENABLED,
                ALWAYS_SPRINT_ENABLED,
                GAMMA_OVERRIDE_ENABLED,
                FREE_CAMERA_ENABLED,
                OPEN_GUI_CONFIGS,
                OPEN_HUD_EDITOR
        );

        public static final List<IHotkey> HOTKEY_LIST = ImmutableList.of(
                HUD_MASTER,
                WAVE_SOUND_ENABLED,
                RECORD_ENABLED,
                TEAMMATE_ESP,
                ZOMBIE_ESP,
                POWERUP_ESP,
                ENTITY_HEALTH_BAR,
                PLAYER_INVISIBLE,
                HIDE_BOSS_BAR,
                HIDE_SCOREBOARD,
                RIGHT_CLICK_FIRE_ONLY,
                NO_GUN_FIRE,
                NO_FIRE_EFFECT,
                AA_COMMAND_ENABLED,
                ZOOM_ENABLED,
                ALWAYS_SNEAK_ENABLED,
                ALWAYS_SPRINT_ENABLED,
                GAMMA_OVERRIDE_ENABLED,
                FREE_CAMERA_ENABLED,
                OPEN_GUI_CONFIGS,
                OPEN_HUD_EDITOR,
                Query.OPEN_QUERY_GUI
        );

        /** 全部可绑定热键（含各分组的 {@link ConfigBooleanHotkeyed} 功能开关）。只有注册进
         * MaLiLib 键位管理，热键才真正生效——否则配置界面能绑、按下却静默失效；
         * 功能开关注册后 MaLiLib 自带 toggle 回调，开关注册即接线，不用再手动挂事件。
         */
        public static final List<IHotkey> ALL_HOTKEYS = ImmutableList.<IHotkey>builder()
                .addAll(HOTKEY_LIST)
                .add(Spawntimes.FINAL_WAVE_COUNTDOWN)
                .add(Spawntimes.COLOR_ALERT)
                .add(Powerups.POWERUP_PREDICT)
                .addAll(ImmutableList.of(
                        Hide.HIDE_GOLD,
                        Hide.HIDE_WINDOW,
                        Hide.HIDE_HIT_TARGET,
                        Hide.HIDE_LUCKY_CHEST,
                        Hide.HIDE_OPEN_AREA,
                        Hide.HIDE_PLAYER_CONNECTION))
                .add(Zoom.ZOOM_KEY) // 缩放键：纯热键（无布尔），缩放时按 isKeybindHeld/isPressed 轮询
                .build();
    }

    // ======================= 玩家数据查询 (player query) =======================

    /**
     * 玩家数据查询：Hypixel API Key（掩码显示 + 加密落盘）与打开查询界面的热键。
     *
     * <p>Key 的明文只在 {@link #getApiKeyPlain()} 一处交给查询逻辑读取，界面文本框
     * 始终显示掩码——即使别人盯屏也抄不走完整 Key。
     */
    public static class Query {
        public static final ConfigApiKey API_KEY =
                new ConfigApiKey("apiKey", "").apply(PREFIX_QOL);
        /** 打开玩家数据查询界面（自由查询 / 局内查询）——查询功能的唯一入口热键。 */
        public static final ConfigHotkey OPEN_QUERY_GUI =
                new ConfigHotkey("openQueryGui", "").apply(PREFIX_QOL);

        public static final ImmutableList<IConfigValue> OPTIONS = ImmutableList.of(
                API_KEY,
                OPEN_QUERY_GUI
        );
    }

    /** 当前配置的 Hypixel API Key 明文，查询逻辑统一从这取；未配置时返回空串。 */
    public static String getApiKeyPlain() {
        return Query.API_KEY.getPlainValue();
    }

    // ======================= 聊天过滤 (chat filter) =======================

    /**
     * 聊天消息过滤开关（默认全关，用户按需开启）。统一用 {@link ConfigBooleanHotkeyed}：
     * 每个过滤项既能单独绑热键随时切换，也能在配置界面用开关按钮直接启停。
     *
     * <p>故意不提供「击倒 / 救治 / 道具拾取」的隐藏——这三类消息正是 mod 队伍统计与
     * 道具检测的聊天输入，藏了它们功能就瞎了，所以宁可让玩家自己决定。
     */
    public static class Hide {
        public static final ConfigBooleanHotkeyed HIDE_GOLD =
                new ConfigBooleanHotkeyed("hideGold", false, "").apply(PREFIX_HIDE);
        public static final ConfigBooleanHotkeyed HIDE_WINDOW =
                new ConfigBooleanHotkeyed("hideWindowRepair", false, "").apply(PREFIX_HIDE);
        public static final ConfigBooleanHotkeyed HIDE_HIT_TARGET =
                new ConfigBooleanHotkeyed("hideHitTarget", false, "").apply(PREFIX_HIDE);
        public static final ConfigBooleanHotkeyed HIDE_LUCKY_CHEST =
                new ConfigBooleanHotkeyed("hideLuckyChest", false, "").apply(PREFIX_HIDE);
        public static final ConfigBooleanHotkeyed HIDE_OPEN_AREA =
                new ConfigBooleanHotkeyed("hideOpenArea", false, "").apply(PREFIX_HIDE);
        /** 玩家进出（默认关）：会连带隐藏「X 离开了游戏」行——队伍统计的 LEFT 事件
         * 不依赖这条聊天，靠计分板兜底，所以藏着也不误报。 */
        public static final ConfigBooleanHotkeyed HIDE_PLAYER_CONNECTION =
                new ConfigBooleanHotkeyed("hidePlayerConnection", false, "").apply(PREFIX_HIDE);

        public static final ImmutableList<IConfigValue> OPTIONS = ImmutableList.of(
                HIDE_GOLD,
                HIDE_WINDOW,
                HIDE_HIT_TARGET,
                HIDE_LUCKY_CHEST,
                HIDE_OPEN_AREA,
                HIDE_PLAYER_CONNECTION
        );
    }

    // ======================= 平滑缩放 (smooth zoom) =======================

    /**
     * 平滑缩放（Zoomify 简化版）：5 个参数 + 缩放快捷键。
     *
     * <p>只有在「实用功能」页的 ZOOM_ENABLED 开启后、且正处于 Zombies 局内时才生效
     * （门控见 {@link cn.gsfy.nmz.client.feature.zoom.ZoomHandler#isActive()}）；
     * 缩放走 FOV 除法实现（注入 {@link net.minecraft.client.render.GameRenderer#getFov}），
     * 所以视角拉近不重新建模，纯视觉放大。
     */
    public static class Zoom {
        /** 完全放大时的倍率（1.0 ~ 10.0，默认 4.0，与 Zoomify 默认一致）——越大看得越细，也越晃。 */
        public static final ConfigDouble INITIAL_ZOOM =
                new ConfigDouble("initialZoom", 4.0, 1.0, 10.0, true).apply(PREFIX_ZOOM);
        /** 放大动画时长（秒，0.1 ~ 5.0，默认 1.0）——拉近太快晃眼、太慢耽误开枪，给个中间值。 */
        public static final ConfigDouble ZOOM_IN_TIME =
                new ConfigDouble("zoomInTime", 1.0, 0.1, 5.0, true).apply(PREFIX_ZOOM);
        /** 缩小动画时长（秒，0.1 ~ 5.0，默认 0.5）——缩回比放大快，视野恢复不拖沓。 */
        public static final ConfigDouble ZOOM_OUT_TIME =
                new ConfigDouble("zoomOutTime", 0.5, 0.1, 5.0, true).apply(PREFIX_ZOOM);
        /** 动画过渡方式（缓动曲线）：放大用所选曲线，缩小自动用其相反——默认缓出指数，
         * 与 Zoomify 一致。细节见 {@link ZoomEasing}。 */
        public static final ConfigOptionList EASING =
                new ConfigOptionList("easing", ZoomEasing.EASE_OUT_EXP).apply(PREFIX_ZOOM);
        /** 按键行为：长按 (HOLD) / 点按切换 (TOGGLE)——习惯瞄一眼松手选 HOLD，想常驻缩放就选 TOGGLE。 */
        public static final ConfigOptionList KEY_BEHAVIOUR =
                new ConfigOptionList("keyBehaviour", ZoomKeyBehaviour.HOLD).apply(PREFIX_ZOOM);
        /** 缩放快捷键（默认 C）；配合 KEY_BEHAVIOUR 决定按住还是切换。 */
        public static final ConfigHotkey ZOOM_KEY =
                new ConfigHotkey("zoomKey", "C").apply(PREFIX_ZOOM);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                INITIAL_ZOOM,
                ZOOM_IN_TIME,
                ZOOM_OUT_TIME,
                EASING,
                KEY_BEHAVIOUR,
                ZOOM_KEY
        );
    }

    // ======================= 永久潜行 (always sneak) =======================

    /**
     * 永久潜行配置组：总开关在 {@link QoL#ALWAYS_SNEAK_ENABLED}，这里只放它的子选项。
     *
     * <p>全组只有一个「GUI 界面里是否仍强制潜行」的开关——把总开关的默认行为细分，
     * 不另起炉灶；开了它才在菜单里也下蹲，否则一进背包就自动解除。
     */
    public static class Sneak {
        /** 允许在 GUI 界面（背包 / 容器等）中仍强制潜行。默认关——界面里解除强制，
         * 免得一开背包角色就蹲下去，菜单里意外下蹲怪怪的。 */
        public static final ConfigBoolean ALLOW_IN_GUIS =
                new ConfigBoolean("alwaysSneakAllowInGuis", false).apply(PREFIX_SNEAK);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                ALLOW_IN_GUIS
        );
    }

    // ======================= 伽马覆写 (gamma override) =======================

    /**
     * 伽马覆写配置组：总开关在 {@link QoL#GAMMA_OVERRIDE_ENABLED}，这里只放覆写的亮度值。
     *
     * <p>只此一个参数，替总开关回答「亮到什么程度」；不直写原版选项，为什么绕开它
     * 见下方 {@link #OVERRIDE_VALUE} 的踩坑说明。
     */
    public static class Gamma {
        /**
         * 开启时作用于光照的亮度值（0.0 ~ 32.0，默认 16.0，夜视级；不受原版亮度滑条
         * 0~1 限制）。
         *
         * <p>当初踩过坑：原版亮度选项的 codec 合法范围是 [0,1]，直接写 16 会让
         * GameOptions.save() 编码越界报错，接着资源重载、直接黑屏——所以后来改成不碰
         * vanilla 选项字段，由 {@code LightmapBrightnessMixin} 在
         * LightmapTextureManager.update() 里用该值替换 BrightnessFactor 计算中的 gamma
         * （原版 lightmap shader 对 BrightnessFactor 最终会 clamp 到 [0,1]，>1 安全产生
         * 夜视级亮度）。原值无需存档：选项字段始终是玩家原值，离开 Zombies 自然还原。
         */
        public static final ConfigDouble OVERRIDE_VALUE =
                new ConfigDouble("gammaOverrideValue", 16.0, 0.0, 32.0, true).apply(PREFIX_GAMMA);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                OVERRIDE_VALUE
        );
    }

    // ======================= 自由视角 (free camera) =======================

    /**
     * 自由视角配置组：总开关在 {@link QoL#FREE_CAMERA_ENABLED}，这里放相机飞行模式下的
     * 玩家移动 / 输入允许度与相机飞行速度。
     *
     * <p>两个布尔子选项决定飞行时玩家本体还能不能动、能不能操作——全关最省心，
     * 相机只管飞，本体冻结，防止盲视误操作。
     */
    public static class FreeCam {
        /** 允许玩家移动：开启后相机飞行期间玩家仍可走动，相机反而成了静止观察点；
         * 关闭（默认）则玩家完全冻结，相机随 WASD / 鼠标自由飞行。
         * 只有在「相机飞行模式」（即本项关闭）时，相机才受本组移动参数驱动。 */
        public static final ConfigBoolean PLAYER_MOVEMENT =
                new ConfigBoolean("playerMovement", false).apply(PREFIX_FREECAM);
        /** 允许玩家输入：开启后相机飞行期间仍可攻击 / 挖掘 / 使用 / 交互；关闭（默认）则
         * 完全封禁——本体冻着又乱操作，容易盲视误触，默认先锁死。 */
        public static final ConfigBoolean PLAYER_INPUTS =
                new ConfigBoolean("playerInputs", false).apply(PREFIX_FREECAM);
        /** 相机飞行速度倍率（0.1 ~ 10.0，默认 1.0）；按住冲刺键再乘 ×3，赶路够快。 */
        public static final ConfigDouble SPEED =
                new ConfigDouble("speed", 1.0, 0.1, 10.0, true).apply(PREFIX_FREECAM);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                PLAYER_MOVEMENT,
                PLAYER_INPUTS,
                SPEED
        );
    }

    // ======================= 枚举 =======================

    /** 回合用时录制频率选项（无「关闭」——录制只挑播报密度，开 / 关由实用功能页的
     * {@link QoL#RECORD_ENABLED} 承担）：QUINTUPLE 每 5 回合、TENFOLD 每 10 回合、ALL 每回合都播。 */
    public enum RecordTiming implements IConfigOptionListEntry {
        QUINTUPLE("quintuple"),
        TENFOLD("tenfold"),
        ALL("all");

        public static final ImmutableList<RecordTiming> VALUES = ImmutableList.copyOf(values());

        private final String configString;

        RecordTiming(String configString) {
            this.configString = configString;
        }

        @Override
        public String getStringValue() {
            return this.configString;
        }

        @Override
        public String getDisplayName() {
            return StringUtils.translate("nomorezombies.config.record.timing." + this.configString);
        }

        @Override
        public RecordTiming fromString(String value) {
            for (RecordTiming v : VALUES) {
                if (value.compareToIgnoreCase(v.getStringValue()) == 0) {
                    return v;
                }
            }
            return ALL; // 未知/旧值（如已移除的 "off"）回落默认，免得 ConfigOptionList.value 变 null 崩一屏
        }

        @Override
        public IConfigOptionListEntry cycle(boolean forward) {
            int id = this.ordinal();
            if (forward) {
                if (++id >= values().length) {
                    id = 0;
                }
            } else {
                if (--id < 0) {
                    id = values().length - 1;
                }
            }
            return values()[id];
        }

        @Override
        public String toString() {
            return this.getStringValue();
        }
    }

    /** 道具提醒输出方式（镜像 NEZ ChatOutput；无「关闭」——道具提醒的开 / 关由
     * {@link Powerups#POWERUP_PREDICT} 承担）：自己 / 队伍 / 公聊三档频道。 */
    public enum AlertOutput implements IConfigOptionListEntry {
        SELF("self"),
        PARTY("party"),
        CHAT("chat");

        public static final ImmutableList<AlertOutput> VALUES = ImmutableList.copyOf(values());

        private final String configString;

        AlertOutput(String configString) {
            this.configString = configString;
        }

        @Override
        public String getStringValue() {
            return this.configString;
        }

        @Override
        public String getDisplayName() {
            return StringUtils.translate("nomorezombies.config.powerup.output." + this.configString);
        }

        @Override
        public AlertOutput fromString(String value) {
            for (AlertOutput v : VALUES) {
                if (value.compareToIgnoreCase(v.getStringValue()) == 0) {
                    return v;
                }
            }
            return SELF; // 未知/旧值回落默认，免得 ConfigOptionList.value 变 null 崩一屏
        }

        @Override
        public IConfigOptionListEntry cycle(boolean forward) {
            int id = this.ordinal();
            if (forward) {
                if (++id >= values().length) {
                    id = 0;
                }
            } else {
                if (--id < 0) {
                    id = values().length - 1;
                }
            }
            return values()[id];
        }

        @Override
        public String toString() {
            return this.getStringValue();
        }
    }

    // ======================= 平滑缩放 (smooth zoom) =======================

    /** 缩放动画过渡方式（无「关闭」——开 / 关由 {@link QoL#ZOOM_ENABLED} 总开关承担）。
     *
     * <p>精简到 4 种核心曲线，是嵌入 mod 时做的取舍：放大用所选方式，缩小自动切到
     * {@link #opposite()} 相反曲线（选 EASE_OUT_SINE → 放大缓出、缩小缓入，进出对称）。
     * IN 方向的 EASE_IN_SINE / EASE_IN_EXP 保留在枚举里只给 opposite() 内部映射用，
     * 不进下拉——{@link #VALUES} 才是 UI 展示 / fromString / cycle 的统一集合。 */
    public enum ZoomEasing implements IConfigOptionListEntry {
        LINEAR("linear") {
            @Override public double apply(double t) { return t; }
        },
        EASE_OUT_SINE("ease_out_sine") {
            @Override public double apply(double t) { return Math.sin(t * Math.PI / 2.0); }
            @Override public double inverse(double x) { return Math.asin(x) * 2.0 / Math.PI; }
            @Override public boolean hasInverse() { return true; }
        },
        EASE_IN_SINE("ease_in_sine") {
            @Override public double apply(double t) { return 1.0 - Math.cos(t * Math.PI / 2.0); }
            @Override public double inverse(double x) { return Math.acos(-(x - 1.0)) * 2.0 / Math.PI; }
            @Override public boolean hasInverse() { return true; }
        },
        EASE_OUT_EXP("ease_out_exp") {
            @Override public double apply(double t) {
                if (t == 0.0) return 0.0;
                if (t == 1.0) return 1.0;
                return 1.0 - Math.pow(2.0, 10.0 - LOG2_1023 - 10.0 * t) + INV_1023;
            }
            @Override public double inverse(double x) {
                if (x == 0.0) return 0.0;
                if (x == 1.0) return 1.0;
                return -((Math.log(1.0 - x + INV_1023) - TEN_LN_2 + LN_1023) / TEN_LN_2);
            }
            @Override public boolean hasInverse() { return true; }
        },
        EASE_IN_EXP("ease_in_exp") {
            @Override public double apply(double t) {
                if (t == 0.0) return 0.0;
                if (t == 1.0) return 1.0;
                return Math.pow(2.0, 10.0 * t - LOG2_1023) - INV_1023;
            }
            @Override public double inverse(double x) {
                if (x == 0.0) return 0.0;
                if (x == 1.0) return 1.0;
                return Math.log(1023.0 * x + 1.0) / TEN_LN_2;
            }
            @Override public boolean hasInverse() { return true; }
        },
        INSTANT("instant") {
            @Override public double apply(double t) { return t; }
        };

        /** UI 展示集合：线性 / 缓出正弦 / 缓出指数(默认) / 瞬间——IN 方向曲线只给
         * opposite() 内部映射，刻意不进下拉，免得玩家选到「反着缩」的怪曲线。 */
        public static final ImmutableList<ZoomEasing> VALUES = ImmutableList.of(
                LINEAR, EASE_OUT_SINE, EASE_OUT_EXP, INSTANT);

        // Zoomify TransitionType EXP 曲线用到的预计算常数：1023 = 2^10 - 1，
        // 名称自解释、一次算好，保证曲线在端点处平滑收尾到 [0,1]
        private static final double LOG2_1023 = Math.log(1023.0) / Math.log(2.0);
        private static final double TEN_LN_2 = 10.0 * Math.log(2.0);
        private static final double LN_1023 = Math.log(1023.0);
        private static final double INV_1023 = 1.0 / 1023.0;

        private final String configString;

        ZoomEasing(String configString) {
            this.configString = configString;
        }

        /** 缓动核心：把线性进度 t ∈ [0,1] 映射为缓动后的进度（0 → 1），动画驱动逐帧调用。 */
        public abstract double apply(double t);

        /** 曲线反函数：方向反转时把进度重投影回线性空间，保证衔接连续；未实现反函数的曲线抛异常。 */
        public double inverse(double x) {
            throw new UnsupportedOperationException();
        }

        /** 是否定义了 {@link #inverse(double)}：单边 IN/OUT 曲线有，INSTANT/LINEAR/IN_OUT 没有。 */
        public boolean hasInverse() {
            return false;
        }

        /** 相反方向的曲线：放大用所选、缩小自动用其相反（ease-in ↔ ease-out；LINEAR/INSTANT 为自身）。 */
        public ZoomEasing opposite() {
            switch (this) {
                case EASE_OUT_SINE: return EASE_IN_SINE;
                case EASE_IN_SINE: return EASE_OUT_SINE;
                case EASE_OUT_EXP: return EASE_IN_EXP;
                case EASE_IN_EXP: return EASE_OUT_EXP;
                default: return this;
            }
        }

        @Override
        public String getStringValue() {
            return this.configString;
        }

        @Override
        public String getDisplayName() {
            return StringUtils.translate("nomorezombies.config.zoom.easing." + this.configString);
        }

        @Override
        public ZoomEasing fromString(String value) {
            for (ZoomEasing v : VALUES) {
                if (value.compareToIgnoreCase(v.getStringValue()) == 0) {
                    return v;
                }
            }
            return EASE_OUT_EXP; // 未知/旧值回落默认，免得 ConfigOptionList.value 变 null 崩一屏
        }

        @Override
        public IConfigOptionListEntry cycle(boolean forward) {
            int id = VALUES.indexOf(this);
            if (id < 0) {
                id = 0;
            }
            if (forward) {
                if (++id >= VALUES.size()) {
                    id = 0;
                }
            } else {
                if (--id < 0) {
                    id = VALUES.size() - 1;
                }
            }
            return VALUES.get(id);
        }

        @Override
        public String toString() {
            return this.getStringValue();
        }
    }

    /** 缩放按键行为（无「关闭」——开 / 关由 {@link QoL#ZOOM_ENABLED} 总开关承担）：
     * HOLD 按住缩放、TOGGLE 按一下切一次。 */
    public enum ZoomKeyBehaviour implements IConfigOptionListEntry {
        HOLD("hold"),
        TOGGLE("toggle");

        public static final ImmutableList<ZoomKeyBehaviour> VALUES = ImmutableList.copyOf(values());

        private final String configString;

        ZoomKeyBehaviour(String configString) {
            this.configString = configString;
        }

        @Override
        public String getStringValue() {
            return this.configString;
        }

        @Override
        public String getDisplayName() {
            return StringUtils.translate("nomorezombies.config.zoom.keyBehaviour." + this.configString);
        }

        @Override
        public ZoomKeyBehaviour fromString(String value) {
            for (ZoomKeyBehaviour v : VALUES) {
                if (value.compareToIgnoreCase(v.getStringValue()) == 0) {
                    return v;
                }
            }
            return HOLD; // 未知/旧值回落默认，免得 ConfigOptionList.value 变 null 崩一屏
        }

        @Override
        public IConfigOptionListEntry cycle(boolean forward) {
            int id = this.ordinal();
            if (forward) {
                if (++id >= values().length) {
                    id = 0;
                }
            } else {
                if (--id < 0) {
                    id = values().length - 1;
                }
            }
            return values()[id];
        }

        @Override
        public String toString() {
            return this.getStringValue();
        }
    }

    // ======================= ESP 渲染机制 =======================

    /** ESP / 血条渲染机制（无「关闭」——开 / 关由各 ESP/血条总开关承担）：
     * 常规（NORMAL）= 深度测试 + 穿墙双层叠加（默认，当前行为不变）；
     * 穿墙（THROUGH_WALLS）= 仅穿墙层，不画墙前部分。 */
    public enum EspRenderMode implements IConfigOptionListEntry {
        NORMAL("normal"),
        THROUGH_WALLS("through_walls");

        public static final ImmutableList<EspRenderMode> VALUES = ImmutableList.copyOf(values());

        private final String configString;

        EspRenderMode(String configString) {
            this.configString = configString;
        }

        @Override
        public String getStringValue() {
            return this.configString;
        }

        @Override
        public String getDisplayName() {
            return StringUtils.translate("nomorezombies.config.qol.espRenderMode." + this.configString);
        }

        @Override
        public EspRenderMode fromString(String value) {
            for (EspRenderMode v : VALUES) {
                if (value.compareToIgnoreCase(v.getStringValue()) == 0) {
                    return v;
                }
            }
            return NORMAL;
        }

        @Override
        public IConfigOptionListEntry cycle(boolean forward) {
            int id = this.ordinal();
            if (forward) {
                if (++id >= values().length) {
                    id = 0;
                }
            } else {
                if (--id < 0) {
                    id = values().length - 1;
                }
            }
            return values()[id];
        }

        @Override
        public String toString() {
            return this.getStringValue();
        }
    }

    // ======================= 持久化 =======================

    /**
     * 从 {@code config/nomorezombies.json} 读出全部配置分组（MaLiLib 格式），
     * 按类名分节填回各组的 OPTIONS。
     *
     * <p>文件不存在或不可读时静默跳过，配置保持出厂默认，首启不报错；
     * JSON 解析失败才记一条错误日志，方便排查损坏的配置文件。
     */
    public static void loadFromFile() {
        Path configFile = FileUtils.getConfigDirectoryAsPath().resolve(CONFIG_FILE_NAME);

        if (Files.exists(configFile) && Files.isReadable(configFile)) {
            JsonElement element = JsonUtils.parseJsonFileAsPath(configFile);

            if (element != null && element.isJsonObject()) {
                JsonObject root = element.getAsJsonObject();

                ConfigUtils.readConfigBase(root, "Spawntimes", Spawntimes.OPTIONS);
                ConfigUtils.readConfigBase(root, "Powerups", Powerups.OPTIONS);
                ConfigUtils.readConfigBase(root, "Record", Record.OPTIONS);
                ConfigUtils.readConfigBase(root, "AaCommand", AaCommand.OPTIONS);
                ConfigUtils.readConfigBase(root, "Hud", Hud.OPTIONS);
                ConfigUtils.readConfigBase(root, "QoL", QoL.OPTIONS);
                ConfigUtils.readConfigBase(root, "Query", Query.OPTIONS);
                ConfigUtils.readConfigBase(root, "Hide", Hide.OPTIONS);
                ConfigUtils.readConfigBase(root, "Zoom", Zoom.OPTIONS);
                ConfigUtils.readConfigBase(root, "Sneak", Sneak.OPTIONS);
                ConfigUtils.readConfigBase(root, "Gamma", Gamma.OPTIONS);
                ConfigUtils.readConfigBase(root, "FreeCamera", FreeCam.OPTIONS);

                // 各 HUD 可见性默认全开，默认关的只有总开关 HUD_MASTER——所以
                // 旧版独立开关（showSpawnTimes/powerupAlert/teamStats/lightningRodQueue）
                // 的迁移代码可以删掉：新键缺失时自然取默认 true，不必再搬旧值。
            } else {
                NoMoreZombies.LOGGER.error("loadFromFile(): Failed to parse config file '{}' as a JSON element.", configFile.toAbsolutePath());
            }
        }
    }

    /** 把全部配置分组按 MaLiLib 格式写回 {@code config/nomorezombies.json}；
     * 配置目录不存在就先建目录，保证首次保存不落空。 */
    public static void saveToFile() {
        Path dir = FileUtils.getConfigDirectoryAsPath();

        if (!Files.exists(dir)) {
            FileUtils.createDirectoriesIfMissing(dir);
        }

        if (Files.isDirectory(dir)) {
            JsonObject root = new JsonObject();

            ConfigUtils.writeConfigBase(root, "Spawntimes", Spawntimes.OPTIONS);
            ConfigUtils.writeConfigBase(root, "Powerups", Powerups.OPTIONS);
            ConfigUtils.writeConfigBase(root, "Record", Record.OPTIONS);
            ConfigUtils.writeConfigBase(root, "AaCommand", AaCommand.OPTIONS);
            ConfigUtils.writeConfigBase(root, "Hud", Hud.OPTIONS);
            ConfigUtils.writeConfigBase(root, "QoL", QoL.OPTIONS);
            ConfigUtils.writeConfigBase(root, "Query", Query.OPTIONS);
            ConfigUtils.writeConfigBase(root, "Hide", Hide.OPTIONS);
            ConfigUtils.writeConfigBase(root, "Zoom", Zoom.OPTIONS);
            ConfigUtils.writeConfigBase(root, "Sneak", Sneak.OPTIONS);
            ConfigUtils.writeConfigBase(root, "Gamma", Gamma.OPTIONS);
            ConfigUtils.writeConfigBase(root, "FreeCamera", FreeCam.OPTIONS);

            JsonUtils.writeJsonToFileAsPath(root, dir.resolve(CONFIG_FILE_NAME));
        }
    }

    /** 配置变更回调：先落盘、再重载，保证文件与内存永远同步——改了就得立刻生效并留存。 */
    @Override
    public void onConfigsChanged() {
        saveToFile();
        loadFromFile();
    }

    /** MaLiLib 生命周期回调：游戏启动阶段读一次配置文件，把上次的设置捞回来。 */
    @Override
    public void load() {
        loadFromFile();
    }

    /** MaLiLib 生命周期回调：退出 / 保存时机把当前设置写盘，避免关游戏丢配置。 */
    @Override
    public void save() {
        saveToFile();
    }

    // ======================= HUD 坐标解析（-1 = 自动定位） =======================
    //
    // 所有 getX*/getY* 方法统一语义：返回值是屏幕比例（0.0~1.0），谁用谁乘屏宽/屏高。
    // 渲染器里这样用：absoluteX = getXFoo(screenWidth) * screenWidth；
    // 编辑器里这样用：workX = resolvedX.applyAsDouble(screenWidth)（即直接调 getXFoo）。
    // 负值哨兵（< 0）代表「用户没手动调过」，触发对应组件的自动贴边回退逻辑——
    // 也就是下面这些方法里一大半的兜底计算。

    // ---- 波次时间 HUD：默认右下角，内缩不溢出 ----

    /**
     * 波次时间 HUD 默认贴右：把组件像素宽算出来再转成比例，保证右边不溢出。
     *
     * <p>组件宽 = 箭头宽 + 最宽行文字宽，与 buildSpawnTimePreview.totalW 保持同一
     * 口径——预览图怎么量、这里就怎么排，所见即所得。
     */
    public static double getXSpawnTime(int screenWidth) {
        if (Hud.X_SPAWN_TIME.getDoubleValue() < 0) {
            net.minecraft.client.font.TextRenderer tr = MinecraftClient.getInstance().textRenderer;
            if (tr == null) return 0.85;
            int arrowW = tr.getWidth("➤ ");
            String[] lines = {"W1 00:12", "W2 00:18", "W3 00:24", "W4 00:30", "W5 00:36", "W6 00:44"};
            int lineW = 0;
            for (String s : lines) lineW = Math.max(lineW, tr.getWidth(s));
            int hudW = arrowW + lineW;
            // 再留 4px 边距，转成比例——宁可稍离边也别碰着屏幕边缘
            return Math.max(0.0, (double)(screenWidth - hudW - 4) / screenWidth);
        }
        return Hud.X_SPAWN_TIME.getDoubleValue();
    }

    /**
     * 波次时间 HUD 默认贴底：组件高 6 行文字，悬在物品栏上方——
     * 留出 hotbar 22px + 4px 边距，避免压着快捷栏。
     */
    public static double getYSpawnTime(int screenHeight) {
        if (Hud.Y_SPAWN_TIME.getDoubleValue() < 0) {
            net.minecraft.client.font.TextRenderer tr = MinecraftClient.getInstance().textRenderer;
            if (tr == null) return 0.7;
            int hudH = tr.fontHeight * 6;
            int hotbarMargin = 22 + 4; // 原版热键栏约 22px（GUI scale 2 下），再留 4px 边距
            return Math.max(0.0, (double)(screenHeight - hudH - hotbarMargin) / screenHeight);
        }
        return Hud.Y_SPAWN_TIME.getDoubleValue();
    }

    // ---- 道具 HUD：默认左侧垂直居中 ----

    /** 道具 HUD 默认贴左（X=0）——纵向列表放左缘，不跟右侧信息抢地方。 */
    public static double getXPowerup(int screenWidth) {
        return Hud.X_POWERUP.getDoubleValue() < 0 ? 0.0 : Hud.X_POWERUP.getDoubleValue();
    }

    /** 道具 HUD 默认垂直居中：4 行文字，以屏幕中心上方为基准，列表不偏不倚。 */
    public static double getYPowerup(int screenHeight) {
        if (Hud.Y_POWERUP.getDoubleValue() < 0) {
            net.minecraft.client.font.TextRenderer tr = MinecraftClient.getInstance().textRenderer;
            if (tr == null) return 0.4;
            int hudH = tr.fontHeight * 4;
            return Math.max(0.0, (double)(screenHeight / 2 - hudH / 2) / screenHeight);
        }
        return Hud.Y_POWERUP.getDoubleValue();
    }

    // ---- 队伍统计 HUD：默认左上角 ----

    /** 队伍统计 HUD 默认贴左（X 比例 = 0），表格从左边排起。 */
    public static double getXTeamStats(int screenWidth) {
        return Hud.X_TEAM_STATS.getDoubleValue() < 0 ? 0.0 : Hud.X_TEAM_STATS.getDoubleValue();
    }

    /** 队伍统计 HUD 默认贴顶（Y 比例 = 0），表格从顶排起。 */
    public static double getYTeamStats(int screenHeight) {
        return Hud.Y_TEAM_STATS.getDoubleValue() < 0 ? 0.0 : Hud.Y_TEAM_STATS.getDoubleValue();
    }

    // ---- 时间 HUD：默认右上角，精确内缩不溢出 ----

    /**
     * 时间 HUD 默认贴右：取「游戏时长」「本回合」两行里较宽者当组件宽，
     * 转成比例并留 4px 边距，保证右侧不溢出。
     */
    public static double getXGameTime(int screenWidth) {
        double v = Hud.X_GAME_TIME.getDoubleValue();
        if (v >= 0) return v;
        net.minecraft.client.font.TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        if (tr == null) return 0.8;
        int wGame  = tr.getWidth(net.minecraft.text.Text.translatable("nomorezombies.timehud.game").getString())
                + tr.getWidth("00:12:34");
        int wRound = tr.getWidth(net.minecraft.text.Text.translatable("nomorezombies.timehud.round").getString())
                + tr.getWidth("00:56");
        int hudW = Math.max(wGame, wRound);
        return Math.max(0.0, (double)(screenWidth - hudW - 4) / screenWidth);
    }

    /** 时间 HUD 默认贴顶（Y 比例 = 0），右上角稳稳钉住。 */
    public static double getYGameTime(int screenHeight) {
        return Hud.Y_GAME_TIME.getDoubleValue() < 0 ? 0.0 : Hud.Y_GAME_TIME.getDoubleValue();
    }

    // ---- 电击棒队列 HUD：默认水平居中，物品栏上方 ----

    /**
     * 电击棒队列 HUD 默认水平居中：组件宽按 4 格算——
     * 4格 * (26px + 3px gap) - 3px gap = 113px，正正好好悬在屏幕中轴。
     */
    public static double getXLRQueue(int screenWidth) {
        if (Hud.X_LRQUEUE.getDoubleValue() < 0) {
            // 与 buildLRQueuePreview 同口径：tileW=26, gap=3, 4格
            int hudW = 26 * 4 + 3 * 3; // = 113px
            return Math.max(0.0, (double)(screenWidth / 2 - hudW / 2) / screenWidth);
        }
        return Hud.X_LRQUEUE.getDoubleValue();
    }

    /**
     * 电击棒队列 HUD 默认贴物品栏顶：组件高 34px，物品栏 22px，再留 4px 间距，
     * 不压着快捷栏。
     */
    public static double getYLRQueue(int screenHeight) {
        if (Hud.Y_LRQUEUE.getDoubleValue() < 0) {
            int hudH = 34;
            int hotbarMargin = 22 + 4;
            return Math.max(0.0, (double)(screenHeight - hudH - hotbarMargin) / screenHeight);
        }
        return Hud.Y_LRQUEUE.getDoubleValue();
    }

    // ---- AA 指挥 HUD：默认左下角 ----

    /** AA 指挥 HUD 默认贴左（X 比例 = 0），文字行从左边排起。 */
    public static double getXAaCommand(int screenWidth) {
        double v = Hud.X_AA_COMMAND.getDoubleValue();
        return v >= 0 ? v : 0.0;
    }

    /**
     * AA 指挥 HUD 默认贴底：5 行文字悬在物品栏上方，
     * 留 hotbar 22px + 4px 边距，不挡快捷栏。
     */
    public static double getYAaCommand(int screenHeight) {
        double v = Hud.Y_AA_COMMAND.getDoubleValue();
        if (v >= 0) return v;
        net.minecraft.client.font.TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        if (tr == null) return 0.8;
        int hudH = tr.fontHeight * 5;
        int hotbarMargin = 22 + 4;
        return Math.max(0.0, (double)(screenHeight - hudH - hotbarMargin) / screenHeight);
    }

    // ---- CPS 统计 HUD：默认右中 ----

    /** CPS HUD 默认贴右：按「L 88  R 88」这种典型行宽估组件宽，留 4px 边距——别让数字贴到屏幕边。 */
    public static double getXCps(int screenWidth) {
        double v = Hud.X_CPS.getDoubleValue();
        if (v >= 0) return v;
        net.minecraft.client.font.TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        if (tr == null) return 0.88;
        int hudW = tr.getWidth("RMB 88 CPS");
        return Math.max(0.0, (double)(screenWidth - hudW - 4) / screenWidth);
    }

    /** CPS HUD 默认垂直居中（Y 比例 = 0.5），右侧中段不碍事。 */
    public static double getYCps(int screenHeight) {
        double v = Hud.Y_CPS.getDoubleValue();
        return v >= 0 ? v : 0.5;
    }
}