package com.syrnnik.syrradio.client;

import com.syrnnik.syrradio.SyrRadio;
import com.syrnnik.syrradio.net.AddPacket;
import com.syrnnik.syrradio.net.ControlPacket;
import com.syrnnik.syrradio.net.MovePacket;
import com.syrnnik.syrradio.net.Net;
import com.syrnnik.syrradio.net.RemovePacket;
import com.syrnnik.syrradio.net.RepeatMode;
import com.syrnnik.syrradio.net.SeekPacket;
import com.syrnnik.syrradio.net.TrackInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Экран радио: что играет, кнопки плеера, громкость, добавить трек по ссылке
 * и весь список треков — сыгранные серым, порядок меняется перетаскиванием.
 *
 * Панель подстраивается под окно: по ширине не шире 340, по высоте список
 * занимает столько строк, сколько влезает, и прокручивается колесом.
 */
public final class RadioScreen extends Screen {
    static final ResourceLocation ICONS = new ResourceLocation(SyrRadio.MOD_ID, "textures/gui/icons.png");
    static final int ICON_SHUFFLE = 0, ICON_PREV = 1, ICON_PLAY = 2, ICON_PAUSE = 3, ICON_NEXT = 4,
        ICON_REPEAT = 5, ICON_REPEAT_ONE = 6, ICON_REMOVE = 7, ICON_PLAY_SMALL = 8;
    private static final int ICON_COUNT = 9;

    private static final int MAX_W = 340;
    private static final int ROW_H = 12;
    private static final int ROW_ICON = 10;
    // Раскладка по вертикали, от верха панели. Отступ между строкой времени и
    // кнопками плеера такой же, как между кнопками и полем ссылки.
    private static final int NOW_Y = 14;
    private static final int BAR_Y = NOW_Y + 24;
    private static final int STATUS_Y = BAR_Y + 6;
    private static final int CONTROLS_Y = STATUS_Y + 15;
    private static final int URL_Y = CONTROLS_Y + 26;
    private static final int NOTICE_Y = URL_Y + 23;
    private static final int LIST_HEADER_Y = NOTICE_Y + 12;
    /** Сколько от верха панели до первой строки списка. */
    private static final int HEADER_H = LIST_HEADER_Y + 13;

    private static final int COLOR_PLAYED = 0x7A7A7A;
    private static final int COLOR_CURRENT = 0x7CFF9E;
    private static final int COLOR_NEXT = 0xE6E6E6;
    private static final int COLOR_FAILED = 0xFF7777;

    private int panelW;
    private int left;
    private int top;
    private int listTop;
    private int listRows;

    private EditBox url;
    private String urlDraft = "";
    private IconButton prevBtn;
    private IconButton playBtn;
    private IconButton nextBtn;
    private IconButton shuffleBtn;
    private IconButton repeatBtn;
    private boolean builtCompatible;

    private int scroll;
    private int lastCurrentId = -1;

    /** Перетаскивание: id трека, где нажали, и началось ли движение. */
    private int pressId = -1;
    private double pressY;
    private boolean dragging;
    private double mouseYNow;
    private double mouseXNow;

    /** Первый клик по «Очистить» взводит, второй очищает. Сбрасывается только с новым открытием экрана. */
    private boolean clearArmed;

    /** Перемотка: тащим ползунок по полосе времени. */
    private boolean seeking;
    private long seekMs;

    public RadioScreen() {
        super(Component.translatable("syrradio.screen.title"));
    }

    private ClientRadio radio() {
        return ClientRadio.get();
    }

    // ---------------------------------------------------------------- раскладка

