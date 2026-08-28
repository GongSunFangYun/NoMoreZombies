package cn.gsfy.nmz.client.config;

import cn.gsfy.nmz.client.feature.gamehud.TotalHUDRenderer;
import cn.gsfy.nmz.client.util.AvatarManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntToDoubleFunction;
import java.util.function.Supplier;

/**
 * HUD 拖拽编辑界面（继承 Screen）——把各 HUD 用鼠标拖到顺手位置，
 * 顺带调缩放与显隐。位置以屏幕比例（0~1）存储，缩放范围 0.5~2.0，
 * 拖动只改比例、不改像素，换分辨率不跑位。
 *
 * <p>编辑全程落在「工作区状态」上而不直接改配置：拖拽与滑条只写 workX/workY/workScale，
 * 点保存才回写全局配置并落盘，取消 / Esc 直接丢弃——之所以绕这一层，是让玩家反复试位、
 * 不满意就放弃，拖一下不会污染已生效配置。控件画在屏幕中央、无背景框，不挡 HUD 拖动；
 * 布局垂直居中，两行共用同一对齐区（bandLeft ~ bandRight）：行1 保存 / 信息图标 / 取消，
 * 选中元素才出现的行2 是「缩放：1.00」滑条与「显示：开/关」。悬浮在 HUD 上时显示跟随
 * 鼠标的黑色名称 Tooltip，按住拖动 / 离开边界自动隐藏，无需「选取」文本；
 * 且 {@link #IS_OPEN} 为 true 时，游戏侧 HUD 渲染器应跳过绘制，避免与编辑器预览重叠。
 */
public class HUDEditor extends Screen {

    /**
     * 编辑器打开期间为 true——游戏侧 HUD 渲染器据此跳过实际绘制，
     * 否则已生效的 HUD 会和编辑器里的预览叠在一起，看起来像重影。
     */
    public static boolean IS_OPEN = false;

    private static final double MIN_SCALE = 0.5;
    private static final double MAX_SCALE = 2.0;

    /** 控件区宽度（px）：屏幕中央一横条，行1 行2 都在这条里对齐。 */
    private static final int CTRL_W = 300;
    /** 行高（px）：未选中 1 行、选中后 2 行都按它叠。 */
    private static final int ROW_H = 24;
    /** 按钮/文字/滑条共用的对齐宽度（两行左右边界一致），视觉上才成一条线。 */
    private static final int BAND_W = 190;
    /** 按钮宽度（px）：保存/取消/显示开关统一，换行时边界才对得齐。 */
    private static final int BTN_W = 60;

    /** 队伍统计列间距（与 TeamStatsRenderer.drawTable 一致），预览尺寸和真实 HUD 对得上。 */
    private static final int PAD = 8;

    /** 队伍统计名字列左侧头像区（与 TeamStatsRenderer 的 AVATAR_W / NAME_OFFSET 一致），预览不跑版。 */
    private static final int AVATAR_W = 8;
    private static final int NAME_OFFSET = AVATAR_W + 2;

    /** 信息/警告图标尺寸（px，正方形热区），悬浮检测与绘制都按它。 */
    private static final int ICON_SIZE = 20;

    private final Screen parent;
    private final HudElement[] elements;
    private HudElement selected;
    private HudElement dragging;

    private double dragOffsetX;
    private double dragOffsetY;

    private boolean workInitialized;
    private final ButtonWidget saveButton;
    private final ButtonWidget cancelButton;
    private final ButtonWidget visibleToggleButton;
    private ScaleSliderWidget scaleSlider;

    /** 信息图标中心位置（行1，保存与取消之间），在 layoutWidgets 中算好供绘制与悬浮检测复用。 */
    private int iconCenterX;
    private int iconCenterY;

    /** 当前帧鼠标是否悬浮在信息图标上——render 里判断后记录，renderTooltip 据此决定画不画。 */
    private boolean iconHovered;

    /** 取翻译文本（跟随客户端语言），预览文案与真实 HUD 同源、不写死。 */
    private static String trans(String key) {
        return Text.translatable(key).getString();
    }

