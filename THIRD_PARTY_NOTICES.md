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

## Build dependencies

AndroidX, Jetpack Compose, Kotlin, Kotlin coroutines, JUnit, and Android test
libraries are resolved from their publishers at build time and retain their
individual licenses. JDKs, Android SDKs, Gradle distributions, dependency caches,
and generated APKs are not included in this source repository.

AYN and Odin are names of their respective owners. This community project is
not affiliated with or endorsed by AYN.
