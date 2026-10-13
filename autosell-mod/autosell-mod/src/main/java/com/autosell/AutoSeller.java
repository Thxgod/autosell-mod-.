package com.autosell;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

/** The sell loop and the global key handling. */
public final class AutoSeller {
    private AutoSeller() {}

    private static int timer = 0;
    private static boolean confirmed = false;
    private static boolean cycleActive = false;     // a sale is in progress (command sent / menu open)
    private static boolean waitingForMenu = false;  // /sell was sent, waiting for the menu to open
    private static boolean menuSeenThisCycle = false;
    private static int failedOpens = 0;

    // "Nothing is actually selling" protection
    private static int movedThisCycle = 0;
    private static int lastMoved = 0;
    private static int lastAfterMove = 0;
    private static int stagnantCycles = 0;

    // ------------------------------------------------------------------ control

    public static boolean isCycleActive() {
        return cycleActive;
    }

    public static void setEnabled(boolean on) {
        setEnabled(on, on ? "ON" : "OFF");
    }

    public static void setEnabled(boolean on, String message) {
        AutoSellConfig.get().enabled = on;
        timer = 0;
        confirmed = false;
        cycleActive = false;
        waitingForMenu = false;
        menuSeenThisCycle = false;
        failedOpens = 0;
        movedThisCycle = 0;
        lastMoved = 0;
        lastAfterMove = 0;
        stagnantCycles = 0;
        if (message != null) message(message);
    }

    private static void message(String text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        mc.player.sendMessage(
            Text.literal("[AutoSell] ").formatted(Formatting.GOLD).append(Text.literal(text).formatted(Formatting.WHITE)),
            true);
    }

    // ------------------------------------------------------------------ keys

