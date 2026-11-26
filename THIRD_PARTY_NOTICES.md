# Third-Party Notices

While Musico's own source code is released under the [MIT License](LICENSE), prebuilt distributions include or use the following third-party libraries:

## Windows (`MusicoWindows/`)

- **TagLibSharp** (`2.3.0`) — Licensed under the **GNU Lesser General Public License, version 2.1 (LGPL-2.1-only)**  
  Project: https://github.com/mono/taglib-sharp  
  Full source code and build scripts for MusicoWindows (including portable single-file and installer builds) are publicly provided in this repository so you can modify or relink `MusicoWindows` against your own build of TagLibSharp.

- **NAudio** (`2.2.1`) — Licensed under the **MIT License**  
  Project: https://github.com/naudio/NAudio

- **System.Text.Encoding.CodePages** (`8.0.0`) — Licensed under the **MIT License**  
  Project: https://github.com/dotnet/runtime

- **Jint** (`4.18.0`) — Licensed under the **BSD 2-Clause License (BSD-2-Clause)**  
  Project: https://github.com/sebastienros/jint

- **Microsoft WebView2 SDK** (`1.0.2903.40`) — Licensed under the **BSD 3-Clause License (Microsoft WebView2 SDK License)**  
  Project: https://developer.microsoft.com/en-us/microsoft-edge/webview2/

## Android (`MusicoAndroid/`)

- **AndroidX Libraries** (`core-ktx`, `activity-compose`, `lifecycle`, `documentfile`, Jetpack Compose) — Licensed under the **Apache License 2.0**
- **AndroidX Media3** (`ExoPlayer`, `MediaSession`, `Common`) — Licensed under the **Apache License 2.0**
- **Material Components for Android** (`com.google.android.material:material`) — Licensed under the **Apache License 2.0**
- **Kotlinx Libraries** (`kotlinx.coroutines`, `kotlinx.serialization`) — Licensed under the **Apache License 2.0**
- **quickjs-kt** (`1.0.0-alpha13`) — Licensed under the **Apache License 2.0** *(bundles **QuickJS**, licensed under the **MIT License**)*  
  Project: https://github.com/dokar3/quickjs-kt
- **JUnit 4** *(test dependency only)* — Licensed under the **Eclipse Public License 1.0 (EPL-1.0)**

## Apple (`Musico/`)

- Uses Apple system frameworks only (no third-party packages).
