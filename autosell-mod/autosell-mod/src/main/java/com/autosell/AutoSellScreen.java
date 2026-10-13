package com.autosell;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The AutoSell settings UI. Opened/closed with the GUI key (Right Shift by default).
 * Click an item in the grid to add/remove it from the sell list.
 */
public class AutoSellScreen extends Screen {
    private enum Listening { NONE, GUI, TOGGLE, STOP }

    private static final int[] CLICK_DELAYS = {0, 1, 2, 3, 5, 8, 10};
    private static final int[] CONFIRM_DELAYS = {2, 4, 6, 10, 20};
    private static final int[] COOLDOWNS = {20, 40, 60, 100, 200};
    private static final int CELL = 18;
    private static final int PAD = 10;

    private static List<Item> allItems;

    private final ButtonWidget[] btn = new ButtonWidget[15];
    private ButtonWidget bOnlySelected;
    private TextFieldWidget search;
    private String lastSearch = "";
    private boolean onlySelected = false;
    private Listening listening = Listening.NONE;

    private final List<Item> filtered = new ArrayList<>();
    private final List<ItemStack> filteredStacks = new ArrayList<>();

    private int px, py, pw, ph;       // panel
    private int gx, gy, cols, rows;   // item grid
    private int scrollRow = 0;

    public AutoSellScreen() {
        super(Text.literal("AutoSell"));
    }

