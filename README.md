<div align="center">
  <a href="https://github.com/Jelofen1962/RWV"><img src="docs/rwv.svg" alt="RWV · Rustmade-WebView" width="840"></a>

  <p><b>One URL. Any platform. That's it.</b></p>
  <p>A tiny project that turns websites and local dashboards into native Windows, Linux and Android apps.</p>

  [![Rust](https://img.shields.io/badge/Rust-000000?style=flat-square&logo=rust&logoColor=white)](https://www.rust-lang.org/)
  [![Windows](https://img.shields.io/badge/Windows-0078D4?style=flat-square&logo=windows&logoColor=white)](https://www.microsoft.com/windows/)
  [![Linux](https://img.shields.io/badge/Linux-FCC624?style=flat-square&logo=linux&logoColor=black)](https://www.kernel.org/)
  [![Android](https://img.shields.io/badge/Android-3DDC84?style=flat-square&logo=android&logoColor=white)](https://www.android.com/)
  [![WebView2](https://img.shields.io/badge/WebView2-0078D7?style=flat-square&logo=microsoftedge&logoColor=white)](https://developer.microsoft.com/microsoft-edge/webview2/)
  [![GitHub Actions](https://img.shields.io/badge/Build-GitHub_Actions-2088FF?style=flat-square&logo=githubactions&logoColor=white)](https://github.com/Jelofen1962/RWV/actions)

  [Quick start](#-quick-start) · [Build in the cloud](#-build-in-the-cloud) · [Stack](#-the-stack) · [Contact](#-made-by)
</div>

---

## ✦ What's RWV?

**Rustmade-WebView** wraps a website in a native app and packages it as a customized Windows `.exe`, Linux `.tar.gz` or Android `.apk`. Give it a name, a version, a PNG and a URL. RWV handles the rest.

I wanted something simple for local dev servers, personal tools and web dashboards without spinning up an entire Electron project. No npm install, no bundled Chromium, no extra config files to carry around. Just Rust on desktop, a small native Java app on Android and the platform’s own webview.

<div align="center">
  <img src="docs/splash-preview.svg" alt="RWV splash design with app icon, name and creator credit" width="660">
  <br><sub>Startup screen design · Your own icon and title replace the example above.</sub>
</div>

## ✦ What you get

| | Feature |
|:--:|:--|
| 🪟 | Native, resizable desktop window or Android app with your own title |
| 🎨 | PNG from a local file or a URL, used as your app icon |
| ✨ | White startup screen with your icon and app name (Poppins on desktop) |
| 🌐 | HTTP, HTTPS, localhost, LAN servers and local `file:///` pages (desktop only for local files) |
| 💾 | Persistent cookies, browser storage and a separate profile per app identity |
| 🛠️ | Desktop DevTools, Windows zoom shortcuts and native web engines; Android Back navigation and file picker |
| 📦 | An EXE, Linux archive or APK, with name, version and icon compiled in |
| ☁️ | Manual GitHub Actions build with Windows, Linux and Android checkboxes |

## ✦ Quick start

For the original local Windows builder, you'll need **Windows**, [Rust stable](https://rustup.rs/) and [Visual Studio Build Tools](https://visualstudio.microsoft.com/downloads/) with the **Desktop development with C++** workload and Windows SDK. On first build, Cargo downloads the dependencies.

Clone the project and run the builder:

```powershell
git clone https://github.com/Jelofen1962/RWV.git
cd RWV
.\build.cmd
```

> If you're using the source ZIP, just extract it and run `build.cmd` instead.

```text
RWV / Rustmade-WebView
by @Jelofen1962 | github.com/Jelofen1962

Application name: My Dashboard
Application version: 1.0.0
PNG icon: .\icons\example.png
Website URL: http://localhost:3000
Treat this HTTP origin as secure? [y/N]: n
```

Your executable lands in `dist/` as `My Dashboard-1.0.0-windows-x64.exe`. The icon can be a local path, `file:///` URL or HTTP(S) PNG URL. Square transparent PNGs at 256×256 or larger work nicely. Versions contain one to four numeric components, each between 0 and 65535.

<details>
<summary><b>Prefer a one-liner or scripting?</b></summary>

```powershell
.\build.ps1 -AppName 'My Dashboard' -AppVersion '1.0.0' `
  -IconSource '.\icons\example.png' `
  -WebsiteUrl 'http://localhost:3000' -NonInteractive
```

Use `-RunTests` to run Rust unit tests before compilation. For ARM64, install the appropriate MSVC ARM64 tooling and add `-Target aarch64-pc-windows-msvc`. If script execution is blocked, run `build.cmd` from a trusted checkout.

</details>

<details>
<summary><b>Building locally on Linux or Android?</b></summary>

Install Python 3.12 and the shared build dependency:

```sh
python -m pip install -r requirements-build.txt
```

On Linux (Ubuntu 22.04 or compatible), install Rust stable and the native development libraries:

```sh
sudo apt install build-essential pkg-config libgtk-3-dev libwebkit2gtk-4.1-dev libxdo-dev
python scripts/build.py --platform linux --name 'My Dashboard' --version 1.0.0 --icon icons/example.png --url https://example.com
```

For Android, install JDK 17, Gradle 8.9 and Android SDK command-line tools. Set `ANDROID_HOME`, add its `cmdline-tools/latest/bin` directory to PATH, accept SDK licenses and install the required SDK components:

```sh
sdkmanager --licenses
sdkmanager 'platforms;android-35' 'build-tools;35.0.0'
python scripts/build.py --platform android --name 'My Dashboard' --version 1.0.0 --icon icons/example.png --url https://example.com
```

Outputs land in `dist/`. GitHub Actions handles these toolchain and SDK setup steps automatically.

</details>

## ✦ Build in the cloud

No compiler installed? GitHub can build for Windows, Linux and Android for you.

1. Push or fork this project to your GitHub repository, including `.github/`.
2. Open **Actions → Build with RWV → Run workflow**. Enable workflows if GitHub asks.
3. Fill in the fields below, check the platforms you want (one or more), and hit **Run workflow**.
4. Open the successful run and download the artifacts for your selected platforms.

| Input | Example |
|:--|:--|
| `app_name` | `My Dashboard` |
| `app_version` | `1.0.0` |
| `icon_source` | `icons/example.png` or `https://example.com/icon.png` |
| `website_url` | `http://localhost:3000` |
| `windows` | ☑ Windows (checked by default) |
| `linux` | ☐ Linux |
| `android` | ☐ Android |
| `trust_http_origin` | `false` (Windows only) |
| `android_id` | Optional package ID; blank = automatic |
| `android_version_code` | `1` (increase for Android updates) |

| Platform | Output | Artifact |
|:--|:--|:--|
| Windows x64 | `.exe` | `RWV-windows-x64-<run-id>` |
| Linux x64 | `.tar.gz` | `RWV-linux-x64-<run-id>` |
| Android (API 23+) | `.apk` | `RWV-android-universal-<run-id>` |

The workflow validates inputs, runs tests and builds the selected platforms independently. It checks Windows metadata, Linux dependencies and Android APK signatures before uploading artifacts for 30 days. Android SDK setup and license acceptance are handled by the workflow. GitHub artifacts are downloaded as ZIPs.

**Local URLs are fine.** GitHub doesn't need to visit the website you're packaging. Your device opens it when you launch the app. An icon URL, on the other hand, has to be accessible to the runner. For an offline icon, commit the PNG to the repo and enter its relative path. Don't put secrets in workflow inputs or URLs. On a physical Android device, use your computer's reachable LAN IP instead of `localhost` (or `10.0.2.2` on the Android emulator).

## ✦ The stack

| Tool | What it does |
|:--|:--|
| [Rust](https://www.rust-lang.org/) | The desktop app, no JS desktop runtime |
| [Tao](https://github.com/tauri-apps/tao) | Native window and event loop |
| [Wry](https://github.com/tauri-apps/wry) | Rust bindings for the embedded webview |
| [WebView2](https://developer.microsoft.com/microsoft-edge/webview2/) | Microsoft Edge's web engine |
| [WebKitGTK](https://webkitgtk.org/) | Linux web engine |
| [Android WebView](https://developer.android.com/reference/android/webkit/WebView) | Native Android web engine |
| Java + Gradle | Native Android app and APK build |
| [image](https://github.com/image-rs/image) + [winres](https://github.com/mxre/winres) | PNG processing, ICO and EXE resources |
| [Poppins](https://fonts.google.com/specimen/Poppins) | Startup screen typography |
| Python + PowerShell + GitHub Actions | Local builds and cloud builds |

## ✦ A few details worth knowing

<details>
<summary><b>HTTP and local development</b></summary>

Normal HTTP is supported on desktop; Android enables cleartext traffic when the configured URL uses HTTP. On Windows, loopback URLs such as `http://localhost:3000` are generally already treated as potentially trustworthy by Chromium. For trusted non-loopback development servers, enable `-TrustHttpOrigin` (or the workflow checkbox) to pass `--unsafely-treat-insecure-origin-as-secure` for that exact origin and port only.

This does not encrypt traffic, bypass TLS certificate validation or CORS, grant permissions, or disable sandbox protections. Stick with HTTPS for production. For desktop URLs such as `file:///C:/app/index.html`, the file has to exist on the target computer. Android accepts HTTP(S) URLs only; RWV packages the URL, not the website files.

</details>
<details>
<summary><b>Browser stuff and persistent sessions</b></summary>

The native web engines support JavaScript, browser storage, WebSockets, fetch and forms. Android also supports system Back navigation and the system file picker. Device permissions, downloads, popups, DRM, screen sharing, WebAuthn and other special features remain subject to the native webview, the website and OS policies; RWV is not a complete Edge browser or a permissions bypass. There is no privileged JS-to-native bridge.

Profiles live in `%LOCALAPPDATA%\RustWebViewApps\<identity>\WebView2`. The legacy folder name keeps sessions from earlier builder versions intact. Rebuild with the same app name and origin to keep cookies; changing either creates a separate profile. For `file:///` apps, the full URL is used. Close all instances before deleting a profile or changing browser flags for that profile. Linux profiles live in `$XDG_DATA_HOME/RustWebViewApps/<identity>/WebKitGTK` (or `~/.local/share` if unset); Android uses private app storage.

</details>
<details>
<summary><b>The splash and console signature</b></summary>

The desktop startup screen embeds the app's PNG, uses Poppins through Google Fonts and displays **powered by Jelofen1962** at the bottom. It runs in the same webview as the site. A short nonblocking timer opens the configured URL after the splash loads, with a five-second fallback if external font loading stalls. If Google Fonts is unavailable, the desktop uses its fallback font. Android uses a native splash with the same icon, title and creator credit.

On desktop, each new top-level web document gets a small styled DevTools console signature with creator links and contact details. It does not edit the target page, add tracking, or send its contents anywhere. DevTools controls its own font availability, so the console's Poppins font-family hint may fall back to the browser's console font. SPA route changes stay in the same document and do not print duplicate signatures. A site can clear or override its console. Android injects the signature after web pages load, with WebView debugging disabled in release builds.

</details>
<details>
<summary><b>Runtime, signing and troubleshooting</b></summary>

On Windows, the target computer needs Microsoft's [WebView2 Evergreen Runtime](https://developer.microsoft.com/microsoft-edge/webview2/). It's commonly present on Windows 10/11. RWV does not bundle Chromium or a runtime installer, and the generated EXE is not code-signed. Windows SmartScreen may warn about it.

On Linux, extract the archive and run `./rwv` from a graphical desktop with GTK 3 and WebKitGTK 4.1 installed. On Ubuntu 22.04, install `libgtk-3-0` and `libwebkit2gtk-4.1-0`. The binary is dynamically linked, not an AppImage.

On Android, install the APK on Android 6.0 (API 23) or newer with Android System WebView. The default APK uses a debug signing key for sideloading; the key may change between hosted workflow runs. To build reliable updates, configure these four GitHub Actions repository secrets:

- `RWV_ANDROID_KEYSTORE_BASE64`
- `RWV_ANDROID_KEYSTORE_PASSWORD`
- `RWV_ANDROID_KEY_ALIAS`
- `RWV_ANDROID_KEY_PASSWORD`

Keep the same signing key and application ID, and increase `android_version_code` for each update. The automatic package ID is based on app name and website origin; you can also supply `android_id` explicitly. This builds a sideloadable APK, not a Play Store AAB. Never commit the signing key.

If desktop builds fail, check the Rust toolchain and platform development libraries first. For Android, check JDK 17, Gradle 8.9 and SDK 35 / Build Tools 35.0.0. Use the build scripts rather than bare `cargo build`, since they provide the app configuration and PNG. Cargo generates a lockfile at the first successful resolution; commit that lockfile to pin future builds. If a website doesn't load, make sure it is reachable from the target device.

</details>

## ✦ Project layout

```text
RWV/
├── .github/workflows/    # build, test, upload
├── docs/                # README artwork
├── icons/               # sample PNG
├── android/             # native Android WebView app
├── scripts/build.py     # shared platform builder
├── tests/               # shared builder tests
├── requirements-build.txt
├── src/
│   ├── ui/
│   │   ├── console.js   # little creator signature
│   │   └── splash.html  # startup screen
│   ├── config.rs        # config validation
│   └── main.rs          # native window + webview
├── build.cmd            # double-click to build
├── build.ps1            # the actual build flow
├── build.rs             # icon + Windows metadata
└── Cargo.toml
```

## ✦ Made by

<div align="center">
  <h3>Jelofen1962</h3>
  <p>Building small tools that make the everyday stuff a bit less annoying.</p>

  [![GitHub](https://img.shields.io/badge/GitHub-Jelofen1962-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/Jelofen1962)
  [![Telegram](https://img.shields.io/badge/Telegram-@jelofen1962-26A5E4?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/jelofen1962)

  <sub>If RWV saves you some time, a star is always appreciated.</sub>
</div>
