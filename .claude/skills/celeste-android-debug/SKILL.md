---
name: celeste-android-debug
description: Use when the Celeste Android app (Discord APK patched by CelesteManager + CelesteXposed + the rain JS bundle) does not start, hangs on the Discord splash, crashes ("UH OH" screen, LSPatch crash), or a new Discord version broke it; when CelesteManager, CelesteXposed or rain-src must be rebuilt, tested on an emulator, pointed at a local Celeste stack, or rain must be updated from upstream raincord/rain. Encodes the 2026-11 fix (Discord 354.12, R8-obfuscated builds) and the emulator-only verification loop.
---

# Celeste Android debug

Repo layout: `CelesteManager/` (downloads Discord splits from `tracker.vendetta.rocks`, embeds the module with LSPatch, installs `gg.celeste.app`), `CelesteXposed/` (hooks: URL redirect, bundle loader, bridge), `rain-src/` (vendored copy of codeberg.org/raincord/rain plus 3 Celeste patches; it is NOT a submodule).
At runtime the module downloads `celeste.js` from `https://github.com/vbxq/celeste-patcher/releases/latest/download/celeste.js` into `/data/user/0/gg.celeste.app/cache/pyoncord/bundle.js`.

Helpers live in `scripts/` next to this file. They are bash: call them by path (zsh does not word-split `$VAR`). Defaults are in `scripts/env.sh` (`ANDROID_SERIAL=emulator-5580`, `SCRATCH=~/.cache/celeste-android`); set `SCRATCH` to the session's scratch dir.

## 1. Environment

```bash
df -h /                      # emulator setup costs ~5 GB of SDK + ~3 GB of AVD; stop below the machine's free-space floor
ls /dev/kvm                  # required
ls ~/Android/Sdk             # needs cmdline-tools/latest; setup installs the rest
S=.claude/skills/celeste-android-debug/scripts
$S/emu.sh setup              # sdkmanager: emulator, platform-tools, build-tools;36.0.0, system-images;android-34;google_apis;x86_64; AVD pixel_6, 3 GB RAM, 6 GB data
$S/emu.sh start              # emulator -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -no-boot-anim -port 5580 -cores 4
$S/emu.sh wait               # polls sys.boot_completed, then adb root (google_apis images allow it; the helpers need root)
```

Use the `google_apis` image, not `google_apis_playstore` (no root). Under heavy host load the manager shows "isn't responding" dialogs during patching: tap Wait (`adb shell input tap 300 1359` on a pixel_6). Do not set `hide_error_dialogs`: it kills the ANR'd app.

Taps: screenshots come back 1080x2400; `shot.sh` also writes a 360 px preview, so multiply preview coordinates by 3.

## 2. Install flows

Through the manager (faithful to what users do):
```bash
adb install -r release/CelesteManager.apk     # or the published one from the GitHub release
adb shell appops set gg.celeste.manager MANAGE_EXTERNAL_STORAGE allow
adb shell appops set gg.celeste.manager REQUEST_INSTALL_PACKAGES allow
adb shell monkey -p gg.celeste.manager -c android.intent.category.LAUNCHER 1
# tap Install (540 810) or Reinstall (540 864); the system "install this app?" prompt then needs Install (894 1380)
```
- Testing an unpublished module: the manager reuses `/sdcard/Android/data/gg.celeste.manager/cache/xposed.apk` if present. Push it AFTER the manager home screen loaded (its init deletes a stale cache when the latest `vbxq/celeste-patcher` tag changed), then tap (Re)install:
  `adb push CelesteXposed/app/build/outputs/apk/release/app-release.apk /sdcard/Android/data/gg.celeste.manager/cache/xposed.apk`
- Discord splits are cached under `.../cache/<versionCode>/`, so a reinstall takes ~1-2 min, a first install ~5 min.
- LSPatch quirk: the FIRST launch after a fresh install always dies in the LSPatch bootstrap (`LSPosed-Bridge: Crash unexpectedly: java.lang.ExceptionInInitializerError ... NoClassDefFoundError: Class not found using the boot class loader`). Launch again; only the second launch counts. Do not diagnose from a first launch.

