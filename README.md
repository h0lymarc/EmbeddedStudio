<div align="center">

# EmbeddedStudio

**A modern Android IDE for creating, editing, and managing embedded Arduino projects.**

[![Kotlin](https://img.shields.io/badge/Kotlin-99.9%25-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-API_30+-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![License](https://img.shields.io/badge/License-Not_Specified-lightgrey?style=for-the-badge)](#license)

[Features](#features) · [Screenshots](#screenshots) · [Getting Started](#getting-started) · [Project Structure](#project-structure) · [Contributing](#contributing)

</div>

---

## Overview

**EmbeddedStudio** is an Android development environment built for embedded and Arduino-style workflows. It combines project management, a rich code editor, board discovery, library management, and build tooling inside a native Jetpack Compose interface.

> The project is in active development. Some features may be experimental or change frequently.

## Features

- **Code editing** — syntax-aware editing with the Sora code editor
- **Arduino support** — board and keyword management for embedded development
- **Project workspace** — create, open, organize, and manage projects
- **Library management** — browse and manage project libraries
- **Symbol indexing** — project-wide symbol discovery and navigation
- **Search tools** — find relevant code and project definitions quickly
- **Material 3 interface** — modern Compose UI with AndroidX components
- **Native Android experience** — responsive editor layouts and activity-based workflows

## Screenshots

> Screenshots will be added as the interface continues to evolve.

<!--
<p align="center">
  <img src="docs/screenshots/home.png" alt="EmbeddedStudio home" width="45%" />
  <img src="docs/screenshots/editor.png" alt="EmbeddedStudio editor" width="45%" />
</p>
-->

## Tech Stack

| Technology | Purpose |
| --- | --- |
| Kotlin | Primary application language |
| Android SDK 36 | Compile platform |
| Jetpack Compose | Declarative UI toolkit |
| Material 3 | Application design system |
| AndroidX | Core, lifecycle, activity, and UI libraries |
| Sora Editor | Code editing and textmate-style syntax support |
| Gradle Kotlin DSL | Build configuration |
| Version Catalog | Centralized dependency management |
| Kotlin Coroutines | Asynchronous and concurrent operations |

The app targets Android 14 while supporting devices running Android 11 and newer:

- **Minimum SDK:** 30
- **Target SDK:** 34
- **Compile SDK:** 36
- **JVM target:** 17

## Getting Started

### Prerequisites

- Android Studio with Android SDK 36
- JDK 17
- An Android device or emulator running API 30 or newer
- Internet access for Gradle dependencies

### Clone and open

```bash
git clone https://github.com/h0lymarc/EmbeddedStudio.git
cd EmbeddedStudio
```

Open the repository folder in Android Studio, allow Gradle to synchronize, and select the **app** run configuration.

### Build from the command line

**macOS / Linux**

```bash
./gradlew assembleDebug
```

**Windows**

```bat
gradlew.bat assembleDebug
```

The debug APK is generated in:

```text
app/build/outputs/apk/debug/
```

### Install on a connected device

```bash
./gradlew installDebug
```

## Project Structure

```text
EmbeddedStudio/
├── app/
│   ├── src/main/
│   │   ├── assets/              # Bundled editor and application assets
│   │   ├── kotlin/
│   │   │   └── com/h0lymarc/eside/
│   │   │       ├── drawer/      # Navigation drawer components
│   │   │       ├── editor/      # Editor chrome and editing UI
│   │   │       ├── manager/     # Boards, libraries, projects, and tools
│   │   │       ├── search/      # Search utilities
│   │   │       └── ui/theme/    # Compose theme and styling
│   │   ├── res/                 # Android resources
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── gradle/
│   └── libs.versions.toml       # Dependency version catalog
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew
```

## Development

### Run checks

```bash
./gradlew check
```

### Clean the build

```bash
./gradlew clean
```

## Repository Information

- **Package:** `com.h0lymarc.eside`
- **Default branch:** `main`
- **Primary language:** Kotlin
- **Visibility:** Private
- **Languages:** Kotlin 99.9% · C++ 0.1%

## Contributing

Contributions are welcome. Before submitting changes:

1. Fork the repository.
2. Create a focused feature or fix branch.
3. Follow the existing Kotlin and Jetpack Compose conventions.
4. Run a clean build and relevant checks.
5. Open a pull request with a clear description and test notes.

## License

No license is currently specified in this repository. Please contact the repository owner before using, distributing, or modifying the project.

---

<div align="center">

Built by [h0lymarc](https://github.com/h0lymarc) for embedded development on Android.

</div>
