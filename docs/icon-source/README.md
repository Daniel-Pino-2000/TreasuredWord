# TreasuredWord app icon

A gilded open Bible on a Parchment brown tile (#368): a gold cover, gilt page edges and cream pages
with lines of text, two verses highlighted in the app's own Blue and Purple swatches
(`ui/theme/HighlightColors.kt`), and a red ribbon hanging from under the right page. The gutter and
a bar across both pages are cut out of the book, so the brown tile shows through as a cross.

It's one fixed icon in every color theme (#244). On Android 13+ the themed-icon setting tints it to
match the wallpaper (the monochrome layer below), which is where the personalising happens.

## One source

`icon.svg` is the only file to edit. It holds the full 108 × 108 adaptive-icon grid in three
groups: `background`, `foreground` and `monochrome` (hidden when the SVG is viewed). Launchers
show only the middle 72 × 72 (18..90), and keep the 66-unit circle around the centre visible
under every mask shape. The pages, highlights, cross and ribbon sit inside that circle; only the
cover's outer bottom corners reach just past it (about 35 units from the centre), still inside the
round mask's 36.

The cross is a real cut-out, not paint: the gutter splits the cover, gilt edges and pages into two
halves, and the bar is notched into each half's outline. A painted cross would drift out of line
when a launcher moves the two layers separately (parallax, press animations).

After editing it, run:

```
python docs/icon-source/make_icons.py
```

It needs Python with Pillow and Chrome or Edge (used headless to draw the SVGs), all free. It
writes:

```
app/src/main/res/
├── drawable/
│   ├── ic_launcher_background.xml   ← the brown gradient (vector)
│   ├── ic_launcher_foreground.xml   ← the book (vector)
│   ├── ic_launcher_monochrome.xml   ← one-color version for Android 13+ themed icons (#105)
│   └── splash_icon_{light,dark}.xml ← startup screen icon, through make_splash_icons.py
└── mipmap-{mdpi…xxxhdpi}/
    ├── ic_launcher.png              ← Android 7 (minSdk 24) fallback, rounded square
    └── ic_launcher_round.png        ← same, round
docs/icon-source/playstore-icon-512.png
docs/play-store/feature-graphic.png  ← and the book copied into feature-graphic.svg
```

`mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` put the three layers together as
the adaptive icon (Android 8.0+). Both layers are vectors, so the icon is sharp at every density
and there are no foreground PNGs. Only Android 7 uses the PNGs, which have the shape baked in
(rounded square with a corner radius of 16/72 of the side, and a circle).

`make_icons.py` turns `<path>` and `<rect>` (with `rx`) into vector paths, colors and linear
gradients into fills, `fill-rule="evenodd"` into `fillType="evenOdd"`, and the shapes in a `<g>`
with a fill into one path. Comments in `icon.svg` are copied into the drawables. Anything else
(strokes, transforms, other gradients) isn't supported, so keep to those.

## Startup screen

`make_splash_icons.py` (#135, #143, #151, #182) builds one 288 dp vector per mode: the launcher's
background and book as a 116 dp circle, with the app's name under it from
`splash_wordmark_{light,dark}.xml`. Those wordmarks are Droid Serif (Apache 2.0) letter outlines,
so no font ships with the app. `make_icons.py` runs it; run it on its own after changing only a
wordmark.

## Google Play

`playstore-icon-512.png` and `../play-store/feature-graphic.png` aren't part of the app. Upload
them in the Play Console under Grow users → Store presence → Main store listing (App icon and
Feature graphic), and mark them as made with AI's help. The icon is a full square; Play rounds it.

The feature graphic's source is `../play-store/feature-graphic.svg`: the brown tile, the book
(copied in from `icon.svg` between its `icon:begin` and `icon:end` markers) and the words as
letter outlines, made with `../play-store/outline_text.py` from Noto Serif Bold (OFL) and Roboto
(Apache 2.0). Both fonts are in any Android system image's `/system/fonts`.