Which Discord the manager installs: latest stable from `curl -s https://tracker.vendetta.rocks/tracker/index`, capped by `DiscordVersion.Type.STABLE.maxValidatedVersionCode` in `CelesteManager/app/src/main/java/gg/celeste/manager/utils/VersionUtils.kt` (354012 today). rain warns "Incompatible discord version" unless the build equals `supportedVersionsAndroid` in `rain-src/scripts/build.mjs`. Bump both together, only after verifying the new build.

Testing an unpublished bundle: launch once (creates `etag.txt`), then `$S/push_bundle.sh rain-src/dist/rain.js`; it keeps the etag so the background updater gets a 304 instead of restoring the published bundle.

## 3. Debugging

```bash
$S/launch.sh run1 30         # force-stop, clear logcat, cold start, wait, save logcat + screenshot, print the lines below
```
Lines that matter (grep `$SCRATCH/logs/logcat_<tag>.txt`):
- `LSPosed-Bridge` / `LSPatch`: module loading; an exception here with `Main.handleLoadPackage` means no hook ran at all (app stays on the Discord splash, talks to discord.com).
- `Celeste` tag: `HttpUrl constructor: ...`, `Using ReactInstance accessors a/b as script loader`, `Hooking launcher activity ...`, `Loading script: .../bundle.js`, `Redirect: https://discord.com/... -> ...`, `<Module>.onLoad failed` (a module broke on this build, others still run).
- `BundleUpdater`: Discord's own OTA check. `Unexpected JSON token ... '<'` is harmless (Celeste answers the manifest URL with HTML).
- `E ReactNativeJS` / `E ReactNative`: JS errors. A rain crash shows the "UH OH" screen with `rain:<line>:<col>` frames: map them with `sed -n '<line>p' rain-src/dist/rain.js`; a frame through `onModuleRequire` means a lazy patch threw when Discord required the module.

Lesson from 354.12: Discord 352+ ships R8-obfuscated. `okhttp3.HttpUrl`, `com.discord.react_activities.ReactActivity`, `ReactInstance$loadJSBundle$1`, `CatalystInstanceImpl` are gone or renamed, and methods R8 found unused are stripped. Find hook targets by shape, never by name:
- launcher activity: resolve the launch intent at runtime (`Main.kt`), today `com.discord.main.MainActivity`;
- bundle loader: static methods of `com.facebook.react.runtime.ReactInstance` with params `(ReactInstance, AssetManager, String)` / `(ReactInstance, String, String)` (`HookScriptLoaderModule.kt`);
- URL class: walk the types reachable from `NetworkingModule` to the class with the 9-arg `(String,String,String,String,int,List,List,String,String)` constructor (`NetworkRedirectModule.kt`), today `l50.x`.
Kept by name because JS/JNI calls them: `NetworkingModule`, `WebSocketModule`, `Arguments`, `ReadableMap`, `Promise`, `RNSVGRenderableManager.getBBox`, `FileReaderModule.readAsDataURL`.

Check a new build without an emulator (downloads base + x86_64 splits, ~170 MB):
```bash
$S/inspect_discord.sh 354012   # manifest launcher/factory, Hermes bytecode version, which hook classes resolve (OK/MISS), ReactInstance accessors, obfuscated HttpUrl ctor
```
Deeper dives: `jadx --single-class com.discord.TTIComponentFactory -d out dex/classes.dex`; `$ANDROID_HOME/build-tools/36.0.0/dexdump -d classes3.dex | grep 'const-string.*"unexpected host: "'` locates the HttpUrl builder.

## 4. Pointing the app at the local stack

```bash
python3 -I $S/local_api.py mkuser        # throwaway local user + guild -> $SCRATCH/user.json (email, password123, channel_id)
$S/set_override.sh                       # writes files/pyoncord/celeste_target_override.json (root): alpha.celeste.gg -> http://10.0.2.2:5002, alpha-gateway -> http://10.0.2.2:5001
$S/launch.sh local 30                    # expect "W Celeste : Target override active" and "Redirect: ... -> http://10.0.2.2:5002/..."
```
Login: tap Log In (540 2229), email field (540 639), `adb shell input text "<email>"`, password field (540 906), `adb shell input text password123`, `adb shell input keyevent 111`, Log In button (540 1209). Then dismiss the notification prompt (540 1461), open the guild (93 393), the channel (400 597; the first tap may only dismiss a tutorial sheet), message box (400 2259), `adb shell input text "hello%sfrom%sandroid"`, send (960 1413).
Prove both directions: `python3 -I $S/local_api.py messages` shows the app's message; `python3 -I $S/local_api.py send <channel_id> realtime test` must appear in the app within seconds (gateway uses `compress=zstd-stream`).
Never use real accounts; production is only exercised up to the login screen (no override file). `set_override.sh --clear` reverts. A fresh install wipes app data, including the override.