    public HUDEditor(Screen parent) {
        super(Text.translatable("nomorezombies.hudeditor.title"));
        this.parent = parent;
        this.elements = new HudElement[]{
                new HudElement(trans("nomorezombies.hudeditor.element.spawntime"),
                        () -> GlobalConfig.Hud.X_SPAWN_TIME.getDoubleValue(), v -> GlobalConfig.Hud.X_SPAWN_TIME.setDoubleValue(v),
                        () -> GlobalConfig.Hud.Y_SPAWN_TIME.getDoubleValue(), v -> GlobalConfig.Hud.Y_SPAWN_TIME.setDoubleValue(v),
                        () -> GlobalConfig.Hud.SCALE_SPAWN_TIME.getDoubleValue(), v -> GlobalConfig.Hud.SCALE_SPAWN_TIME.setDoubleValue(v),
                        () -> GlobalConfig.Hud.VISIBLE_SPAWN_TIME.getBooleanValue(), v -> GlobalConfig.Hud.VISIBLE_SPAWN_TIME.setBooleanValue(v),
                        GlobalConfig::getXSpawnTime, GlobalConfig::getYSpawnTime,
                        this::buildSpawnTimePreview),
                new HudElement(trans("nomorezombies.hudeditor.element.powerup"),
                        () -> GlobalConfig.Hud.X_POWERUP.getDoubleValue(), v -> GlobalConfig.Hud.X_POWERUP.setDoubleValue(v),
                        () -> GlobalConfig.Hud.Y_POWERUP.getDoubleValue(), v -> GlobalConfig.Hud.Y_POWERUP.setDoubleValue(v),
                        () -> GlobalConfig.Hud.SCALE_POWERUP.getDoubleValue(), v -> GlobalConfig.Hud.SCALE_POWERUP.setDoubleValue(v),
                        () -> GlobalConfig.Hud.VISIBLE_POWERUP.getBooleanValue(), v -> GlobalConfig.Hud.VISIBLE_POWERUP.setBooleanValue(v),
                        GlobalConfig::getXPowerup, GlobalConfig::getYPowerup,
                        this::buildPowerupPreview),
                new HudElement(trans("nomorezombies.hudeditor.element.teamstats"),
                        () -> GlobalConfig.Hud.X_TEAM_STATS.getDoubleValue(), v -> GlobalConfig.Hud.X_TEAM_STATS.setDoubleValue(v),
                        () -> GlobalConfig.Hud.Y_TEAM_STATS.getDoubleValue(), v -> GlobalConfig.Hud.Y_TEAM_STATS.setDoubleValue(v),
                        () -> GlobalConfig.Hud.SCALE_TEAM_STATS.getDoubleValue(), v -> GlobalConfig.Hud.SCALE_TEAM_STATS.setDoubleValue(v),
                        () -> GlobalConfig.Hud.VISIBLE_TEAM_STATS.getBooleanValue(), v -> GlobalConfig.Hud.VISIBLE_TEAM_STATS.setBooleanValue(v),
                        GlobalConfig::getXTeamStats, GlobalConfig::getYTeamStats,
                        this::buildTeamStatsPreview),
                new HudElement(trans("nomorezombies.hudeditor.element.gametime"),
                        () -> GlobalConfig.Hud.X_GAME_TIME.getDoubleValue(), v -> GlobalConfig.Hud.X_GAME_TIME.setDoubleValue(v),
                        () -> GlobalConfig.Hud.Y_GAME_TIME.getDoubleValue(), v -> GlobalConfig.Hud.Y_GAME_TIME.setDoubleValue(v),
                        () -> GlobalConfig.Hud.SCALE_GAME_TIME.getDoubleValue(), v -> GlobalConfig.Hud.SCALE_GAME_TIME.setDoubleValue(v),
                        () -> GlobalConfig.Hud.VISIBLE_GAME_TIME.getBooleanValue(), v -> GlobalConfig.Hud.VISIBLE_GAME_TIME.setBooleanValue(v),
                        GlobalConfig::getXGameTime, GlobalConfig::getYGameTime,
                        this::buildGameTimePreview),
                new HudElement(trans("nomorezombies.hudeditor.element.lrqueue"),
                        () -> GlobalConfig.Hud.X_LRQUEUE.getDoubleValue(), v -> GlobalConfig.Hud.X_LRQUEUE.setDoubleValue(v),
                        () -> GlobalConfig.Hud.Y_LRQUEUE.getDoubleValue(), v -> GlobalConfig.Hud.Y_LRQUEUE.setDoubleValue(v),
                        () -> GlobalConfig.Hud.SCALE_LRQUEUE.getDoubleValue(), v -> GlobalConfig.Hud.SCALE_LRQUEUE.setDoubleValue(v),
                        () -> GlobalConfig.Hud.VISIBLE_LRQUEUE.getBooleanValue(), v -> GlobalConfig.Hud.VISIBLE_LRQUEUE.setBooleanValue(v),
                        GlobalConfig::getXLRQueue, GlobalConfig::getYLRQueue,
                        this::buildLRQueuePreview),
                new HudElement(trans("nomorezombies.hudeditor.element.aacommand"),
                        () -> GlobalConfig.Hud.X_AA_COMMAND.getDoubleValue(), v -> GlobalConfig.Hud.X_AA_COMMAND.setDoubleValue(v),
                        () -> GlobalConfig.Hud.Y_AA_COMMAND.getDoubleValue(), v -> GlobalConfig.Hud.Y_AA_COMMAND.setDoubleValue(v),
                        () -> GlobalConfig.Hud.SCALE_AA_COMMAND.getDoubleValue(), v -> GlobalConfig.Hud.SCALE_AA_COMMAND.setDoubleValue(v),
                        () -> GlobalConfig.Hud.VISIBLE_AA_COMMAND.getBooleanValue(), v -> GlobalConfig.Hud.VISIBLE_AA_COMMAND.setBooleanValue(v),
                        GlobalConfig::getXAaCommand, GlobalConfig::getYAaCommand,
                        this::buildAaCommandPreview),
                new HudElement(trans("nomorezombies.hudeditor.element.cps"),
                        () -> GlobalConfig.Hud.X_CPS.getDoubleValue(), v -> GlobalConfig.Hud.X_CPS.setDoubleValue(v),
                        () -> GlobalConfig.Hud.Y_CPS.getDoubleValue(), v -> GlobalConfig.Hud.Y_CPS.setDoubleValue(v),
                        () -> GlobalConfig.Hud.SCALE_CPS.getDoubleValue(), v -> GlobalConfig.Hud.SCALE_CPS.setDoubleValue(v),
                        () -> GlobalConfig.Hud.VISIBLE_CPS.getBooleanValue(), v -> GlobalConfig.Hud.VISIBLE_CPS.setBooleanValue(v),
                        GlobalConfig::getXCps, GlobalConfig::getYCps,
                        this::buildCpsPreview)
        };
        this.saveButton = ButtonWidget.builder(Text.translatable("nomorezombies.hudeditor.save"), b -> save())
                .dimensions(0, 0, 60, 20).build();
        this.cancelButton = ButtonWidget.builder(Text.translatable("nomorezombies.hudeditor.cancel"), b -> cancel())
                .dimensions(0, 0, 60, 20).build();
        this.visibleToggleButton = ButtonWidget.builder(Text.translatable("nomorezombies.hudeditor.visible.on"), b -> toggleVisible())
                .dimensions(0, 0, 60, 20).build();
    }