    @Override
    protected void init() {
        builtCompatible = radio().isCompatible();
        panelW = Math.min(MAX_W, width - 24);
        left = (width - panelW) / 2;

        if (!builtCompatible) {
            top = Math.max(8, height / 2 - 60);
            addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 - 50, top + 90, 100, 20).build());
            return;
        }

        listRows = wantedRows();
        int panelH = HEADER_H + listRows * ROW_H + 6;
        top = Math.max(8, (height - panelH) / 2);
        listTop = top + HEADER_H;

        // Кнопки плеера слева, громкость справа.
        int y = top + CONTROLS_Y;
        int x = left;
        shuffleBtn = addRenderableWidget(new IconButton(x, y, () -> ICON_SHUFFLE, () -> radio().shuffle(),
            b -> control(ControlPacket.Action.SHUFFLE)));
        x += 24;
        prevBtn = addRenderableWidget(new IconButton(x, y, () -> ICON_PREV, () -> false,
            b -> control(ControlPacket.Action.PREV)));
        x += 24;
        playBtn = addRenderableWidget(new IconButton(x, y, () -> showsPause() ? ICON_PAUSE : ICON_PLAY, () -> false,
            b -> control(ControlPacket.Action.PAUSE)));
        x += 24;
        nextBtn = addRenderableWidget(new IconButton(x, y, () -> ICON_NEXT, () -> false,
            b -> control(ControlPacket.Action.NEXT)));
        x += 24;
        repeatBtn = addRenderableWidget(new IconButton(x, y,
            () -> radio().repeat() == RepeatMode.ONE ? ICON_REPEAT_ONE : ICON_REPEAT,
            () -> radio().repeat() != RepeatMode.OFF,
            b -> control(ControlPacket.Action.REPEAT)));
        x += 28;
        addRenderableWidget(new VolumeSlider(x, y, left + panelW - x, 20, radio().volume()));

        y = top + URL_Y;
        url = new EditBox(font, left + 1, y + 1, panelW - 72, 18, Component.translatable("syrradio.screen.url_hint"));
        url.setMaxLength(AddPacket.MAX_URL);
        url.setHint(Component.translatable("syrradio.screen.url_hint").withStyle(ChatFormatting.DARK_GRAY));
        url.setValue(urlDraft);
        url.setResponder(s -> urlDraft = s);
        addRenderableWidget(url);
        addRenderableWidget(Button.builder(Component.translatable("syrradio.screen.add"), b -> submit())
            .bounds(left + panelW - 66, y, 66, 20).build());

        scrollToCurrent();
        updateButtons();
        setInitialFocus(url);
    }

    /** Сколько строк списка показать: по числу треков (не меньше 8), но не больше, чем влезает в окно. */
    private int wantedRows() {
        int maxRows = Math.max(2, (height - 16 - HEADER_H - 6) / ROW_H);
        return Math.min(maxRows, Math.max(8, radio().tracks().size()));
    }

    private boolean showsPause() {
        return radio().current() != null && !radio().isPaused();
    }

    private void control(ControlPacket.Action action) {
        TrackInfo cur = radio().current();
        Net.toServer(new ControlPacket(action, cur == null ? -1 : cur.id()));
    }

    private void submit() {
        String text = url.getValue().trim();
        if (!text.isEmpty()) {
            Net.toServer(new AddPacket(text));
            url.setValue("");
        }
    }

    /** Сервер прислал новое состояние. Кнопки рисуют себя сами, перестраиваем только при смене совместимости. */
    void onRadioChanged() {
        // Список вырос или сжался так, что панель меняет высоту, — раскладываем заново.
        if (radio().isCompatible() != builtCompatible || (builtCompatible && !dragging && !seeking && wantedRows() != listRows)) {
            boolean focused = url != null && url.isFocused();
            rebuildWidgets();
            if (focused && url != null) {
                setFocused(url);
            }
            return;
        }
        TrackInfo cur = radio().current();
        int curId = cur == null ? -1 : cur.id();
        if (curId != lastCurrentId && !dragging) {
            scrollToCurrent();
        }
        clampScroll();
        updateButtons();
    }

    private void scrollToCurrent() {
        TrackInfo cur = radio().current();
        lastCurrentId = cur == null ? -1 : cur.id();
        int c = radio().cursor();
        if (c < scroll + 1 || c > scroll + listRows - 2) {
            scroll = c - 1;
        }
        clampScroll();
    }

    private void clampScroll() {
        int max = Math.max(0, radio().tracks().size() - listRows);
        scroll = Math.max(0, Math.min(scroll, max));
    }

    @Override
    public void tick() {
        super.tick();
        if (url != null) {
            url.tick();
        }
        updateButtons();
    }

    private void updateButtons() {
        if (playBtn == null) {
            return;
        }
        boolean any = !radio().tracks().isEmpty();
        TrackInfo cur = radio().current();
        prevBtn.active = any;
        nextBtn.active = cur != null;
        playBtn.active = cur != null;
        shuffleBtn.setTooltip(Tooltip.create(Component.translatable(
            radio().shuffle() ? "syrradio.control.shuffle_on" : "syrradio.control.shuffle_off")));
        prevBtn.setTooltip(Tooltip.create(Component.translatable("syrradio.control.prev")));
        nextBtn.setTooltip(Tooltip.create(Component.translatable("syrradio.control.next")));
        playBtn.setTooltip(Tooltip.create(Component.translatable(
            showsPause() ? "syrradio.control.pause" : "syrradio.control.play")));
        repeatBtn.setTooltip(Tooltip.create(Component.translatable(switch (radio().repeat()) {
            case OFF -> "syrradio.control.repeat_off";
            case ALL -> "syrradio.control.repeat_all";
            case ONE -> "syrradio.control.repeat_one";
        })));
    }

    // ---------------------------------------------------------------- рисование

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        mouseYNow = mouseY;
        mouseXNow = mouseX;
        renderBackground(g);
        int bottom = builtCompatible ? listTop + listRows * ROW_H + 6 : top + 120;
        g.fill(left - 8, top - 8, left + panelW + 8, bottom, 0xC0101010);
        g.drawCenteredString(font, title, width / 2, top, 0xFFFFFF);

        if (!builtCompatible) {
            Component msg = radio().serverHasMod()
                ? Component.translatable("syrradio.screen.version_mismatch", Net.PROTOCOL, radio().serverProtocol())
                : Component.translatable("syrradio.screen.no_server");
            g.drawWordWrap(font, msg, left, top + 30, panelW, 0xFFAAAA);
            super.render(g, mouseX, mouseY, partial);
            return;
        }

        renderNowPlaying(g);
        renderNotice(g);
        renderList(g, mouseX, mouseY);
        super.render(g, mouseX, mouseY, partial);
        renderRowTooltip(g, mouseX, mouseY);
    }

    /** Подсказки у ▶ и ✕ в строках списка — как у кнопок плеера. */
    private void renderRowTooltip(GuiGraphics g, int mx, int my) {
        if (dragging || seeking || !inside(mx, my, left, listTop, panelW, listRows * ROW_H)) {
            return;
        }
        int i = scroll + (my - listTop) / ROW_H;
        if (i < 0 || i >= radio().tracks().size()) {
            return;
        }
        int y = listTop + (i - scroll) * ROW_H;
        int px = left + panelW - 2 * (ROW_ICON + 3) + 1;
        int rx = px + ROW_ICON + 3;
        if (inside(mx, my, px, y + 1, ROW_ICON, ROW_ICON)) {
            g.renderTooltip(font, Component.translatable("syrradio.screen.row_play"), mx, my);
        } else if (inside(mx, my, rx, y + 1, ROW_ICON, ROW_ICON)) {
            g.renderTooltip(font, Component.translatable("syrradio.screen.row_remove"), mx, my);
        }
    }

    private void renderNowPlaying(GuiGraphics g) {
        int y = top + NOW_Y;
        TrackInfo cur = radio().current();
        if (cur == null) {
            String key = radio().tracks().isEmpty() ? "syrradio.screen.nothing" : "syrradio.screen.finished";
            g.drawString(font, Component.translatable(key), left, y + 8, 0xAAAAAA);
            return;
        }
        g.drawString(font, font.plainSubstrByWidth(cur.displayName(), panelW), left, y, 0xFFFFFF);
        Component by = Component.translatable("syrradio.screen.requested_by", cur.requestedBy(), sourceName(cur.source()));
        g.drawString(font, by, left, y + 11, 0x909090);

        int barY = top + BAR_Y;
        long pos = seeking ? seekMs : Math.max(0, Math.min(cur.durationMs(), radio().positionMs()));
        g.fill(left, barY, left + panelW, barY + 3, 0xFF333333);
        if (radio().isStarted() && cur.durationMs() > 0) {
            int knobX = left + (int) (panelW * pos / cur.durationMs());
            g.fill(left, barY, knobX, barY + 3, 0xFF55CC77);
            if (seeking || overBar(mouseXNow, mouseYNow)) {
                drawKnob(g, knobX, barY + 1);
            }
        }

        Component status;
        if (!radio().isListening()) {
            status = Component.translatable("syrradio.screen.muted_hint");
        } else if (seeking) {
            status = Component.literal(time(pos) + " / " + time(cur.durationMs()));
        } else if (radio().isPaused()) {
            status = Component.translatable("syrradio.screen.paused", time(pos), time(cur.durationMs()));
        } else if (cur.status() == TrackInfo.Status.FAILED) {
            status = Component.translatable("syrradio.screen.failed");
        } else if (!radio().isStarted()) {
            status = Component.translatable("syrradio.screen.preparing");
        } else if (radio().loadingPercent() >= 0) {
            status = Component.translatable("syrradio.screen.loading", radio().loadingPercent());
        } else if (radio().positionMs() < 0) {
            status = Component.translatable("syrradio.screen.starting");
        } else {
            status = Component.literal(time(pos) + " / " + time(cur.durationMs()));
        }
        g.drawString(font, status, left, top + STATUS_Y, 0xC0C0C0);
    }

    /** Кружок ползунка перемотки, 7×7. */
    private static void drawKnob(GuiGraphics g, int cx, int cy) {
        int[] half = {1, 2, 3, 3, 3, 2, 1};
        for (int i = 0; i < half.length; i++) {
            int y = cy - 3 + i;
            g.fill(cx - half[i], y, cx + half[i] + 1, y + 1, 0xFFFFFFFF);
        }
    }

    private boolean canSeek() {
        TrackInfo cur = radio().current();
        return cur != null && radio().isStarted() && cur.durationMs() > 0;
    }

    private boolean overBar(double mx, double my) {
        int barY = top + BAR_Y;
        return builtCompatible && canSeek() && inside(mx, my, left - 3, barY - 4, panelW + 6, 11);
    }

    private long seekAt(double mx) {
        TrackInfo cur = radio().current();
        double f = Math.max(0, Math.min(1, (mx - left) / panelW));
        return (long) (f * cur.durationMs());
    }

    private void renderNotice(GuiGraphics g) {
        Component n = radio().notice();
        if (n != null) {
            g.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(n, panelW)),
                left, top + NOTICE_Y, radio().noticeError() ? 0xFF7777 : 0x88DD88);
        }
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        List<TrackInfo> tracks = radio().tracks();
        int headerY = top + LIST_HEADER_Y;
        g.drawString(font, Component.translatable("syrradio.screen.tracks", tracks.size()), left, headerY, 0xFFFFFF);

        // «Очистить» справа в заголовке — с подтверждением вторым кликом.
        if (!tracks.isEmpty()) {
            Component label = clearLabel();
            int w = font.width(label);
            int cx = left + panelW - w;
            boolean hover = inside(mouseX, mouseY, cx - 2, headerY - 2, w + 4, 12);
            g.drawString(font, label, cx, headerY, clearArmed ? (hover ? 0xFF8888 : 0xFF5555) : hover ? 0xFFFFFF : 0x909090);
        }

        if (tracks.isEmpty()) {
            g.drawString(font, Component.translatable("syrradio.screen.list_empty"), left, listTop + 2, 0x808080);
            return;
        }

        if (dragging) {
            autoScroll();
        }

        int cursor = radio().cursor();
        int dragFrom = dragging ? indexOf(pressId) : -1;
        g.enableScissor(left - 2, listTop, left + panelW + 2, listTop + listRows * ROW_H);
        for (int row = 0; row < listRows; row++) {
            int i = scroll + row;
            if (i >= tracks.size()) {
                break;
            }
            TrackInfo t = tracks.get(i);
            int y = listTop + row * ROW_H;
            boolean hover = !dragging && inside(mouseX, mouseY, left, y, panelW, ROW_H);
            if (i == cursor) {
                g.fill(left - 2, y, left + panelW + 2, y + ROW_H, 0x3055CC77);
            } else if (hover) {
                g.fill(left - 2, y, left + panelW + 2, y + ROW_H, 0x20FFFFFF);
            }
            int color = t.status() == TrackInfo.Status.FAILED ? COLOR_FAILED
                : i < cursor ? COLOR_PLAYED : i == cursor ? COLOR_CURRENT : COLOR_NEXT;
            if (i == dragFrom) {
                color = 0x505050;
            }
            renderRow(g, t, i, y, color, mouseX, mouseY, !dragging);
        }
        g.disableScissor();

        if (dragging && dragFrom >= 0) {
            int slot = dropSlot();
            int lineY = listTop + (slot - scroll) * ROW_H - 1;
            g.fill(left - 2, lineY, left + panelW + 2, lineY + 2, 0xFF55CC77);
            // «Призрак» строки идёт за мышью, но не вылезает за рамки списка.
            int y = Math.max(listTop, Math.min((int) mouseYNow - ROW_H / 2, listTop + (listRows - 1) * ROW_H));
            g.fill(left - 2, y, left + panelW + 2, y + ROW_H, 0xFF303030);
            renderRow(g, tracks.get(dragFrom), dragFrom, y, 0xFFFFFF, -1, -1, false);
        }

        // Полоса прокрутки, если строк больше, чем видно.
        if (tracks.size() > listRows) {
            int trackH = listRows * ROW_H;
            int thumbH = Math.max(8, trackH * listRows / tracks.size());
            int thumbY = listTop + (trackH - thumbH) * scroll / Math.max(1, tracks.size() - listRows);
            g.fill(left + panelW + 4, listTop, left + panelW + 6, listTop + trackH, 0x40FFFFFF);
            g.fill(left + panelW + 4, thumbY, left + panelW + 6, thumbY + thumbH, 0xC0FFFFFF);
        }
    }

    private void renderRow(GuiGraphics g, TrackInfo t, int i, int y, int color, int mouseX, int mouseY, boolean icons) {
        String who = t.requestedBy();
        int iconsW = 2 * (ROW_ICON + 3);
        int whoX = left + panelW - iconsW - font.width(who) - 4;
        String line = (i + 1) + ". " + t.displayName();
        g.drawString(font, font.plainSubstrByWidth(line, whoX - left - 6), left, y + 2, color);
        g.drawString(font, who, whoX, y + 2, 0x707070);
        int px = left + panelW - iconsW + 1;
        rowIcon(g, ICON_PLAY_SMALL, px, y + 1, icons && inside(mouseX, mouseY, px, y + 1, ROW_ICON, ROW_ICON));
        int rx = px + ROW_ICON + 3;
        rowIcon(g, ICON_REMOVE, rx, y + 1, icons && inside(mouseX, mouseY, rx, y + 1, ROW_ICON, ROW_ICON));
    }

    private void rowIcon(GuiGraphics g, int icon, int x, int y, boolean hover) {
        float c = hover ? 1f : 0.55f;
        g.setColor(c, c, c, 1f);
        g.blit(ICONS, x, y, ROW_ICON, ROW_ICON, icon * 16, 0, 16, 16, ICON_COUNT * 16, 16);
        g.setColor(1f, 1f, 1f, 1f);
    }

    private static Component sourceName(String id) {
        return Component.translatableWithFallback("syrradio.source." + id, id);
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ---------------------------------------------------------------- мышь и клавиатура

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (builtCompatible && button == 0) {
            if (overBar(mx, my)) {
                seeking = true;
                seekMs = seekAt(mx);
                return true;
            }
            if (clickClear(mx, my) || clickList(mx, my)) {
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private Component clearLabel() {
        return Component.translatable(clearArmed ? "syrradio.screen.clear_confirm" : "syrradio.screen.clear");
    }

    private boolean clickClear(double mx, double my) {
        if (radio().tracks().isEmpty()) {
            return false;
        }
        int headerY = top + LIST_HEADER_Y;
        int w = font.width(clearLabel());
        if (!inside(mx, my, left + panelW - w - 2, headerY - 2, w + 4, 12)) {
            return false;
        }
        if (clearArmed) {
            clearArmed = false;
            Net.toServer(new ControlPacket(ControlPacket.Action.CLEAR, -1));
        } else {
            clearArmed = true;
        }
        return true;
    }

    private boolean clickList(double mx, double my) {
        if (!inside(mx, my, left, listTop, panelW, listRows * ROW_H)) {
            return false;
        }
        int i = scroll + (int) ((my - listTop) / ROW_H);
        List<TrackInfo> tracks = radio().tracks();
        if (i < 0 || i >= tracks.size()) {
            return true;
        }
        TrackInfo t = tracks.get(i);
        int y = listTop + (i - scroll) * ROW_H;
        int iconsW = 2 * (ROW_ICON + 3);
        int px = left + panelW - iconsW + 1;
        int rx = px + ROW_ICON + 3;
        if (inside(mx, my, px, y + 1, ROW_ICON, ROW_ICON)) {
            Net.toServer(new ControlPacket(ControlPacket.Action.PLAY, t.id()));
        } else if (inside(mx, my, rx, y + 1, ROW_ICON, ROW_ICON)) {
            Net.toServer(new RemovePacket(t.id()));
        } else {
            pressId = t.id();
            pressY = my;
            dragging = false;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (seeking && button == 0) {
            if (canSeek()) {
                seekMs = seekAt(mx);
            } else {
                seeking = false; // трек сменился, пока тащили
            }
            return true;
        }
        if (pressId >= 0 && button == 0) {
            mouseYNow = my;
            if (!dragging && Math.abs(my - pressY) > 3) {
                dragging = true;
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (seeking && button == 0) {
            seeking = false;
            TrackInfo cur = radio().current();
            if (cur != null && canSeek()) {
                Net.toServer(new SeekPacket(cur.id(), seekAt(mx)));
            }
            return true;
        }
        if (pressId >= 0 && button == 0) {
            if (dragging) {
                mouseYNow = my;
                int from = indexOf(pressId);
                if (from >= 0) {
                    int slot = dropSlot();
                    int to = slot > from ? slot - 1 : slot;
                    if (to != from) {
                        Net.toServer(new MovePacket(pressId, to));
                    }
                }
            }
            pressId = -1;
            dragging = false;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (builtCompatible && inside(mx, my, left, listTop, panelW + 8, listRows * ROW_H)) {
            scroll -= (int) Math.signum(delta);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    /** Между какими строками окажется трек, если отпустить сейчас: 0 — перед первой. */
    private int dropSlot() {
        int slot = scroll + (int) Math.round((mouseYNow - listTop) / ROW_H);
        return Math.max(0, Math.min(slot, radio().tracks().size()));
    }

    private int autoScrollTicker;

    /** Тащим к краю списка — он сам прокручивается. */
    private void autoScroll() {
        if (++autoScrollTicker % 6 != 0) {
            return;
        }
        if (mouseYNow < listTop + 4) {
            scroll--;
        } else if (mouseYNow > listTop + listRows * ROW_H - 4) {
            scroll++;
        }
        clampScroll();
    }

    private int indexOf(int id) {
        List<TrackInfo> tracks = radio().tracks();
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).id() == id) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (url != null && url.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            submit();
            return true;
        }
        if (ClientEvents.OPEN.matches(key, scan) && (url == null || !url.isFocused())) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------------------------------------------------------- виджеты

    /** Квадратная кнопка с иконкой; подсвеченная (включённый режим) — зелёная. */
    private static final class IconButton extends Button {
        private final IntSupplier icon;
        private final BooleanSupplier highlighted;

        IconButton(int x, int y, IntSupplier icon, BooleanSupplier highlighted, OnPress onPress) {
            super(x, y, 20, 20, Component.empty(), onPress, DEFAULT_NARRATION);
            this.icon = icon;
            this.highlighted = highlighted;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
            super.renderWidget(g, mouseX, mouseY, partial);
            if (!active) {
                g.setColor(0.45f, 0.45f, 0.45f, 1f);
            } else if (highlighted.getAsBoolean()) {
                g.setColor(0.45f, 1f, 0.6f, 1f);
            }
            g.blit(ICONS, getX() + 2, getY() + 2, 16, 16, icon.getAsInt() * 16, 0, 16, 16, ICON_COUNT * 16, 16);
            g.setColor(1f, 1f, 1f, 1f);
        }
    }

    private final class VolumeSlider extends AbstractSliderButton {
        VolumeSlider(int x, int y, int w, int h, double value) {
            super(x, y, w, h, Component.empty(), value);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int pct = (int) Math.round(value * 100);
            setMessage(pct == 0
                ? Component.translatable("syrradio.screen.volume_off")
                : Component.translatable("syrradio.screen.volume", pct));
        }

        @Override
        protected void applyValue() {
            radio().setVolume(value);
        }
    }
}
