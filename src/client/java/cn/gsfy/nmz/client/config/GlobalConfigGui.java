package cn.gsfy.nmz.client.config;

import cn.gsfy.nmz.NoMoreZombies;
import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.data.ModInfo;
import net.minecraft.client.gui.screen.Screen;

import java.util.List;
import java.util.Objects;

/**
 * MaLiLib 配置界面（继承 GuiConfigsBase）——三个标签页：全局配置 / 体验优化 / 实用功能，
 * 顶部按钮切换，每行配置自带开关/滑块/热键绑定按钮。
 * 全局配置页聚合各分组里的非开关参数（热键/API Key/音效/缩放/视角等）；
 * 体验优化页收隐藏类开关（BossBar/计分板/射击粒子/着火遮罩/聊天消息过滤等）；
 * 实用功能页收各功能总开关（HUD/波次音效/道具/ESP/血条/隐身/缩放/自由视角等）。
 *
 * <p>由 {@link ModMenuApi} 与 openGui 热键打开，是设置唯一入口；切换标签会重建列表并复位滚动条，
 * 否则旧页面的行宽会残留在新页面上，看起来东倒西歪。
 */
public class GlobalConfigGui extends GuiConfigsBase {

    private static ConfigGuiTab tab = ConfigGuiTab.GLOBAL;

    public GlobalConfigGui(Screen parent) {
        super(10, 50, NoMoreZombies.MOD_ID, parent, "nomorezombies.config.title");
        // 主动注册配置界面，让 mod 切换列表显示大驼峰名 NoMoreZombies：
        // 若不管，MaLiLib 自动注册会用 splitCamelCase(modId) 生成显示名，
        // 全小写 modId 会原样显示成小写 "nomorezombies"，和其余处 "NoMoreZombies" 对不上。
        Registry.CONFIG_SCREEN.registerConfigScreenFactory(
                new ModInfo(NoMoreZombies.MOD_ID, NoMoreZombies.MOD_NAME, () -> this));
    }

    @Override
    public void initGui() {
        super.initGui();

        this.clearOptions();

        int x = 10;
        int y = 26;

        for (ConfigGuiTab t : ConfigGuiTab.values()) {
            x += this.createButton(x, y, -1, t) + 2;
        }
    }

    private int createButton(int x, int y, int width, ConfigGuiTab t) {
        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, t.getDisplayName());
        button.setEnabled(tab != t);
        this.addButton(button, new ButtonListener(t, this));