    // ---- 控件区几何：宽固定、位置居中，全由这几个小函数按屏幕尺寸现算 ----

    /** 控件区总高度：未选中 1 行、选中后多出滑条行共 2 行，随选中态伸缩。 */
    private int ctrlHeight() {
        return selected != null ? ROW_H * 2 : ROW_H;
    }

    /** 控件区左上角 X（水平居中）：固定宽度，居中只需减半。 */
    private int ctrlX() {
        return (this.width - CTRL_W) / 2;
    }

    /** 控件区左上角 Y（垂直居中）：高度随选中态变，居中位置也跟着变。 */
    private int ctrlY() {
        return (this.height - ctrlHeight()) / 2;
    }

    /** 对齐区域左边界（行1 行2 所有元素共用）：在控件区内再居中。 */
    private int bandLeft() {
        return ctrlX() + (CTRL_W - BAND_W) / 2;
    }

    /** 对齐区域右边界（行1 行2 所有元素共用）：左边界加固定带宽。 */
    private int bandRight() {
        return bandLeft() + BAND_W;
    }

    /** 摆放控件：行1 常驻、行2 随选中态增减；图标中心也在这一起算。 */
    private void layoutWidgets() {
        this.clearChildren();

        int cy = ctrlY();
        int left = bandLeft();
        int right = bandRight();

        // 行1：[保存] 贴对齐区左、[取消] 贴右，图标落在两者中间，一眼看出主次
        int row1Y = cy;
        saveButton.setPosition(left, row1Y);
        cancelButton.setPosition(right - BTN_W, row1Y);
        this.addDrawableChild(saveButton);
        this.addDrawableChild(cancelButton);

        // 图标取「保存右边界 ~ 取消左边界」的中点：正好卡在两按钮之间不重叠
        int saveRight = left + BTN_W;
        int cancelLeft = right - BTN_W;
        iconCenterX = (saveRight + cancelLeft) / 2;
        iconCenterY = row1Y + ICON_SIZE / 2;

        if (selected != null) {
            // 行2：滑条靠 left（消息即「缩放：1.00」，原版风味），[显示：开/关] 右对齐 right，
            // 与行1 的保存/取消边界对齐，两行看起来才成一体
            int row2Y = cy + ROW_H;
            scaleSlider = new ScaleSliderWidget(left, row2Y, right - left - BTN_W - 6, 20, this::onSliderChanged);
            scaleSlider.setScaleValue(selected.workScale);
            this.addDrawableChild(scaleSlider);

            visibleToggleButton.setPosition(right - BTN_W, row2Y);
            visibleToggleButton.visible = true;
            this.addDrawableChild(visibleToggleButton);
        } else {
            visibleToggleButton.visible = false;
        }
    }

    /** 首次打开时把各 HUD 的已生效配置快照成工作区状态，之后编辑只动快照、不动配置。 */
    private void ensureWorkInit() {
        if (workInitialized) return;
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        if (tr == null) return;
        for (HudElement e : elements) {
            e.workX = e.resolvedX.applyAsDouble(this.width);
            e.workY = e.resolvedY.applyAsDouble(this.height);
            e.workScale = e.getScale.getAsDouble();
            e.workVisible = e.getVisible.getAsBoolean();
        }
        workInitialized = true;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        if (tr == null) {
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        ensureWorkInit();

        // ---- HUD 元素预览：读工作区状态，不碰已生效配置，所见即试位结果 ----
        HudElement hoveredElement = null;
        for (HudElement e : elements) {
            int x = (int) (e.workX * this.width);
            int y = (int) (e.workY * this.height);
            HudPreview p = e.preview.get();
            float s = (float) e.workScale;
            int bw = (int) (p.width * s);
            int bh = (int) (p.height * s);

            // 悬浮检测：拖动中（dragging）或鼠标出界就不算悬浮，Tooltip 只在真正停住时冒出来
            if (dragging == null
                    && mouseX >= x - 2 && mouseX <= x + bw + 2
                    && mouseY >= y - 2 && mouseY <= y + bh + 2) {
                hoveredElement = e;
            }

            int boxColor = (e == selected) ? 0x55FFFF55 : 0x40FFFFFF;
            if (!e.workVisible) boxColor = (e == selected) ? 0x55FF5555 : 0x40888888;
            context.fill(x - 2, y - 2, x + bw + 2, y + bh + 2, boxColor);
            TotalHUDRenderer.drawScaled(context, x, y, s, () -> p.render.render(context, x, y));
            if (!e.workVisible) context.fill(x - 2, y - 2, x + bw + 2, y + bh + 2, 0x44000000);
        }

        // 选中态只需同步「显示」按钮文字：滑条自己会把「缩放：1.00」画在脸上
        if (selected != null) {
            visibleToggleButton.setMessage(Text.translatable(
                    selected.workVisible ? "nomorezombies.hudeditor.visible.on" : "nomorezombies.hudeditor.visible.off"));
        }

        // ---- 信息/警告图标（保存与取消之间）：总开关状态不同，图标与配色随之切换 ----
        boolean hudMasterOn = GlobalConfig.QoL.HUD_MASTER.getBooleanValue();
        renderInfoIcon(context, tr, mouseX, mouseY, hudMasterOn);

        super.render(context, mouseX, mouseY, delta);

        // ---- 悬浮 Tooltip：必须在 super.render 之后画，否则会被界面自身内容盖住 ----
        if (iconHovered) {
            renderIconTooltip(context, tr, mouseX, mouseY, hudMasterOn);
        }
        // HUD 名称 Tooltip 压轴画：图标 Tooltip 或 HUD 预览若与它重叠，都以它为准
        if (hoveredElement != null) {
            renderHoverTooltip(context, tr, mouseX, mouseY, hoveredElement);
        }
    }

    /**
     * 在保存与取消按钮中间绘制圆形信息/警告图标：HUD 总开关开着是 ℹ 蓝、关着是 ⚠ 橙，
     * 一眼看出当前 HUD 到底生不生效。悬浮时高亮，提示这图标可以点看说明。
     */
    private void renderInfoIcon(DrawContext context, TextRenderer tr, int mouseX, int mouseY, boolean hudMasterOn) {
        int half = ICON_SIZE / 2;
        int x0 = iconCenterX - half;
        int y0 = iconCenterY - half;
        int x1 = iconCenterX + half;
        int y1 = iconCenterY + half;

        iconHovered = mouseX >= x0 && mouseX <= x1 && mouseY >= y0 && mouseY <= y1;

        // 背景圆形：Minecraft DrawContext 没有原生圆，用两层矩形叠出圆角近似——外框 + 缩进 1px 的内填充
        int bgAlpha = iconHovered ? 0xCC : 0x88;
        int bgColor = hudMasterOn
                ? (bgAlpha << 24 | 0x1A3A6A)   // 蓝色背景：信息态
                : (bgAlpha << 24 | 0x5A2A00);   // 橙色背景：警告态，总开关没开就警告你
        int borderColor = hudMasterOn ? 0xFF4A9EFF : 0xFFFFAA00;

        // 外框：先铺一圈边界色，做圆角矩形的边
        context.fill(x0, y0, x1, y1, borderColor);
        // 内填充缩进 1px：外框漏一圈当描边，视觉上就是圆角
        context.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, bgColor);

        // 图标字符按中心对齐：水平居中算字宽，垂直居中算行高
        String icon = hudMasterOn ? "ℹ" : "⚠";
        int iconColor = hudMasterOn ? 0xFFCCE5FF : 0xFFFFDD44;
        int textX = iconCenterX - tr.getWidth(icon) / 2;
        int textY = iconCenterY - tr.fontHeight / 2;
        context.drawTextWithShadow(tr, icon, textX, textY, iconColor);
    }