    private static List<Item> allItems() {
        if (allItems == null) {
            allItems = new ArrayList<>();
            for (Item item : Registries.ITEM) {
                if (item != Items.AIR) allItems.add(item);
            }
        }
        return allItems;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        final AutoSellConfig cfg = AutoSellConfig.get();

        pw = Math.min(width - 16, 440);
        ph = Math.min(height - 16, 320);
        px = (width - pw) / 2;
        py = (height - ph) / 2;

        // ---- settings buttons: 3 columns x 5 rows
        int colW = (pw - PAD * 2 - 8) / 3;
        int sx = px + PAD;
        int sy = py + 22;

        btn[0] = add(0, colW, sx, sy, () -> AutoSeller.setEnabled(!cfg.enabled),
            "Turn AutoSell on or off.");
        btn[1] = add(1, colW, sx, sy, () -> cfg.whitelist = !cfg.whitelist,
            "Whitelist: sell ONLY the selected items. Blacklist: sell everything EXCEPT the selected items.");
        btn[2] = add(2, colW, sx, sy, () -> cfg.autoOpen = !cfg.autoOpen,
            "Run /sell automatically. Off = you open /sell yourself and AutoSell fills it.");
        btn[3] = add(3, colW, sx, sy, () -> cfg.skipHotbar = !cfg.skipHotbar,
            "Never sell items in the hotbar.");
        btn[4] = add(4, colW, sx, sy, () -> cfg.closeAfter = !cfg.closeAfter,
            "Close the sell menu after confirming.");
        btn[5] = add(5, colW, sx, sy, () -> cfg.clickDelay = next(CLICK_DELAYS, cfg.clickDelay),
            "Ticks between each shift-click (20 ticks = 1 second).");
        btn[6] = add(6, colW, sx, sy, () -> cfg.confirmDelay = next(CONFIRM_DELAYS, cfg.confirmDelay),
            "Ticks to wait before clicking the confirm button.");
        btn[7] = add(7, colW, sx, sy, () -> cfg.openCooldown = next(COOLDOWNS, cfg.openCooldown),
            "Ticks to wait between sell cycles.");
        btn[8] = add(8, colW, sx, sy, () -> cfg.stopOnEsc = !cfg.stopOnEsc,
            "Pressing ESC stops AutoSell.");
        btn[9] = add(9, colW, sx, sy, () -> cfg.stopOnInventoryKey = !cfg.stopOnInventoryKey,
            "Pressing your inventory key stops AutoSell.");
        btn[10] = add(10, colW, sx, sy, () -> cfg.onlyWhileSelling = !cfg.onlyWhileSelling,
            "Stop keys only work while a sale is running, so opening your inventory normally will not turn AutoSell off.");
        btn[11] = add(11, colW, sx, sy, () -> listening = Listening.GUI,
            "Key that opens/closes this window. Click, then press a key (ESC cancels).");
        btn[12] = add(12, colW, sx, sy, () -> listening = Listening.TOGGLE,
            "Optional quick on/off key. Click, then press a key (ESC clears).");
        btn[13] = add(13, colW, sx, sy, () -> listening = Listening.STOP,
            "Optional extra stop key. Click, then press a key (ESC clears).");
        btn[14] = add(14, colW, sx, sy, this::close, null);

        // ---- search row
        int searchY = sy + 5 * 20 + 4;
        int selW = 100;
        int clearW = 46;
        int searchW = pw - PAD * 2 - selW - clearW - 8;

        search = new TextFieldWidget(textRenderer, px + PAD, searchY, searchW, 16, Text.literal("Search"));
        search.setMaxLength(48);
        search.setPlaceholder(Text.literal("Search items..."));
        search.setText(lastSearch);
        search.setChangedListener(s -> {
            lastSearch = s;
            scrollRow = 0;
            rebuildFilter();
        });
        addDrawableChild(search);

        bOnlySelected = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
            onlySelected = !onlySelected;
            scrollRow = 0;
            rebuildFilter();
            refresh();
        }).dimensions(px + PAD + searchW + 4, searchY - 1, selW, 18).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Clear"), b -> {
            cfg.clearItems();
            AutoSellConfig.save();
            rebuildFilter();
        }).dimensions(px + PAD + searchW + 4 + selW + 4, searchY - 1, clearW, 18).build());

        // ---- item grid
        gx = px + PAD;
        gy = searchY + 16 + 6;
        cols = Math.max(1, (pw - PAD * 2 - 8) / CELL);
        rows = Math.max(1, (py + ph - 18 - gy) / CELL);

        rebuildFilter();
        refresh();
    }

    private ButtonWidget add(int index, int colW, int sx, int sy, Runnable action, String tip) {
        ButtonWidget.Builder builder = ButtonWidget.builder(Text.empty(), b -> {
            action.run();
            AutoSellConfig.save();
            refresh();
        }).dimensions(sx + (index % 3) * (colW + 4), sy + (index / 3) * 20, colW, 18);
        if (tip != null) builder.tooltip(Tooltip.of(Text.literal(tip)));
        return addDrawableChild(builder.build());
    }

    // ------------------------------------------------------------------ state -> widgets

    private void refresh() {
        AutoSellConfig c = AutoSellConfig.get();
        if (btn[0] == null) return;

        btn[0].setMessage(onOff("Enabled", c.enabled));
        btn[1].setMessage(Text.literal("Mode: " + (c.whitelist ? "Whitelist" : "Blacklist")));
        btn[2].setMessage(onOff("Auto /sell", c.autoOpen));
        btn[3].setMessage(onOff("Skip hotbar", c.skipHotbar));
        btn[4].setMessage(onOff("Close menu", c.closeAfter));
        btn[5].setMessage(Text.literal("Click delay: " + c.clickDelay + "t"));
        btn[6].setMessage(Text.literal("Confirm delay: " + c.confirmDelay + "t"));
        btn[7].setMessage(Text.literal("Cooldown: " + c.openCooldown + "t"));
        btn[8].setMessage(onOff("Stop on ESC", c.stopOnEsc));
        btn[9].setMessage(onOff("Stop on inv key", c.stopOnInventoryKey));
        btn[10].setMessage(onOff("Only while selling", c.onlyWhileSelling));
        btn[11].setMessage(keyLabel("GUI key", c.guiKey, Listening.GUI));
        btn[12].setMessage(keyLabel("Toggle key", c.toggleKey, Listening.TOGGLE));
        btn[13].setMessage(keyLabel("Stop key", c.stopKey, Listening.STOP));
        btn[14].setMessage(Text.literal("Done"));
        bOnlySelected.setMessage(onOff("Selected only", onlySelected));
    }

    private static Text onOff(String label, boolean on) {
        return Text.literal(label + ": ").append(Text.literal(on ? "ON" : "OFF").formatted(on ? Formatting.GREEN : Formatting.RED));
    }

    private Text keyLabel(String label, int key, Listening which) {
        if (listening == which) {
            return Text.literal(label + ": ").append(Text.literal("press a key...").formatted(Formatting.YELLOW));
        }
        return Text.literal(label + ": " + keyName(key));
    }

    private static String keyName(int key) {
        if (key == GLFW.GLFW_KEY_UNKNOWN) return "None";
        return InputUtil.Type.KEYSYM.createFromCode(key).getLocalizedText().getString();
    }

    private static int next(int[] values, int current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) return values[(i + 1) % values.length];
        }
        return values[0];
    }

    // ------------------------------------------------------------------ item grid

    private void rebuildFilter() {
        AutoSellConfig cfg = AutoSellConfig.get();
        String q = lastSearch.trim().toLowerCase();

        filtered.clear();
        filteredStacks.clear();
        for (Item item : allItems()) {
            if (onlySelected && !cfg.isListed(item)) continue;
            if (!q.isEmpty()) {
                String name = item.getName().getString().toLowerCase();
                String id = Registries.ITEM.getId(item).getPath();
                if (!name.contains(q) && !id.contains(q)) continue;
            }
            filtered.add(item);
            filteredStacks.add(item.getDefaultStack());
        }
        clampScroll();
    }

    private int totalRows() {
        return (filtered.size() + cols - 1) / cols;
    }

    private void clampScroll() {
        int max = Math.max(0, totalRows() - rows);
        scrollRow = Math.max(0, Math.min(scrollRow, max));
    }

    private int cellAt(double mx, double my) {
        if (mx < gx || my < gy || mx >= gx + cols * CELL || my >= gy + rows * CELL) return -1;
        int c = (int) ((mx - gx) / CELL);
        int r = (int) ((my - gy) / CELL);
        int idx = (scrollRow + r) * cols + c;
        return idx < filtered.size() ? idx : -1;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fill(px, py, px + pw, py + ph, 0xF0141418);
        ctx.drawStrokedRectangle(px, py, pw, ph, 0xFFE1AF28);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta); // background, buttons, search box

        AutoSellConfig cfg = AutoSellConfig.get();

        ctx.drawTextWithShadow(textRenderer, Text.literal("AutoSell"), px + PAD, py + 8, 0xFFFFD54F);
        String hint = keyName(cfg.guiKey) + " = close";
        ctx.drawTextWithShadow(textRenderer, hint, px + pw - PAD - textRenderer.getWidth(hint), py + 8, 0xFF9AA0A6);

        // ---- grid
        int gw = cols * CELL;
        int gh = rows * CELL;
        ctx.fill(gx - 1, gy - 1, gx + gw + 1, gy + gh + 1, 0xFF000000);
        ctx.fill(gx, gy, gx + gw, gy + gh, 0xFF2B2B30);

        int hovered = -1;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = (scrollRow + r) * cols + c;
                if (idx >= filtered.size()) break;

                int x = gx + c * CELL;
                int y = gy + r * CELL;

                if (cfg.isListed(filtered.get(idx))) {
                    ctx.fill(x, y, x + CELL, y + CELL, 0xAA2E9E4F);
                    ctx.drawStrokedRectangle(x, y, CELL, CELL, 0xFF5CFF8A);
                }
                if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) {
                    ctx.fill(x, y, x + CELL, y + CELL, 0x55FFFFFF);
                    hovered = idx;
                }
                ctx.drawItem(filteredStacks.get(idx), x + 1, y + 1);
            }
        }

        if (filtered.isEmpty()) {
            String msg = onlySelected ? "No items selected yet" : "No items found";
            ctx.drawTextWithShadow(textRenderer, msg, gx + (gw - textRenderer.getWidth(msg)) / 2, gy + gh / 2 - 4, 0xFF9AA0A6);
        }

        // ---- scrollbar
        int trackX = gx + gw + 2;
        ctx.fill(trackX, gy, trackX + 4, gy + gh, 0xFF1A1A1E);
        int total = totalRows();
        if (total > rows) {
            int thumbH = Math.max(10, gh * rows / total);
            int maxScroll = total - rows;
            int thumbY = gy + (gh - thumbH) * scrollRow / maxScroll;
            ctx.fill(trackX, thumbY, trackX + 4, thumbY + thumbH, 0xFFE1AF28);
        }

        // ---- status line
        String status;
        int color = 0xFF9AA0A6;
        if (cfg.whitelist && cfg.listedCount() == 0) {
            status = "Pick the items to sell by clicking them (nothing is selected yet)";
            color = 0xFFFF6B6B;
        } else {
            status = "Selected: " + cfg.listedCount()
                + "  |  " + (cfg.whitelist ? "selling ONLY selected items" : "selling everything EXCEPT selected items");
        }
        ctx.drawTextWithShadow(textRenderer, status, px + PAD, py + ph - 13, color);

        // ---- tooltip last so it is on top
        if (hovered >= 0) {
            ctx.drawItemTooltip(textRenderer, filteredStacks.get(hovered), mouseX, mouseY);
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (listening != Listening.NONE) {
            // any click cancels key capture
            listening = Listening.NONE;
            refresh();
            return true;
        }
        if (super.mouseClicked(click, doubled)) return true;

        if (click.button() == 0) {
            int idx = cellAt(click.x(), click.y());
            if (idx >= 0) {
                AutoSellConfig.get().toggleItem(filtered.get(idx));
                AutoSellConfig.save();
                if (onlySelected) rebuildFilter();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount != 0) {
            scrollRow -= (int) Math.signum(verticalAmount) * 2;
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        AutoSellConfig cfg = AutoSellConfig.get();
        int key = input.key();

        // ---- capturing a key for one of the key buttons
        if (listening != Listening.NONE) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                // ESC clears toggle/stop keys; the GUI key is kept so you can always open this window again
                if (listening == Listening.TOGGLE) cfg.toggleKey = GLFW.GLFW_KEY_UNKNOWN;
                if (listening == Listening.STOP) cfg.stopKey = GLFW.GLFW_KEY_UNKNOWN;
            } else {
                switch (listening) {
                    case GUI -> cfg.guiKey = key;
                    case TOGGLE -> cfg.toggleKey = key;
                    case STOP -> cfg.stopKey = key;
                    default -> { }
                }
            }
            listening = Listening.NONE;
            AutoSellConfig.save();
            refresh();
            return true;
        }

        // ---- GUI key closes the window (unless you are typing in the search box with a normal key)
        if (cfg.guiKey != GLFW.GLFW_KEY_UNKNOWN && key == cfg.guiKey
            && !(search.isFocused() && !isModifier(key))) {
            close();
            return true;
        }

        return super.keyPressed(input);
    }

    private static boolean isModifier(int key) {
        return key >= GLFW.GLFW_KEY_LEFT_SHIFT && key <= GLFW.GLFW_KEY_RIGHT_SUPER;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        AutoSellConfig.save();
    }
}
