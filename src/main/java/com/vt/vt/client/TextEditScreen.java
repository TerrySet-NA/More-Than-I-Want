package com.vt.vt.client;

import com.vt.vt.translation.LanguageHotInjector;
import com.vt.vt.translation.TranslationExporter;
import com.vt.vt.util.TextOverrideManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.regex.Pattern;

public class TextEditScreen extends Screen {
    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    private final String targetKey;
    private final String initialText;
    private final boolean isTranslationTarget; 
    private final Screen previousScreen;
    private boolean isItemMode; 

    private EditBox targetField;
    private EditBox replaceField;
    private Button modeToggleButton;
    private boolean isUpdatingField = false;

    public static void open(String targetKey, String initialText, boolean isTranslationTarget, Screen previousScreen) {
        TranslationExporter.ensurePackExistsAndScanned();
        Minecraft.getInstance().setScreen(new TextEditScreen(targetKey, initialText, isTranslationTarget, previousScreen));
    }

    public TextEditScreen(String targetKey, String initialText, boolean isTranslationTarget, Screen previousScreen) {
        super(Component.translatable("vt.screen.title"));
        this.targetKey = targetKey != null ? targetKey : "";
        this.initialText = initialText != null ? initialText : "";
        this.isTranslationTarget = isTranslationTarget;
        this.isItemMode = isTranslationTarget; 
        this.previousScreen = previousScreen;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.targetField = new EditBox(this.font, centerX - 120, centerY - 45, 240, 20, Component.translatable("vt.screen.field.target"));
        this.targetField.setMaxLength(512);
        this.addRenderableWidget(this.targetField);

        this.replaceField = new EditBox(this.font, centerX - 120, centerY - 10, 240, 20, Component.translatable("vt.screen.field.replacement"));
        this.replaceField.setMaxLength(512);
        
        // 預編譯 Pattern 並加入防重入鎖，避免每次擊鍵編譯正則或產生死循環
        this.replaceField.setResponder(text -> {
            if (isUpdatingField || text == null || !text.contains("&")) return;
            String colorized = COLOR_CODE_PATTERN.matcher(text).replaceAll("§$1");
            if (!colorized.equals(text)) {
                isUpdatingField = true;
                int cursor = this.replaceField.getCursorPosition();
                this.replaceField.setValue(colorized);
                this.replaceField.setCursorPosition(Math.min(cursor, colorized.length()));
                isUpdatingField = false;
            }
        });
        this.addRenderableWidget(this.replaceField);

        if (this.isItemMode) {
            String pristineText = TranslationExporter.getOriginalTranslation(this.targetKey);
            this.targetField.setValue(pristineText);
            this.targetField.setEditable(false);

            String customOverride = TranslationExporter.getCustomOverride(this.targetKey);
            this.replaceField.setValue(customOverride.isEmpty() ? pristineText : customOverride);
        } else {
            this.targetField.setValue(this.initialText.isEmpty() ? this.targetKey : this.initialText);
            this.targetField.setEditable(true);
            this.replaceField.setValue(TextOverrideManager.hasTextOverride(this.targetField.getValue()) ? TextOverrideManager.getTextOverride(this.targetField.getValue()) : "");
            this.targetField.setResponder(currentText -> {
                this.replaceField.setValue(TextOverrideManager.hasTextOverride(currentText) ? TextOverrideManager.getTextOverride(currentText) : "");
            });
        }

        this.modeToggleButton = Button.builder(getModeComponent(), btn -> {
            this.isItemMode = !this.isItemMode;
            btn.setMessage(getModeComponent());
            this.rebuildWidgets(); // 使用原生 rebuildWidgets 取代 init 重複初始化
        }).bounds(centerX - 60, centerY + 18, 120, 20).build();
        this.modeToggleButton.active = this.isTranslationTarget;
        this.addRenderableWidget(this.modeToggleButton);

        this.addRenderableWidget(Button.builder(Component.translatable("vt.screen.button.confirm"), btn -> handleConfirm())
                .bounds(centerX - 115, centerY + 45, 70, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("vt.screen.button.restore"), btn -> handleRestore())
                .bounds(centerX - 35, centerY + 45, 70, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("vt.screen.button.cancel"), btn -> onClose())
                .bounds(centerX + 45, centerY + 45, 70, 20).build());
    }

    private void handleConfirm() {
        String val = this.replaceField.getValue();
        if (!this.targetKey.isEmpty() || !this.targetField.getValue().isEmpty()) {
            if (this.isItemMode) {
                LanguageHotInjector.updateKeyInMemory(this.targetKey, val); 
                TranslationExporter.updateSingleKeyAndSave(this.targetKey, val); 
            } else {
                TextOverrideManager.putTextOverride(this.targetField.getValue(), val);
            }
        }
        TextOverrideManager.invalidateCache();
        onClose();
    }

    private void handleRestore() {
        if (this.isItemMode) {
            String rawOrigText = TranslationExporter.getOriginalTranslation(this.targetKey);
            LanguageHotInjector.updateKeyInMemory(this.targetKey, rawOrigText);
            TranslationExporter.removeSingleKey(this.targetKey);
        } else {
            TextOverrideManager.putTextOverride(this.targetField.getValue(), "");
        }
        TextOverrideManager.invalidateCache();
        onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 支援 Enter / 小鍵盤 Enter 快捷儲存提交
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            handleConfirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private Component getModeComponent() {
        return Component.translatable(this.isItemMode ? "vt.screen.mode.translation_key" : "vt.screen.mode.text");
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xD50A0A0A);
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        graphics.drawCenteredString(this.font, Component.translatable("vt.screen.header"), centerX, centerY - 80, 0xFFFFFFFF);
        graphics.drawString(this.font, Component.translatable("vt.screen.label.current"), centerX - 120, centerY - 56, 0xDDDDDD, true);
        graphics.drawString(this.font, Component.translatable("vt.screen.label.replacement"), centerX - 120, centerY - 21, 0xDDDDDD, true);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.previousScreen);
        }
    }
}