    /**
     * 悬浮在信息/警告图标上时绘制多行说明：拖动、点选、缩放、保存各一条；
     * 若 HUD 总开关是关的，首行还要补一句警告，别让玩家以为拖了就有用。
     */
    private void renderIconTooltip(DrawContext context, TextRenderer tr, int mouseX, int mouseY, boolean hudMasterOn) {
        List<Text> lines = new ArrayList<>();
        if (!hudMasterOn) {
            lines.add(Text.translatable("nomorezombies.hudeditor.warn.master"));
        }
        lines.add(Text.translatable("nomorezombies.hudeditor.hint.drag"));
        lines.add(Text.translatable("nomorezombies.hudeditor.hint.click"));
        lines.add(Text.translatable("nomorezombies.hudeditor.hint.scale"));
        lines.add(Text.translatable("nomorezombies.hudeditor.hint.save"));

        // 先量尺寸：宽取最长一行、高按行数堆，边距 4px
        int padding = 4;
        int lineH = tr.fontHeight + 2;
        int tooltipW = 0;
        for (Text t : lines) tooltipW = Math.max(tooltipW, tr.getWidth(t.getString()));
        tooltipW += padding * 2;
        int tooltipH = lineH * lines.size() + padding * 2;

        // 定位：优先停在鼠标上方，顶出屏幕就翻到下方，左右同样做越界钳制
        int tx = mouseX - tooltipW / 2;
        int ty = mouseY - tooltipH - 6;
        if (ty < 4) ty = mouseY + 12;
        if (tx < 4) tx = 4;
        if (tx + tooltipW > this.width - 4) tx = this.width - tooltipW - 4;

        // 提到正面 Z（vanilla tooltip 用 +400）：HUD 预览在本层写深度，不抬 Z 会被深度测试压住
        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 400);
        // 背景两层：外圈深色描边 + 内里半透明，跟原版 tooltip 同款
        context.fill(tx - 1, ty - 1, tx + tooltipW + 1, ty + tooltipH + 1, 0xFF252525);
        context.fill(tx, ty, tx + tooltipW, ty + tooltipH, 0xEE111111);

