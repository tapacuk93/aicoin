# App icon generator

Renders the AICoin mark — dark `#0b0d10` ground, mint `#6ee7b7` coin, milled
rim, knocked-out "AI" — and fills every `AppIcon.appiconset` under `~/src`
with it. The palette is the same one `WalletTheme.swift` and `site/index.html`
use; change it in one place, in `MakeIcon.swift`.

Each slot is rendered **natively at its own pixel size** rather than
downscaled from a single 1024, so the 16pt macOS icon stays as crisp as the
1024 marketing one.

## Regenerating every app's icon

```sh
cd tools/appicon
swiftc -O MakeIcon.swift -o makeicon
python3 plan_icons.py            # writes manifest.tsv from each Contents.json
./makeicon manifest manifest.tsv
```

`plan_icons.py` reads each catalog's `Contents.json` as the source of truth
for which sizes and filenames that app expects, so slots are never invented
or dropped. Entries with no `filename` get one assigned and written back —
that is how `aimusicgen`, which had eleven empty slots, got its icon.

## Regenerating just the wallet's own assets

```sh
./makeicon out
```

writes `icon-1024.png`, the `launch-logo{,@2x,@3x}.png` set that
`Assets.xcassets/LaunchLogo.imageset` uses for the launch screen, and the
`favicon-{16,32,180}.png` files the landing page links from its `<head>`.

## Note on macOS slots

These are rendered full-bleed, identical to the iOS icon. macOS convention is
a rounded rect with transparent margin, so they will look slightly oversized
in the Dock next to native apps. Fixing that means insetting the mark and
masking a squircle in `renderIcon` for `mac`-idiom slots.
