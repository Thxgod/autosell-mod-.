package com.autosell;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Settings saved to config/autosell.json. */
public class AutoSellConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("autosell.json");

    private static AutoSellConfig instance = new AutoSellConfig();

    // ---- saved settings ----
    public boolean whitelist = true;                // true: sell ONLY listed items, false: sell everything EXCEPT listed
    public List<String> items = new ArrayList<>();  // item ids, e.g. "minecraft:cobblestone"
    public int clickDelay = 2;                      // ticks between shift-clicks
    public int confirmDelay = 4;                    // ticks before clicking the confirm slot
    public int openCooldown = 40;                   // ticks between /sell cycles
    public boolean autoOpen = true;                 // run /sell automatically
    public boolean skipHotbar = true;
    public boolean closeAfter = true;
    public boolean stopOnEsc = true;
    public boolean stopOnInventoryKey = true;
    public boolean onlyWhileSelling = true;         // stop keys only act while a sale is running
    public int guiKey = GLFW.GLFW_KEY_RIGHT_SHIFT;  // opens/closes the UI
    public int toggleKey = GLFW.GLFW_KEY_UNKNOWN;   // quick on/off (optional)
    public int stopKey = GLFW.GLFW_KEY_UNKNOWN;     // extra stop key (optional)

    // ---- runtime only (not saved) ----
    public transient boolean enabled = false;
    private transient Set<Item> itemSet = new HashSet<>();

    public static AutoSellConfig get() {
        return instance;
    }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                AutoSellConfig loaded = GSON.fromJson(Files.readString(FILE), AutoSellConfig.class);
                if (loaded != null) instance = loaded;
            }
        } catch (Exception e) {
            AutoSellMod.LOGGER.warn("Could not read autosell.json, using defaults", e);
            instance = new AutoSellConfig();
        }
        if (instance.items == null) instance.items = new ArrayList<>();
        instance.rebuild();
    }

    public static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(instance));
        } catch (IOException e) {
            AutoSellMod.LOGGER.warn("Could not save autosell.json", e);
        }
    }

    // ---- item list helpers ----

    private static Map<String, Item> byId;

    private static Map<String, Item> byId() {
        if (byId == null) {
            byId = new HashMap<>();
            for (Item item : Registries.ITEM) {
                byId.put(Registries.ITEM.getId(item).toString(), item);
            }
        }
        return byId;
    }

    private void rebuild() {
        itemSet = new HashSet<>();
        for (String id : items) {
            Item item = byId().get(id);
            if (item != null) itemSet.add(item);
        }
    }

    public boolean isListed(Item item) {
        return itemSet.contains(item);
    }

    public int listedCount() {
        return itemSet.size();
    }

    public void toggleItem(Item item) {
        if (!itemSet.remove(item)) itemSet.add(item);
        syncList();
    }

    public void clearItems() {
        itemSet.clear();
        syncList();
    }

    private void syncList() {
        items = new ArrayList<>();
        for (Item item : itemSet) items.add(Registries.ITEM.getId(item).toString());
        items.sort(String::compareTo);
    }

    /** Should this item be sold, given the list and the whitelist/blacklist mode? */
    public boolean canSell(Item item) {
        boolean listed = itemSet.contains(item);
        return whitelist ? listed : !listed;
    }
}
