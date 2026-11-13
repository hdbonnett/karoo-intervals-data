# Development setup on Windows 11

This gets you from a clean Windows 11 machine to building the APK, testing it on an Android emulator, and installing it on a Karoo 3. You'll edit in VS Code and build from its terminal; Android Studio is installed mainly because it is the easiest way to get the Android SDK, a JDK and the emulator, and it remains the better debugger if you ever need breakpoints.

## 1. Turn on virtualisation (for the emulator)

1. Check that virtualisation is enabled in the BIOS/UEFI (Intel VT-x or AMD SVM). Task Manager › Performance › CPU shows **Virtualization: Enabled** when it is.
2. Open **Turn Windows features on or off** and tick **Windows Hypervisor Platform** (and **Virtual Machine Platform** if you use WSL). Reboot.

The Android Emulator on Windows uses the Windows Hypervisor Platform; no separate Intel HAXM or AMD driver is needed.

## 2. Install the tools

Using winget in an elevated PowerShell (or install each one manually):

```powershell
winget install --id Git.Git
winget install --id Google.AndroidStudio
winget install --id Microsoft.VisualStudioCode
```

Then start Android Studio once and let the setup wizard install the **standard** components (Android SDK, platform tools, emulator, a system image). After that:

- In **Settings › Languages & Frameworks › Android SDK › SDK Platforms**, install **Android 15 (API 35)** (the project compiles against it).
- In **SDK Tools**, make sure **Android SDK Build-Tools**, **Android Emulator** and **Android SDK Platform-Tools** are ticked.

Note the SDK location shown at the top of that screen, normally `C:\Users\<you>\AppData\Local\Android\Sdk`.

## 3. Environment variables

Set these as user variables (Start › "Edit environment variables for your account"):

| Variable | Value |
|---|---|
| `ANDROID_HOME` | `C:\Users\<you>\AppData\Local\Android\Sdk` |
| `JAVA_HOME` | `C:\Program Files\Android\Android Studio\jbr` (the JDK bundled with Android Studio) |

Add to `Path`:

- `%ANDROID_HOME%\platform-tools` (for `adb`)
- `%ANDROID_HOME%\emulator`
- `%JAVA_HOME%\bin`

Open a new terminal and check: `java -version` (17 or newer) and `adb version`.

## 4. GitHub token for karoo-ext

Hammerhead publishes karoo-ext on GitHub Packages, which requires a token even for public packages.

1. GitHub › Settings › Developer settings › Personal access tokens › **Tokens (classic)** › Generate new token.
2. Tick only **read:packages**. Give it an expiry you're comfortable with.
3. Create (or edit) `C:\Users\<you>\.gradle\gradle.properties` and add:

```properties
gpr.user=<your GitHub username>
gpr.key=<the token>
```

Keeping it in your user Gradle folder means it never ends up in the repository. The GitHub Actions workflow in this repo uses the built-in `GITHUB_TOKEN` instead, so CI needs no extra secret.

## 5. VS Code

Recommended extensions:

- **Kotlin** language support: search the Marketplace for "Kotlin" and install JetBrains' official Kotlin extension if it's listed, otherwise *Kotlin Language* (fwcd). Completion and navigation for Android projects are weaker than in Android Studio but fine for this codebase.
- **Gradle for Java** (`vscjava.vscode-gradle`): runs Gradle tasks from the sidebar.
- **XML** (Red Hat) for the layout and resource files.

Open the repository folder in VS Code and use the integrated terminal (PowerShell) for the commands below.

## 6. Build and test

```powershell
# calculation tests (pure Kotlin, no Android needed)
.\gradlew.bat :core:test

# app unit tests (JSON parsing, formatting)
.\gradlew.bat :app:testDebugUnitTest

# debug APK -> app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat :app:assembleDebug

# release APK (signed with the debug key, fine for sideloading)
.\gradlew.bat :app:assembleRelease
```

The first build downloads Gradle, the Android Gradle plugin and karoo-ext, so it takes a few minutes. If it fails with `401 Unauthorized` from `maven.pkg.github.com`, the token in step 4 is missing or lacks `read:packages`.

## 7. Android emulator

Create a virtual device roughly the shape of the Karoo 3 (3.2" screen, 480 × 800):

1. Android Studio › **Device Manager** › **+** › **Create Virtual Device**.
2. **New Hardware Profile**: name it `Karoo-like`, screen 3.2", resolution 480 × 800, no hardware buttons or keyboard. Finish.
3. Select it, choose a **x86_64 Google APIs** system image (API 34 is a good choice), finish, and start it.

Or from the terminal, once a device exists:

```powershell
emulator -list-avds
emulator -avd Karoo-like
```

Install and run:

```powershell
.\gradlew.bat :app:installDebug
adb shell am start -n com.infoprime.karoointervals/.ui.MainActivity
```

### What the emulator can and can't test

There is no Karoo OS for the emulator, so **the data fields themselves can't appear there**: they are drawn by Karoo's ride app. Everything up to that point can be tested:

- Settings screen: enter your Intervals athlete id and API key, then **Save and refresh**. The status panel shows yesterday's closing fitness, fatigue and CIL, the FTP used for live load, and the "now" and "end of week" values the fields would show. Compare these with the Intervals fitness chart and week card.
- **Simulate a ride today**: enter minutes, normalised power and distance to see what each field would read mid-ride, including the plan replacement.
- Logs: `adb logcat -s KarooIntervals`.

To pass credentials without typing them:

```powershell
adb shell am start -n com.infoprime.karoointervals/.ui.MainActivity --es athlete_id i12345 --es api_key YOUR_KEY
```

## 8. Install on the Karoo 3

**Companion app (simplest).** Copy `app-release.apk` to your phone, open it with (or share it to) the Hammerhead Companion app and send it to the Karoo. The Karoo installs it like any other extension.

**adb (handy while iterating).** If you enable USB debugging in the Karoo's Android developer settings, the Karoo appears in `adb devices` over USB and you can use `.\gradlew.bat :app:installDebug` and `adb logcat -s KarooIntervals` directly. Check Hammerhead's current guidance before changing developer settings on the device.

Then, on the Karoo:

1. Open **Intervals Data** from the apps list and enter your athlete id and API key (or use the adb command above with the Karoo connected). Make sure the Karoo is on Wi-Fi or connected to the Companion app, and tap **Save and refresh**.
2. Edit a ride profile page and add fields from the **Intervals Data** group: the numeric fields individually, or **Week summary** (works best at half page or larger; smaller slots show just the two progress bars).
3. Start a ride and check the values move.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `401` from maven.pkg.github.com during build | GitHub token missing or without `read:packages` |
| Emulator very slow or won't start | Windows Hypervisor Platform not enabled, or virtualisation off in BIOS |
| "HTTP 401/403 (check athlete id and API key)" in the app | Wrong athlete id (it starts with `i`) or API key |
| "HTTP 0 (no network)" on the Karoo | Karoo has neither Wi-Fi nor a Companion app connection; fields keep the last cached data |
| Fields show "–" | No data fetched yet; open the app and refresh |
