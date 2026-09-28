<!-- 说明：本项目派生自一个 GPL-3.0 的上游项目，公开分发时请自行补上对上游的署名与本仓库地址。 -->
# YTP Framework

[![Android CI](https://github.com/Yutou04-sa/YTP/actions/workflows/android.yml/badge.svg)](https://github.com/Yutou04-sa/YTP/actions/workflows/android.yml) [![Download](https://img.shields.io/github/v/release/Yutou04-sa/YTP?color=orange&logoColor=orange&label=Download&logo=DocuSign)](https://github.com/Yutou04-sa/YTP/releases/latest) [![Total](https://shields.io/github/downloads/Yutou04-sa/YTP/total?logo=Bookmeter&label=Counts&logoColor=yellow&color=yellow)](https://github.com/Yutou04-sa/YTP/releases)

## Introduction

Rootless implementation of LSPosed framework, integrating Xposed API by inserting dex and so into the target APK.

## Supported Versions

- Min: Android 8.1
- Max: In theory, same with [JingMatrix/LSPosed](https://github.com/JingMatrix/LSPosed#supported-versions)

## Download

For stable releases, please go to [Github Releases page](https://github.com/Yutou04-sa/YTP/releases)

## Usage

+ Through manager
1. Download and install `YTP-v2.2.0-release.apk` on an Android device
2. Follow the instructions of the manager app

- devices

  ```powershell
  adb devices
  ```

  ```powershell
  adb install .\out\release\YTP-v2.2.0-release.apk
  ```

+ Build from source

  ```powershell
  ./gradlew :manager:buildRelease
  ```

  The APK is written to `manager/build/outputs/apk/release/` and copied to `out/release/`.
  Everything this project needs is in this repository — there are no git submodules, the former
  `core` submodule (LSPlant / Dobby / axml …) is vendored under `core/` as plain source.

  Signing is optional: the signing material is read from `manager/signing/signing.properties`
  (`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); copy
  `manager/signing/signing.properties.example` and point `KEYSTORE_FILE` at your own keystore.
  That file is git-ignored, so a checkout never carries anybody's private key. Without it the
  release APK is simply left unsigned (`manager-release-unsigned.apk`, sign it yourself), and the
  debug build falls back to the standard Android debug keystore — a fresh clone always builds.

  Build requirements: JDK 21 (`JAVA_HOME`), the Android SDK with **platform 37**, **build-tools
  37.0.0** and **NDK 29.0.13113456** (`ndkVersion` in `build.gradle.kts`; the patch loader's JNI part
  is built with CMake), plus `local.properties` → `sdk.dir`. The Gradle wrapper pins Gradle 9.4.1, so
  the first build downloads the toolchain it needs.

  GitHub Actions (`.github/workflows/android.yml`) builds the same release APK on every push and, for
  a `v*` tag, attaches it to a release whose version is the tag (`git tag v1.0 && git push origin v1.0`).
  The workflow signs that APK when the repository defines the `SIGNING_KEYSTORE_BASE64`,
  `SIGNING_STORE_PASSWORD` and `SIGNING_KEY_PASSWORD` secrets; a fork without them still builds and
  publishes an unsigned APK.

## Changes in this fork

+ The original APK(s) are copied to the manager's private storage **before** an app is patched.
+ They can be put back at any time from the **Original apks** page (install / uninstall / delete,
  single-APK and split-APK apps alike); the list shows the backed-up version, size and time.
+ The **Patched apps** page lists the apps this manager has patched. Each card can **re-patch the
  app from its backup** (a fresh patch built from the untouched original APKs) or export the
  backed-up APK(s) into a folder you pick. An app only counts as patched when it carries this
  manager's own marker (`assets/ytp/config.json`).
+ Lists refresh themselves every time a page is resumed, so there is no refresh button.
+ Multi-APK apps are installed through one `PackageInstaller` session, so split APKs are no longer
  installed partially.
+ The module repository entry lives in the bottom navigation bar; the old "Tools & resources" group
  (including the random package name feature) was removed.
+ Patching can **rename the app**: a new package name (so the patched build installs as a separate
  app next to the original) and a new display name. Relative component names in the manifest are
  completed with the original package name because the dex is not rewritten, and authorities or
  permissions are left untouched on purpose. Keep that in mind for apps that derive a provider
  authority from `Context.getPackageName()` at runtime: such a component can keep the old authority
  while the code asks for the new one. The dex is never rewritten, so compile-time constants
  (`BuildConfig.APPLICATION_ID`) still hold the original package name.
+ Patches of renamed apps are still matched with their origin backup — the new package name is
  recorded in the backup's `meta.properties`, so re-patching and "extract the original" keep working.
+ The entry a patched app adds to its launcher long-press menu and the screen behind it are both
  called **World Gate** — the shortcut is registered by
  `patch-loader/src/main/java/org/ytp/loader/util/ShortcutUtils.java` and the screen is
  `share/android/src/main/java/androidx/app/ModuleActivity.java`; the manager's own pages are called
  **Modules** and **Apps**. Its icon is a door drawn at runtime with `Bitmap`/`Canvas` (the loader
  cannot ship its own resources), and `ShortcutUtils.SHORTCUT_VERSION` is bumped so already-patched
  apps rewrite the shortcut on their next launch instead of keeping the old icon.
+ The theme defaults to Miku teal (`0xFF39C5BB`). An existing installation is migrated once and
  keeps whatever colour is chosen in the settings palette afterwards. Every icon added by this fork
  comes from Material's icon set, so no third-party artwork is bundled.
+ Settings start with a **Theme** group: the colour palette, a **background image** that is copied
  into the manager's private storage, and a **Widget opacity** slider. The image sits behind every
  page, while cards, the top bar and the bottom bar are drawn translucent by the chosen amount
  (default 80 %, so the image shows through) — at 100 % they are fully opaque again and text keeps
  its original contrast.
+ The home screen lists **Modules** and **Apps** as full-width NP-style rows (rounded icon chip,
  name, chevron) — the chips use Material icons (`Icons.Outlined.Extension` and
  `Icons.Outlined.Apps`). The **+** patch button stays the default floating action button in the
  bottom-end corner. The launcher icon keeps the upstream artwork (LSPosed-style, GPL-3.0) with its
  green swapped for Miku teal, and the in-app module manager of a patched app uses the same palette.
+ The bottom bar is a floating **liquid-glass** pill: a translucent gradient with a rim highlight and a
  diagonal sheen, a spring-animated highlight lens that slides to the selected tab, and a soft shadow.
  When a background image is set the glass follows the **Widget opacity** slider so the wallpaper shows
  through it; without a background image it stays almost opaque.
+ The built-in patch signing keystore reuses the maintainer's release key, so a patched app is signed
  with the same certificate as the manager. That keystore ships **inside** the APK
  (`manager/src/main/assets/keystore`, BKS, alias `鱼子`, store/key password `123456`), so treat it as a
  public key: generate your own keystore and repack this asset if you publish your own builds.
+ The community card on the home screen opens the maintainer's Telegram channel
  ([@Yutou_v50](https://t.me/Yutou_v50), `HomeScreen.kt` → `CommunityAction`); point it at your own
  channel if you redistribute this build.
+ The **in-app update check is disabled**: `UpdateChecker.UPDATE_URL` is empty, so the manager makes no
  update request at launch and never shows an update dialog. Fill in your own `update2` URL (format:
  `update-msg/`) to re-enable it — don't point it at the upstream endpoint, whose downloads are signed
  with a different key.

## Repository layout

- `manager/` — the manager app (Kotlin, Jetpack Compose).
- `patch/`, `patch-loader/`, `meta-loader/`, `share/` — the patch pipeline and the code that is
  injected into the target APK.
- `core/` — the vendored rootless-Xposed core (LSPlant, Dobby, axml, hiddenapi bridges …), shipped as
  plain source with its own GPL-3.0 `LICENSE`; there are **no git submodules** anywhere in the tree.
- `out/`, `build/` — build output, git-ignored.

## Credits

- [LSPosed](https://github.com/JingMatrix/LSPosed): Core framework
- [Xpatch](https://github.com/WindySha/Xpatch): Fork source
- [Apkzlib](https://android.googlesource.com/platform/tools/apkzlib): Repacking tool

## License

YTP is licensed under the **GNU General Public License v3 (GPL-3)** (http://www.gnu.org/copyleft/gpl.html).
