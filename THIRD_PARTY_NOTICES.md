# Third-party notices

The project's MIT license applies to original Odin Reader code, documentation,
and the bundled original demo story. It does not replace the licenses of
third-party software.

## Gradle Wrapper

Files: `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar`.

- Upstream: [Gradle 8.13.0](https://github.com/gradle/gradle/tree/v8.13.0).
- License: Apache License, Version 2.0.
- Original copyright and license headers in the startup scripts are retained.
- The wrapper JAR retains its bundled `META-INF/LICENSE`. A readable copy is
  included in [licenses/Gradle-Wrapper-LICENSE.txt](licenses/Gradle-Wrapper-LICENSE.txt).

The startup scripts include project-local cache defaults. These modifications
are part of this repository; the upstream Apache 2.0 terms still apply.

## Catppuccin colors

The Latte, Frappé, Macchiato, and Mocha theme colors in
`app/src/main/java/io/github/odinreader/ui/Theme.kt` come from
[Catppuccin Palette v1.8.0](https://github.com/catppuccin/palette/tree/v1.8.0)
(commit `07d02aa110ef9eb7e7427afca5c73ba9cf7f8ebd`).

- Copyright (c) 2021 Catppuccin.
- License: MIT. The complete license is included in
  [licenses/Catppuccin-LICENSE.txt](licenses/Catppuccin-LICENSE.txt).
- Material 3 role assignments are adapted for Odin Reader; palette values are
  preserved. System theme selects Latte in light mode and Mocha in dark mode.

## Bundled reader fonts

The files in `app/src/main/assets/fonts/` are unchanged upstream Regular font
binaries. They are bundled for offline reading, without subsetting, converting,
or changing the font's internal names. File names in this app are normalized for
asset lookup. Their upstream copyright and license notices are included both in
`licenses/` and in the APK at `assets/fonts/licenses/`.

| Font | Upstream version | License notices |
| --- | --- | --- |
| JetBrainsMono Nerd Font Regular | JetBrains Mono 2.304, Nerd Fonts 3.5.1 | [JetBrains Mono OFL](licenses/JetBrainsMono-Nerd-OFL.txt), [Nerd Fonts and icon notices](licenses/Nerd-Fonts-LICENSES.txt) |
| Literata Regular | 3.103 | [Literata OFL](licenses/Literata-OFL.txt) |
| LXGW WenKai Regular / 霞鹜文楷 | 1.530 | [LXGW WenKai OFL](licenses/LXGWWenKai-OFL.txt) |
| Noto Serif CJK SC Regular | 2.003 | [Noto Serif CJK OFL](licenses/NotoSerifCJK-OFL.txt) |
| Noto Sans CJK SC Regular | 2.004 | [Noto Sans CJK OFL](licenses/NotoSansCJK-OFL.txt) |

These fonts are released under the SIL Open Font License 1.1. The Nerd Fonts
notices additionally preserve its upstream license audit and notices for
Codicons, Font Awesome, Material Design Icons, Octicons, Pomicons, Powerline
symbols, and Weather Icons. These remain under their respective licenses as
stated in the included notices. Odin Reader does not modify the patched font or
its icon glyphs.

Pinned source commits, direct download URLs, SHA-256 checksums, sizes, and font
metadata are recorded in [the bundled font manifest](app/src/main/assets/fonts/manifest.json).
The full CJK fonts are retained so that the app is not limited to the characters
in its own interface or demo book. See [font asset details](docs/font-assets.md).

## Build dependencies

AndroidX, Jetpack Compose, Kotlin, Kotlin coroutines, JUnit, and Android test
libraries are resolved from their publishers at build time and retain their
individual licenses. JDKs, Android SDKs, Gradle distributions, dependency caches,
and generated APKs are not included in this source repository.

AYN and Odin are names of their respective owners. This community project is
not affiliated with or endorsed by AYN.
