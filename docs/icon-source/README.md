# TreasuredWord app icon

An open Bible on espresso: gold-edged pages with lines of text, verses highlighted in the app's
own Blue and Purple swatches (`ui/theme/HighlightColors.kt`), a note in its Yellow stuck to the
corner of a page, and a red ribbon down the gutter. It shows what the app is for: reading,
highlighting and taking notes.

`icon.svg` is the editable source: the full 108 × 108 adaptive-icon grid, background and
foreground together. Launchers show only the middle 72 × 72 (18..90), and keep the 66-unit
circle around the centre visible under every mask shape, so the book stays inside that circle.

## Where it lives in the app

```
app/src/main/res/
├── drawable/
│   ├── ic_launcher_background.xml   ← espresso gradient (vector)
│   ├── ic_launcher_foreground.xml   ← the open Bible (vector, same shapes as icon.svg)
│   └── ic_launcher_monochrome.xml   ← one-color version for Android 13+ themed icons
├── mipmap-anydpi-v26/
│   ├── ic_launcher.xml              ← adaptive icon (Android 8.0+)
│   └── ic_launcher_round.xml
└── mipmap-{mdpi…xxxhdpi}/
    ├── ic_launcher.png              ← Android 7 (minSdk 24) fallback, rounded square
    └── ic_launcher_round.png        ← same, round
```

Both layers are vectors, so they're sharp at every density and there are no foreground PNGs.
Only Android 7 uses the PNGs, which have the shape baked in.

`playstore-icon-512.png` isn't part of the app. Upload it in the Play Console under
Grow users → Store presence → Main store listing → App icon. It's a full square; Play rounds it.

## Changing the icon

Edit the foreground drawable and `icon.svg` together; they use the same coordinates. Then
regenerate the PNGs from `icon.svg`: render its middle 72 × 72 (`viewBox="18 18 72 72"`) as a
large square, scale it to 512 for the Play Store, and to 48/72/96/144/192 px for the mipmap
PNGs with a rounded-square mask (corner radius 16/72 of the side) and a round one.
