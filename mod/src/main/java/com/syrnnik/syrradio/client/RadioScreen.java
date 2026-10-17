package com.syrnnik.syrradio.client;

import com.syrnnik.syrradio.net.AddPacket;
import com.syrnnik.syrradio.net.Net;
import com.syrnnik.syrradio.net.RemovePacket;
import com.syrnnik.syrradio.net.SkipPacket;
import com.syrnnik.syrradio.net.TrackInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Экран радио: что играет, очередь, добавить трек по ссылке. */
public final class RadioScreen extends Screen {
    private static final int W = 320;
    private static final int QUEUE_ROWS = 7;
    private static final int ROW_H = 13;

    private EditBox url;
    private String urlDraft = "";
    private int left;
    private int top;

    public RadioScreen() {
        super(Component.translatable("syrradio.screen.title"));
    }

    private ClientRadio radio() {
        return ClientRadio.get();
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = Math.max(8, height / 2 - 112);
        if (!radio().isCompatible()) {
            addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 - 50, top + 90, 100, 20).build());
            return;
        }

        int y = top + 66;
        TrackInfo cur = radio().current();
        Button skip = addRenderableWidget(Button.builder(Component.translatable("syrradio.screen.skip"), b -> {
            TrackInfo c = radio().current();
            if (c != null) {
                Net.toServer(new SkipPacket(c.id()));
            }
        }).bounds(left, y, 96, 20).build());
        skip.active = cur != null;

        addRenderableWidget(Button.builder(toggleLabel(), b -> {
            radio().setEnabled(!radio().isEnabled());
            b.setMessage(toggleLabel());
        }).bounds(left + 100, y, 96, 20).build());

        addRenderableWidget(new VolumeSlider(left + 200, y, W - 200, 20, radio().volume()));

        y += 26;
        url = new EditBox(font, left + 1, y + 1, W - 72, 18, Component.translatable("syrradio.screen.url_hint"));
        url.setMaxLength(AddPacket.MAX_URL);
        url.setHint(Component.translatable("syrradio.screen.url_hint").withStyle(ChatFormatting.DARK_GRAY));
        url.setValue(urlDraft);
        url.setResponder(s -> urlDraft = s);
        addRenderableWidget(url);
        addRenderableWidget(Button.builder(Component.translatable("syrradio.screen.add"), b -> submit())
            .bounds(left + W - 66, y, 66, 20).build());

        // Крестики у треков в очереди.
        List<TrackInfo> q = radio().queue();
        int listTop = queueTop() + 12;
        for (int i = 0; i < Math.min(q.size(), QUEUE_ROWS); i++) {
            int id = q.get(i).id();
            addRenderableWidget(Button.builder(Component.literal("✕"), b -> Net.toServer(new RemovePacket(id)))
                .bounds(left + W - 12, listTop + i * ROW_H - 2, 12, 12).build());
        }
        setInitialFocus(url);
    }

    private int queueTop() {
        return top + 132;
    }

    private Component toggleLabel() {
        return Component.translatable(radio().isEnabled() ? "syrradio.screen.radio_on" : "syrradio.screen.radio_off");
    }

    private void submit() {
        String text = url.getValue().trim();
        if (!text.isEmpty()) {
            Net.toServer(new AddPacket(text));
            url.setValue("");
        }
    }

    /** Сервер прислал новое состояние: перестраиваем кнопки, не теряя набранную ссылку. */
    void onRadioChanged() {
        boolean focused = url != null && url.isFocused();
        rebuildWidgets();
        if (focused && url != null) {
            setFocused(url);
        }
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
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        g.fill(left - 8, top - 8, left + W + 8, queueTop() + 12 + QUEUE_ROWS * ROW_H + 6, 0xC0101010);
        g.drawCenteredString(font, title, width / 2, top, 0xFFFFFF);

        if (!radio().isCompatible()) {
            Component msg = radio().serverHasMod()
                ? Component.translatable("syrradio.screen.version_mismatch", Net.PROTOCOL, radio().serverProtocol())
                : Component.translatable("syrradio.screen.no_server");
            g.drawWordWrap(font, msg, left, top + 30, W, 0xFFAAAA);
            super.render(g, mouseX, mouseY, partial);
            return;
        }

        renderNowPlaying(g);
        renderNotice(g);
        renderQueue(g);
        super.render(g, mouseX, mouseY, partial);
    }

    private void renderNowPlaying(GuiGraphics g) {
        int y = top + 16;
        TrackInfo cur = radio().current();
        if (cur == null) {
            g.drawString(font, Component.translatable("syrradio.screen.nothing"), left, y + 8, 0xAAAAAA);
            return;
        }
        g.drawString(font, font.plainSubstrByWidth(cur.displayName(), W), left, y, 0xFFFFFF);
        Component by = Component.translatable("syrradio.screen.requested_by", cur.requestedBy(), sourceName(cur.source()));
        g.drawString(font, by, left, y + 11, 0x909090);

        int barY = y + 26;
        long pos = Math.max(0, Math.min(cur.durationMs(), radio().positionMs()));
        g.fill(left, barY, left + W, barY + 4, 0xFF333333);
        if (cur.status() == TrackInfo.Status.READY && cur.durationMs() > 0) {
            int filled = (int) (W * pos / cur.durationMs());
            g.fill(left, barY, left + filled, barY + 4, 0xFF55CC77);
        }

        Component status;
        if (!radio().isEnabled()) {
            status = Component.translatable("syrradio.screen.off_hint");
        } else if (cur.status() != TrackInfo.Status.READY) {
            status = Component.translatable("syrradio.screen.preparing");
        } else if (radio().loadingPercent() >= 0) {
            status = Component.translatable("syrradio.screen.loading", radio().loadingPercent());
        } else if (radio().positionMs() < 0) {
            status = Component.translatable("syrradio.screen.starting");
        } else {
            status = Component.literal(time(pos) + " / " + time(cur.durationMs()));
        }
        g.drawString(font, status, left, barY + 8, 0xC0C0C0);
    }

    private void renderNotice(GuiGraphics g) {
        Component n = radio().notice();
        if (n != null) {
            g.drawString(font, net.minecraft.locale.Language.getInstance().getVisualOrder(font.substrByWidth(n, W)), left, top + 116, radio().noticeError() ? 0xFF7777 : 0x88DD88);
        }
    }

    private void renderQueue(GuiGraphics g) {
        List<TrackInfo> q = radio().queue();
        int y = queueTop();
        g.drawString(font, Component.translatable("syrradio.screen.queue", q.size()), left, y, 0xFFFFFF);
        if (q.isEmpty()) {
            g.drawString(font, Component.translatable("syrradio.screen.queue_empty"), left, y + 12, 0x808080);
            return;
        }
        for (int i = 0; i < Math.min(q.size(), QUEUE_ROWS); i++) {
            TrackInfo t = q.get(i);
            String line = (i + 1) + ". " + t.displayName();
            String tail = "  " + t.requestedBy();
            int maxLine = W - 16 - font.width(tail);
            g.drawString(font, font.plainSubstrByWidth(line, maxLine), left, y + 12 + i * ROW_H, 0xDDDDDD);
            g.drawString(font, tail, left + W - 16 - font.width(tail), y + 12 + i * ROW_H, 0x808080);
        }
        if (q.size() > QUEUE_ROWS) {
            g.drawString(font, Component.translatable("syrradio.screen.queue_more", q.size() - QUEUE_ROWS),
                left, y + 12 + QUEUE_ROWS * ROW_H, 0x808080);
        }
    }

    private static Component sourceName(String id) {
        return Component.translatableWithFallback("syrradio.source." + id, id);
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class VolumeSlider extends AbstractSliderButton {
        VolumeSlider(int x, int y, int w, int h, double value) {
            super(x, y, w, h, Component.empty(), value);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("syrradio.screen.volume", (int) Math.round(value * 100)));
        }

        @Override
        protected void applyValue() {
            radio().setVolume(value);
        }
    }
}