## 5. Building

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
cd CelesteXposed && systemd-run --user --scope -q -p MemoryMax=6G -p MemorySwapMax=0 ./gradlew assembleRelease --no-daemon --console=plain --max-workers=2 -Dorg.gradle.jvmargs=-Xmx3g   # ~3 min
cd CelesteManager && (same command)                                                                                                                                                    # ~9 min, R8 + lint
cd rain-src && bun install && bun run build                                                                                                                                             # dist/rain.js, ~2 s
```
Fresh worktrees need `local.properties` (`sdk.dir=$HOME/Android/Sdk`) and `celeste-debug.jks` copied from the main checkout (both gitignored); `cp -r --reflink=always rain-src/node_modules` from the main checkout avoids a 500 MB reinstall on btrfs. Both apps are signed with `celeste-debug.jks` (same certificate as earlier releases, so the manager installs as an update). Bump `versionCode`/`versionName` in each `app/build.gradle.kts`.

Release artifacts (`./build.sh` collects them into the gitignored `release/`): `rain-src/dist/rain.js` as `celeste.js`, `CelesteXposed/app/build/outputs/apk/release/app-release.apk` as `CelesteXposed.apk`, `CelesteManager/app/build/outputs/apk/release/app-release.apk` as `CelesteManager.apk`.
Publish: a new GitHub release on `vbxq/celeste-patcher` with exactly those 3 files (the module downloads `releases/latest/download/<name>`). Existing installs pick up `celeste.js` automatically; the old manager keeps its cached `xposed.apk`, so users must press "Clear cache" in the manager (or install the new manager) and Reinstall.

Updating rain: `git clone https://codeberg.org/raincord/rain`; the vendored base is recorded in the sync commit message (`git log --grep 'sync rain-src'`). Replace the tracked files of `rain-src/` with `git archive <upstream-commit>`, then re-apply the Celeste deltas (`src/celesteInvites.ts`, its call in `src/index.ts`, the host rewrite block in `src/metro/internals/modules.ts`, the `painter/themes/patches/background.tsx` fix until upstream has it) and verify with `diff -r` against the upstream archive that nothing else differs.

## 6. Known gaps on 354.12 and the next-Discord checklist

Not working, logged and isolated: custom fonts (`ReactFontManager$Companion` gone), themes (`com.discord.theme.*` gone), Sentry blocking (`CrashReporting.isDisabled/init` gone), DevSupport/LogBox menu, rain's "Update" button (calls bridge `updater.download`, never implemented in CelesteXposed), manager self-update (points at non-existent `celeste-gg/CelesteManager`).

New Discord stable:
1. `$S/inspect_discord.sh <code>`: any new MISS among the hook classes, a different accessor shape, or no 9-arg HttpUrl ctor means CelesteXposed work.
2. Install through the manager with the cap temporarily raised (or set the version in the manager's developer settings), launch twice, run the section 4 loop: login, open a channel, send, receive.
3. Watch for rain lazy-patch crashes when opening screens (the "UH OH" screen).
4. Bump `maxValidatedVersionCode` + `supportedVersionsAndroid`, rebuild, publish.

## 7. Cleanup

```bash
$S/emu.sh stop
$S/emu.sh delete                          # AVD (~3 GB)
rm -rf "$SCRATCH"/apk*                    # downloaded splits and dex dumps
git worktree remove --force <wt> && git worktree prune   # after committing; release/ is gitignored, copy it out first
# optional: ~/Android/Sdk/system-images/android-34 (4.2 GB) and ~/Android/Sdk/emulator (0.9 GB); keep them if another check is likely soon
```
