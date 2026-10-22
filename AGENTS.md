# Odin Reader

- This is a native Android app: Kotlin, Jetpack Compose / Material 3, Android TextView for book text. Do not replace it with a website or WebView shell.
- Primary target: Odin 3 landscape handheld. Preserve touch, controller focus and accessibility.
- Keep controller mappings in input/, book parsing in data/, pagination in reader/, and presentation in ui/.
- Rendering and StaticLayout must use identical font, fallback line spacing, width, text size and break settings. Persist character offsets, not just page numbers.
- Keep parsing and text measurement off the main thread. Preserve cancellation and serialize progress writes.
- Imports must remain offline, bounded, and safe for untrusted ZIP paths. Do not request broad storage access or network permission for local books.
- Build on Windows with scripts/dev.ps1 assembleDebug testDebugUnitTest lintDebug --console=plain. Keep SDKs, downloads and Gradle caches on the project drive, not C:.
- Run meaningful existing tests and lint for input, import or pagination changes. Real controller axes and text clipping require device verification; report honestly if no device was used.
- Do not commit local.properties, .tools/, .gradle-user-home/, build outputs or signing keys.
