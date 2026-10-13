# AutoSell (Fabric 1.21.11)

Standalone Fabric mod (needs only Fabric Loader, no Fabric API, no Meteor).
Auto sells items through the `/sell` menu (6-row chest GUI titled "Sell").

## Use in game
- **Right Shift** opens / closes the AutoSell window.
- Click items in the grid to select what to sell (Whitelist = sell only selected, Blacklist = sell everything except selected).
- Press **Enabled** to start. Settings are saved to `config/autosell.json`.
- Stop keys: **ESC**, your **inventory key**, and an optional custom **Stop key** stop AutoSell immediately.
  By default they only act while a sale is running ("Only while selling").
- Optional **Toggle key** for quick on/off (set it in the window, none by default).

## Build the jar

### Option A: on your PC (needs JDK 21)
```
./gradlew build        (Windows: gradlew.bat build)
```
The jar is `build/libs/autosell-1.0.0.jar`. Put it in your `mods` folder.

### Option B: no install, build on GitHub
1. Create a new GitHub repository and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab, wait for "Build jar" to finish (or press "Run workflow").
3. Open the finished run, download the **autosell-jar** artifact (a zip), and unzip it to get the jar.

## Compatibility
Minecraft 1.21.11, Fabric Loader 0.18.2+, Java 21.
