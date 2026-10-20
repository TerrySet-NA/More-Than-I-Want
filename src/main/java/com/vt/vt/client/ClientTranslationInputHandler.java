package com.vt.vt.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.datafixers.util.Either;
import com.vt.vt.VTMod;
import com.vt.vt.mixin.AccessorTooltip;
import com.vt.vt.util.TextOverrideManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;

@EventBusSubscriber(modid = VTMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class ClientTranslationInputHandler {

    public static final KeyMapping MODIFY_KEY = new KeyMapping(
            "key.vt.universal_modifier", 
            net.neoforged.neoforge.client.settings.KeyConflictContext.UNIVERSAL,
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_K,
            "key.categories.vt"
    );

    public static final KeyMapping SELECTOR_KEY = new KeyMapping(
            "key.vt.tooltip_selector", 
            net.neoforged.neoforge.client.settings.KeyConflictContext.UNIVERSAL,
            net.neoforged.neoforge.client.settings.KeyModifier.SHIFT,
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_K,
            "key.categories.vt"
    );

    private static ItemStack currentHoveredItemStack = ItemStack.EMPTY;
    private static List<Component> currentHoveredTooltipLines = Collections.emptyList();
    private static long lastTooltipUpdateTime = 0;

    // JEI 支援：透過 ModList 檢查，未安裝時直接短路
    public static class JeiCompat {
        private static final boolean IS_LOADED = ModList.get().isLoaded("jei");
        private static boolean initialized = false;
        private static Method getRuntimeMethod;
        private static Method getIngredientListOverlayMethod;
        private static Method getBookmarkOverlayMethod;
        private static Method getRecipesGuiMethod;
        private static Method getIngredientUnderMouseMethod;
        private static Method hasKeyboardFocusMethod;

        private static void init() {
            if (initialized || !IS_LOADED) return;
            initialized = true;
            try {
                Class<?> internalClass = Class.forName("mezz.jei.common.Internal");
                getRuntimeMethod = internalClass.getMethod("getRuntime");
                
                Class<?> runtimeClass = Class.forName("mezz.jei.api.runtime.IJeiRuntime");
                getIngredientListOverlayMethod = runtimeClass.getMethod("getIngredientListOverlay");
                getBookmarkOverlayMethod = runtimeClass.getMethod("getBookmarkOverlay");
                getRecipesGuiMethod = runtimeClass.getMethod("getRecipesGui");

                Class<?> overlayClass = Class.forName("mezz.jei.api.runtime.IIngredientListOverlay");
                getIngredientUnderMouseMethod = overlayClass.getMethod("getIngredientUnderMouse");
                hasKeyboardFocusMethod = overlayClass.getMethod("hasKeyboardFocus");
            } catch (Throwable ignored) {}
        }

        public static boolean isTyping() {
            if (!IS_LOADED) return false;
            init();
            try {
                if (getRuntimeMethod == null) return false;
                Object runtime = getRuntimeMethod.invoke(null);
                if (runtime != null) {
                    Object overlay = getIngredientListOverlayMethod.invoke(runtime);
                    if (overlay != null) {
                        return (Boolean) hasKeyboardFocusMethod.invoke(overlay);
                    }
                }
            } catch (Throwable ignored) {}
            return false;
        }

        public static ItemStack getHoveredStack() {
            if (!IS_LOADED) return ItemStack.EMPTY;
            init();
            try {
                if (getRuntimeMethod == null) return ItemStack.EMPTY;
                Object runtime = getRuntimeMethod.invoke(null);
                if (runtime == null) return ItemStack.EMPTY;

                ItemStack stack = extractFromOverlay(getIngredientListOverlayMethod.invoke(runtime));
                if (!stack.isEmpty()) return stack;

                stack = extractFromOverlay(getBookmarkOverlayMethod.invoke(runtime));
                if (!stack.isEmpty()) return stack;

                stack = extractFromOverlay(getRecipesGuiMethod.invoke(runtime));
                if (!stack.isEmpty()) return stack;
            } catch (Throwable ignored) {}
            return ItemStack.EMPTY;
        }

        private static ItemStack extractFromOverlay(Object overlay) {
            if (overlay == null) return ItemStack.EMPTY;
            try {
                Object opt = getIngredientUnderMouseMethod.invoke(overlay);
                if (opt instanceof Optional<?> optional && optional.isPresent()) {
                    Object typed = optional.get();
                    if (typed instanceof ItemStack is) return is;
                    
                    try {
                        Method getItemStack = typed.getClass().getMethod("getItemStack");
                        Object res = getItemStack.invoke(typed);
                        if (res instanceof Optional<?> sOpt && sOpt.isPresent() && sOpt.get() instanceof ItemStack is) {
                            return is;
                        }
                    } catch (Throwable ignored) {}

                    try {
                        Method getIngredient = typed.getClass().getMethod("getIngredient");
                        Object res = getIngredient.invoke(typed);
                        if (res instanceof ItemStack is) return is;
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            return ItemStack.EMPTY;
        }

        private static ItemStack getRecipeViewerHovered() {
            // 1. 優先查 JEI
            ItemStack jei = JeiCompat.getHoveredStack();
            if (!jei.isEmpty()) return jei;

            // 2. 次查 EMI (EMI 官方有極度穩定的 EmiApi)
            if (ModList.get().isLoaded("emi")) {
                try {
                    Class<?> api = Class.forName("dev.emi.emi.api.EmiApi");
                    Object stack = api.getMethod("getHoveredStack").invoke(null);
                    if (stack != null) {
                        Object itemStack = stack.getClass().getMethod("getItemStack").invoke(stack);
                        if (itemStack instanceof ItemStack is && !is.isEmpty()) return is;
                    }
                } catch (Throwable ignored) {}
            }
            return ItemStack.EMPTY;
        }
    }

    @EventBusSubscriber(modid = VTMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static class ModBusEvents {
        @SubscribeEvent
        public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(MODIFY_KEY);
            event.register(SELECTOR_KEY);
        }
    }

    @SubscribeEvent
    public static void onRenderTooltipGather(RenderTooltipEvent.GatherComponents event) {
        ItemStack stack = event.getItemStack();
        currentHoveredItemStack = (stack != null && !stack.isEmpty()) ? stack.copy() : ItemStack.EMPTY;

        List<Component> lines = new ArrayList<>();
        for (Either<FormattedText, TooltipComponent> element : event.getTooltipElements()) {
            element.ifLeft(text -> {
                if (text instanceof Component comp) {
                    lines.add(comp);
                } else if (text != null) {
                    lines.add(Component.literal(text.getString()));
                }
            });
        }
        currentHoveredTooltipLines = lines;
        lastTooltipUpdateTime = System.currentTimeMillis();
    }

    private static boolean isTooltipActive() {
        return (System.currentTimeMillis() - lastTooltipUpdateTime) < 200 && !currentHoveredTooltipLines.isEmpty();
    }

    @SubscribeEvent
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        int pressedKeyCode = event.getKeyCode();
        
        int rawModifierKey = MODIFY_KEY.getKey().getValue();
        int rawSelectorKey = SELECTOR_KEY.getKey().getValue();

        if (pressedKeyCode != rawModifierKey && pressedKeyCode != rawSelectorKey) return;
        if (mc.screen instanceof TextEditScreen || mc.screen instanceof TooltipSelectionScreen) return;
        if (isUserTyping(event.getScreen())) return;

        double mouseX = mc.mouseHandler.xpos() / mc.getWindow().getGuiScale();
        double mouseY = mc.mouseHandler.ypos() / mc.getWindow().getGuiScale();

        boolean isSelectorTriggered = (rawSelectorKey == rawModifierKey) ? 
                (pressedKeyCode == rawSelectorKey && Screen.hasShiftDown()) : (pressedKeyCode == rawSelectorKey);

        if (isSelectorTriggered) {
            List<Component> aggregated = new ArrayList<>();
            Set<String> seenTexts = new HashSet<>();

            Consumer<Component> addUnique = comp -> {
                if (comp == null) return;
                String text = comp.getString().trim();
                if (!text.isEmpty() && seenTexts.add(text)) {
                    aggregated.add(comp);
                }
            };

            if (isTooltipActive()) {
                currentHoveredTooltipLines.forEach(addUnique);
            } else if (TextOverrideManager.lastActiveTooltip != null) {
                TextOverrideManager.lastActiveTooltip.forEach(addUnique);
            }

            List<ItemStack> potentialItems = new ArrayList<>();
            if (!currentHoveredItemStack.isEmpty()) potentialItems.add(currentHoveredItemStack);
            if (event.getScreen() instanceof AbstractContainerScreen<?> containerScreen) {
                Slot slot = containerScreen.getSlotUnderMouse();
                if (slot != null && slot.hasItem()) potentialItems.add(slot.getItem());
            }

            ItemStack jeiItem = JeiCompat.getRecipeViewerHovered();
            if (!jeiItem.isEmpty()) potentialItems.add(jeiItem);

            if (TextOverrideManager.lastActiveItemStack != null && !TextOverrideManager.lastActiveItemStack.isEmpty()) {
                potentialItems.add(TextOverrideManager.lastActiveItemStack);
            }

            for (ItemStack item : potentialItems) {
                List<Component> lines = item.getTooltipLines(
                    TooltipContext.of(mc.level), mc.player,
                    mc.options.advancedItemTooltips ? TooltipFlag.Default.ADVANCED : TooltipFlag.Default.NORMAL
                );
                lines.forEach(addUnique);
            }

            List<AbstractWidget> allHoveredWidgets = new ArrayList<>();
            findAllHoveredWidgets(event.getScreen(), mouseX, mouseY, allHoveredWidgets);
            for (AbstractWidget widget : allHoveredWidgets) {
                addUnique.accept(widget.getMessage());
                addUnique.accept(extractWidgetTooltip(widget));
            }

            String rawText = TextOverrideManager.hoveredTextString;
            if (rawText != null && !rawText.trim().isEmpty()) {
                addUnique.accept(Component.literal(TextOverrideManager.getTrulyOriginalText(rawText)));
            }

            if (!aggregated.isEmpty()) {
                mc.setScreen(new TooltipSelectionScreen(aggregated, event.getScreen()));
                event.setCanceled(true);
                return;
            }

        } else {
            AbstractWidget topmostWidget = findTopmostHoveredWidget(event.getScreen(), mouseX, mouseY);
            if (topmostWidget != null) {
                Component widgetMsg = topmostWidget.getMessage();
                if (widgetMsg != null && !widgetMsg.getString().trim().isEmpty()) {
                    openEditScreenForComponent(widgetMsg, event.getScreen());
                    event.setCanceled(true);
                    return;
                }
            }

            if (event.getScreen() instanceof AbstractContainerScreen<?> containerScreen) {
                Slot slot = containerScreen.getSlotUnderMouse();
                if (slot != null && slot.hasItem()) {
                    openEditScreenForItem(slot.getItem(), event.getScreen());
                    event.setCanceled(true);
                    return;
                }
            }

            ItemStack jeiItem = JeiCompat.getRecipeViewerHovered();
            if (!jeiItem.isEmpty()) {
                openEditScreenForItem(jeiItem, event.getScreen());
                event.setCanceled(true);
                return;
            }

            if (topmostWidget != null) {
                Component iconTooltip = extractWidgetTooltip(topmostWidget);
                if (iconTooltip != null && !iconTooltip.getString().trim().isEmpty()) {
                    openEditScreenForComponent(iconTooltip, event.getScreen());
                    event.setCanceled(true);
                    return;
                }
            }

            if (isTooltipActive()) {
                if (!currentHoveredItemStack.isEmpty()) {
                    openEditScreenForItem(currentHoveredItemStack, event.getScreen());
                    event.setCanceled(true);
                    return;
                }
                if (!currentHoveredTooltipLines.isEmpty()) {
                    openEditScreenForComponent(currentHoveredTooltipLines.get(0), event.getScreen());
                    event.setCanceled(true);
                    return;
                }
            }

            if (TextOverrideManager.lastActiveItemStack != null && !TextOverrideManager.lastActiveItemStack.isEmpty()) {
                openEditScreenForItem(TextOverrideManager.lastActiveItemStack, event.getScreen());
                event.setCanceled(true);
                return;
            }

            String rawText = TextOverrideManager.hoveredTextString;
            String ctxKey = TextOverrideManager.hoveredKey;
            if (rawText != null && !rawText.isEmpty()) {
                String pristineText = TextOverrideManager.getTrulyOriginalText(rawText);
                TextEditScreen.open(ctxKey, pristineText, TextOverrideManager.hoveredTextHasNativeKey, event.getScreen());
                event.setCanceled(true);
            }
        }
    }

    private static AbstractWidget findTopmostHoveredWidget(ContainerEventHandler container, double mx, double my) {
        List<? extends GuiEventListener> children = container.children();
        for (int i = children.size() - 1; i >= 0; i--) {
            GuiEventListener child = children.get(i);
            if (child instanceof ContainerEventHandler subContainer) {
                AbstractWidget innerWidget = findTopmostHoveredWidget(subContainer, mx, my);
                if (innerWidget != null) return innerWidget;
            }
            if (child instanceof AbstractWidget widget && widget.visible && widget.isMouseOver(mx, my)) {
                return widget;
            }
        }
        return null;
    }

    private static void findAllHoveredWidgets(ContainerEventHandler container, double mx, double my, List<AbstractWidget> result) {
        List<? extends GuiEventListener> children = container.children();
        for (int i = children.size() - 1; i >= 0; i--) {
            GuiEventListener child = children.get(i);
            if (child instanceof ContainerEventHandler subContainer) {
                findAllHoveredWidgets(subContainer, mx, my, result);
            }
            if (child instanceof AbstractWidget widget && widget.visible && widget.isMouseOver(mx, my)) {
                if (!result.contains(widget)) {
                    result.add(widget);
                }
            }
        }
    }

    // 透過 Accessor 直接以常數時間獲取 Tooltip 內容，完全不產生任何反射開銷
    private static Component extractWidgetTooltip(AbstractWidget widget) {
        Tooltip tooltip = widget.getTooltip();
        if (tooltip instanceof AccessorTooltip accessor) {
            Component comp = accessor.vt$getMessage();
            if (comp != null && !comp.getString().trim().isEmpty()) {
                return comp;
            }
        }
        return null;
    }

    private static boolean isUserTyping(Screen screen) {
        if (screen == null) return false;

        if (screen instanceof ChatScreen
                || screen instanceof BookEditScreen
                || screen instanceof AbstractSignEditScreen) {
            return true;
        }

        if (JeiCompat.isTyping()) {
            return true;
        }

        GuiEventListener focused = screen.getFocused();
        while (focused instanceof ContainerEventHandler container && container.getFocused() != null) {
            focused = container.getFocused();
        }

        if (focused != null && focused.isFocused()) {
            if (focused instanceof EditBox editBox) {
                return editBox.canConsumeInput();
            }
            String clsName = focused.getClass().getName().toLowerCase(Locale.ROOT);
            // 🎯 將 searchbox 改為 search，原生涵蓋 EmiSearchWidget 與 REI SearchFieldWidget
            if (clsName.contains("editbox") || clsName.contains("textfield") 
                    || clsName.contains("textbox") || clsName.contains("search") || clsName.contains("input")) {
                return true;
            }
        }

        return hasActiveTextInput(screen);
    }

    private static boolean hasActiveTextInput(ContainerEventHandler container) {
        for (GuiEventListener child : container.children()) {
            if (child instanceof EditBox editBox) {
                if (editBox.canConsumeInput()) {
                    return true;
                }
            } else if (child != null && child.isFocused()) {
                String name = child.getClass().getName().toLowerCase(Locale.ROOT);
                // 🎯 同步補上 search 與 input
                if (name.contains("editbox") || name.contains("textfield") || name.contains("textbox") || name.contains("search") || name.contains("input")) {
                    return true;
                }
            }
            if (child instanceof ContainerEventHandler subContainer) {
                if (hasActiveTextInput(subContainer)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void openEditScreenForComponent(Component component, Screen previousScreen) {
        String plainText = component.getString();
        TextOverrideManager.DecodedResult result;
        if (component.getContents() instanceof TranslatableContents translatable) {
            String key = translatable.getKey();
            String template = TextOverrideManager.getRawTemplateOfKey(key);
            result = new TextOverrideManager.DecodedResult(key, template.isEmpty() ? plainText : template, true);
        } else {
            result = TextOverrideManager.decodeRenderedText(plainText);
        }
        TextEditScreen.open(result.key, result.template, result.isTranslation, previousScreen);
    }

    private static void openEditScreenForItem(ItemStack stack, Screen previousScreen) {
        String targetKey = stack.getItem().getDescriptionId();
        String pristineItemName = TextOverrideManager.getRawTemplateOfKey(targetKey);
        if (pristineItemName.isEmpty()) {
            pristineItemName = TextOverrideManager.getTrulyOriginalText(stack.getHoverName().getString());
        }
        TextEditScreen.open(targetKey, pristineItemName, true, previousScreen);
    }

    @SubscribeEvent
    public static void onInGameKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        int pressedKey = event.getKey();
        if (event.getAction() == InputConstants.PRESS) {
            boolean isSelectorTriggered = (SELECTOR_KEY.getKey().getValue() == MODIFY_KEY.getKey().getValue()) ? 
                    (pressedKey == SELECTOR_KEY.getKey().getValue() && Screen.hasShiftDown()) : (pressedKey == SELECTOR_KEY.getKey().getValue());

            if (pressedKey == MODIFY_KEY.getKey().getValue() && !isSelectorTriggered) {
                ItemStack mainHandItem = mc.player.getMainHandItem();
                if (!mainHandItem.isEmpty()) {
                    openEditScreenForItem(mainHandItem, null);
                }
            }
        }
    }
}