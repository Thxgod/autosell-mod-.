package com.autosell;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoSellMod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("autosell");

    @Override
    public void onInitializeClient() {
        AutoSellConfig.load();
        LOGGER.info("AutoSell loaded. Press Right Shift in game to open the settings.");
    }
}