        return button.getWidth();
    }

    /**
     * 当前标签页要展示的配置项列表，供 MaLiLib 逐行渲染——不同标签页聚合不同的组。
     * 全局配置页收各分组里的非开关参数，实用功能页收各功能总开关，体验优化页收隐藏类开关。
     */
    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<? extends IConfigBase> configs;

        switch (tab) {
            // 全局配置页 = 非开关参数的大本营：设置/编辑器/查询热键 + API Key + 道具提醒输出 + 用时记录
            //            + 波次出生音效地图范围与音效 ID/音高 + ESP/血条渲染机制
            //            + 平滑缩放（倍率/时长/动画/按键行为/缩放热键）
            //            + 自由视角子选项（移动/输入/速度）
            case GLOBAL -> configs = ImmutableList.<IConfigBase>builder()
                    .add(GlobalConfig.QoL.OPEN_GUI_CONFIGS)
                    .add(GlobalConfig.QoL.OPEN_HUD_EDITOR)
                    .add(GlobalConfig.Query.OPEN_QUERY_GUI)
                    .add(GlobalConfig.Query.API_KEY)
                    .add(GlobalConfig.Powerups.ALERT_OUTPUT)
                    .add(GlobalConfig.AaCommand.OUTPUT)
                    .add(GlobalConfig.AaCommand.TEMPLATE)
                    .add(GlobalConfig.Record.ROUNDS_RECORD)
                    .add(GlobalConfig.Spawntimes.WAVE_SOUND_AA)
                    .add(GlobalConfig.Spawntimes.WAVE_SOUND_DE)
                    .add(GlobalConfig.Spawntimes.WAVE_SOUND_BB)
                    .add(GlobalConfig.Spawntimes.WAVE_SOUND_PRISON)
                    .add(GlobalConfig.Spawntimes.PRECEDED_WAVE_SOUND)
                    .add(GlobalConfig.Spawntimes.PRECEDED_WAVE_PITCH)
                    .add(GlobalConfig.Spawntimes.LAST_WAVE_SOUND)
                    .add(GlobalConfig.Spawntimes.LAST_WAVE_PITCH)
                    .add(GlobalConfig.Spawntimes.COUNTDOWN_SOUND)
                    .add(GlobalConfig.Spawntimes.COUNTDOWN_PITCH)
                    .add(GlobalConfig.QoL.TEAMMATE_ESP_RENDER_MODE)
                    .add(GlobalConfig.QoL.ZOMBIE_ESP_RENDER_MODE)
                    .add(GlobalConfig.QoL.POWERUP_ESP_RENDER_MODE)
                    .add(GlobalConfig.QoL.HEALTH_BAR_RENDER_MODE)
                    .add(GlobalConfig.QoL.THROUGH_WALL_RENDER_DISTANCE)
                    .add(GlobalConfig.Zoom.INITIAL_ZOOM)
                    .add(GlobalConfig.Zoom.ZOOM_IN_TIME)
                    .add(GlobalConfig.Zoom.ZOOM_OUT_TIME)
                    .add(GlobalConfig.Zoom.EASING)
                    .add(GlobalConfig.Zoom.KEY_BEHAVIOUR)
                    .add(GlobalConfig.Zoom.ZOOM_KEY)
                    .add(GlobalConfig.Sneak.ALLOW_IN_GUIS)
                    .add(GlobalConfig.Gamma.OVERRIDE_VALUE)
                    .add(GlobalConfig.FreeCam.PLAYER_MOVEMENT)
                    .add(GlobalConfig.FreeCam.PLAYER_INPUTS)
                    .add(GlobalConfig.FreeCam.SPEED)
                    .build();
            // 实用功能页 = 各功能总开关：HUD/波次音效/道具预测/用时/AA 指挥/缩放/自由视角
            //            + ESP（队友/僵尸/道具）+ 血条/隐身 + 屏蔽非开火右键 + 常驻潜行疾跑伽马
            case QOL -> configs = ImmutableList.<IConfigBase>builder()
                    .add(GlobalConfig.QoL.HUD_MASTER)
                    .add(GlobalConfig.QoL.WAVE_SOUND_ENABLED)
                    .add(GlobalConfig.Spawntimes.FINAL_WAVE_COUNTDOWN)
                    .add(GlobalConfig.Spawntimes.COLOR_ALERT)
                    .add(GlobalConfig.Powerups.POWERUP_PREDICT)
                    .add(GlobalConfig.QoL.RECORD_ENABLED)
                    .add(GlobalConfig.QoL.TEAMMATE_ESP)
                    .add(GlobalConfig.QoL.ZOMBIE_ESP)
                    .add(GlobalConfig.QoL.POWERUP_ESP)
                    .add(GlobalConfig.QoL.ENTITY_HEALTH_BAR)
                    .add(GlobalConfig.QoL.PLAYER_INVISIBLE)
                    .add(GlobalConfig.QoL.RIGHT_CLICK_FIRE_ONLY)
                    .add(GlobalConfig.QoL.AA_COMMAND_ENABLED)
                    .add(GlobalConfig.QoL.ZOOM_ENABLED)
                    .add(GlobalConfig.QoL.ALWAYS_SNEAK_ENABLED)
                    .add(GlobalConfig.QoL.ALWAYS_SPRINT_ENABLED)
                    .add(GlobalConfig.QoL.GAMMA_OVERRIDE_ENABLED)
                    .add(GlobalConfig.QoL.FREE_CAMERA_ENABLED)
                    .build();
            // 体验优化页 = 隐藏类开关：原生 BossBar / 计分板 + 聊天消息过滤（金钱/窗户/击中/宝箱/区域/进出）
            case HIDE_OPTIMIZATION -> configs = ImmutableList.<IConfigBase>builder()
                    .add(GlobalConfig.QoL.HIDE_BOSS_BAR)
                    .add(GlobalConfig.QoL.HIDE_SCOREBOARD)
                    .add(GlobalConfig.QoL.NO_GUN_FIRE)
                    .add(GlobalConfig.QoL.NO_FIRE_EFFECT)
                    .add(GlobalConfig.Hide.HIDE_GOLD)
                    .add(GlobalConfig.Hide.HIDE_WINDOW)
                    .add(GlobalConfig.Hide.HIDE_HIT_TARGET)
                    .add(GlobalConfig.Hide.HIDE_LUCKY_CHEST)
                    .add(GlobalConfig.Hide.HIDE_OPEN_AREA)
                    .add(GlobalConfig.Hide.HIDE_PLAYER_CONNECTION)
                    .build();
            default -> configs = GlobalConfig.QoL.OPTIONS;
        }

        return ConfigOptionWrapper.createFor(configs);
    }

    private static class ButtonListener implements IButtonActionListener {

        private final GlobalConfigGui parent;
        private final ConfigGuiTab tab;

        public ButtonListener(ConfigGuiTab tab, GlobalConfigGui parent) {
            this.tab = tab;
            this.parent = parent;
        }

        /** 点击标签按钮：切页、按新页面的行宽重建列表、滚动条归零再重绘，避免串页。 */
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            GlobalConfigGui.tab = this.tab;

            this.parent.reCreateListWidget(); // 重建列表，套用新页面的配置行宽
            Objects.requireNonNull(this.parent.getListWidget()).resetScrollbarPosition();
            this.parent.initGui();
        }
    }

    /** 配置界面顶部的标签页枚举：全局配置（参数）/ 实用功能（开关）/ 体验优化（隐藏），显示名走翻译键。 */
    private enum ConfigGuiTab {
        GLOBAL("nomorezombies.config.category.global"),
        HIDE_OPTIMIZATION("nomorezombies.config.category.hideOptimization"),
        QOL("nomorezombies.config.category.qol");

        private final String translationKey;

        ConfigGuiTab(String translationKey) {
            this.translationKey = translationKey;
        }

        public String getDisplayName() {
            return StringUtils.translate(this.translationKey);
        }
    }
}
