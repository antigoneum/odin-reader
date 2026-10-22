# Bundled reader fonts

Odin Reader includes five original upstream Regular font binaries, totaling
69,800,944 bytes before APK compression. Fonts work offline and are not fetched
at runtime. The app includes the fonts' licenses under `assets/fonts/licenses/`;
the source repository has identical copies in `licenses/`.

| Asset file | Face | Version | Bytes |
| --- | --- | --- | ---: |
| `jetbrains_mono_nerd_regular.ttf` | JetBrainsMono Nerd Font Regular | JetBrains Mono 2.304 / Nerd Fonts 3.5.1 | 2,571,596 |
| `literata_regular.ttf` | Literata Regular | 3.103 | 319,964 |
| `lxgw_wenkai_regular.ttf` | 霞鹜文楷 / LXGW WenKai Regular | 1.530 | 25,928,940 |
| `noto_serif_sc_regular.otf` | Noto Serif CJK SC Regular | 2.003 | 24,543,080 |
| `noto_sans_sc_regular.otf` | Noto Sans CJK SC Regular | 2.004 | 16,437,364 |

Noto's SC faces use Simplified Chinese glyph forms while retaining their full
CJK character coverage. They are the upstream CJK OTF fonts rather than app-made
subsets. LXGW WenKai is also kept intact. Literata provides a proportional serif
face for English reading; JetBrainsMono Nerd Font provides monospaced Latin text
and retains its patched Nerd Font private-use icons. Font selection/fallback is
handled by the reader, rather than modifying font binaries.

## Provenance and verification

All downloads use the projects' official GitHub repositories, pinned to these
commits rather than a moving `main` branch:

- [Nerd Fonts v3.5.1](https://github.com/ryanoasis/nerd-fonts/tree/b894ea7803af6aade63d60a4381e006098ec9c4d)
- [Literata](https://github.com/googlefonts/literata/tree/0c2761b727a1b3a7cffd313c37f0f5163dfc7a63)
- [LXGW WenKai](https://github.com/lxgw/LxgwWenKai/tree/b4ad8194e6aff48f682992c2b5541beeff49df82)
- [Noto CJK](https://github.com/notofonts/noto-cjk/tree/f8d157532fbfaeda587e826d4cd5b21a49186f7c)

[The asset manifest](../app/src/main/assets/fonts/manifest.json) records the exact
upstream paths and URLs, file sizes, SHA-256 hashes, license sources, names,
versions, and glyph counts. File headers and table bounds have been checked;
all five are static Regular fonts, not variable font instances. The Chinese
faces' cmap tables contain the Chinese reading sample and punctuation; the Nerd
font retains its Font Awesome home and Powerline private-use code points.

The binaries are copied byte for byte from upstream. Only the external file
names are normalized for Android asset lookup; internal names and copyright
metadata are preserved. The fonts retain their own SIL OFL 1.1 licenses; the
application's MIT license does not replace those licenses.
