package cn.gsfy.nmz.client.feature.playerquery;

import cn.gsfy.nmz.client.data.ZombiesStatsParser;
import cn.gsfy.nmz.client.data.model.ApiResult;
import cn.gsfy.nmz.client.data.model.ZombiesStats;
import cn.gsfy.nmz.client.util.AvatarManager;
import cn.gsfy.nmz.client.util.PlayerUtils;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 玩家数据查询界面（原生 Screen，不暂停游戏）——把 Hypixel API 拉到的
 * 数据摊成一块能滚动的统计面板。
 *
 * <p>顶部在「自由查询」（输入玩家名/UUID）与「局内查询」（基于缓存、
 * 带 ◀▶ 切换和自动轮换）之间切换；局内查询按钮只在游戏进行中
 * （{@link PlayerUtils#isInZombies()} 为 true）可点。没配 API Key 时，
 * 查询按钮和局内自动请求全被禁用，顶部先亮黄色风险提示，配上后自动消失。
 * 数据区只做一个面板：{@link DrawContext#enableScissor} 裁剪（注意它收
 * 的是绝对坐标）+ {@code scrollOffset} 平移，鼠标滚轮翻页。背景与
 * HUD 编辑器一致，只盖半透明 {@code 0x44000000} 遮罩，右上角 ✕ 退出。
 *
 * <p>坐标体系：模式按钮与局内工具栏各自焊死 Y、不随 hasKey 漂移，
 * 自由模式的输入框和结果面板则跟着 Key 状态走，锚点如下——
 * <pre>
 *  Y=6   标题居中 / 关闭按钮
 *  Y=32  模式切换按钮（Free Query | In-Game）——固定，不随 hasKey 漂移
 *  Y=56  局内工具栏（◀ ▶ Auto-rotate）——固定，与模式按钮无关
 *  Y=58  自由模式输入框（hasKey）/ 警告横幅起点（no-key）
 *  Y=80  局内玩家列表起点（工具栏底端 56+20+4px）
 *  panelY（自由）= inputField.getY() + inputField.getHeight() + 8  动态跟随
 *  panelY（局内）= 80（与列表起点对齐）
 * </pre>
 */
public class PlayerQueryScreen extends Screen {

    /** 局内列表自动轮换间隔（tick，8 秒）——倒计时到点就自动切到下一位。 */
    private static final int AUTO_ROTATE_TICKS = 160;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 同行 label 与右对齐值之间的间距（px）——并排判定时也算进可用宽度。 */
    private static final int GAP = 10;
    /** 折行值相对面板左缘的缩进（px）——折下来的行往右让一点，层次分得清。 */
    private static final int INDENT = 8;

    /**
     * 模式切换按钮的固定 Y——两个模式按钮焊死在 32，
     * 不随 hasKey 上下漂移，随时都能切回另一个模式。
     */
    private static final int MODE_BTN_Y = 32;
    /**
     * 局内工具栏（◀ ▶ Auto-rotate）的固定 Y——和模式按钮各占一层，
     * 互不牵连，也不随内容区下移，切模式时按钮不乱跳。
     */
    private static final int TOOLBAR_Y = 56;
    /**
     * 局内玩家列表 / 详情面板的起点 Y——正好是
     * TOOLBAR_Y + 按钮高度(20) + 间距(4)，跟工具栏底端对齐。
     */
    private static final int LIST_Y = 80;

    private final Screen parent;

    private boolean freeMode = true;
    private boolean autoRotate = true;
    private int autoTicks;
    private int selectedIndex;
    private int scrollOffset;

    // 自由查询状态：请求是否在飞、上次结果落在哪
    private boolean freeLoading;
    private ApiResult freeResult;

    // 鼠标位置——每帧 render 刷新，drawStatsPanel 靠它判断悬浮的是哪一行
    private int hoverMouseX;
    private int hoverMouseY;

    // 界面控件：init() 里建好，layout() 里摆位
    private TextFieldWidget inputField;
    private ButtonWidget queryButton;
    private ButtonWidget closeButton;
    private ButtonWidget modeFreeButton;
    private ButtonWidget modeInGameButton;
    private ButtonWidget prevButton;
    private ButtonWidget nextButton;
    private ButtonWidget autoRotateButton;

    public PlayerQueryScreen(Screen parent) {
        super(Text.translatable("nomorezombies.query.title"));
        this.parent = parent;
    }

    // ── 生命周期 ─────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        this.clearChildren();
        this.selectedIndex = 0;
        this.scrollOffset = 0;

        this.closeButton = ButtonWidget.builder(
                        Text.translatable("nomorezombies.query.button.close"), b -> close())
                .dimensions(this.width - 58, 6, 50, 20).build();

        // 模式切换按钮焊在 Y=32，不参与后续坐标漂移
        this.modeFreeButton = ButtonWidget.builder(
                        Text.translatable("nomorezombies.query.mode.free"), b -> switchMode(true))
                .dimensions(10, MODE_BTN_Y, 90, 20).build();
        this.modeInGameButton = ButtonWidget.builder(
                        Text.translatable("nomorezombies.query.mode.ingame"), b -> switchMode(false))
                .dimensions(104, MODE_BTN_Y, 90, 20).build();

        // 自由模式：输入框 + 查询按钮——Y 不定死，交给 layout() 按 hasKey 摆
        this.inputField = new TextFieldWidget(this.textRenderer, 10, 58, 200, 20,
                Text.translatable("nomorezombies.query.input.placeholder"));
        this.inputField.setMaxLength(40);
        this.inputField.setPlaceholder(Text.translatable("nomorezombies.query.input.placeholder"));
        this.queryButton = ButtonWidget.builder(
                        Text.translatable("nomorezombies.query.button.query"), b -> doFreeQuery())
                .dimensions(214, 58, 60, 20).build();

        // 局内工具栏：◀ ▶ 与 Auto-rotate——焊在 Y=TOOLBAR_Y，跟模式按钮互不干扰
        this.prevButton = ButtonWidget.builder(Text.literal("◀"), b -> cycleSelection(-1))
                .dimensions(10, TOOLBAR_Y, 24, 20).build();
        this.nextButton = ButtonWidget.builder(Text.literal("▶"), b -> cycleSelection(1))
                .dimensions(36, TOOLBAR_Y, 24, 20).build();
        this.autoRotateButton = ButtonWidget.builder(
                        Text.translatable("nomorezombies.query.toggle.autorotate" + (this.autoRotate ? ".on" : ".off")), b -> toggleAutoRotate())
                .dimensions(66, TOOLBAR_Y, 92, 20).build();

        this.addDrawableChild(closeButton);
        this.addDrawableChild(modeFreeButton);
        this.addDrawableChild(modeInGameButton);
        this.addDrawableChild(inputField);
        this.addDrawableChild(queryButton);
        this.addDrawableChild(prevButton);
        this.addDrawableChild(nextButton);
        this.addDrawableChild(autoRotateButton);

        layout();
    }

    @Override
    public void tick() {
        super.tick();
        if (!freeMode && autoRotate) {
            if (++autoTicks >= AUTO_ROTATE_TICKS) {
                autoTicks = 0;
                cycleSelection(1);
            }
        }
    }

    @Override
    public void close() {
        if (parent != null) {
            this.client.setScreen(parent);
        } else {
            super.close();
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ── 模式切换 / 布局 ───────────────────────────────────────────────────────

    private void switchMode(boolean free) {
        this.freeMode = free;
        this.selectedIndex = 0;
        this.scrollOffset = 0;
        this.autoTicks = 0;
        layout();
    }

    private void toggleAutoRotate() {
        this.autoRotate = !this.autoRotate;
        this.autoTicks = 0;
        this.autoRotateButton.setMessage(Text.translatable(
                "nomorezombies.query.toggle.autorotate" + (this.autoRotate ? ".on" : ".off")));
    }

    private void cycleSelection(int dir) {
        List<String> names = PlayerQueryManager.get().currentInGameNames();
        if (names.isEmpty()) {
            return;
        }
        this.selectedIndex = Math.floorMod(this.selectedIndex + dir, names.size());
        this.scrollOffset = 0;
        this.autoTicks = 0;
    }

    /**
     * 依据模式、Key 状态、是否在游戏中摆放控件（init 与模式切换时调用）。
     *
     * <p>坐标职责分离：模式按钮与局内工具栏各自焊死 Y（{@link #MODE_BTN_Y}
     * / {@link #TOOLBAR_Y}），永不漂移；自由模式输入框的 Y 随 hasKey 决定
     * （有 Key=58，无 Key=100），结果面板再动态跟随输入框底端——这样没 Key
     * 时警告横幅有地方站，不会压住输入框。局内模式按钮只在
     * {@link PlayerUtils#isInZombies()} 为 true 时可点，免得在非 Zombies
     * 界面点出一个空列表。
     */
    private void layout() {
        PlayerQueryManager manager = PlayerQueryManager.get();
        boolean hasKey = manager.hasApiKey();
        boolean inZombies = PlayerUtils.isInZombies();
        boolean inGame = !freeMode;

        // 当前模式对应的按钮置灰（灰 = 已选中），另一侧才亮着可点
        this.modeFreeButton.active = inGame;
        // 局内查询按钮：不在 Zombies 局内就禁用——点了也没数据可列
        this.modeInGameButton.active = freeMode && inZombies;

        // 自由模式控件：局内模式下全藏起来，只留列表和详情
        this.inputField.visible = !inGame;
        this.queryButton.visible = !inGame;
        this.queryButton.active = !inGame && hasKey;

        // 局内工具栏：自由模式下藏起 ◀ ▶ 与 Auto-rotate
        this.prevButton.visible = inGame;
        this.nextButton.visible = inGame;
        this.autoRotateButton.visible = inGame;
        if (inGame) {
            this.autoRotateButton.setMessage(Text.translatable(
                    "nomorezombies.query.toggle.autorotate" + (this.autoRotate ? ".on" : ".off")));
        }

        // 自由模式：输入框 Y 跟着 hasKey 走——没 Key 时警告横幅占约 42px，整体下移让路
        if (!inGame) {
            int inputY = hasKey ? 58 : 100;
            this.inputField.setY(inputY);
            this.queryButton.setY(inputY);
            this.inputField.setX(10);
            this.queryButton.setX(this.inputField.getX() + this.inputField.getWidth() + 4);
        }
    }

    // ── 渲染 ─────────────────────────────────────────────────────────────────

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // 和 HUD 编辑器同款：只盖半透明遮罩，游戏画面不打断，还能看见身后战况
        context.fill(0, 0, this.width, this.height, 0x44000000);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        // 先记下鼠标位置，drawStatsPanel 才能算悬浮行——每帧都要刷新
        this.hoverMouseX = mouseX;
        this.hoverMouseY = mouseY;

        // 标题居中画在顶部
        String title = this.title.getString();
        context.drawTextWithShadow(this.textRenderer, title,
                (this.width - this.textRenderer.getWidth(title)) / 2, 8, 0xFFFFFF);

        if (!PlayerQueryManager.get().hasApiKey()) {
            drawNoKeyWarning(context);
        }

        if (freeMode) {
            renderFreeMode(context);
        } else {
            renderInGameMode(context);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    /** 没配 Key 时的黄色风险提示——讲清去哪申请、填到哪，
     *  否则玩家只看到一块永远查不了的界面。
     */
    private void drawNoKeyWarning(DrawContext context) {
        String[] lines = {
                Text.translatable("nomorezombies.query.warning.nokey.line1").getString(),
                Text.translatable("nomorezombies.query.warning.nokey.line2").getString(),
                Text.translatable("nomorezombies.query.warning.nokey.line3").getString()
        };
        int y = 58;
        // Bug5 踩坑：行高用 fontHeight + 2 而非写死 10px——字号一变，写死就压字
        int lineH = this.textRenderer.fontHeight + 2;
        for (String line : lines) {
            context.drawTextWithShadow(this.textRenderer, line, 10, y, 0xFFFF55);
            y += lineH;
        }
    }

    // ── 自由查询 ─────────────────────────────────────────────────────────────

    /** 发起自由查询：空输入直接报错，请求在途则忽略，结果异步写回并重置滚动。 */
    private void doFreeQuery() {
        if (this.freeLoading) {
            return;
        }
        String input = this.inputField.getText();
        if (input == null || input.trim().isEmpty()) {
            this.freeResult = ApiResult.error("nomorezombies.query.status.nodata");
            return;
        }
        this.freeLoading = true;
        this.freeResult = null;
        PlayerQueryManager.get().queryFree(input, result -> {
            this.freeLoading = false;
            this.freeResult = result;
            this.scrollOffset = 0;
        });
    }

    /** 自由模式渲染：加载中 / 空闲 / 出错 / 出结果，四种状态各画各的。 */
    private void renderFreeMode(DrawContext context) {
        // Bug3 踩坑：panelY 动态跟随输入框底端而非写死——
        // 输入框随 hasKey 上下移，写死就错位
        int panelY = this.inputField.getY() + this.inputField.getHeight() + 8;
        int panelX = 10;
        int panelW = this.width - 20;

        if (freeLoading) {
            drawStatus(context, panelX, panelY, tr("nomorezombies.query.status.loading"), 0xFFFF55);
            return;
        }
        if (freeResult == null) {
            drawStatus(context, panelX, panelY, tr("nomorezombies.query.status.idle"), 0xAAAAAA);
            return;
        }
        if (!freeResult.ok()) {
            drawError(context, panelX, panelY, panelW, freeResult.errorKey(), freeResult.arg());
            return;
        }
        drawStatsPanel(context, freeResult.stats(), panelX, panelY, panelX + panelW);
    }

    // ── 局内查询 ─────────────────────────────────────────────────────────────

    /** 局内模式渲染：左侧玩家列表 + 右侧详情面板，列表行内嵌请求状态小字。 */
    private void renderInGameMode(DrawContext context) {
        PlayerQueryManager manager = PlayerQueryManager.get();
        List<String> names = manager.currentInGameNames();
        boolean hasKey = manager.hasApiKey();

        // 左侧玩家列表：宽度随名字自适应，横向空间尽量让给右侧详情面板
        // 列表起点焊在 LIST_Y，跟工具栏各算各的坐标
        int listX = 10;
        int listY = LIST_Y;
        int entryH = 20;
        int listW = inGameListWidth();
        int panelX = listX + listW + 10;

        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            int ey = listY + i * entryH;
            if (i == selectedIndex) {
                context.fill(listX, ey, listX + listW, ey + entryH - 2, 0x30FFFFFF);
            }
            AvatarManager.drawHead(context, name, null, listX + 3, ey + 5);
            context.drawTextWithShadow(this.textRenderer, name, listX + 16, ey + 4, 0xFFFFFF);
            // 第二行小字：加载中 / 出错 / 网络等级——一眼看清每人请求状态
            String brief = briefFor(manager, name, hasKey);
            context.drawTextWithShadow(this.textRenderer, brief, listX + 16, ey + 12, 0x888888);
        }
        if (names.isEmpty()) {
            drawStatus(context, listX, listY, tr("nomorezombies.query.status.noplayer"), 0xAAAAAA);
        }

        // 右侧详情面板：裁剪 + 滚动，正文在 drawStatsPanel 里画
        if (names.isEmpty()) {
            return;
        }
        if (selectedIndex >= names.size()) {
            selectedIndex = names.size() - 1;
        }
        String selected = names.get(selectedIndex);
        ZombiesStats stats = manager.getCached(selected);
        if (stats != null) {
            drawStatsPanel(context, stats, panelX, listY, this.width - 10);
        } else if (manager.isLoading(selected)) {
            drawStatus(context, panelX, listY, tr("nomorezombies.query.status.loading"), 0xFFFF55);
        } else {
            ApiResult err = manager.getError(selected);
            if (err != null) {
                drawError(context, panelX, listY, this.width - 10 - panelX, err.errorKey(), err.arg());
                drawStatus(context, panelX, listY + this.textRenderer.fontHeight + 4,
                        tr("nomorezombies.query.hint.retry"), 0xFFFF55);
            } else {
                drawStatus(context, panelX, listY,
                        hasKey ? tr("nomorezombies.query.status.nodata") : tr("nomorezombies.query.warning.nokey"),
                        0xAAAAAA);
            }
        }
    }

    /** 列表条目第二行小字——按「无 Key → 加载中 → 失败 → 网络等级 → 待请求」取一句话。 */
    private String briefFor(PlayerQueryManager manager, String name, boolean hasKey) {
        if (!hasKey) {
            return tr("nomorezombies.query.hint.nokey.short");
        }
        if (manager.isLoading(name)) {
            return tr("nomorezombies.query.status.loading");
        }
        if (manager.getError(name) != null) {
            return tr("nomorezombies.query.status.failed");
        }
        ZombiesStats stats = manager.getCached(name);
        if (stats != null) {
            return "Lv." + stats.networkLevel;
        }
        return tr("nomorezombies.query.status.waiting");
    }

    // ── 详情面板（自由查询结果 / 局内选中玩家共用） ─────────────────────────

    /**
     * 绘制详情面板（统计数据滚动区）。
     *
     * <p>Bug1 踩坑：{@link DrawContext#enableScissor} 收的是两个角的
     * <b>绝对坐标</b> {@code (x1, y1, x2, y2)}，不是 {@code (x, y, width, height)}。
     * 早先传宽高进去，裁剪区整体偏到左上角附近，内容全被裁没了。
     */
    private void drawStatsPanel(DrawContext context, ZombiesStats s, int panelX, int panelY, int panelRight) {
        int panelBottom = this.height - 10;
        int avail = panelRight - panelX;
        int fh = this.textRenderer.fontHeight;

        // 先展开成可绘制的文本行、精确算出内容高度，滚动区间才准，再裁剪绘制
        List<DrawRow> rows = buildRows(buildLines(s), avail);
        int contentH = 0;
        for (DrawRow r : rows) {
            contentH += r.gap(fh);
        }
        int maxScroll = Math.max(0, contentH - (panelBottom - panelY));
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }

        // Bug1 修复：这里传绝对坐标 (panelX, panelY, panelRight, panelBottom)，
        // 宽高版会整体错位
        context.enableScissor(panelX, panelY, panelRight, panelBottom);
        int y = panelY - scrollOffset;
        for (DrawRow r : rows) {
            int rowH = r.gap(fh);
            // 悬浮行高亮：只有数据行（非分区标题）响应，鼠标落进行区域才画淡白底
            // 高亮高度只盖文字本身（fh）、不延伸到行间距——否则条带下沿会串行
            // 判定热区仍用完整 rowH：行间空隙也能触发当前行，鼠标更好点中
            if (!r.header
                    && hoverMouseX >= panelX && hoverMouseX < panelRight
                    && hoverMouseY >= y && hoverMouseY < y + rowH) {
                context.fill(panelX, y, panelRight, y + fh, 0x28FFFFFF);
            }
            int textX = r.indent ? panelX + INDENT : panelX;
            if (r.right == null) {
                context.drawTextWithShadow(this.textRenderer, r.left, textX, y, r.color);
            } else {
                context.drawTextWithShadow(this.textRenderer, r.left, textX, y, r.color);
                int vw = this.textRenderer.getWidth(r.right);
                context.drawTextWithShadow(this.textRenderer, r.right, panelRight - vw, y, 0xFFFFFF);
            }
            y += rowH;
        }
        context.disableScissor();
    }

    /**
     * 把语义行（label/value）展开成可绘制的文本行：一行放得下就
     * 「label 左灰 + 值右白」并排；放不下就让 label 独占一行、
     * 值按可用宽度折行——不然地图四列、杂项长串这类长数据会被裁掉。
     */
    private List<DrawRow> buildRows(List<Line> lines, int avail) {
        List<DrawRow> out = new ArrayList<>();
        for (Line l : lines) {
            if (l.value == null) {
                appendWrapped(out, l.label, l.color, true, avail, false);
                continue;
            }
            int labelW = this.textRenderer.getWidth(l.label);
            int valueW = this.textRenderer.getWidth(l.value);
            if (labelW + GAP + valueW <= avail) {
                out.add(new DrawRow(l.label, l.value, l.color, false, false));
            } else {
                appendWrapped(out, l.label, l.color, false, avail, false);
                appendWrapped(out, l.value, 0xFFFFFF, false, avail, true);
            }
        }
        return out;
    }

    /** 追加一行文本，放不下就交给 wrapText 折行（header 标记决定行间距）。 */
    private void appendWrapped(List<DrawRow> out, String text, int color, boolean header,
                               int avail, boolean indent) {
        if (this.textRenderer.getWidth(text) <= avail) {
            out.add(new DrawRow(text, null, color, indent, header));
            return;
        }
        for (String line : wrapText(text, avail)) {
            out.add(new DrawRow(line, null, color, indent, header));
        }
    }

    /**
     * 按可用宽度折行：优先在最后一个空格处断开，不把拉丁单词劈成两半；
     * 没有空格（中文等）就逐字符断。每行至少塞得下一个字符，必然收敛。
     */
    private List<String> wrapText(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add(text == null ? "" : text);
            return out;
        }
        if (maxWidth <= 0) {
            out.add(text);
            return out;
        }
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            if (line.length() > 0 && this.textRenderer.getWidth(line.toString() + ch) > maxWidth) {
                // 行满了：行里有空格就在最后一个空格处换行，别把英文单词拆断
                int sp = line.lastIndexOf(" ");
                if (sp > 0) {
                    out.add(line.substring(0, sp));
                    line = new StringBuilder(line.substring(sp + 1));
                    continue;
                }
                out.add(line.toString());
                line = new StringBuilder();
            }
            line.append(ch);
            i += Character.charCount(cp);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    /** 局内左侧列表宽度：按最长名字自适应（钳在 130–210px），横向空间让给详情面板。 */
    private int inGameListWidth() {
        int maxName = 0;
        for (String n : PlayerQueryManager.get().currentInGameNames()) {
            maxName = Math.max(maxName, this.textRenderer.getWidth(n));
        }
        return Math.max(130, Math.min(16 + maxName + 10, 210));
    }

    /** 把一份 ZombiesStats 汇总成全部分区的绘制行——概览、综合、各图、敌人、最快、杂项。 */
    private List<Line> buildLines(ZombiesStats s) {
        List<Line> lines = new ArrayList<>();

        // 玩家概览
        lines.add(header(tr("nomorezombies.query.section.overview")));
        if (!s.displayName.isEmpty()) {
            lines.add(row(tr("nomorezombies.query.overview.name"), s.displayName));
        }
        lines.add(row(tr("nomorezombies.query.overview.level"), "Lv." + s.networkLevel));
        if (s.karma > 0) {
            lines.add(row(tr("nomorezombies.query.overview.karma"), fmt(s.karma)));
        }
        if (s.networkExp > 0) {
            lines.add(row(tr("nomorezombies.query.overview.exp"), fmt(s.networkExp)));
        }
        lines.add(row(tr("nomorezombies.query.overview.firstlogin"), fmtDate(s.firstLogin)));
        lines.add(row(tr("nomorezombies.query.overview.lastlogin"), fmtDate(s.lastLogin)));
        lines.add(row(tr("nomorezombies.query.overview.lastlogout"), fmtDate(s.lastLogout)));

        // 综合统计
        if (!s.overall.isEmpty()) {
            lines.add(header(tr("nomorezombies.query.section.overall")));
            for (ZombiesStats.Row r : s.overall) {
                lines.add(row(r.label, r.value));
            }
        }

        // 各地图统计
        if (!s.perMap.isEmpty()) {
            lines.add(header(tr("nomorezombies.query.section.maps")));
            for (Map.Entry<String, List<ZombiesStats.MapStat>> e : s.perMap.entrySet()) {
                lines.add(header("   " + e.getKey()));
                for (ZombiesStats.MapStat ms : e.getValue()) {
                    lines.add(row(ms.label, formatMapStat(ms)));
                }
            }
        }

        // 敌人击杀
        if (!s.enemyKills.isEmpty()) {
            lines.add(header(tr("nomorezombies.query.section.enemies")));
            for (Map.Entry<String, Long> e : s.enemyKills.entrySet()) {
                lines.add(row(e.getKey(), fmt(e.getValue())));
            }
        }

        // 最快回合记录
        if (!s.fastestTimes.isEmpty()) {
            lines.add(header(tr("nomorezombies.query.section.fastest")));
            for (Map.Entry<Integer, Map<String, Long>> e : s.fastestTimes.entrySet()) {
                lines.add(header("   " + tr("nomorezombies.query.fastest.round", e.getKey())));
                for (Map.Entry<String, Long> scope : e.getValue().entrySet()) {
                    lines.add(row(scope.getKey(), ZombiesStatsParser.formatTime(scope.getValue())));
                }
            }
        }

        // 杂项
        if (!s.misc.isEmpty()) {
            lines.add(header(tr("nomorezombies.query.section.misc")));
            for (ZombiesStats.Row r : s.misc) {
                lines.add(row(r.label, r.value));
            }
        }

        return lines;
    }

    private String formatMapStat(ZombiesStats.MapStat ms) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : ms.values.entrySet()) {
            if (sb.length() > 0) {
                sb.append("   ");
            }
            sb.append(e.getKey()).append(": ").append(fmt(e.getValue()));
        }
        return sb.toString();
    }

    // ── 状态 / 错误 / 辅助 ───────────────────────────────────────────────────

    private void drawStatus(DrawContext context, int x, int y, String text, int color) {
        context.drawTextWithShadow(this.textRenderer, text, x, y, color);
    }

    private void drawError(DrawContext context, int x, int y, int maxWidth, String key, String arg) {
        String msg = (arg != null) ? tr(key, arg) : tr(key);
        if (this.textRenderer.getWidth(msg) <= maxWidth) {
            context.drawTextWithShadow(this.textRenderer, msg, x, y, 0xFF5555);
            return;
        }
        int yy = y;
        for (String line : wrapText(msg, maxWidth)) {
            context.drawTextWithShadow(this.textRenderer, line, x, yy, 0xFF5555);
            yy += this.textRenderer.fontHeight + 2;
        }
    }

    private static Line header(String text) {
        return new Line(text, null, 0xFFFF55);
    }

    private static Line row(String label, String value) {
        return new Line(label, value, 0xAAAAAA);
    }

    private static String fmt(long n) {
        return String.format("%,d", n);
    }

    private static String fmtDate(long epochMs) {
        if (epochMs <= 0) {
            return "—";
        }
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(DATE_FMT);
    }

    private static String tr(String key) {
        return Text.translatable(key).getString();
    }

    private static String tr(String key, Object... args) {
        return Text.translatable(key, args).getString();
    }

    // ── 鼠标交互 ─────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (freeMode) {
            return false;
        }
        // Bug4 踩坑：listY 必须与 renderInGameMode 同源，统一用 LIST_Y 常量，
        // 两处各写一个数，一改就错位
        PlayerQueryManager manager = PlayerQueryManager.get();
        List<String> names = manager.currentInGameNames();
        int listX = 10;
        int listY = LIST_Y;
        int entryH = 20;
        int listW = inGameListWidth();
        if (mouseX >= listX && mouseX < listX + listW) {
            int idx = (int) ((mouseY - listY) / entryH);
            if (idx >= 0 && idx < names.size()) {
                this.selectedIndex = idx;
                this.scrollOffset = 0;
                this.autoTicks = 0;
                String name = names.get(idx);
                if (manager.getError(name) != null) {
                    // 点中带错误的条目 = 重试：onGameStart 会重新发起请求，
                    // 错误不在缓存里，不会被跳过
                    manager.onGameStart(List.of(name));
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }
        this.scrollOffset -= (int) (verticalAmount * 10);
        if (this.scrollOffset < 0) {
            this.scrollOffset = 0;
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (keyCode == net.minecraft.client.util.InputUtil.GLFW_KEY_ENTER
                || keyCode == net.minecraft.client.util.InputUtil.GLFW_KEY_KP_ENTER) {
            if (freeMode) {
                doFreeQuery();
                return true;
            }
        }
        return false;
    }

    /** 语义行：label/value 成对；value 为 null 就是分区标题，只画一行黄字。 */
    private record Line(String label, String value, int color) {
    }

    /**
     * 实际绘制的文本行：{@code right} 非 null 时「左 label + 右对齐值」同行，
     * 为 null 则是独立文本行（分区标题 / 折行值）。{@code indent} 给折行值
     * 缩进，{@code header} 决定行间距——标题留白更大，分区一眼能分开。
     */
    private record DrawRow(String left, String right, int color, boolean indent, boolean header) {
        int gap(int fh) {
            return header ? fh + 5 : fh + 2;
        }
    }
}