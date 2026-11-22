# PenDock

PenDock is a customizable M-Pencil shortcut ring for compatible Huawei MatePad tablets. Double-tap the side of the stylus to open the ring near the pen tip and launch favorite apps or use system tools.

## Install

Download the APK from [GitHub Releases](https://github.com/gigabeat89/PenDock/releases) and open it on your MatePad.

1. Open PenDock and tap **Enable ring menu**. Grant **Display over other apps** if prompted, then return to PenDock and enable the ring.
2. Tap **Add app** to customize the ring. Select up to 10 items in total, including apps and built-in tools. Press and hold a row, then drag it to reorder the items. The preview updates while you drag.
3. Hold M-Pencil near the screen and double-tap its side. Hover over an icon to see its label, then tap to select it. Hover over the center to see the battery level when the tablet reports it.

Tap outside the ring or double-tap the stylus again to dismiss it. With **Use only the pen in the ring** enabled, touching the screen or clicking the touchpad dismisses the ring. Turn that setting off to select actions with touch or a touchpad.

The gear icon opens **Settings**, where you can choose English or Thai and set the theme to Light, Dark, or System. New installations default to English and Light. Enable **Laser pointer** to mark the stylus position while hovering over PenDock or its ring.

## Pen battery widgets

From the Home screen widget picker, add **Pen Battery** in 1×1, 1×2, 1×3, 2×1, or 3×1 (rows × columns). Widgets use PenDock's appearance setting and show their labels in English. The model, percentage, and charging indicator use the information reported by the tablet. A red meter indicates less than 20%, and **Unavailable** appears when the tablet does not report a battery level.

## Tools and permissions

Use the **Permissions** panel to enable the services for the features you use.

| Feature | Setup and behavior |
| --- | --- |
| Screenshot and screen lock | Enable **PenDock · Screenshot and screen lock** in Accessibility. The ring calls the system screenshot and lock actions. |
| Floating window | Enable **PenDock · Window status** and **PenDock · Screenshot and screen lock**. The floating-window tool is disabled on Home, when the current app is already floating, or when the app does not support floating windows. Opening a shortcut as a floating window can fall back to full screen if the system or app restricts floating windows. |
| Circle to Search | Enable **PenDock · Circle to Search**. This requires Android 11 or later and a compatible version of the Google app. Circle an area with the pen to send only the circled image to Google Lens. The cropped image stays in the app cache for up to one day. |
| Excluded apps | Enable **PenDock · Window status**, then add apps in **Excluded apps** to keep PenDock from opening over them. Huawei Notes and Notepad are included by default when available. |

If Huawei stops PenDock in the background, use **Battery → Launch manager** to turn off **Manage automatically** for PenDock and enable **Run in background**. To keep PenDock open when you tap **Close all**, swipe down on its card in Recent tasks to lock it. The info buttons in PenDock show both steps.

## Build from source

Use JDK 17, Android SDK Platform 35, and Android SDK Build Tools 35.0.0. Set `JAVA_HOME` and `ANDROID_SDK_ROOT`, then run:

```sh
./build.sh
```

The APK is written to `build/PenDock.apk`. The script generates a local test signing key on the first build. Use the same signing key for subsequent APK updates to the same installation.

- Package ID `th.pendock`
- Minimum Android API level 29
- The app does not request Internet access. The window services use app and activity names, focus, and window geometry. Draft notes stay in the app's private storage.

## License

PenDock is free to use. You may share the official download links and redistribute the original, unmodified APK for free. Sale or paid distribution requires written permission. See the [PenDock Free Use License](res/raw/pendock_license.txt).
