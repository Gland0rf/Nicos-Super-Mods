# Nico's Super Mods (NSM)

A [Fabric](https://fabricmc.net/) mod for **Minecraft** focused on quality-of-life tools for **Hypixel SkyBlock**.

The goal of NSM is to fill niche gaps in Hypixel SkyBlock by providing high-quality, lightweight quality-of-life features that aren't available elsewhere.

> [IMPORTANT]
> NSM is an unofficial community project. It is not affiliated with or endorsed by Mojang, Microsoft, Hypixel, or the Hypixel SkyBlock Wiki. Use modifications at your own risk and follow the rules of every server you join.

## Features

### Dungeon tools
- **Room Stacking Detector**
- **Room Secret Timer**
- **Lag Monitor**

### Hypixel Wiki browser

NSM contains an in-game browser for the Hypixel SkyBlock Wiki, so you don't have to open up your external browser every single time.

The integrated browser does **not** use a traditional browser engine. It was built from the ground up with the only purpose being browsing the Wiki, keeping resource usage as low as possible and preventing unnecessary RAM bloating.

Useful controls:

| Control                          | Action |
|----------------------------------|---|
| `Ctrl + [DEFAULT: Right Click]`  | Open the wiki of the selected item
| `Ctrl + L` / `Ctrl + K`          | Focus Wiki search |
| `Ctrl + T`                       | Open a new tab |
| `Ctrl + W`                       | Close the current tab |
| `Ctrl + Tab`                     | Next tab |
| `Ctrl + Shift + Tab`             | Previous tab |
| `Ctrl + F`                       | Find on page |
| `Enter` / `Shift + Enter`        | Next / previous find result |
| `Ctrl + D`                       | Toggle bookmark |
| `Ctrl + R`                       | Reload |
| `Alt + Left` / `Alt + Right`     | Back / forward |
| `Ctrl + Click` / middle-click    | Open an internal link in a new tab |
| Right-click                      | Open the link context menu |

#### Wiki content and attribution

The wiki browser fetches content from the [Independent Hypixel Skyblock Wiki](https://hypixelskyblock.minecraft.wiki/) at runtime.

Wiki text is provided under [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/) and may be reformatted for display inside Minecraft. Images and other media may have separate licensing or attribution requirements; their original metadata and source information are shown where available.

NSM is not affiliated with or endorsed by Hypixel, the Independent Hypixel Skyblock Wiki, Weird Gloop, MediaWiki, or the Wikimedia Foundation.

The Wiki browser requires an internet connection (duh) and makes requests directly to the Hypixel Skyblock Wiki.

### Minion tools

NSM includes minion output and ROI tools backed by the public Hypixel Skyblock Bazaar API.

### Central HUD editor

HUD elements share one central layout system.

Open it with:

```text
/nsm gui
```

HUD positions and scales are saved automatically.

## Requirements

- **Mod loader:** Fabric Loader
- **Required mods:** Fabric API, Fabric Language Kotlin, ModMenu

## Configuration

NSM uses MoulConfig for its main user-facing settings.

### Generated data

Depending on which features are used, NSM may create files under the Minecraft `config` directory.

The config location is:

```text
%APPDATA%/.minecraft/config/nicos_super_mods
```

## Acknowledgements

Some ideas and implementation details were inspired by existing Hypixel SkyBlock mods and the work of their developers.

## Network access

NSM is client-side, but some features communicate over the network:

- The Wiki browser requests public Wiki and MediaWiki data.
- Bazaar tools request public Hypixel/Bazaar data through the project's API service.
- Remote Wiki images are downloaded asynchronously.
- The lag monitor sends lightweight ping requests over the active Minecraft connection while monitoring a run.

## Notes
The license of this repository applies to NSM's source code only and does not grant additional rights to third-party wiki content, images, or other assets.