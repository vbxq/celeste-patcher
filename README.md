# Celeste Patcher

Patches the Discord Android app to connect to a [Celeste](https://celeste.gg) instead of Discord servers

## How it works

CelesteManager downloads the official Discord APK, embeds the CelesteXposed module via [LSPatch](https://github.com/LSPosed/LSPatch), and installs the patched app

At runtime, CelesteXposed hooks `okhttp3.HttpUrl`

| Original | Redirected to |
|----------|---------------|
| `discord.com` | `alpha.celeste.gg` |
| `gateway.discord.gg` | `alpha-gateway.celeste.gg` |
| `cdn.discordapp.com` | `cdn.celeste.gg` |
| `media.discordapp.net` | `media.celeste.gg` |
| `latency.discord.gg` | `latency.celeste.gg` |


## Install (prebuilt)

1. Download [CelesteManager.apk](https://github.com/vbxq/celeste-patcher/releases/latest/download/CelesteManager.apk)
2. Install and open CelesteManager
3. Tap **Install**
4. Open the newly installed app (requires two launches on first run)
5. Log in with your Celeste credentials

## Build from source

### Prerequisites

- JDK 21+
- Android SDK with `compileSdk 36` and `build-tools 36.0.0`
- A signing keystore (or create one, see below)

### 1. Clone

```bash
git clone https://github.com/vbxq/celeste-patcher.git
cd celeste-patcher
```

### 2. Create a signing keystore (first time only)

```bash
keytool -genkey -v -keystore celeste.jks -alias celeste \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass changeme -keypass changeme \
  -dname "CN=Celeste"
```

### 3. Build CelesteXposed

```bash
cd CelesteXposed
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleRelease
```

The APK is at `app/build/outputs/apk/release/app-release.apk`.

Sign it if your `build.gradle.kts` doesn't have a signing config:

```bash
$ANDROID_HOME/build-tools/36.0.0/zipalign -f 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner sign --ks ../celeste.jks --ks-pass pass:changeme --key-pass pass:changeme --ks-key-alias celeste aligned.apk
```

### 4. Host CelesteXposed.apk

CelesteManager downloads CelesteXposed at install time. Host the signed APK somewhere accessible and update the URL in:

- `CelesteManager/app/src/main/java/gg/celeste/manager/installer/step/download/DownloadVendettaStep.kt`
- `CelesteManager/app/src/main/java/gg/celeste/manager/domain/manager/DownloadManager.kt`

### 5. Build CelesteManager

```bash
cd CelesteManager
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleRelease
```

The signed APK is at `app/build/outputs/apk/release/app-release.apk`. Install it on your phone.

## Project structure

```
CelesteManager/     Android app — downloads Discord, injects CelesteXposed via LSPatch, installs
CelesteXposed/      Xposed module — hooks OkHttp to redirect all Discord traffic to Celeste
```

The key file is [`NetworkRedirectModule.kt`](CelesteXposed/app/src/main/kotlin/gg/celeste/xposed/modules/NetworkRedirectModule.kt). It contains the `HttpUrl` constructor hook and the host mapping table.

## Changing the target instance

Edit the `HOST_MAP` in `NetworkRedirectModule.kt`:

```kotlin
private val HOST_MAP = mapOf(
    "discord.com" to "your-instance.example.com",
    "gateway.discord.gg" to "gateway.your-instance.example.com",
    "cdn.discordapp.com" to "cdn.your-instance.example.com",
    "media.discordapp.net" to "media.your-instance.example.com",
)
```

Rebuild CelesteXposed and redeploy.

## When Discord updates


this should keep working across Discord updates with no changes, to confirm a build still redirects, watch logcat:

```bash
adb logcat -s Celeste
```

or just try logging in to your celeste account


## TODOs ? 

patch the hermes bytecode to rewrite `discord.gg` -> `celeste.gg` in bundled invite links, actual Discord => Celeste

## Credits

Fork of [KettuManager](https://github.com/C0C0B01/KettuManager) and [KettuXposed](https://codeberg.org/cocobo1/KettuXposed) by cocobo1.
Built on the work of [Vendetta](https://github.com/vendetta-mod) by maisymoe and [Bunny](https://github.com/pyoncord/Bunny) by pylixonly.
