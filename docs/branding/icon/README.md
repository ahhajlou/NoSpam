# Messages app icon — "Envelope-bubble" (option 1c)

Colors: background `#F4EFE6` (paper), ink `#1B1A19`.

## Drop-in
Copy `res/` over your module's `src/main/res/`. Manifest:

    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round"

## Contents
| Path | Purpose |
|---|---|
| res/mipmap-anydpi-v26/ic_launcher.xml, ic_launcher_round.xml | Adaptive icon (API 26+), incl. `<monochrome>` for Android 13+ themed icons |
| res/drawable/ic_launcher_foreground.xml | Foreground vector, 108dp viewport, art inside the 66dp safe circle |
| res/drawable/ic_launcher_monochrome.xml | Themed-icon layer (outline treatment, single color) |
| res/values/ic_launcher_background.xml | Background color resource |
| res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png | Legacy square icons, 48/72/96/144/192 px |
| res/mipmap-*/ic_launcher_round.png | Legacy round icons, same densities |
| res/mipmap-*/ic_launcher_foreground.png | Raster foreground, 108/162/216/324/432 px (for tooling that needs PNG layers) |
| res/drawable/ic_stat_message.xml | Notification icon, 24dp, white on transparent |
| playstore/ic_launcher-playstore.png | Play Console app icon, 512×512, full-bleed square, no transparency |
| icon.svg | Master vector source |

## Notes
- Adaptive layers are vectors, so the XML pair fully replaces the PNGs on API 26+; PNGs remain for API 25 and below.
- The monochrome layer uses an outline rather than a solid bubble: the envelope fold must stay visible when the system paints the layer in one theme color.
- Notification icons must be white-on-transparent; `ic_stat_message` is pre-tinted.