    /**
     * Called for every key event, before the game sees it.
     *
     * @return true if the key press was used here and must not reach the game
     */
    public static boolean onKey(MinecraftClient mc, int action, KeyInput input) {
        if (action != GLFW.GLFW_PRESS || mc.player == null) return false;

        AutoSellConfig cfg = AutoSellConfig.get();
        Screen screen = mc.currentScreen;
        int key = input.key();

        // The settings UI handles its own keys.
        if (screen instanceof AutoSellScreen) return false;

        // Stop keys: work with no screen or any container/inventory screen (including the sell menu).
        // They are checked first and do not wait for any delay, so they bypass everything else.
        if (cfg.enabled && (!cfg.onlyWhileSelling || cycleActive) && (screen == null || screen instanceof HandledScreen)) {
            boolean stop = (cfg.stopOnEsc && key == GLFW.GLFW_KEY_ESCAPE)
                || (cfg.stopOnInventoryKey && mc.options.inventoryKey.matchesKey(input))
                || (cfg.stopKey != GLFW.GLFW_KEY_UNKNOWN && key == cfg.stopKey);
            if (stop) {
                setEnabled(false, "Stopped");
                return false; // not cancelled: ESC / E still close the screen like normal
            }
        }

        if (screen == null) {
            if (cfg.guiKey != GLFW.GLFW_KEY_UNKNOWN && key == cfg.guiKey) {
                mc.setScreen(new AutoSellScreen());
                return true;
            }
            if (cfg.toggleKey != GLFW.GLFW_KEY_UNKNOWN && key == cfg.toggleKey) {
                setEnabled(!cfg.enabled);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ sell loop

    public static void tick(MinecraftClient mc) {
        AutoSellConfig cfg = AutoSellConfig.get();
        if (!cfg.enabled) return;
        if (mc.player == null || mc.interactionManager == null || mc.getNetworkHandler() == null) return;

        if (timer > 0) {
            timer--;
            return;
        }

        Screen screen = mc.currentScreen;

        // ---- not in the sell menu: optionally open it
        if (!(screen instanceof GenericContainerScreen container) || !isSellScreen(container)) {
            confirmed = false;
            menuSeenThisCycle = false;

            if (waitingForMenu) {
                // /sell was sent but no menu appeared
                waitingForMenu = false;
                cycleActive = false;
                if (++failedOpens >= 3) {
                    setEnabled(false, "Sell menu did not open, stopped");
                    return;
                }
                timer = cfg.openCooldown;
                return;
            }

            if (cfg.autoOpen && screen == null && countSellable(mc) > 0) {
                cycleActive = true;
                waitingForMenu = true;
                mc.getNetworkHandler().sendChatCommand("sell");
                timer = 20; // give the menu time to open
            } else {
                cycleActive = false;
            }
            return;
        }

        // ---- inside the sell menu
        waitingForMenu = false;
        failedOpens = 0;
        cycleActive = true;

        ScreenHandler handler = container.getScreenHandler();
        int total = handler.slots.size();
        int topSize = total - 36;        // menu slots (54 for a 6-row chest)
        int confirmSlot = topSize - 1;   // bottom-right green pane

        if (!menuSeenThisCycle) {
            menuSeenThisCycle = true;
            // If the items we sent last cycle are back in the inventory, the server is not buying them.
            int count = countSellable(handler, topSize, total);
            if (lastMoved > 0 && count - lastAfterMove >= lastMoved) {
                if (++stagnantCycles >= 2) {
                    setEnabled(false, "Nothing is being sold, stopped");
                    mc.player.closeHandledScreen();
                    return;
                }
            } else {
                stagnantCycles = 0;
            }
            movedThisCycle = 0;
        }

        // Already confirmed: close and cool down.
        if (confirmed) {
            confirmed = false;
            cycleActive = false;
            menuSeenThisCycle = false;
            if (cfg.closeAfter) mc.player.closeHandledScreen();
            timer = cfg.openCooldown;
            return;
        }

        // Is there room left in the sell menu?
        boolean hasRoom = false;
        for (int i = 0; i < confirmSlot; i++) {
            if (handler.getSlot(i).getStack().isEmpty()) {
                hasRoom = true;
                break;
            }
        }

        // Shift-click the next item that matches the list.
        if (hasRoom) {
            int end = cfg.skipHotbar ? total - 9 : total;
            for (int i = topSize; i < end; i++) {
                ItemStack stack = handler.getSlot(i).getStack();
                if (stack.isEmpty() || !cfg.canSell(stack.getItem())) continue;

                movedThisCycle += stack.getCount();
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                timer = cfg.clickDelay;
                return;
            }
        }

        // Nothing left to move (or the menu is full): confirm if anything is inside.
        boolean menuHasItems = false;
        for (int i = 0; i < confirmSlot; i++) {
            if (!handler.getSlot(i).getStack().isEmpty()) {
                menuHasItems = true;
                break;
            }
        }

        if (menuHasItems) {
            lastMoved = movedThisCycle;
            lastAfterMove = countSellable(handler, topSize, total);
            mc.interactionManager.clickSlot(handler.syncId, confirmSlot, 0, SlotActionType.PICKUP, mc.player);
            confirmed = true;
            timer = cfg.confirmDelay;
        } else {
            // Nothing to sell right now.
            cycleActive = false;
            menuSeenThisCycle = false;
            lastMoved = 0;
            if (cfg.closeAfter) mc.player.closeHandledScreen();
            timer = cfg.openCooldown;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isSellScreen(GenericContainerScreen screen) {
        return screen.getTitle().getString().toLowerCase().contains("sell")
            && screen.getScreenHandler().slots.size() == 90; // 6 rows + player inventory
    }

    /** Sellable item count in the player's inventory (no screen open). */
    private static int countSellable(MinecraftClient mc) {
        AutoSellConfig cfg = AutoSellConfig.get();
        int start = cfg.skipHotbar ? 9 : 0;
        int count = 0;
        for (int i = start; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && cfg.canSell(stack.getItem())) count += stack.getCount();
        }
        return count;
    }

    /** Sellable item count in the player part of an open sell menu. */
    private static int countSellable(ScreenHandler handler, int topSize, int total) {
        AutoSellConfig cfg = AutoSellConfig.get();
        int end = cfg.skipHotbar ? total - 9 : total;
        int count = 0;
        for (int i = topSize; i < end; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && cfg.canSell(stack.getItem())) count += stack.getCount();
        }
        return count;
    }
}
