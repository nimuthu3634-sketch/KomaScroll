# KomaScroll

KomaScroll is an Android manga reader built on top of [Komikku](https://github.com/komikku-app/komikku),
which is itself based on [Mihon](https://github.com/mihonapp/mihon) and [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY).
Everything Komikku does, KomaScroll does too; its own additions live under **Settings → KomaScroll Lab**,
each with its own on/off switch.

KomaScroll is source-agnostic: it ships with no content sources or extension repositories, and does
not recommend any. You add the extension repositories you want yourself.

*Requires Android 8.0 or higher.* Licensed under the Apache License 2.0 — see [LICENSE](LICENSE) and
[NOTICE](NOTICE). KomaScroll is not affiliated with the Komikku, Mihon or Tachiyomi projects.

## KomaScroll Lab features

Heavy features are off by default; turn them on in Settings → KomaScroll Lab.

| Feature | What it does | Default |
|---|---|---|
| AI upscaling | Real-ESRGAN (NCNN, Vulkan GPU) upscales low-resolution pages 2× or 4× while you read, with a disk cache you can size and clear | Off |
| Live raw translation | On-device OCR and translation (ML Kit) of raw Japanese, Chinese, Korean or English pages, typeset into the bubbles; optional DeepL with your own key | Off |
| Guided panel view | Detects panels and steps through them one at a time in the paged readers | Off |
| Release prediction | "Next chapter likely: Friday" on series pages, from past upload dates | On |
| Source failover | Finds a series in your other sources, checks it is the same one by comparing page fingerprints, and offers this when a chapter fails to load | On |
| Reading Wrapped | Yearly reading stats with a shareable card | On |
| Sound-effect haptics | Vibrates to sound effects on the page (ドン, BOOM, ドキドキ…) | Off |
| Panel clipper | Share one panel or area of a page, with spoilers blurred | On |
| Smart downloads | Keeps the next few unread chapters of series you are reading downloaded, while charging and on Wi-Fi | Off |
| PIN app lock and decoy library | A PIN pad with fingerprint/face unlock; a second PIN opens a decoy library | Off |

API keys you enter (DeepL) are stored encrypted with the Android Keystore; lock PINs are only stored
as salted hashes. Lab features only go online for what you turn on: downloading ML Kit models through
Google Play services, DeepL if you add a key, and your own sources.

## Download

Signed releases go on the [Releases](https://github.com/nimuthu3634-sketch/KomaScroll/releases)
page. Test builds of every branch are attached to its run on the
[Actions](https://github.com/nimuthu3634-sketch/KomaScroll/actions) tab (open a run → **Artifacts**).
Most current phones need the `arm64-v8a` APK; the `universal` one works everywhere.

## Building on Windows

### What you need

- **Windows 10 or 11** with about 15 GB of free disk space and 8 GB of RAM or more.
- **JDK 21**, for example [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21).
  Tick "Set JAVA_HOME" in the installer, then check in a new PowerShell window: `java -version`.
- **Android SDK**: the easiest way is [Android Studio](https://developer.android.com/studio). In
  *Settings → Languages & Frameworks → Android SDK*, install **Android SDK Platform 36**, and under
  *SDK Tools* also **NDK (Side by side)** and **CMake** (the AI upscaler is native code).
- **Git for Windows**.

### Build a debug APK

Open PowerShell. Clone into a short path, because native builds can hit Windows' path length limit:

```powershell
git config --global core.longpaths true
git clone https://github.com/nimuthu3634-sketch/KomaScroll.git C:\dev\KomaScroll
cd C:\dev\KomaScroll
```

Tell Gradle where the SDK is (skip this if you opened the project in Android Studio once, which
writes the same file):

```powershell
"sdk.dir=$($env:LOCALAPPDATA -replace '\\','/')/Android/Sdk" | Out-File -Encoding ascii local.properties
```

Then build:

```powershell
.\gradlew.bat assembleDebug
```

The first build downloads about 1 GB of dependencies (plus the NCNN library) and takes a while; later
builds are much faster. The APKs end up in `app\build\outputs\apk\debug\`. The debug app has its
own package name (`com.chama.komascroll.dev`), so it installs next to a release build.

To install on a phone with USB debugging enabled:

```powershell
adb install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
```

Before sending changes, run the same checks as CI:

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat spotlessCheck
.\gradlew.bat testDebugUnitTest
```

If Gradle runs out of memory, run `.\gradlew.bat --stop` and try again.

## Signing a release

Release APKs are shrunk and optimized by R8 and must be signed with **your own** key. The key file
and its passwords must never be committed: `keystore.properties`, `*.jks` and `*.keystore` are
git-ignored.

**1. Create a keystore** (once). `keytool` comes with the JDK:

```powershell
mkdir $env:USERPROFILE\keystores
keytool -genkeypair -v -keystore $env:USERPROFILE\keystores\komascroll-release.jks `
  -alias komascroll -keyalg RSA -keysize 4096 -validity 10000
```

It asks for a password and your name/organization. **Back up the `.jks` file and the password**
somewhere safe (a password manager and an offline copy): Android only installs updates signed
with the same key, so losing it means users have to uninstall to get new versions.

**2. Point the build at it.** Copy `keystore.properties.example` to `keystore.properties` in the
project folder and fill in the path (with forward slashes), alias and passwords.

**3. Build:**

```powershell
.\gradlew.bat assembleRelease
```

The signed APKs are in `app\build\outputs\apk\release\`. To check the signature:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs `
  app\build\outputs\apk\release\app-arm64-v8a-release.apk
```

(Use whichever build-tools version you have installed.)

### Releasing from GitHub

The **Release Builder** workflow builds signed APKs on GitHub when you push a tag such as `v1.0.0`,
and attaches them to a *draft* release for you to review and publish. It needs four repository
secrets (*Settings → Secrets and variables → Actions → New repository secret*):

| Secret | Value |
|---|---|
| `KOMASCROLL_KEYSTORE_BASE64` | The keystore file as Base64 (see below) |
| `KOMASCROLL_KEYSTORE_PASSWORD` | The keystore password |
| `KOMASCROLL_KEY_ALIAS` | `komascroll` (or the alias you chose) |
| `KOMASCROLL_KEY_PASSWORD` | The key password (the same as the keystore password unless you set another) |

To copy the keystore as Base64 to the clipboard:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\keystores\komascroll-release.jks")) | Set-Clipboard
```

Then tag and push:

```powershell
git tag v1.0.0
git push origin v1.0.0
```

Without the secrets the workflow still builds, but the APKs are unsigned and no release is created.

## Performance

- Heavy work (OCR, translation, upscaling, panel detection, fingerprints, page hashing) runs off the
  main thread, one page at a time, and can be cancelled. Results go to disk caches with limits you
  set; only small results are kept in memory.
- When the app goes to the background or Android is short on memory, KomaScroll releases its ML
  models and the upscaler's GPU memory; they are reloaded when next needed. Leaving the reader does
  the same.
- To measure it yourself, use Android Studio's profiler on a release-like build (the `benchmark`
  build type is profileable), or check memory from PowerShell while reading:
  `adb shell dumpsys meminfo com.chama.komascroll`.

## Credits

KomaScroll stands on the work of:

- [Komikku](https://github.com/komikku-app/komikku), the app this is built on
- [Mihon](https://github.com/mihonapp/mihon), [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY)
  and [Tachiyomi](https://github.com/tachiyomiorg/tachiyomi), which Komikku is built on
- [NCNN](https://github.com/Tencent/ncnn) (BSD 3-Clause) and
  [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) (BSD 3-Clause) for AI upscaling
- [Google ML Kit](https://developers.google.com/ml-kit) for on-device text recognition and translation
- [Comic Neue](https://github.com/crozynski/comicneue) (SIL Open Font License) for translated text

All original copyright notices are kept; see [NOTICE](NOTICE) for details. The app lists them under
Settings → About → Credits, along with every library's license.

> The rest of this file is Komikku's original README, kept for reference. Its download links and
> community channels belong to Komikku, not KomaScroll.

---

<div align="center">

<a href="https://komikku-app.github.io">
  <img width=200px height=200px src="./.github/readme-images/app-icon.png"/>
</a><br/>
<a href="https://trendshift.io/repositories/13696" target="_blank"><img src="https://trendshift.io/api/badge/repositories/13696" alt="komikku-app%2Fkomikku | Trendshift" style="width: 250px; height: 55px;" width="250" height="55"/></a>
 <h1 align="center"> Komikku </h1>

| Releases | Preview |
|----------|---------|
| <div align="center"> [![GitHub downloads](https://img.shields.io/github/downloads/komikku-app/komikku/latest/total?label=Latest%20Downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/komikku-app/komikku/releases/latest) [![GitHub downloads](https://img.shields.io/github/downloads/komikku-app/komikku/total?label=Total%20Downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/komikku-app/komikku/releases) [![Stable build](https://img.shields.io/github/actions/workflow/status/komikku-app/komikku/build_release.yml?labelColor=27303D&label=Stable&labelColor=06599d&color=043b69)](https://github.com/komikku-app/komikku/actions/workflows/build_release.yml) | <div align="center"> [![GitHub downloads](https://img.shields.io/github/downloads/komikku-app/komikku-preview/latest/total?label=Latest%20Downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/komikku-app/komikku-preview/releases/latest) [![GitHub downloads](https://img.shields.io/github/downloads/komikku-app/komikku-preview/total?label=Total%20Downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/komikku-app/komikku-preview/releases) [![Preview build](https://img.shields.io/github/actions/workflow/status/komikku-app/komikku-preview/build_app.yml?labelColor=27303D&label=Preview&labelColor=2c2c47&color=1c1c39)](https://github.com/komikku-app/komikku-preview/actions/workflows/build_app.yml) |

*Requires Android 8.0 or higher.*

[![Discord](https://img.shields.io/discord/1242381704459452488.svg?label=&labelColor=6A7EC2&color=7389D8&logo=discord&logoColor=FFFFFF)](https://discord.gg/85jB7V5AJR)
[![CI](https://img.shields.io/github/actions/workflow/status/komikku-app/komikku/build_push.yml?labelColor=27303D&label=CI)](https://github.com/komikku-app/komikku/actions/workflows/build_push.yml)
[![License: Apache-2.0](https://img.shields.io/github/license/komikku-app/komikku?labelColor=27303D&color=0877d2)](/LICENSE)
[![Translation status](https://img.shields.io/weblate/progress/komikku-app?labelColor=27303D&color=946300)](https://hosted.weblate.org/engage/komikku-app/)

## Download

[![Stable](https://img.shields.io/github/release/komikku-app/komikku.svg?maxAge=3600&label=Stable&labelColor=06599d&color=043b69)](https://github.com/komikku-app/komikku/releases/latest)
[![Preview](https://img.shields.io/github/v/release/komikku-app/komikku-preview.svg?maxAge=3600&label=Preview&labelColor=2c2c47&color=1c1c39)](https://github.com/komikku-app/komikku-preview/releases/latest)

*Requires Android 8.0 or higher.*

[![Sponsor me on GitHub](https://custom-icon-badges.demolab.com/badge/-Sponsor-ea4aaa?style=for-the-badge&logo=heart&logoColor=white)](https://github.com/sponsors/cuong-tran "Sponsor me on GitHub")

<div align="left">
A free and open source manga reader which is based off TachiyomiSY & Mihon/Tachiyomi. This fork is meant to provide new & useful features while regularly take features/updates from Mihon or other forks like SY, J2K and Neko...

![screenshots of app](./.github/readme-images/screens.png)

<div align="left">

## Features

### Komikku's unique features:
- `Suggestions` automatically showing source-website's recommendations / suggestions / related to current entry for all sources.
- `Hidden categories` to hide yours things from *nosy* people.
- `Auto theme color` based on each entry's cover for entry View & Reader.
- `App custom theme` with `Color palettes` for endless color lover.
- `Bulk-favorite` multiple entries all at once.
- Source & Language icon on Library & various places. (Some language flags are not really accurate)
- `Feed` now supports **all** sources, with more items (20 for now).
- Fast browsing (for who with large library experiencing slow loading)
- Grouped entries in Update tab (inspired by J2K).
- Update notification with manga cover.
- Auto `2-way sync` progress with trackers.
- Chips for `Saved search` in source browse
- `Panorama cover` showing wide cover in full.
- `Merge multiple` library entries together at same time.
- `Range-selection` for Migration.
- Ability to `enable/disable repo`, with icon.
- `Update Error` screen & migrating them away.
- `to-be-updated` screen: which entries are going to be checked with smart-update?
- `Search for sources` & Quick NSFW sources filter in Extensions, Browse & Migration screen.
- `Feed` backup/restore/sync/re-order.
- Long-click to add/remove single entry to/from library, everywhere.
- Docking Read/Resume button to left/right.
- In-app progress banner shows Library syncing / Backup restoring / Library updating progress.
- Auto-install app update.
- Configurable interval to refresh entries from downloaded storage.
- Forked from SY so everything from SY.
- Always up-to-date with Mihon & SY
- More app themes & better UI, improvements...


<details>
  <summary>Features from Mihon / Tachiyomi</summary>

#### All up-to-date features from Mihon / Tachiyomi (original), include:

* Online reading from a variety of sources
* Local reading of downloaded content
* A configurable reader with multiple viewers, reading directions and other settings.
* Tracker support: [MyAnimeList](https://myanimelist.net/), [AniList](https://anilist.co/), [Kitsu](https://kitsu.app/), [MangaUpdates](https://mangaupdates.com), [Shikimori](https://shikimori.one), [Bangumi](https://bgm.tv/)
* Categories to organize your library
* Light and dark themes
* Schedule updating your library for new chapters
* Create backups locally to read offline or to your desired cloud service
* Continue reading button in library

</details>

<details>
  <summary>Features from Tachiyomi SY</summary>

#### All features from TachiyomiSY:
* Feed tab, where you can easily view the latest entries or saved search from multiple sources at same time.
* Automatic webtoon detection, allowing the reader to switch to webtoon mode automatically when viewing one
* Manga recommendations, uses MAL and Anilist, as well as Neko Similar Manga for Mangadex manga (Thanks to Az, She11Shocked, Carlos, and Goldbattle)
* Lewd filter, hide the lewd manga in your library when you want to
* Tracking filter, filter your tracked manga so you can see them or see non-tracked manga, made by She11Shocked
* Search tracking status in library, made by She11Shocked
* Custom categories for sources, liked the pinned sources, but you can make your own versions and put any sources in them
* Manga info edit
* Manga Cover view + share and save
* Dynamic Categories, view the library in multiple ways
* Smart background for reading modes like LTR or Vertical, changes the background based on the page color
* Force disable webtoon zoom
* Hentai features enable/disable, in advanced settings
* Quick clean titles
* Source migration, migrate all your manga from one source to another
* Saving searches
* Autoscroll
* Page preload customization
* Customize image cache size
* Batch import of custom sources and featured extensions
* Advanced source settings page, searching, enable/disable all
* Click tag for local search, long click tag for global search
* Merge multiple of the same manga from different sources
* Drag and drop library sorting
* Library search engine, includes exclude, quotes as absolute, and a bunch of other ways to search
* New E-Hentai/ExHentai features, such as language settings and watched list settings
* Enhanced views for internal and integrated sources
* Enhanced usability for internal and delegated sources

Custom sources:
* E-Hentai/ExHentai

Additional features for some extensions, features include custom description, opening in app, batch add to library, and a bunch of other things based on the source:
* 8Muses (EroMuse)
* Mangadex
* NHentai
* Puruin
* LANraragi

</details>

## Issues, Feature Requests and Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

<details><summary>Issues</summary>

[Website](https://komikku-app.github.io/)

1. **Before reporting a new issue, take a look at the [FAQ](https://komikku-app.github.io/docs/faq/general), the [changelog](https://github.com/komikku-app/komikku/releases) and the already opened [issues](https://github.com/komikku-app/komikku/issues).**
2. If you are unsure, ask here: [![Discord](https://img.shields.io/discord/1242381704459452488.svg?label=&labelColor=6A7EC2&color=7389D8&logo=discord&logoColor=FFFFFF)](https://discord.gg/85jB7V5AJR)

</details>

<details><summary>Bugs</summary>

* Include version (More → About → Version)
 * If not latest, try updating, it may have already been solved
 * Preview version is equal to the number of commits as seen on the main page
* Include steps to reproduce (if not obvious from description)
* Include screenshot (if needed)
* If it could be device-dependent, try reproducing on another device (if possible)
* Don't group unrelated requests into one issue

Use the [issue forms](https://github.com/komikku-app/komikku/issues/new/choose) to submit a bug.

</details>

<details><summary>Feature Requests</summary>

* Write a detailed issue, explaining what it should do or how.
* Include screenshot (if needed).
</details>

<details><summary>Contributing</summary>

See [CONTRIBUTING.md](./CONTRIBUTING.md).
</details>

<details><summary>Code of Conduct</summary>

See [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md).
</details>

<div align="center">

### Credits

Thank you to all the people who have contributed!

<a href="https://github.com/komikku-app/komikku/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=komikku-app/komikku" alt="Komikku app contributors" title="Komikku app contributors" width="800"/>
</a>

![Visitor Count](https://count.getloli.com/get/@komikku-app?theme=capoo-2)

### Disclaimer

The developer(s) of this application does not have any affiliation with the content providers available, and this application hosts zero content.

<div align="left">

## License

    Copyright 2015 Javier Tomás

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