        // 文字按行排，行高 fontHeight+2
        int yy = ty + padding;
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i).getString();
            // 首行若带警告，用黄字突出，其余正常灰
            int color = (!hudMasterOn && i == 0) ? 0xFFFF55 : 0xAAAAAA;
            context.drawTextWithShadow(tr, s, tx + padding, yy, color);
            yy += lineH;
        }
        context.getMatrices().pop();
    }

    /**
     * 悬浮在 HUD 元素上时绘制跟随鼠标的黑色名称 Tooltip——让玩家知道正对着的是谁。
     * 按住拖动（{@code dragging != null}）或鼠标离开边界时由调用方隐藏，拖动途中不打扰。
     */
    private void renderHoverTooltip(DrawContext context, TextRenderer tr, int mouseX, int mouseY, HudElement e) {
        String name = e.name;
        int padding = 4;
        int tooltipW = tr.getWidth(name) + padding * 2;
        int tooltipH = tr.fontHeight + padding * 2;

        // 跟随鼠标：停在右下 8px 处，快出屏幕就翻到左上，别让名称悬空
        int tx = mouseX + 8;
        int ty = mouseY + 8;
        if (tx + tooltipW > this.width - 4) tx = mouseX - tooltipW - 8;
        if (ty + tooltipH > this.height - 4) ty = mouseY - tooltipH - 8;

        // 提到正面 Z（vanilla tooltip 用 +400）：HUD 预览在本层 z≈0 写深度，
        // 同级 tooltip 会被预览文本/背景按深度测试压住——上移 Z 才盖得住。
        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 400);
        context.fill(tx - 1, ty - 1, tx + tooltipW + 1, ty + tooltipH + 1, 0xFF252525);
        context.fill(tx, ty, tx + tooltipW, ty + tooltipH, 0xEE111111);
        context.drawTextWithShadow(tr, name, tx + padding, ty + padding, 0xFFFFFF);
        context.getMatrices().pop();
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // 只压一层半透明遮罩：游戏画面仍透得出来，HUD 位置才好对着看
        context.fill(0, 0, this.width, this.height, 0x44000000);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        ensureWorkInit();

        // 先让已注册控件（按钮、滑条）优先吃点击：它们命中就不往下走
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        // 控件都没中：轮到 HUD 元素——命中即选中并开始拖动
        for (HudElement e : elements) {
            int x = (int) (e.workX * this.width);
            int y = (int) (e.workY * this.height);
            HudPreview p = e.preview.get();
            float s = (float) e.workScale;
            int bw = (int) (p.width * s);
            int bh = (int) (p.height * s);
            if (mouseX >= x - 2 && mouseX <= x + bw + 2
                    && mouseY >= y - 2 && mouseY <= y + bh + 2) {
                boolean wasSelected = (selected == e);
                selected = e;
                dragging = e;
                dragOffsetX = mouseX - x;
                dragOffsetY = mouseY - y;
                if (!wasSelected) layoutWidgets();
                return true;
            }
        }

        // 点空白：取消选中并收起行2，别让滑条悬着
        boolean hadSelected = (selected != null);
        selected = null;
        dragging = null;
        if (hadSelected) layoutWidgets();
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging != null) {
            HudPreview p = dragging.preview.get();
            float s = (float) dragging.workScale;
            int bw = (int) (p.width * s);
            int bh = (int) (p.height * s);

            double targetPx = mouseX - dragOffsetX;
            double targetPy = mouseY - dragOffsetY;
            final int BOX_PAD = 2;
            targetPx = Math.max(BOX_PAD, Math.min(this.width - bw - BOX_PAD, targetPx));
            targetPy = Math.max(BOX_PAD, Math.min(this.height - bh - BOX_PAD, targetPy));

            dragging.workX = targetPx / this.width;
            dragging.workY = targetPy / this.height;
            dragging.modified = true;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = null;
        dragOffsetX = 0;
        dragOffsetY = 0;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 滑条拖动回调：改工作区缩放并标记 modified，保存时才写回。 */
    private void onSliderChanged(double newScale) {
        if (selected == null) return;
        selected.workScale = newScale;
        selected.modified = true;
    }

    /** 保存：把工作区状态写回配置（只写改过的），再落盘并退出编辑器。 */
    private void save() {
        for (HudElement e : elements) {
            e.setVisible.accept(e.workVisible);
            if (e.modified) {
                e.setX.accept(e.workX);
                e.setY.accept(e.workY);
                e.setScale.accept(e.workScale);
            }
        }
        GlobalConfig.saveToFile();
        IS_OPEN = false;
        MinecraftClient.getInstance().setScreen(parent);
    }

    /** 显示开关：翻转工作区显隐并标记 modified，只影响预览、不动配置。 */
    private void toggleVisible() {
        if (selected == null) return;
        selected.workVisible = !selected.workVisible;
        selected.modified = true;
    }

    /** 取消：直接关编辑器回上一屏，工作区未保存的状态整体丢弃。 */
    private void cancel() {
        IS_OPEN = false;
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public void close() {
        IS_OPEN = false;
        if (parent != null) MinecraftClient.getInstance().setScreen(parent);
        else super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ---- init/close 生命周期：进出编辑器的唯一时机，负责置位 IS_OPEN ----

    @Override
    protected void init() {
        IS_OPEN = true;
        this.clearChildren();
        workInitialized = false;
        layoutWidgets();
    }

    // ---- 原生缩放滑条：直接继承 SliderWidget，消息即「缩放：1.00」 ----

    private static final class ScaleSliderWidget extends SliderWidget {

        private final Consumer<Double> onChange;

        ScaleSliderWidget(int x, int y, int width, int height, Consumer<Double> onChange) {
            super(x, y, width, height, Text.empty(), 0.5);
            this.onChange = onChange;
            updateMessage();
        }

        void setScaleValue(double scale) {
            double t = (scale - MIN_SCALE) / (MAX_SCALE - MIN_SCALE);
            this.value = Math.max(0.0, Math.min(1.0, t));
            updateMessage();
        }

        double getScaleValue() {
            return MIN_SCALE + this.value * (MAX_SCALE - MIN_SCALE);
        }

        @Override
        protected void updateMessage() {
            // 原版滑条风味：消息即「缩放：1.00」——冒号在 lang 文案里，中英各自排版
            setMessage(Text.translatable("nomorezombies.hudeditor.scale").copy()
                    .append(Text.literal(String.format("%.2f", getScaleValue()))));
        }

        @Override
        protected void applyValue() {
            onChange.accept(getScaleValue());
        }
    }

    // ---- 代表性内容预览：每个 HUD 按真实数据画一张静态样张，量出宽高供碰撞与绘制 ----

    private HudPreview buildSpawnTimePreview() {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int arrowW = tr.getWidth("➤ ");
        // AA 等地图有 W6 波次：样张画 6 行，碰撞框才不会被真实 W6 顶出边界
        String[] lines = {"W1 00:12", "W2 00:18", "W3 00:24", "W4 00:30", "W5 00:36", "W6 00:44"};
        int[] colors = {0x5A5A5A, 0x5A5A5A, 0xFFFF00, 0x808080, 0x808080, 0x808080};
        int lineW = 0;
        for (String s : lines) lineW = Math.max(lineW, tr.getWidth(s));
        final int fh = tr.fontHeight;
        return new HudPreview(arrowW + lineW, fh * lines.length, (ctx, x, y) -> {
            ctx.drawTextWithShadow(tr, "➤ ", x, y + fh * 2, 0xCC00CC);
            for (int i = 0; i < lines.length; i++)
                ctx.drawTextWithShadow(tr, lines[i], x + arrowW, y + fh * i, colors[i]);
        });
    }

    private HudPreview buildPowerupPreview() {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        String insta     = Text.translatable("nomorezombies.powerup.instaKill").getString();
        String maxAmmo   = Text.translatable("nomorezombies.powerup.maxAmmo").getString();
        String doubleGold= Text.translatable("nomorezombies.powerup.doubleGold").getString();
        String shopping  = Text.translatable("nomorezombies.powerup.shoppingSpree").getString();
        String roundLabel= Text.translatable("nomorezombies.powerup.hud.round").getString();
        String[] allNames = {
                insta, maxAmmo, doubleGold, shopping,
                Text.translatable("nomorezombies.powerup.carpenter").getString(),
                Text.translatable("nomorezombies.powerup.bonusGold").getString()
        };
        int widthBasic = tr.getWidth("-");
        int maxNameW = 0;
        for (String n : allNames) maxNameW = Math.max(maxNameW, tr.getWidth(n));
        final int nameCol = maxNameW;
        int wSplit = tr.getWidth(" - ");
        int colEnd = Math.max(tr.getWidth(roundLabel), wSplit + tr.getWidth("00:30"));
        final int fh = tr.fontHeight;
        return new HudPreview(widthBasic + nameCol + colEnd, fh * 4, (ctx, x, y) -> {
            int x0 = x + widthBasic;
            ctx.drawTextWithShadow(tr, "§c" + insta,      x0, y,          0xFFFFFF);
            ctx.drawTextWithShadow(tr, roundLabel,         x0 + nameCol, y, 0xFFFFFF);
            ctx.drawTextWithShadow(tr, "§9" + maxAmmo,    x0, y + fh,    0xFFFFFF);
            ctx.drawTextWithShadow(tr, roundLabel,         x0 + nameCol, y + fh, 0xFFFFFF);
            ctx.drawTextWithShadow(tr, "§6" + doubleGold, x0, y + fh*2,  0xFFFFFF);
            ctx.drawTextWithShadow(tr, " - ",              x0 + nameCol, y + fh*2, 0xFFFFFF);
            ctx.drawTextWithShadow(tr, "00:30",            x0 + nameCol + wSplit, y + fh*2, 0x99CCFF);
            ctx.drawTextWithShadow(tr, "§5" + shopping,   x0, y + fh*3,  0xFFFFFF);
            ctx.drawTextWithShadow(tr, " - ",              x0 + nameCol, y + fh*3, 0xFFFFFF);
            ctx.drawTextWithShadow(tr, "00:18",            x0 + nameCol + wSplit, y + fh*3, 0x99CCFF);
        });
    }

    private HudPreview buildTeamStatsPreview() {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        String[] headers = new String[]{
                trans("nomorezombies.teamstats.header.hp"),
                trans("nomorezombies.teamstats.header.status"),
                trans("nomorezombies.teamstats.header.kills"),
                trans("nomorezombies.teamstats.header.downs"),
                trans("nomorezombies.teamstats.header.deaths"),
                trans("nomorezombies.teamstats.header.gold")
        };
        String[] names   = {"PlayerA","PlayerB","PlayerC","PlayerD"};
        // 四种状态机各来一个：A 战斗中 / B 已倒地 / C 已死亡 / D 已退出，
        // 颜色与 TeamStatsRenderer.statusColor 对齐，预览和实战不两张皮
        int[]    healths = {20, 0, 0, 0};    // B/C/D 血量均显示 0——mod 没有「未知血量」机制
        int[] healthColors = {0x55FF55, 0xFF5555, 0xFF5555, 0xFF5555};
        String[] statuses = new String[]{
                trans("nomorezombies.teamstats.status.combat"),
                trans("nomorezombies.teamstats.status.downed"),
                trans("nomorezombies.teamstats.status.dead"),
                trans("nomorezombies.teamstats.status.left")
        };
        int[] statusColors = {0x55FF55, 0xFFFF55, 0xFF5555, 0xAA0000};
        int[] kills  = {12, 9, 5, 3};
        int[] downs  = {0, 1, 2, 0};
        int[] deaths = {0, 0, 1, 2};
        int[] golds  = {1200, 950, 700, 300};
        int count = names.length;

        int nameCol = 0;
        for (String n : names) nameCol = Math.max(nameCol, tr.getWidth(n));
        String[][] values   = new String[count][headers.length];
        int[][]    colors   = new int[count][headers.length];
        int[]      colWidth = new int[headers.length];
        for (int r = 0; r < count; r++) {
            values[r][0] = healths[r] < 0 ? "?" : String.valueOf(healths[r]); colors[r][0] = healthColors[r];
            values[r][1] = statuses[r];                                         colors[r][1] = statusColors[r];
            values[r][2] = String.valueOf(kills[r]);   colors[r][2] = 0xFFFFFF;
            values[r][3] = String.valueOf(downs[r]);   colors[r][3] = 0xFFFFFF;
            values[r][4] = String.valueOf(deaths[r]);  colors[r][4] = 0xFFFFFF;
            values[r][5] = String.valueOf(golds[r]);   colors[r][5] = 0xFFFFFF;
            for (int i = 0; i < headers.length; i++) {
                colWidth[i] = Math.max(colWidth[i], tr.getWidth(headers[i]));
                colWidth[i] = Math.max(colWidth[i], tr.getWidth(values[r][i]));
            }
        }
        int[] colX = new int[headers.length];
        int cx2 = NAME_OFFSET + nameCol + PAD;
        for (int i = 0; i < headers.length; i++) { colX[i] = cx2; cx2 += colWidth[i] + PAD; }
        final int totalW = cx2;
        final int totalH = tr.fontHeight + 2 + count * (tr.fontHeight + 1);
        return new HudPreview(totalW, totalH, (ctx, x, y) -> {
            for (int i = 0; i < headers.length; i++)
                ctx.drawTextWithShadow(tr, headers[i], x + colX[i], y, 0xFFFF55);
            int yy = y + tr.fontHeight + 2;
            for (int r = 0; r < count; r++) {
                // 名称前裁切头像：示例用本地 default_avatar.png，不发皮肤请求、离线也能预览
                AvatarManager.drawDefaultHead(ctx, x, yy + (tr.fontHeight - AVATAR_W) / 2, AVATAR_W);
                ctx.drawTextWithShadow(tr, names[r], x + NAME_OFFSET, yy, 0xFFFFFF);
                for (int i = 0; i < headers.length; i++)
                    ctx.drawTextWithShadow(tr, values[r][i], x + colX[i], yy, colors[r][i]);
                yy += tr.fontHeight + 1;
            }
        });
    }

    private HudPreview buildGameTimePreview() {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        String gameLabel  = Text.translatable("nomorezombies.timehud.game").getString();
        String roundLabel = Text.translatable("nomorezombies.timehud.round").getString();
        final int fh = tr.fontHeight;
        final int wGame  = tr.getWidth(gameLabel)  + tr.getWidth("00:12:34");
        final int wRound = tr.getWidth(roundLabel) + tr.getWidth("00:45");
        return new HudPreview(Math.max(wGame, wRound), fh * 2, (ctx, x, y) -> {
            ctx.drawTextWithShadow(tr, gameLabel,   x, y,       0xFFAA00);
            ctx.drawTextWithShadow(tr, "00:12:34",  x + tr.getWidth(gameLabel),  y,      0xFFFFFF);
            ctx.drawTextWithShadow(tr, roundLabel,  x, y + fh,  0xFFAA00);
            ctx.drawTextWithShadow(tr, "00:45",     x + tr.getWidth(roundLabel), y + fh, 0xFFFFFF);
        });
    }

    private HudPreview buildLRQueuePreview() {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        final int tileW = 26, tileH = 34, gap = 3;
        final int totalW = tileW * 4 + gap * 3;
        ItemStack cooldownIcon = new ItemStack(Items.GRAY_DYE);
        ItemStack readyIcon = new ItemStack(Items.BLAZE_ROD);
        return new HudPreview(totalW, tileH, (ctx, x, y) -> {
            for (int i = 0; i < 4; i++) {
                int tx = x + i * (tileW + gap);
                boolean cooling = i < 2;
                int border = cooling ? 0xFF41A5FF : 0xFF46DC78;
                int textColor = cooling ? 0xFFEBF5FF : 0xFF64FF91;
                String status = cooling ? (i == 0 ? "20" : "12") : trans("nomorezombies.lrqueue.ready");
                ctx.fill(tx, y, tx + tileW, y + tileH, 0xBE0D1117);
                ctx.fill(tx, y, tx + tileW, y + 1, border);
                ctx.fill(tx, y + tileH - 1, tx + tileW, y + tileH, border);
                ctx.fill(tx, y, tx + 1, y + tileH, border);
                ctx.fill(tx + tileW - 1, y, tx + tileW, y + tileH, border);
                ctx.drawItem(cooling ? cooldownIcon : readyIcon, tx + (tileW - 16) / 2, y + 2);
                if (cooling) {
                    ctx.fill(tx + 4, y + 2, tx + tileW - 4, y + 20, 0x9B05080D);
                }
                ctx.drawTextWithShadow(tr, Integer.toString(i + 1), tx + 2, y + 2, 0xFFAFBECD);
                ctx.drawTextWithShadow(tr, status, tx + (tileW - tr.getWidth(status)) / 2, y + 21, textColor);

                // 底部进度条与 LightningRodQueue.drawSlot 同款：冷却按剩余比例、就绪满条，预览即实战
                float progress = cooling ? (i == 0 ? 1.0f : 0.6f) : 1.0f;
                int progressColor = cooling ? 0xFF37B4FF : 0xFF46DC78;
                int progressWidth = Math.round((tileW - 2) * progress);
                ctx.fill(tx + 1, y + tileH - 3, tx + 1 + progressWidth, y + tileH - 1, progressColor);
            }
        });
    }

    private HudPreview buildAaCommandPreview() {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        String yes = trans("nomorezombies.aacommand.yes");
        String no = trans("nomorezombies.aacommand.no");
        String[] labels = {
                trans("nomorezombies.aacommand.hud.round"),
                trans("nomorezombies.aacommand.hud.giant"),
                trans("nomorezombies.aacommand.hud.oldone"),
                trans("nomorezombies.aacommand.hud.difficulty"),
                trans("nomorezombies.aacommand.hud.spots")
        };
        String[] values = {"r15", yes, no, "III", "#1 rc #2 cc"};
        int[] colors = {0xFFFFFF, 0xFF5555, 0x55FF55, 0xFFFF55, 0xFFFFFF};

        // 推荐点位行分段着色，与真实 HUD drawSpotsLine / 聊天 buildPointsText 同步：#N 蓝 + 点位名白
        String[][] spotSegments = {{"#1", "rc"}, {"#2", "cc"}};
        final int SPOTS_ROW = labels.length - 1;

        int totalW = 0;
        for (int i = 0; i < labels.length; i++) {
            totalW = Math.max(totalW, tr.getWidth(labels[i]) + tr.getWidth(values[i]));
        }
        final int fh = tr.fontHeight;
        return new HudPreview(totalW, fh * labels.length, (ctx, x, y) -> {
            for (int i = 0; i < labels.length; i++) {
                int cx = x + tr.getWidth(labels[i]);
                ctx.drawTextWithShadow(tr, labels[i], x, y + fh * i, 0xFFAA00);
                if (i == SPOTS_ROW) {
                    // "#N" 蓝 0x5555FF（= §9 = Formatting.BLUE）+ 点位名白 0xFFFFFF——与聊天输出同色，别各画各的
                    for (int s = 0; s < spotSegments.length; s++) {
                        String marker = spotSegments[s][0] + " ";
                        ctx.drawTextWithShadow(tr, marker, cx, y + fh * i, 0x5555FF);
                        cx += tr.getWidth(marker);
                        ctx.drawTextWithShadow(tr, spotSegments[s][1], cx, y + fh * i, 0xFFFFFF);
                        cx += tr.getWidth(spotSegments[s][1]);
                        if (s != spotSegments.length - 1) {
                            ctx.drawTextWithShadow(tr, " ", cx, y + fh * i, 0xFFFFFF);
                            cx += tr.getWidth(" ");
                        }
                    }
                } else {
                    ctx.drawTextWithShadow(tr, values[i], cx, y + fh * i, colors[i]);
                }
            }
        });
    }

    private HudPreview buildCpsPreview() {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        String left = trans("nomorezombies.cps.left");
        String right = trans("nomorezombies.cps.right");
        String unit = trans("nomorezombies.cps.unit");
        String line1 = left + " 12 " + unit;
        String line2 = right + " 8 " + unit;
        final int w = Math.max(tr.getWidth(line1), tr.getWidth(line2));
        final int h = tr.fontHeight * 2;
        return new HudPreview(w, h, (ctx, x, y) -> {
            int cx = x;
            ctx.drawTextWithShadow(tr, left, cx, y, 0xFF5555);
            cx += tr.getWidth(left);
            ctx.drawTextWithShadow(tr, " 12", cx, y, 0xFFFFFF);
            cx += tr.getWidth(" 12");
            ctx.drawTextWithShadow(tr, " " + unit, cx, y, 0xAAAAAA);

            cx = x;
            ctx.drawTextWithShadow(tr, right, cx, y + tr.fontHeight, 0x55FF55);
            cx += tr.getWidth(right);
            ctx.drawTextWithShadow(tr, " 8", cx, y + tr.fontHeight, 0xFFFFFF);
            cx += tr.getWidth(" 8");
            ctx.drawTextWithShadow(tr, " " + unit, cx, y + tr.fontHeight, 0xAAAAAA);
        });
    }

    // ---- 数据结构：HudElement 绑一个 HUD 的全部读写口，HudPreview 只装样张 ----

    /** 一个 HUD 的全部编辑信息：读写口、解析口、样张，外加一组工作区状态。 */
    private static final class HudElement {
        final String name;
        final DoubleSupplier getX, getY, getScale;
        final DoubleConsumer setX, setY, setScale;
        final BooleanSupplier getVisible;
        final Consumer<Boolean> setVisible;
        final IntToDoubleFunction resolvedX, resolvedY;
        final Supplier<HudPreview> preview;
        double workX, workY, workScale;
        boolean workVisible;
        boolean modified;

        HudElement(String name, DoubleSupplier gx, DoubleConsumer sx, DoubleSupplier gy, DoubleConsumer sy,
                   DoubleSupplier gs, DoubleConsumer ss,
                   BooleanSupplier getVisible, Consumer<Boolean> setVisible,
                   IntToDoubleFunction resolvedX, IntToDoubleFunction resolvedY,
                   Supplier<HudPreview> preview) {
            this.name = name;
            this.getX = gx; this.setX = sx;
            this.getY = gy; this.setY = sy;
            this.getScale = gs; this.setScale = ss;
            this.getVisible = getVisible; this.setVisible = setVisible;
            this.resolvedX = resolvedX;
            this.resolvedY = resolvedY;
            this.preview = preview;
        }
    }

    /** 静态样张：宽高 + 绘制动作，render 用它量碰撞框并画预览。 */
    private static final class HudPreview {
        final int width;
        final int height;
        final RenderFunction render;

        HudPreview(int width, int height, RenderFunction render) {
            this.width = width;
            this.height = height;
            this.render = render;
        }
    }

    /** 预览绘制回调：把样张画到指定 (x, y)。 */
    private interface RenderFunction {
        void render(DrawContext ctx, int x, int y);
    }
}