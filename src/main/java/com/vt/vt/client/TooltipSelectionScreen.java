package com.vt.vt.client;

import com.vt.vt.util.TextOverrideManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public class TooltipSelectionScreen extends Screen {
    private final List<Component> lines;
    private final Screen previousScreen;
    private TooltipSelectionList list;

    public TooltipSelectionScreen(List<Component> lines, Screen previousScreen) {
        super(Component.translatable("vt.screen.selection.title"));
        this.lines = lines;
        this.previousScreen = previousScreen;
    }

    @Override
    protected void init() {
        int listTop = 32;
        int listBottom = this.height - 36;
        
        this.list = new TooltipSelectionList(this.minecraft, this.width, listBottom - listTop, listTop, 24);
        for (Component line : this.lines) {
            if (!line.getString().trim().isEmpty()) {
                this.list.addLine(line); // 呼叫公開封裝方法
            }
        }
        this.addRenderableWidget(this.list);

        this.addRenderableWidget(Button.builder(Component.translatable("vt.screen.button.cancel"), btn -> onClose())
                .bounds(this.width / 2 - 50, this.height - 28, 100, 20).build());
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xD50A0A0A);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, Component.translatable("vt.screen.selection.header"), this.width / 2, 12, 0x55FF55);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.previousScreen);
        }
    }

    public class TooltipSelectionList extends ObjectSelectionList<TooltipSelectionList.Entry> {
        public TooltipSelectionList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        // 公開方法暴露 protected 的 addEntry
        public void addLine(Component line) {
            this.addEntry(new Entry(line));
        }

        @Override
        public int getRowWidth() {
            return Math.min(360, this.width - 40);
        }

        public class Entry extends ObjectSelectionList.Entry<Entry> {
            private final Component line;

            public Entry(Component line) {
                this.line = line;
            }

            @Override
            public Component getNarration() {
                return this.line;
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean isHovered, float partialTick) {
                int bgColor = isHovered ? 0x80555555 : 0x40000000;
                graphics.fill(left, top, left + width, top + height - 2, bgColor);
                graphics.renderOutline(left, top, width, height - 2, isHovered ? 0xFF55FF55 : 0x60FFFFFF);

                int textY = top + (height - 2 - font.lineHeight) / 2;
                graphics.drawCenteredString(font, this.line, left + width / 2, textY, 0xFFFFFF);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0) {
                    String plainText = this.line.getString();
                    String trulyOriginal = TextOverrideManager.getTrulyOriginalText(plainText);
                    String nativeKey = TextOverrideManager.findNativeKeyForRenderedText(trulyOriginal);
                    boolean isTranslation = nativeKey != null;

                    TextEditScreen.open(
                            isTranslation ? nativeKey : plainText,
                            isTranslation ? TextOverrideManager.getRawTemplateOfKey(nativeKey) : trulyOriginal,
                            isTranslation,
                            previousScreen
                    );
                    return true;
                }
                return false;
            }
        }
    }
}